import Foundation

public enum PollingSchedule {
    public static func backoff(failures: Int) -> TimeInterval {
        failures <= 0 ? 0 : min(60, pow(2, Double(min(failures, 4))) * 5)
    }

    // Healthy nodes keep the selected cadence. Failed nodes get an earlier,
    // bounded retry even when energy-saving mode polls every 60 seconds.
    public static func nextDelay(cadence: TimeInterval, failed: [(lastAttempt: Date, failures: Int)], now: Date) -> TimeInterval {
        let next = failed.filter { $0.failures > 0 }.map { entry in
            max(1, backoff(failures: entry.failures) - now.timeIntervalSince(entry.lastAttempt))
        }.min() ?? cadence
        return min(cadence, next)
    }
}
