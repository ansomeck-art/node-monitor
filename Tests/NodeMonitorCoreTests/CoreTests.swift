import Foundation
import NodeMonitorCore

final class CoreTests {
    private var directory: URL!
    func setUpWithError() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        directory = root.appendingPathComponent(".cache/tests/" + UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }
    func tearDownWithError() throws { if let directory { try FileManager.default.removeItem(at: directory) } }
    func testUnknownMetricsAndActualZeroRemainDifferent() throws {
        let sample = try fixture(at: Date(), cpu: 0, temperature: nil)
        XCTAssertEqual(sample.cpu.usagePercent, 0)
        XCTAssertNil(sample.cpuTemperature); XCTAssertNil(sample.memoryTemperature); XCTAssertNil(sample.gpuTemperature)
        XCTAssertEqual(MetricFormat.percent(0), "0%"); XCTAssertEqual(MetricFormat.percent(nil), "—")
    }
    func testIndividualHardwareAndLegacyAgentCompatibility() throws {
        let now = Date()
        let legacy = try fixture(at: now, cpu: 90, temperature: 99, gpuAge: 0)
        XCTAssertNil(legacy.cpu.packages)
        XCTAssertEqual(legacy.cpuReadings.count, 1)
        XCTAssertEqual(legacy.cpuReadings.first?.title, "CPU")
        var object = try JSONSerialization.jsonObject(with: JSONCodec.encoder().encode(legacy)) as! [String: Any]
        var cpu = object["cpu"] as! [String: Any]
        cpu["packages"] = [
            ["id": "package-0", "packageID": 0, "name": "Xeon A", "logicalCores": 32, "usagePercent": 7, "temperatureCelsius": 32],
            ["id": "package-1", "packageID": 1, "name": "Xeon B", "logicalCores": 32, "usagePercent": 63, "temperatureCelsius": 48]
        ]
        object["cpu"] = cpu
        var gpus = object["gpus"] as! [[String: Any]]
        var secondGPU = gpus[0]
        secondGPU["id"] = "gpu1"; secondGPU["usagePercent"] = 86; secondGPU["temperatureCelsius"] = 73
        secondGPU["memoryUsedBytes"] = 1200; secondGPU["memoryTotalBytes"] = 2000
        gpus.append(secondGPU); object["gpus"] = gpus
        let dual = try JSONCodec.decoder().decode(Snapshot.self, from: JSONSerialization.data(withJSONObject: object))
        XCTAssertEqual(dual.hardwareReadings.count, 4)
        XCTAssertEqual(dual.hardwareReadings.map(\.title), ["CPU 1", "CPU 2", "GPU 1", "GPU 2"])
        XCTAssertEqual(dual.menuStatus(metric: "summary"), "C1 7% 32°  C2 63% 48°  G1 0% 35°  G2 86% 73°")
        XCTAssertEqual(dual.gpuReadings[1].memoryUsedBytes, 1200)
        gpus[1]["sampledAt"] = ISO8601DateFormatter().string(from: now.addingTimeInterval(-60)); object["gpus"] = gpus
        let stale = try JSONCodec.decoder().decode(Snapshot.self, from: JSONSerialization.data(withJSONObject: object))
        XCTAssertEqual(stale.gpuReadings.count, 2)
        XCTAssertEqual(stale.gpuReadings[0].usagePercent, 0)
        XCTAssertNil(stale.gpuReadings[1].usagePercent); XCTAssertNil(stale.gpuReadings[1].memoryUsedBytes)
    }
    func testAddressRejectsCredentialsAndRequiresExplicitHTTP() throws {
        XCTAssertThrowsError(try AgentClient.endpoint("https://example-user@example.com", allowHTTP: false))
        XCTAssertThrowsError(try AgentClient.endpoint("http://192.168.1.20:9274", allowHTTP: false))
        XCTAssertThrowsError(try AgentClient.endpoint("https://example.com?token=bad", allowHTTP: false))
        XCTAssertEqual(try AgentClient.endpoint("http://127.0.0.1:9274", allowHTTP: false).path, "/v1/snapshot")
        XCTAssertEqual(try AgentClient.endpoint("https://example.com/agent/", allowHTTP: false).path, "/agent/v1/snapshot")
    }
    func testGPUCacheExpiryKeepsDeviceButRemovesCurrentSummary() throws {
        let now = Date()
        let fresh = try fixture(at: now, gpuAge: 0)
        XCTAssertEqual(fresh.gpuUsage, 0); XCTAssertEqual(fresh.gpuTemperature, 35)
        XCTAssertNotNil(fresh.primaryGPU)
        let stale = try fixture(at: now, gpuAge: 46)
        XCTAssertEqual(stale.gpus.count, 1)
        XCTAssertNil(stale.gpuUsage); XCTAssertNil(stale.gpuTemperature); XCTAssertNil(stale.primaryGPU)
    }
    func testPortablePersistenceSeparatesCredentialAndRestrictsPermissions() throws {
        let paths = try PortablePaths(root: directory); let store = ConfigurationStore(paths: paths)
        let host = HostConfiguration(name: "test", address: "https://example.com")
        var settings = AppConfiguration(); settings.hosts = [host]
        let secret = String(repeating: "a", count: 43)
        try store.saveToken(secret, for: host.id); try store.save(settings)
        XCTAssertEqual(try store.token(for: host.id), secret)
        XCTAssertFalse(try String(contentsOf: paths.configuration, encoding: .utf8).contains(secret))
        XCTAssertEqual(try store.load().hosts.first?.id, host.id)
        let file = paths.credentials.appendingPathComponent(host.id.uuidString + ".token")
        let permission = try FileManager.default.attributesOfItem(atPath: file.path)[.posixPermissions] as? NSNumber
        XCTAssertEqual((permission?.intValue ?? 0o777) & 0o077, 0)
    }
    func testHistoryDeduplicatesCachedSamplesAndKeepsNulls() throws {
        let paths = try PortablePaths(root: directory); let store = try HistoryStore(url: paths.database)
        let id = UUID(); let now = Date(); let sample = try fixture(at: now, cpu: 0)
        try store.insert(host: id, sample: sample); try store.insert(host: id, sample: sample)
        let points = try store.points(host: id, since: now.addingTimeInterval(-30))
        XCTAssertEqual(points.count, 1); XCTAssertEqual(points.first?.cpu, 0); XCTAssertNil(points.first?.gpu)
        try store.insert(host: id, sample: fixture(at: now.addingTimeInterval(-172800)))
        try store.prune(retentionDays: 1, now: now)
        XCTAssertEqual(try store.points(host: id, since: now.addingTimeInterval(-200000)).count, 1)
    }
    func testAlertDurationHysteresisAndRecovery() throws {
        let engine = AlertEngine(); let now = Date(); let host = HostConfiguration(name: "test", address: "https://example.com")
        var thresholds = AlertThresholds(); thresholds.durationSeconds = 10
        XCTAssertTrue(engine.evaluate(host: host, sample: try fixture(at: now, cpu: 98), thresholds: thresholds, now: now).isEmpty)
        XCTAssertTrue(engine.evaluate(host: host, sample: try fixture(at: now.addingTimeInterval(5), cpu: 98), thresholds: thresholds, now: now.addingTimeInterval(5)).isEmpty)
        let trigger = engine.evaluate(host: host, sample: try fixture(at: now.addingTimeInterval(10), cpu: 98), thresholds: thresholds, now: now.addingTimeInterval(10))
        XCTAssertEqual(trigger.map(\.metric), ["cpu"]); XCTAssertEqual(trigger.first?.recovered, false)
        XCTAssertTrue(engine.evaluate(host: host, sample: try fixture(at: now.addingTimeInterval(15), cpu: 88), thresholds: thresholds, now: now.addingTimeInterval(15)).isEmpty)
        let recovery = engine.evaluate(host: host, sample: try fixture(at: now.addingTimeInterval(20), cpu: 84), thresholds: thresholds, now: now.addingTimeInterval(20))
        XCTAssertEqual(recovery.first?.recovered, true)
    }
    func testRepeatedOrGappedSamplesDoNotInventContinuousHighUsage() throws {
        let engine = AlertEngine(); let now = Date(); let host = HostConfiguration(name: "test", address: "https://example.com")
        var thresholds = AlertThresholds(); thresholds.durationSeconds = 10
        let sample = try fixture(at: now, cpu: 99)
        XCTAssertTrue(engine.evaluate(host: host, sample: sample, thresholds: thresholds, now: now).isEmpty)
        XCTAssertTrue(engine.evaluate(host: host, sample: sample, thresholds: thresholds, now: now.addingTimeInterval(12)).isEmpty)
        XCTAssertTrue(engine.evaluate(host: host, sample: try fixture(at: now.addingTimeInterval(25), cpu: 99), thresholds: thresholds, now: now.addingTimeInterval(25)).isEmpty)
    }
    func testOfflineOnlyAlertsOnceAndRecoversOnFreshData() throws {
        let engine = AlertEngine(); let now = Date(); let host = HostConfiguration(name: "test", address: "https://example.com"); let thresholds = AlertThresholds()
        XCTAssertTrue(engine.unreachable(host: host, since: now, thresholds: thresholds, now: now.addingTimeInterval(59)).isEmpty)
        XCTAssertEqual(engine.unreachable(host: host, since: now, thresholds: thresholds, now: now.addingTimeInterval(60)).count, 1)
        XCTAssertTrue(engine.unreachable(host: host, since: now, thresholds: thresholds, now: now.addingTimeInterval(90)).isEmpty)
        let events = engine.evaluate(host: host, sample: try fixture(at: now.addingTimeInterval(95)), thresholds: thresholds, now: now.addingTimeInterval(95))
        XCTAssertEqual(events.first?.metric, "offline"); XCTAssertEqual(events.first?.recovered, true)
    }
    func testBackgroundCadenceCanTriggerSustainedAlert() throws {
        let engine = AlertEngine(); let now = Date(); let host = HostConfiguration(name: "test", address: "https://example.com")
        var thresholds = AlertThresholds(); thresholds.durationSeconds = 60
        XCTAssertTrue(engine.evaluate(host: host, sample: try fixture(at: now, cpu: 99), thresholds: thresholds, now: now, pollingInterval: 60).isEmpty)
        let later = now.addingTimeInterval(60)
        let events = engine.evaluate(host: host, sample: try fixture(at: later, cpu: 99), thresholds: thresholds, now: later, pollingInterval: 60)
        XCTAssertEqual(events.map(\.metric), ["cpu"])
    }
    func testBelowThresholdBreaksPendingDurationBeforeHysteresisApplies() throws {
        let engine = AlertEngine(); let now = Date(); let host = HostConfiguration(name: "test", address: "https://example.com")
        var thresholds = AlertThresholds(); thresholds.durationSeconds = 10
        for (offset, value) in [(0.0, 99.0), (5, 88), (10, 99), (15, 99)] {
            let date = now.addingTimeInterval(offset)
            XCTAssertTrue(engine.evaluate(host: host, sample: try fixture(at: date, cpu: value), thresholds: thresholds, now: date).isEmpty)
        }
        let date = now.addingTimeInterval(20)
        XCTAssertEqual(engine.evaluate(host: host, sample: try fixture(at: date, cpu: 99), thresholds: thresholds, now: date).count, 1)
    }
    func testLiveAgentContractAuthenticationAndCaching() async throws {
        guard let address = ProcessInfo.processInfo.environment["NODEMON_TEST_URL"], let file = ProcessInfo.processInfo.environment["NODEMON_TEST_TOKEN_FILE"] else { throw XCTSkip("Run scripts/test.sh for the local Go-Swift integration test") }
        let token = try String(contentsOfFile: file, encoding: .utf8).trimmingCharacters(in: .whitespacesAndNewlines)
        let client = AgentClient()
        let first = try await client.fetch(address: address, token: token, allowHTTP: false)
        let second = try await client.fetch(address: address, token: token, allowHTTP: false)
        XCTAssertTrue(first.demo); XCTAssertEqual(first.schemaVersion, 1); XCTAssertEqual(first.sampledAt, second.sampledAt)
        XCTAssertNotNil(first.cpu.usagePercent); XCTAssertEqual(first.gpus.count, 1)
        XCTAssertEqual(first.cpu.packages?.count, 1)
        do { _ = try await client.fetch(address: address, token: "invalid", allowHTTP: false); XCTFail("Invalid credential accepted") } catch AgentError.unauthorized { }
    }
    private func fixture(at date: Date, cpu: Double? = 40, temperature: Double? = nil, gpuAge: Double? = nil) throws -> Snapshot {
        let object: [String: Any] = [
            "schemaVersion": 1, "agentVersion": "test", "sampledAt": ISO8601DateFormatter().string(from: date), "sampleIntervalSeconds": 5, "demo": false,
            "host": ["id": "test", "name": "test", "os": "Ubuntu", "arch": "amd64", "kernel": "6", "uptimeSeconds": 100],
            "cpu": ["usagePercent": cpu as Any? ?? NSNull(), "cores": 8, "loadAverage": [1, 1, 1]],
            "memory": ["totalBytes": 1000, "usedBytes": 500, "availableBytes": 500, "swapTotalBytes": 0, "swapUsedBytes": 0, "usagePercent": 50],
            "gpus": gpuAge.map { [["id": "gpu0", "name": "test", "vendor": "NVIDIA", "status": "ok", "usagePercent": 0, "temperatureCelsius": 35, "sampledAt": ISO8601DateFormatter().string(from: date.addingTimeInterval(-$0))]] } ?? [], "disks": [], "network": ["interfaces": []],
            "sensors": temperature.map { [["id": "package", "name": "Package", "kind": "cpu", "source": "fixture", "celsius": $0]] } ?? [],
            "capabilities": ["cpu": "ok"], "warnings": []
        ]
        return try JSONCodec.decoder().decode(Snapshot.self, from: JSONSerialization.data(withJSONObject: object))
    }
}
