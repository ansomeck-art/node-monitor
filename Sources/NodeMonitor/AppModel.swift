import AppKit
import Combine
import Foundation
import NodeMonitorCore
import UserNotifications

struct HostRuntime {
    var snapshot: Snapshot?
    var receivedAt: Date?
    var firstAttempt = Date()
    var lastAttempt: Date?
    var error: String?
    var failures = 0
    var recent: [HistoryPoint] = []
    func status(cadence: Double) -> String {
        if error != nil { return snapshot == nil ? "连接失败" : "连接中断 · 保留旧读数" }
        guard let snapshot else { return "等待连接" }
        if snapshot.isClockSkewed { return "服务器时钟偏差" }
        if !snapshot.isFresh(at: Date(), pollingInterval: cadence) { return "数据已过期" }
        return snapshot.demo ? "演示数据" : "在线"
    }
    func healthy(cadence: Double) -> Bool { error == nil && snapshot?.isFresh(at: Date(), pollingInterval: cadence) == true }
}
private struct PollResult {
    let id: UUID
    let snapshot: Snapshot?
    let error: String?
}

@MainActor final class AppModel: ObservableObject {
    @Published var configuration: AppConfiguration
    @Published var runtimes: [UUID: HostRuntime] = [:]
    @Published var events: [AlertEvent] = []
    @Published var chartPoints: [HistoryPoint] = []
    @Published var rangeSeconds: Double = 900
    @Published var detailVisible = false
    @Published var popoverVisible = false
    @Published var preferencesVisible = false
    @Published var addHostVisible = false
    @Published var editingHost: HostConfiguration?
    @Published var message: String?
    @Published var startingDemo = false
    let paths: PortablePaths
    let persistence: ConfigurationStore
    let history: HistoryStore
    private let client = AgentClient()
    private let alerts = AlertEngine()
    private let demoManager = DemoManager()
    private var pollTask: Task<Void, Never>?
    private var lastNotifications: [String: Date] = [:]
    var showDashboard: (() -> Void)?
    var showPreferences: (() -> Void)?
    var widgetsChanged: (() -> Void)?

