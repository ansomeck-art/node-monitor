import Foundation

public struct WidgetSettings: Codable {
    public var visible = false
    public var locked = false
    public var alwaysOnTop = false
    public var opacity = 0.94
    public var compact = false
    public var frame: String? = nil
    public init() {}
}
public struct HostConfiguration: Codable, Identifiable {
    public var id: UUID
    public var name: String
    public var group: String
    public var address: String
    public var allowHTTP: Bool
    public var widget = WidgetSettings()
    public var demo = false
    public init(id: UUID = UUID(), name: String, group: String = "服务器", address: String, allowHTTP: Bool = false, demo: Bool = false) {
        self.id = id; self.name = name; self.group = group; self.address = address; self.allowHTTP = allowHTTP; self.demo = demo
    }
}
public struct AlertThresholds: Codable {
    public var enabled = true
    public var cpu = 90.0
    public var memory = 90.0
    public var cpuTemperature = 90.0
    public var gpuTemperature = 85.0
    public var disk = 95.0
    public var durationSeconds = 60.0
    public var offlineSeconds = 60.0
    public init() {}
}
public struct AppConfiguration: Codable {
    public var schemaVersion = 1
    public var hosts: [HostConfiguration] = []
    public var selectedHostID: UUID? = nil
    public var menuMetric = "summary"
    public var energySaver = false
    public var appearance = "system"
    public var notifications = false
    public var retentionDays = 7
    public var thresholds = AlertThresholds()
    public init() {}
}

public struct PortablePaths {
    public let root: URL
    public var data: URL { root.appendingPathComponent("data/mac", isDirectory: true) }
    public var configuration: URL { data.appendingPathComponent("settings.json") }
    public var database: URL { data.appendingPathComponent("history.sqlite") }
    public var credentials: URL { data.appendingPathComponent("credentials", isDirectory: true) }
    public init(root: URL) throws {
        self.root = root.standardizedFileURL
        try FileManager.default.createDirectory(at: credentials, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: data.path)
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: credentials.path)
    }
}
public final class ConfigurationStore {
    public let paths: PortablePaths
    public init(paths: PortablePaths) { self.paths = paths }
    public func load() throws -> AppConfiguration {
        guard FileManager.default.fileExists(atPath: paths.configuration.path) else { return AppConfiguration() }
        let configuration = try JSONCodec.decoder().decode(AppConfiguration.self, from: Data(contentsOf: paths.configuration))
        guard configuration.schemaVersion == 1 else { throw ConfigurationError.incompatible }
        return configuration
    }
    public func save(_ configuration: AppConfiguration) throws {
        try privateWrite(JSONCodec.encoder().encode(configuration), to: paths.configuration)
    }
    public func token(for id: UUID) throws -> String {
        let data = try Data(contentsOf: tokenURL(id))
        guard let value = String(data: data, encoding: .utf8), value.trimmingCharacters(in: .whitespacesAndNewlines).count >= 32 else { throw ConfigurationError.invalidToken }
        return value.trimmingCharacters(in: .whitespacesAndNewlines)
    }
    public func saveToken(_ token: String, for id: UUID) throws {
        let value = token.trimmingCharacters(in: .whitespacesAndNewlines)
        guard value.count >= 32 else { throw ConfigurationError.invalidToken }
        try privateWrite(Data(value.utf8), to: tokenURL(id))
    }
    public func removeToken(for id: UUID) throws {
        let url = tokenURL(id)
        if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
    }
    private func tokenURL(_ id: UUID) -> URL { paths.credentials.appendingPathComponent(id.uuidString + ".token") }
    private func privateWrite(_ data: Data, to url: URL) throws {
        try data.write(to: url, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
    }
}
public enum ConfigurationError: LocalizedError {
    case incompatible, invalidToken
    public var errorDescription: String? {
        switch self {
        case .incompatible: return "配置来自不兼容的版本，原文件已保留。"
        case .invalidToken: return "访问令牌至少需要 32 个字符。"
        }
    }
}
