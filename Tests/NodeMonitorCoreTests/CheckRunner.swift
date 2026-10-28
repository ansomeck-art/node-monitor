import Foundation

// Standalone assertions for Command Line Tools installations without XCTest.
// Failure diagnostics intentionally never print compared values (credentials).
struct CheckError: Error, CustomStringConvertible { let description: String }
func XCTAssertEqual<T: Equatable>(_ lhs: @autoclosure () throws -> T, _ rhs: @autoclosure () throws -> T, file: StaticString = #filePath, line: UInt = #line) {
    do { guard try lhs() == rhs() else { fatalError("Equality check failed at \(file):\(line)") } } catch { fatalError("Equality check threw at \(file):\(line)") }
}
func XCTAssertTrue(_ condition: @autoclosure () throws -> Bool, file: StaticString = #filePath, line: UInt = #line) {
    do { guard try condition() else { fatalError("Condition failed at \(file):\(line)") } } catch { fatalError("Condition threw at \(file):\(line)") }
}
func XCTAssertFalse(_ condition: @autoclosure () throws -> Bool, file: StaticString = #filePath, line: UInt = #line) { XCTAssertTrue(try !condition(), file: file, line: line) }
func XCTAssertNil<T>(_ value: @autoclosure () throws -> T?, file: StaticString = #filePath, line: UInt = #line) { XCTAssertTrue(try value() == nil, file: file, line: line) }
func XCTAssertNotNil<T>(_ value: @autoclosure () throws -> T?, file: StaticString = #filePath, line: UInt = #line) { XCTAssertTrue(try value() != nil, file: file, line: line) }
func XCTAssertThrowsError<T>(_ body: @autoclosure () throws -> T, file: StaticString = #filePath, line: UInt = #line) {
    do { _ = try body() } catch { return }; fatalError("Expected rejection at \(file):\(line)")
}
func XCTFail(_ message: String) -> Never { fatalError(message) }
func XCTSkip(_ message: String) -> CheckError { CheckError(description: message) }

@main struct CheckRunner {
    static func main() async {
        let suite = CoreTests()
        let checks: [(String, () throws -> Void)] = [
            ("missing metrics versus real zero", suite.testUnknownMetricsAndActualZeroRemainDifferent),
            ("individual CPUs and GPUs, legacy compatibility and per-card expiry", suite.testIndividualHardwareAndLegacyAgentCompatibility),
            ("GPU cache expiry and unavailable summary", suite.testGPUCacheExpiryKeepsDeviceButRemovesCurrentSummary),
            ("URL, HTTPS policy and embedded credentials", suite.testAddressRejectsCredentialsAndRequiresExplicitHTTP),
            ("project-local credential and configuration storage", suite.testPortablePersistenceSeparatesCredentialAndRestrictsPermissions),
            ("history deduplication, nulls and retention", suite.testHistoryDeduplicatesCachedSamplesAndKeepsNulls),
            ("sustained threshold, hysteresis and recovery", suite.testAlertDurationHysteresisAndRecovery),
            ("duplicate samples and interrupted duration", suite.testRepeatedOrGappedSamplesDoNotInventContinuousHighUsage),
            ("sustained alerts at background cadence", suite.testBackgroundCadenceCanTriggerSustainedAlert),
            ("below-threshold values reset pending alert duration", suite.testBelowThresholdBreaksPendingDurationBeforeHysteresisApplies),
            ("offline event deduplication and recovery", suite.testOfflineOnlyAlertsOnceAndRecoversOnFreshData)
        ]
        do {
            for (name, check) in checks { try suite.setUpWithError(); try check(); try suite.tearDownWithError(); print("PASS \(name)") }
            try suite.setUpWithError(); try await suite.testLiveAgentContractAuthenticationAndCaching(); try suite.tearDownWithError()
            print("PASS live Go-Swift HTTP protocol, authentication and cache")
            print("\(checks.count + 1) checks passed")
        } catch { print("FAIL \(error)"); exit(1) }
    }
}