    init(root: URL) throws {
        paths = try PortablePaths(root: root)
        persistence = ConfigurationStore(paths: paths)
        configuration = try persistence.load()
        history = try HistoryStore(url: paths.database)
        events = try history.events()
        for host in configuration.hosts { runtimes[host.id] = HostRuntime() }
    }
    var selectedHost: HostConfiguration? { configuration.hosts.first { $0.id == configuration.selectedHostID } ?? configuration.hosts.first }
    var selectedRuntime: HostRuntime { selectedHost.flatMap { runtimes[$0.id] } ?? HostRuntime() }
    var cadence: Double {
        if configuration.energySaver { return 60 }
        return detailVisible || popoverVisible || preferencesVisible || configuration.hosts.contains { $0.widget.visible } ? 5 : 60
    }
    var modeText: String { cadence == 5 ? "每 5 秒更新" : "省电 · 每 60 秒更新" }
    func start() {
        restartPolling()
        if configuration.hosts.contains(where: \.demo) { Task { await startDemo() } }
    }
    func stop() { pollTask?.cancel(); demoManager.stop() }
    func suspend() { pollTask?.cancel() }
    func visibilityChanged() { restartPolling(); refreshHistory(); widgetsChanged?() }
    func restartPolling() {
        pollTask?.cancel()
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                await self.refreshAll()
                if Task.isCancelled { return }
                do { try await Task.sleep(nanoseconds: UInt64(self.cadence * 1_000_000_000)) } catch { return }
            }
        }
    }
    func refreshAll() async {
        let now = Date()
        let jobs = configuration.hosts.compactMap { host -> (HostConfiguration, String)? in
            if host.demo && !demoManager.running { return nil }
            let runtime = runtimes[host.id] ?? HostRuntime()
            let backoff = runtime.failures == 0 ? 0 : min(60.0, pow(2, Double(min(runtime.failures, 4))) * 5)
            if let last = runtime.lastAttempt, now.timeIntervalSince(last) < backoff { return nil }
            guard let token = try? persistence.token(for: host.id) else {
                var state = runtime; state.error = "访问令牌文件缺失，请编辑主机重新填写。"; runtimes[host.id] = state
                return nil
            }
            return (host, token)
        }
        let results = await withTaskGroup(of: PollResult.self) { group in
            for (host, token) in jobs {
                group.addTask { [client] in
                    do { return PollResult(id: host.id, snapshot: try await client.fetch(address: host.address, token: token, allowHTTP: host.allowHTTP), error: nil) }
                    catch { return PollResult(id: host.id, snapshot: nil, error: error.localizedDescription) }
                }
            }
            var values: [PollResult] = []
            for await result in group { values.append(result) }
            return values
        }
        guard !Task.isCancelled else { return }
        var next = runtimes
        for result in results {
            guard let host = configuration.hosts.first(where: { $0.id == result.id }) else { continue }
            var runtime = next[result.id] ?? HostRuntime()
            runtime.lastAttempt = Date()
            if let sample = result.snapshot {
                runtime.snapshot = sample; runtime.receivedAt = Date(); runtime.error = nil; runtime.failures = 0
                do { try history.insert(host: host.id, sample: sample) } catch { message = error.localizedDescription }
                record(alerts.evaluate(host: host, sample: sample, thresholds: configuration.thresholds, now: Date(), pollingInterval: cadence))
            } else {
                runtime.error = result.error; runtime.failures += 1
                record(alerts.unreachable(host: host, since: runtime.receivedAt ?? runtime.firstAttempt, thresholds: configuration.thresholds, now: Date()))
            }
            if host.widget.visible || host.id == selectedHost?.id {
                runtime.recent = (try? history.points(host: host.id, since: Date().addingTimeInterval(-900), maxPoints: 90)) ?? []
            }
            next[result.id] = runtime
        }
        runtimes = next
        if detailVisible { refreshHistory() }
        do { try history.prune(retentionDays: configuration.retentionDays) } catch { message = error.localizedDescription }
    }
    func refreshHistory() {
        guard let host = selectedHost else { chartPoints = []; return }
        do { chartPoints = try history.points(host: host.id, since: Date().addingTimeInterval(-rangeSeconds)) }
        catch { message = error.localizedDescription }
    }
    func select(_ id: UUID) {
        configuration.selectedHostID = id; save(); refreshHistory()
    }
    func save() {
        do { try persistence.save(configuration) } catch { message = error.localizedDescription }
    }
    func updatePreferences(_ action: (inout AppConfiguration) -> Void) {
        action(&configuration); save(); restartPolling(); widgetsChanged?()
    }
    func updateWidget(_ id: UUID, action: (inout WidgetSettings) -> Void, refresh: Bool = true) {
        guard let index = configuration.hosts.firstIndex(where: { $0.id == id }) else { return }
        action(&configuration.hosts[index].widget); save()
        if refresh { widgetsChanged?(); restartPolling() }
    }
    func toggleWidget(_ id: UUID) { updateWidget(id) { $0.visible.toggle() } }
    func saveHost(_ host: HostConfiguration, token: String) throws {
        _ = try AgentClient.endpoint(host.address, allowHTTP: host.allowHTTP)
        let oldToken = try? persistence.token(for: host.id)
        if !token.isEmpty { try persistence.saveToken(token, for: host.id) }
        else if oldToken == nil { throw ConfigurationError.invalidToken }
        var changed = configuration
        if let index = changed.hosts.firstIndex(where: { $0.id == host.id }) { changed.hosts[index] = host }
        else { changed.hosts.append(host) }
        changed.selectedHostID = host.id
        do { try persistence.save(changed) }
        catch { if let oldToken { try? persistence.saveToken(oldToken, for: host.id) } else { try? persistence.removeToken(for: host.id) }; throw error }
        configuration = changed; runtimes[host.id] = HostRuntime(); alerts.reset(hostID: host.id)
        restartPolling(); widgetsChanged?()
    }
    func deleteHost(_ id: UUID) {
        do {
            var changed = configuration; changed.hosts.removeAll { $0.id == id }
            if changed.selectedHostID == id { changed.selectedHostID = changed.hosts.first?.id }
            try persistence.save(changed); configuration = changed
            try persistence.removeToken(for: id); try history.remove(host: id)
            runtimes.removeValue(forKey: id); alerts.reset(hostID: id); widgetsChanged?(); restartPolling(); refreshHistory()
            if !configuration.hosts.contains(where: \.demo) { demoManager.stop() }
        } catch { message = error.localizedDescription }
    }
    func startDemo() async {
        guard !startingDemo else { return }
        startingDemo = true; defer { startingDemo = false }
        do {
            let (address, token) = try await demoManager.start(root: paths.root)
            let id = UUID(uuidString: "00000000-0000-4000-8000-000000000001")!
            var host = configuration.hosts.first(where: { $0.id == id }) ?? HostConfiguration(id: id, name: "GPU 工作站 · 演示", group: "演示", address: address, allowHTTP: true, demo: true)
            host.address = address
            try saveHost(host, token: token)
        } catch { message = error.localizedDescription }
    }
    func requestNotifications() async {
        do {
            let enabled = try await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])
            updatePreferences { $0.notifications = enabled }
            if !enabled { message = "系统未允许通知，可在系统设置 → 通知中调整。应用内事件仍会记录。" }
        } catch { message = "无法申请系统通知权限。应用内事件仍会记录。" }
    }
    private func record(_ newEvents: [AlertEvent]) {
        for event in newEvents {
            do { try history.record(event) } catch { message = error.localizedDescription }
            events.insert(event, at: 0); if events.count > 100 { events.removeLast(events.count - 100) }
            guard configuration.notifications else { continue }
            let key = event.hostID.uuidString + event.metric
            if !event.recovered, let previous = lastNotifications[key], event.date.timeIntervalSince(previous) < 300 { continue }
            if !event.recovered { lastNotifications[key] = event.date }
            let content = UNMutableNotificationContent()
            content.title = event.hostName + (event.recovered ? " · 已恢复" : " · 监控提醒")
            content.body = event.message
            UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: event.id.uuidString, content: content, trigger: nil))
        }
    }
}

