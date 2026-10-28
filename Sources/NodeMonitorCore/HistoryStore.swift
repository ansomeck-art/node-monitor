import Foundation
import CSQLite

public struct HistoryPoint: Identifiable {
    public var id: Double { date.timeIntervalSince1970 }
    public let date: Date
    public let cpu: Double?, memory: Double?, gpu: Double?, cpuTemperature: Double?, gpuTemperature: Double?
    public let receive: Double?, send: Double?
}
public final class HistoryStore {
    private var database: OpaquePointer?
    private var lastPrune = Date.distantPast
    private let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
    public init(url: URL) throws {
        guard sqlite3_open_v2(url.path, &database, SQLITE_OPEN_CREATE | SQLITE_OPEN_READWRITE | SQLITE_OPEN_FULLMUTEX, nil) == SQLITE_OK else { throw HistoryError.database }
        sqlite3_busy_timeout(database, 1500)
        try execute("PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL; PRAGMA cache_size=-2048;")
        var statement: OpaquePointer?
        if sqlite3_prepare_v2(database, "PRAGMA user_version", -1, &statement, nil) == SQLITE_OK, sqlite3_step(statement) == SQLITE_ROW, sqlite3_column_int(statement, 0) > 1 { sqlite3_finalize(statement); throw HistoryError.incompatible }
        sqlite3_finalize(statement)
        try execute("""
        CREATE TABLE IF NOT EXISTS samples(host TEXT NOT NULL, time REAL NOT NULL, cpu REAL, memory REAL, gpu REAL, cpu_temp REAL, gpu_temp REAL, rx REAL, tx REAL, PRIMARY KEY(host,time));
        CREATE TABLE IF NOT EXISTS events(id TEXT PRIMARY KEY, time REAL NOT NULL, body BLOB NOT NULL);
        CREATE INDEX IF NOT EXISTS events_time ON events(time);
        PRAGMA user_version=1;
        """)
    }
    deinit { sqlite3_close(database) }
    private func execute(_ sql: String) throws {
        guard sqlite3_exec(database, sql, nil, nil, nil) == SQLITE_OK else { throw HistoryError.database }
    }
    private func prepare(_ sql: String) throws -> OpaquePointer {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(database, sql, -1, &statement, nil) == SQLITE_OK, let statement else { throw HistoryError.database }
        return statement
    }
    public func insert(host: UUID, sample: Snapshot) throws {
        let query = try prepare("INSERT OR IGNORE INTO samples VALUES(?,?,?,?,?,?,?,?,?)")
        defer { sqlite3_finalize(query) }
        sqlite3_bind_text(query, 1, host.uuidString, -1, transient)
        sqlite3_bind_double(query, 2, sample.sampledAt.timeIntervalSince1970)
        let values = [sample.cpu.usagePercent, sample.memory.usagePercent, sample.gpuUsage, sample.cpuTemperature, sample.gpuTemperature, sample.network.receiveBytesPerSecond, sample.network.sendBytesPerSecond]
        for (index, value) in values.enumerated() {
            if let value, value.isFinite { sqlite3_bind_double(query, Int32(index + 3), value) } else { sqlite3_bind_null(query, Int32(index + 3)) }
        }
        guard sqlite3_step(query) == SQLITE_DONE else { throw HistoryError.database }
    }
    public func points(host: UUID, since: Date, maxPoints: Int = 240) throws -> [HistoryPoint] {
        let bucket = max(1, Date().timeIntervalSince(since) / Double(max(2, maxPoints)))
        let query = try prepare("SELECT MIN(time),AVG(cpu),AVG(memory),AVG(gpu),AVG(cpu_temp),AVG(gpu_temp),AVG(rx),AVG(tx) FROM samples WHERE host=? AND time>=? GROUP BY CAST(time / ? AS INTEGER) ORDER BY MIN(time)")
        defer { sqlite3_finalize(query) }
        sqlite3_bind_text(query, 1, host.uuidString, -1, transient)
        sqlite3_bind_double(query, 2, since.timeIntervalSince1970)
        sqlite3_bind_double(query, 3, bucket)
        var result: [HistoryPoint] = []
        while sqlite3_step(query) == SQLITE_ROW {
            func value(_ column: Int32) -> Double? { sqlite3_column_type(query, column) == SQLITE_NULL ? nil : sqlite3_column_double(query, column) }
            result.append(HistoryPoint(date: Date(timeIntervalSince1970: sqlite3_column_double(query, 0)), cpu: value(1), memory: value(2), gpu: value(3), cpuTemperature: value(4), gpuTemperature: value(5), receive: value(6), send: value(7)))
        }
        return result
    }
    public func record(_ event: AlertEvent) throws {
        let body = try JSONCodec.encoder().encode(event)
        let query = try prepare("INSERT OR IGNORE INTO events VALUES(?,?,?)")
        defer { sqlite3_finalize(query) }
        sqlite3_bind_text(query, 1, event.id.uuidString, -1, transient)
        sqlite3_bind_double(query, 2, event.date.timeIntervalSince1970)
        _ = body.withUnsafeBytes { sqlite3_bind_blob(query, 3, $0.baseAddress, Int32(body.count), transient) }
        guard sqlite3_step(query) == SQLITE_DONE else { throw HistoryError.database }
    }
    public func events(limit: Int = 100) throws -> [AlertEvent] {
        let query = try prepare("SELECT body FROM events ORDER BY time DESC LIMIT ?")
        defer { sqlite3_finalize(query) }
        sqlite3_bind_int(query, 1, Int32(max(1, min(limit, 1000))))
        var result: [AlertEvent] = []
        while sqlite3_step(query) == SQLITE_ROW {
            guard let pointer = sqlite3_column_blob(query, 0) else { continue }
            let data = Data(bytes: pointer, count: Int(sqlite3_column_bytes(query, 0)))
            if let event = try? JSONCodec.decoder().decode(AlertEvent.self, from: data) { result.append(event) }
        }
        return result
    }
    public func prune(retentionDays: Int, now: Date = Date()) throws {
        guard now.timeIntervalSince(lastPrune) >= 3600 else { return }
        let cutoff = now.addingTimeInterval(-Double(max(1, min(retentionDays, 30))) * 86400).timeIntervalSince1970
        try execute("DELETE FROM samples WHERE time < \(cutoff); DELETE FROM events WHERE time < \(cutoff);")
        lastPrune = now
    }
    public func remove(host: UUID) throws {
        let query = try prepare("DELETE FROM samples WHERE host=?")
        defer { sqlite3_finalize(query) }
        sqlite3_bind_text(query, 1, host.uuidString, -1, transient)
        guard sqlite3_step(query) == SQLITE_DONE else { throw HistoryError.database }
    }
}
public enum HistoryError: LocalizedError {
    case database, incompatible
    public var errorDescription: String? { self == .incompatible ? "历史数据库版本不兼容，原文件已保留。" : "无法读写本地历史数据库。" }
}
