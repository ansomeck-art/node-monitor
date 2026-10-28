// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "NodeMonitor",
    platforms: [.macOS(.v14)],
    products: [.executable(name: "NodeMonitor", targets: ["NodeMonitor"]), .executable(name: "NodeMonitorChecks", targets: ["NodeMonitorChecks"])],
    targets: [
        .systemLibrary(name: "CSQLite", path: "Sources/CSQLite"),
        .target(name: "NodeMonitorCore", dependencies: ["CSQLite"]),
        .executableTarget(name: "NodeMonitor", dependencies: ["NodeMonitorCore"]),
        .executableTarget(name: "NodeMonitorChecks", dependencies: ["NodeMonitorCore"], path: "Tests/NodeMonitorCoreTests")
    ],
    swiftLanguageModes: [.v5]
)