private final class DemoManager {
    private var process: Process?
    private var logHandle: FileHandle?
    var running: Bool { process?.isRunning == true }
    @MainActor func start(root: URL) async throws -> (String, String) {
        let directory = root.appendingPathComponent("data/demo", isDirectory: true)
        let ready = directory.appendingPathComponent("ready.json")
        let token = directory.appendingPathComponent("agent.token")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        if !running {
            try? FileManager.default.removeItem(at: ready)
            guard let executable = Bundle.main.url(forResource: "nodemon-agent-demo", withExtension: nil) else { throw DemoError.unavailable }
            let log = directory.appendingPathComponent("agent.log")
            FileManager.default.createFile(atPath: log.path, contents: nil, attributes: [.posixPermissions: 0o600])
            logHandle = try FileHandle(forWritingTo: log)
            let task = Process(); task.executableURL = executable
            task.arguments = ["--demo", "--listen", "127.0.0.1:0", "--token-file", token.path, "--ready-file", ready.path]
            task.standardOutput = logHandle; task.standardError = logHandle
            task.environment = ["PATH": "/usr/bin:/bin:/usr/sbin:/sbin", "TMPDIR": root.appendingPathComponent(".cache/tmp").path]
            try task.run(); process = task
        }
        for _ in 0..<100 {
            if let data = try? Data(contentsOf: ready), let object = try? JSONSerialization.jsonObject(with: data) as? [String: String], let address = object["url"], let value = try? String(contentsOf: token, encoding: .utf8), running { return (address, value.trimmingCharacters(in: .whitespacesAndNewlines)) }
            if !running { break }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        stop(); throw DemoError.unavailable
    }
    func stop() { if process?.isRunning == true { process?.terminate() }; process = nil; try? logHandle?.close(); logHandle = nil }
}
private enum DemoError: LocalizedError {
    case unavailable
    var errorDescription: String? { "本地演示采集端未能启动，请重新运行 scripts/build.sh，并检查 data/demo/agent.log。" }
}
