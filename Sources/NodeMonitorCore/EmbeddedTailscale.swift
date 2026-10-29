import Foundation

private struct BridgeRequest: Encodable {
    let id: String
    let action: String
    var address: String?
    var token: String?
}
private struct BridgeResponse: Decodable {
    let id: String
    let state: String?
    let url: String?
    let data: Snapshot?
    let error: String?
}
private enum BridgeFailure: LocalizedError {
    case unavailable, timeout, login, connection
    var errorDescription: String? {
        switch self {
        case .unavailable: return "内置远程连接组件未能启动，请检查项目目录权限。"
        case .timeout: return "远程连接超时，请检查网络或登录状态。"
        case .login: return "无法打开授权，请检查网络后重试。"
        case .connection: return "无法连接远程服务器，请检查设备地址、网络和访问规则。"
        }
    }
}

// The helper is a child process using private pipes; no local TCP port or
// process argument contains the monitoring token or a login URL.
public actor EmbeddedTailscale {
    private let root: URL
    private let executableOverride: URL?
    private var process: Process?
    private var input: FileHandle?
    private var output: FileHandle?
    private var generation = UUID()
    private var pending: [String: CheckedContinuation<BridgeResponse, Error>] = [:]

    public init(root: URL, executable: URL? = nil) { self.root = root; self.executableOverride = executable }

    public func fetch(address: String, token: String) async throws -> Snapshot {
        let response = try await request(action: "fetch", address: address, token: token)
        guard let sample = response.data, sample.schemaVersion == 1, sample.sampleIntervalSeconds >= 1,
              sample.sampledAt.timeIntervalSince1970.isFinite else { throw AgentError.incompatible }
        return sample
    }
    public func status() async throws -> String { try await request(action: "status").state ?? "NoState" }
    public func login() async throws -> URL? {
        let response = try await request(action: "login")
        guard let value = response.url, !value.isEmpty else { return nil }
        guard let url = URL(string: value), url.scheme == "https", url.host == "login.tailscale.com", url.user == nil, url.password == nil else { throw BridgeFailure.login }
        return url
    }

    public func stop() {
        generation = UUID()
        output?.readabilityHandler = nil
        try? input?.close(); try? output?.close()
        if process?.isRunning == true { process?.terminate() }
        input = nil; output = nil; process = nil
        let continuations = pending.values; pending.removeAll()
        for continuation in continuations { continuation.resume(throwing: BridgeFailure.unavailable) }
    }

    private func startIfNeeded() throws {
        if process?.isRunning == true { return }
        stop()
        guard let executable = executableOverride ?? Bundle.main.url(forResource: "nodemon-tailscale-bridge", withExtension: nil) else { throw BridgeFailure.unavailable }
        let state = root.appendingPathComponent("data/mac/tailscale", isDirectory: true)
        let temporary = root.appendingPathComponent(".cache/tmp", isDirectory: true)
        try FileManager.default.createDirectory(at: state, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        try FileManager.default.createDirectory(at: temporary, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: state.path)
        let inPipe = Pipe(), outPipe = Pipe()
        let task = Process(); task.executableURL = executable
        task.arguments = ["--state-dir", state.path]
        // Exclude ambient auth-key and proxy variables to prevent accidental login
        // or forwarding through an unrelated service.
        task.environment = ["PATH": "/usr/bin:/bin:/usr/sbin:/sbin", "TMPDIR": temporary.path]
        task.standardInput = inPipe; task.standardOutput = outPipe; task.standardError = FileHandle.nullDevice
        let run = generation
        let reader = BridgeLineReader { [weak self] data in Task { await self?.receive(data, generation: run) } }
        outPipe.fileHandleForReading.readabilityHandler = { handle in reader.consume(handle.availableData) }
        task.terminationHandler = { [weak self] _ in Task { await self?.ended(generation: run) } }
        do { try task.run() } catch { outPipe.fileHandleForReading.readabilityHandler = nil; throw BridgeFailure.unavailable }
        process = task; input = inPipe.fileHandleForWriting; output = outPipe.fileHandleForReading
    }

    private func request(action: String, address: String? = nil, token: String? = nil) async throws -> BridgeResponse {
        try startIfNeeded()
        let timeoutNanoseconds: UInt64 = action == "login" ? 45_000_000_000 : 20_000_000_000
        let id = UUID().uuidString
        let request = BridgeRequest(id: id, action: action, address: address, token: token)
        var bytes = try JSONEncoder().encode(request); bytes.append(0x0a)
        let result = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<BridgeResponse, Error>) in
            pending[id] = continuation
            do { try input?.write(contentsOf: bytes) } catch {
                pending.removeValue(forKey: id)?.resume(throwing: BridgeFailure.unavailable)
            }
            Task {
                try? await Task.sleep(nanoseconds: timeoutNanoseconds)
                self.expire(id)
            }
        }
        switch result.error {
        case nil: return result
        case "unauthorized": throw AgentError.unauthorized
        case "forbidden": throw AgentError.forbidden
        case "invalid_address": throw AgentError.invalidRemoteAddress
        case "too_large": throw AgentError.tooLarge
        case "incompatible": throw AgentError.incompatible
        case "login": throw BridgeFailure.login
        default: throw BridgeFailure.connection
        }
    }
    private func expire(_ id: String) { pending.removeValue(forKey: id)?.resume(throwing: BridgeFailure.timeout) }
    private func receive(_ data: Data, generation run: UUID) {
        guard generation == run, let response = try? JSONCodec.decoder().decode(BridgeResponse.self, from: data) else { return }
        pending.removeValue(forKey: response.id)?.resume(returning: response)
    }
    private func ended(generation run: UUID) { if generation == run { stop() } }
}

private final class BridgeLineReader {
    private let lock = NSLock()
    private var buffer = Data()
    private let handleLine: (Data) -> Void
    init(handleLine: @escaping (Data) -> Void) { self.handleLine = handleLine }
    func consume(_ data: Data) {
        lock.lock(); defer { lock.unlock() }
        guard !data.isEmpty else { return }
        buffer.append(data)
        // A snapshot is limited to 1 MiB by the helper.
        if buffer.count > 2 * 1_048_576 { buffer.removeAll(); return }
        while let newline = buffer.firstIndex(of: 0x0a) {
            let line = Data(buffer[..<newline]); buffer.removeSubrange(...newline)
            handleLine(line)
        }
    }
}
