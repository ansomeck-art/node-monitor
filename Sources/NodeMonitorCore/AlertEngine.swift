import Foundation

public struct AlertEvent: Codable, Identifiable {
    public let id: UUID
    public let date: Date
    public let hostID: UUID
    public let hostName: String
    public let metric: String
    public let message: String
    public let recovered: Bool
}
public final class AlertEngine {
    private struct State { var since: Date?; var lastSample: Date?; var active = false }
    private var states: [String: State] = [:]
    public init() {}
    public func evaluate(host: HostConfiguration, sample: Snapshot, thresholds: AlertThresholds, now: Date, pollingInterval: Double? = nil) -> [AlertEvent] {
        guard thresholds.enabled, sample.isFresh(at: now, pollingInterval: sample.sampleIntervalSeconds) else { return [] }
        var events: [AlertEvent] = []
        let checks: [(String, Double?, Double, String)] = [
            ("cpu", sample.cpu.usagePercent, thresholds.cpu, "CPU 使用率"),
            ("memory", sample.memory.usagePercent, thresholds.memory, "内存使用率"),
            ("cpuTemperature", sample.cpuTemperature, thresholds.cpuTemperature, "CPU 温度"),
            ("gpuTemperature", sample.gpuTemperature, thresholds.gpuTemperature, "GPU 温度"),
            ("disk", sample.disks.compactMap(\.usagePercent).max(), thresholds.disk, "磁盘使用率")
        ]
        for (metric, value, threshold, title) in checks {
            guard let value else { continue }
            let key = host.id.uuidString + metric
            var state = states[key] ?? State()
            if let last = state.lastSample, sample.sampledAt <= last { continue }
            let expectedInterval = max(sample.sampleIntervalSeconds, pollingInterval ?? sample.sampleIntervalSeconds)
            if let last = state.lastSample, sample.sampledAt.timeIntervalSince(last) > max(15, expectedInterval * 3) { state.since = nil }
            state.lastSample = sample.sampledAt
            if value >= threshold {
                if state.since == nil { state.since = now }
                if !state.active, let since = state.since, now.timeIntervalSince(since) >= thresholds.durationSeconds {
                    state.active = true
                    let unit = metric.contains("Temperature") ? "°C" : "%"
                    events.append(AlertEvent(id: UUID(), date: now, hostID: host.id, hostName: host.name, metric: metric, message: "\(title) \(Int(value))\(unit)，持续 \(Int(thresholds.durationSeconds)) 秒", recovered: false))
                }
            } else if !state.active {
                state.since = nil
            } else if value <= threshold - 5 {
                state.since = nil
                if state.active { state.active = false; events.append(AlertEvent(id: UUID(), date: now, hostID: host.id, hostName: host.name, metric: metric, message: "\(title)已恢复", recovered: true)) }
            }
            states[key] = state
        }
        let offlineKey = host.id.uuidString + "offline"
        if states[offlineKey]?.active == true {
            states[offlineKey] = State()
            events.append(AlertEvent(id: UUID(), date: now, hostID: host.id, hostName: host.name, metric: "offline", message: "连接已恢复", recovered: true))
        }
        return events
    }
    public func unreachable(host: HostConfiguration, since: Date, thresholds: AlertThresholds, now: Date) -> [AlertEvent] {
        guard thresholds.enabled, now.timeIntervalSince(since) >= thresholds.offlineSeconds else { return [] }
        let key = host.id.uuidString + "offline"
        guard states[key]?.active != true else { return [] }
        states[key] = State(since: since, lastSample: nil, active: true)
        return [AlertEvent(id: UUID(), date: now, hostID: host.id, hostName: host.name, metric: "offline", message: "采集端已失联 \(Int(now.timeIntervalSince(since))) 秒", recovered: false)]
    }
    public func reset(hostID: UUID) { states = states.filter { !$0.key.hasPrefix(hostID.uuidString) } }
}
