import Foundation

public struct Snapshot: Codable {
    public let schemaVersion: Int
    public let agentVersion: String
    public let sampledAt: Date
    public let sampleIntervalSeconds: Double
    public let gpuSampleIntervalSeconds: Double?
    public let demo: Bool
    public let host: HostInfo
    public let cpu: CPUInfo
    public let memory: MemoryInfo
    public let gpus: [GPUInfo]
    public let disks: [DiskInfo]
    public let network: NetworkInfo
    public let sensors: [SensorInfo]
    public let capabilities: [String: String]
    public let warnings: [String]

    public var cpuTemperature: Double? { sensors.filter { $0.kind == "cpu" }.map(\.celsius).max() }
    public var cpuReadings: [HardwareReading] {
        if let packages = cpu.packages, !packages.isEmpty {
            return packages.enumerated().map { index, package in
                HardwareReading(id: package.id, title: "CPU \(index + 1)", menuCode: "C\(index + 1)", name: package.name,
                                isGPU: false, usagePercent: package.usagePercent, temperatureCelsius: package.temperatureCelsius,
                                memoryUsedBytes: nil, memoryTotalBytes: nil)
            }
        }
        return [HardwareReading(id: "cpu-total", title: "CPU", menuCode: "C", name: "整机 · \(cpu.cores) 线程",
                                isGPU: false, usagePercent: cpu.usagePercent, temperatureCelsius: cpuTemperature,
                                memoryUsedBytes: nil, memoryTotalBytes: nil)]
    }
    public var gpuReadings: [HardwareReading] {
        gpus.enumerated().map { index, gpu in
            let fresh = gpu.isFresh(at: sampledAt, interval: gpuSampleIntervalSeconds ?? 15)
            return HardwareReading(id: gpu.id, title: "GPU \(index + 1)", menuCode: "G\(index + 1)", name: gpu.name,
                                   isGPU: true, usagePercent: fresh ? gpu.usagePercent : nil, temperatureCelsius: fresh ? gpu.temperatureCelsius : nil,
                                   memoryUsedBytes: fresh ? gpu.memoryUsedBytes : nil, memoryTotalBytes: fresh ? gpu.memoryTotalBytes : nil)
        }
    }
    public var hardwareReadings: [HardwareReading] { cpuReadings + gpuReadings }
    public func menuStatus(metric: String) -> String {
        let readings = metric == "cpu" || metric == "temperature" ? cpuReadings : metric == "gpu" ? gpuReadings : hardwareReadings
        return readings.map { reading in
            if metric == "temperature" { return "\(reading.menuCode) \(MetricFormat.temperature(reading.temperatureCelsius))" }
            return "\(reading.menuCode) \(MetricFormat.percent(reading.usagePercent)) \(MetricFormat.temperature(reading.temperatureCelsius))"
        }.joined(separator: "  ")
    }
    public var memoryTemperature: Double? { sensors.filter { $0.kind == "memory" }.map(\.celsius).max() }
    private var freshGPUs: [GPUInfo] { gpus.filter { $0.isFresh(at: sampledAt, interval: gpuSampleIntervalSeconds ?? 15) } }
    public var gpuUsage: Double? { freshGPUs.compactMap(\.usagePercent).max() }
    public var gpuTemperature: Double? { freshGPUs.compactMap(\.temperatureCelsius).max() }
    public var primaryGPU: GPUInfo? {
        guard let gpu = gpus.first, gpu.isFresh(at: sampledAt, interval: gpuSampleIntervalSeconds ?? 15) else { return nil }
        return gpu
    }
    public var isClockSkewed: Bool { sampledAt.timeIntervalSinceNow > 300 }
    public func isFresh(at now: Date, pollingInterval: Double) -> Bool {
        now.timeIntervalSince(sampledAt) <= max(15, max(sampleIntervalSeconds, pollingInterval) * 3)
    }
}
public struct HostInfo: Codable {
    public let id: String, name: String, os: String, arch: String, kernel: String
    public let uptimeSeconds: Double
}
public struct CPUInfo: Codable {
    public let usagePercent: Double?
    public let cores: Int
    public let loadAverage: [Double]?
    public let packages: [CPUPackageInfo]?
}
public struct CPUPackageInfo: Codable, Identifiable {
    public let id: String, name: String
    public let packageID: Int, logicalCores: Int
    public let usagePercent: Double?, temperatureCelsius: Double?
}
public struct HardwareReading: Identifiable {
    public let id: String, title: String, menuCode: String, name: String
    public let isGPU: Bool
    public let usagePercent: Double?, temperatureCelsius: Double?
    public let memoryUsedBytes: UInt64?, memoryTotalBytes: UInt64?
    public var shortName: String {
        name.replacingOccurrences(of: "Intel(R) Xeon(R) CPU ", with: "Xeon ")
            .replacingOccurrences(of: "NVIDIA GeForce ", with: "")
            .components(separatedBy: " @ ").first ?? name
    }
}
public struct MemoryInfo: Codable {
    public let totalBytes: UInt64, usedBytes: UInt64, availableBytes: UInt64
    public let swapTotalBytes: UInt64, swapUsedBytes: UInt64
    public let usagePercent: Double?
}
public struct GPUInfo: Codable, Identifiable {
    public let id: String, name: String, vendor: String, status: String
    public let usagePercent: Double?, temperatureCelsius: Double?
    public let memoryUsedBytes: UInt64?, memoryTotalBytes: UInt64?
    public let powerWatts: Double?, fanPercent: Double?
    public let sampledAt: Date
    public func isFresh(at now: Date, interval: Double) -> Bool { now.timeIntervalSince(sampledAt) <= max(15, interval * 3) }
}
public struct DiskInfo: Codable, Identifiable {
    public let id: String, mount: String, filesystem: String
    public let totalBytes: UInt64, usedBytes: UInt64, availableBytes: UInt64
    public var usagePercent: Double? { totalBytes > 0 ? 100 * Double(usedBytes) / Double(totalBytes) : nil }
}
public struct NetworkInfo: Codable {
    public let receiveBytesPerSecond: Double?, sendBytesPerSecond: Double?
    public let diskReadBytesPerSecond: Double?, diskWriteBytesPerSecond: Double?
    public let interfaces: [NetworkInterface]
}
public struct NetworkInterface: Codable, Identifiable {
    public let name: String
    public let receivedBytes: UInt64, sentBytes: UInt64
    public let receiveBytesPerSecond: Double?, sendBytesPerSecond: Double?
    public let virtual: Bool
    public var id: String { name }
}
public struct SensorInfo: Codable, Identifiable {
    public let id: String, name: String, kind: String, source: String
    public let celsius: Double
}

