import Foundation

public enum AgentError: LocalizedError {
    case invalidAddress, invalidRemoteAddress, plaintextNotAllowed, remoteRequiresHTTPS, unauthorized, forbidden, incompatible, http(Int), tooLarge, network(Int)
    public var errorDescription: String? {
        switch self {
        case .invalidAddress: return "请输入 http:// 或 https:// 开头的采集端地址，勿包含账号、密码或查询参数。"
        case .invalidRemoteAddress: return "请输入服务器的 Tailscale IP 或完整的 .ts.net 设备名，可附带端口。"
        case .plaintextNotAllowed: return "HTTP 连接需要明确允许；公网请使用 HTTPS 或 SSH 隧道。"
        case .remoteRequiresHTTPS: return "远程地址必须使用 HTTPS。"
        case .unauthorized: return "访问令牌无效，请编辑主机重新填写。"
        case .forbidden: return "采集端拒绝了读取请求。"
        case .incompatible: return "采集端协议不兼容，需要 schemaVersion 1。"
        case .http(let code): return "采集端返回 HTTP \(code)。"
        case .tooLarge: return "采集端响应超过 1 MiB 限制。"
        case .network(let code) where code == URLError.notConnectedToInternet.rawValue: return "无法访问网络。局域网主机请检查“系统设置 → 隐私与安全性 → 本地网络”中是否允许节点监控（\(code)）。"
        case .network(let code): return "连接失败，请检查地址、网络和 TLS 证书（\(code)）。"
        }
    }
}
private final class NoRedirectDelegate: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}
public final class AgentClient {
    private let session: URLSession
    private let delegate = NoRedirectDelegate()
    public init() {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 5
        configuration.timeoutIntervalForResource = 8
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        configuration.urlCredentialStorage = nil
        configuration.httpMaximumConnectionsPerHost = 2
        configuration.waitsForConnectivity = false
        session = URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
    }
    public static func endpoint(_ address: String, allowHTTP: Bool) throws -> URL {
        guard let components = URLComponents(string: address.trimmingCharacters(in: .whitespacesAndNewlines)),
              let scheme = components.scheme?.lowercased(), ["http", "https"].contains(scheme),
              let host = components.host, !host.isEmpty, components.user == nil, components.password == nil,
              components.query == nil, components.fragment == nil, let base = components.url else { throw AgentError.invalidAddress }
        let loopback = host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"
        if scheme == "http" && !allowHTTP && !loopback { throw AgentError.plaintextNotAllowed }
        return base.appendingPathComponent("v1").appendingPathComponent("snapshot")
    }
    public func fetch(address: String, token: String, allowHTTP: Bool) async throws -> Snapshot {
        var request = URLRequest(url: try Self.endpoint(address, allowHTTP: allowHTTP))
        request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.cachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        do {
            let (bytes, response) = try await session.bytes(for: request)
            guard let http = response as? HTTPURLResponse else { throw AgentError.incompatible }
            switch http.statusCode {
            case 200: break
            case 401: throw AgentError.unauthorized
            case 403: throw AgentError.forbidden
            case 404: throw AgentError.incompatible
            default: throw AgentError.http(http.statusCode)
            }
            if response.expectedContentLength > 1_048_576 { throw AgentError.tooLarge }
            var data = Data()
            for try await byte in bytes {
                if data.count >= 1_048_576 { throw AgentError.tooLarge }
                data.append(byte)
            }
            let sample = try JSONCodec.decoder().decode(Snapshot.self, from: data)
            guard sample.schemaVersion == 1, sample.sampleIntervalSeconds >= 1,
                  sample.sampledAt.timeIntervalSince1970.isFinite else { throw AgentError.incompatible }
            return sample
        } catch let error as URLError { throw AgentError.network(error.errorCode) }
    }
}
