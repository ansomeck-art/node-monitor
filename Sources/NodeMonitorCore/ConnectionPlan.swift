import Foundation
import Darwin

public struct ConnectionTarget {
    public enum Route { case local, remote }
    public let address: String
    public let allowHTTP: Bool
    public let route: Route
}

public enum ConnectionPlan {
    public static func targets(for host: HostConfiguration) throws -> [ConnectionTarget] {
        switch host.selectedConnectionMode {
        case .local:
            _ = try AgentClient.endpoint(host.address, allowHTTP: host.allowHTTP)
            return [ConnectionTarget(address: host.address, allowHTTP: host.allowHTTP, route: .local)]
        case .remote:
            guard let remote = host.remoteAddress?.trimmingCharacters(in: .whitespacesAndNewlines), !remote.isEmpty else { throw AgentError.invalidRemoteAddress }
            try validateRemoteAddress(remote)
            return [ConnectionTarget(address: remote, allowHTTP: false, route: .remote)]
        }
    }

    // HTTP here is used only inside the embedded Tailscale transport; the
    // caller must never send this address through an ordinary URLSession.
    public static func validateRemoteAddress(_ value: String) throws {
        let raw = value.trimmingCharacters(in: .whitespacesAndNewlines)
        let address = raw.contains("://") ? raw : "http://" + raw
        guard let parts = URLComponents(string: address), let scheme = parts.scheme?.lowercased(), ["http", "https"].contains(scheme),
              var host = parts.host?.lowercased(), !host.isEmpty, parts.user == nil, parts.password == nil,
              parts.query == nil, parts.fragment == nil, parts.path.isEmpty || parts.path == "/" else { throw AgentError.invalidRemoteAddress }
        if host.hasPrefix("[") && host.hasSuffix("]") { host = String(host.dropFirst().dropLast()) }
        if let port = parts.port, !(1...65535).contains(port) { throw AgentError.invalidRemoteAddress }
        let octets = host.split(separator: ".", omittingEmptySubsequences: false)
        let isIPv4 = octets.count == 4 && octets.allSatisfy { piece in
            guard !piece.isEmpty, piece.utf8.allSatisfy({ (48...57).contains($0) }), let number = Int(piece) else { return false }
            return (0...255).contains(number) && String(number) == String(piece)
        }
        if isIPv4 {
            guard Int(octets[0]) == 100, let second = Int(octets[1]), (64...127).contains(second) else { throw AgentError.invalidRemoteAddress }
        } else if host.contains(":") {
            var ip = in6_addr()
            guard inet_pton(AF_INET6, host, &ip) == 1 else { throw AgentError.invalidRemoteAddress }
            let bytes = withUnsafeBytes(of: &ip) { Array($0) }
            guard Array(bytes.prefix(6)) == [0xfd, 0x7a, 0x11, 0x5c, 0xa1, 0xe0] else { throw AgentError.invalidRemoteAddress }
        } else {
            let labels = host.split(separator: ".", omittingEmptySubsequences: false)
            guard host.hasSuffix(".ts.net"), host.utf8.count <= 253, labels.count >= 3,
                  labels.allSatisfy({ label in
                      !label.isEmpty && label.utf8.count <= 63 && label.first != "-" && label.last != "-" &&
                      label.utf8.allSatisfy { (97...122).contains($0) || (48...57).contains($0) || $0 == 45 }
                  }) else { throw AgentError.invalidRemoteAddress }
        }
    }

    public static func fetch<T>(host: HostConfiguration, attempt: (ConnectionTarget) async throws -> T) async throws -> (T, ConnectionTarget) {
        let target = try targets(for: host)[0]
        return (try await attempt(target), target)
    }
}