public enum JSONCodec {
    public static func decoder() -> JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { input in
            let container = try input.singleValueContainer()
            let text = try container.decode(String.self)
            let formatter = ISO8601DateFormatter()
            formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            if let date = formatter.date(from: text) { return date }
            formatter.formatOptions = [.withInternetDateTime]
            if let date = formatter.date(from: text) { return date }
            throw DecodingError.dataCorruptedError(in: container, debugDescription: "Invalid ISO8601 timestamp")
        }
        return decoder
    }
    public static func encoder() -> JSONEncoder {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return encoder
    }
}

public enum MetricFormat {
    public static func percent(_ value: Double?) -> String { value.map { String(format: "%.0f%%", $0) } ?? "—" }
    public static func temperature(_ value: Double?) -> String { value.map { String(format: "%.0f°", $0) } ?? "—" }
    public static func bytes(_ value: UInt64?) -> String {
        guard let value else { return "—" }
        let number = Double(value)
        if number >= pow(1024, 4) { return String(format: "%.2f TiB", number / pow(1024, 4)) }
        if number >= pow(1024, 3) { return String(format: "%.1f GiB", number / pow(1024, 3)) }
        if number >= pow(1024, 2) { return String(format: "%.0f MiB", number / pow(1024, 2)) }
        return String(format: "%.0f KiB", number / 1024)
    }
    public static func rate(_ value: Double?) -> String {
        guard let value, value.isFinite, value >= 0 else { return "—" }
        if value >= 1024 * 1024 { return String(format: "%.1f MiB/s", value / (1024 * 1024)) }
        return String(format: "%.0f KiB/s", value / 1024)
    }
    public static func uptime(_ value: Double) -> String {
        let hours = Int(max(value, 0) / 3600)
        return hours >= 24 ? "\(hours / 24) 天 \(hours % 24) 小时" : "\(hours) 小时"
    }
}
