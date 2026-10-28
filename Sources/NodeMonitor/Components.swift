import SwiftUI
import Charts
import NodeMonitorCore

enum MonitorPalette {
    static let cpu = Color.cyan
    static let gpu = Color.green
    static let memory = Color.purple
    static let temperature = Color.orange
}

struct UsageBar: View {
    let percent: Double?
    let color: Color
    var height: CGFloat = 4
    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Capsule().fill(color.opacity(0.12))
                if let percent, percent.isFinite {
                    Capsule().fill(color.gradient).frame(width: proxy.size.width * max(0, min(100, percent)) / 100)
                }
            }
        }.frame(height: height).accessibilityLabel("使用率 \(MetricFormat.percent(percent))")
    }
}

struct HardwareStrip: View {
    let reading: HardwareReading
    var compact = false
    static func height(isGPU: Bool, compact: Bool) -> CGFloat { compact ? (isGPU ? 48 : 32) : (isGPU ? 60 : 40) }
    private var color: Color { reading.isGPU ? .teal : .blue }
    private var memoryPercent: Double? {
        guard let used = reading.memoryUsedBytes, let total = reading.memoryTotalBytes, total > 0 else { return nil }
        return 100 * Double(used) / Double(total)
    }
    var body: some View {
        VStack(alignment: .leading, spacing: compact ? 4 : 5) {
            HStack(spacing: 6) {
                Text(reading.title).font(.system(size: 10, weight: .bold)).foregroundStyle(color).frame(width: 39, alignment: .leading)
                if !compact { Text(reading.shortName).font(.system(size: 10)).foregroundStyle(.secondary).lineLimit(1) }
                Spacer(minLength: 0)
                Text(MetricFormat.percent(reading.usagePercent)).font(.system(size: 12, weight: .semibold)).monospacedDigit().frame(minWidth: 26, alignment: .trailing)
                Text(MetricFormat.temperature(reading.temperatureCelsius))
                    .font(.system(size: 10, weight: .medium)).monospacedDigit().foregroundStyle((reading.temperatureCelsius ?? 0) >= 80 ? .orange : .secondary)
                    .frame(width: 33, alignment: .trailing)
            }
            UsageBar(percent: reading.usagePercent, color: color)
            if reading.isGPU {
                HStack(spacing: 6) {
                    Text("显存").foregroundStyle(.secondary)
                    UsageBar(percent: memoryPercent, color: .purple, height: 3).frame(maxWidth: .infinity)
                    Text("\(MetricFormat.bytes(reading.memoryUsedBytes)) / \(MetricFormat.bytes(reading.memoryTotalBytes))").monospacedDigit().foregroundStyle(.secondary).fixedSize()
                }.font(.system(size: 9))
            }
        }
        .padding(.horizontal, 10)
        .frame(height: Self.height(isGPU: reading.isGPU, compact: compact))
        .background(color.opacity(0.045), in: RoundedRectangle(cornerRadius: 8))
        .help("\(reading.title) · \(reading.name)\n使用率 \(MetricFormat.percent(reading.usagePercent)) · 温度 \(MetricFormat.temperature(reading.temperatureCelsius))C")
    }
}

struct StatusBadge: View {
    let runtime: HostRuntime
    let cadence: Double
    var body: some View {
        HStack(spacing: 5) {
            Circle().fill(runtime.snapshot?.demo == true ? .orange : runtime.healthy(cadence: cadence) ? .green : .secondary).frame(width: 6, height: 6)
            Text(runtime.status(cadence: cadence)).font(.caption).foregroundStyle(.secondary)
        }
    }
}

struct MetricTile: View {
    let title: String
    let symbol: String
    let value: String
    let detail: String
    let color: Color
    var fraction: Double? = nil
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label(title, systemImage: symbol).font(.system(size: 12, weight: .medium)).foregroundStyle(.secondary)
            Text(value).font(.system(size: 29, weight: .semibold, design: .rounded)).monospacedDigit().lineLimit(1).minimumScaleFactor(0.65)
            GeometryReader { proxy in
                ZStack(alignment: .leading) {
                    Capsule().fill(color.opacity(0.12))
                    if let fraction { Capsule().fill(color.gradient).frame(width: proxy.size.width * max(0, min(1, fraction))) }
                }
            }.frame(height: 4)
            Text(detail).font(.system(size: 11)).foregroundStyle(.secondary).lineLimit(2).frame(minHeight: 26, alignment: .topLeading)
        }
        .padding(16).frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(nsColor: .controlBackgroundColor).opacity(0.75), in: RoundedRectangle(cornerRadius: 15))
        .overlay(RoundedRectangle(cornerRadius: 15).stroke(.primary.opacity(0.05)))
    }
}

struct MiniSparkline: View {
    let points: [HistoryPoint]
    let color: Color
    let gpu: Bool
    var body: some View {
        Canvas { context, size in
            guard let first = points.first?.date, let last = points.last?.date, last > first else { return }
            var path = Path(); var previous: Date?
            for point in points {
                guard let value = gpu ? point.gpu : point.cpu else { previous = nil; continue }
                let x = point.date.timeIntervalSince(first) / last.timeIntervalSince(first) * size.width
                let position = CGPoint(x: x, y: size.height * (1 - max(0, min(100, value)) / 100))
                if let previous, point.date.timeIntervalSince(previous) <= 180 { path.addLine(to: position) } else { path.move(to: position) }
                previous = point.date
            }
            context.stroke(path, with: .color(color), style: StrokeStyle(lineWidth: 1.8, lineCap: .round, lineJoin: .round))
        }
        .accessibilityLabel(gpu ? "GPU 历史曲线" : "CPU 历史曲线")
    }
}

struct HistoryChart: View {
    let points: [HistoryPoint]
    let range: Double
    var gapThreshold: Double = 180
    @State private var kind = "resources"
    private struct Plot: Identifiable {
        var id: String
        let date: Date
        let value: Double
        let series: String
        let segment: String
        let color: Color
    }
    private var plot: [Plot] {
        let fields: [(String, KeyPath<HistoryPoint, Double?>, Color)] = kind == "temperature" ? [("CPU", \.cpuTemperature, .orange), ("GPU", \.gpuTemperature, .green)] : kind == "network" ? [("接收", \.receive, .cyan), ("发送", \.send, .purple)] : [("CPU", \.cpu, .cyan), ("GPU", \.gpu, .green), ("内存", \.memory, .purple)]
        var result: [Plot] = []
        for (name, key, color) in fields {
            var last: Date?; var segment = 0
            for point in points {
                guard let value = point[keyPath: key] else { last = nil; segment += 1; continue }
                if let previous = last, point.date.timeIntervalSince(previous) > max(gapThreshold, range / 240 * 2) { segment += 1 }
                result.append(Plot(id: name + String(point.id), date: point.date, value: kind == "network" ? value / 1048576 : value, series: name, segment: "\(name)-\(segment)", color: color)); last = point.date
            }
        }
        return result
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack {
                Label("历史趋势", systemImage: "chart.xyaxis.line").font(.headline)
                Spacer()
                Picker("曲线", selection: $kind) {
                    Text("使用率").tag("resources"); Text("温度").tag("temperature"); Text("网络").tag("network")
                }.pickerStyle(.segmented).frame(width: 215)
            }
            if points.isEmpty {
                ContentUnavailableView("等待历史数据", systemImage: "chart.xyaxis.line", description: Text("连接成功后开始在本地记录，离线时保留空档。"))
                    .frame(height: 190)
            } else {
                let values = plot
                Chart(values) { point in
                    LineMark(x: .value("时间", point.date), y: .value("数值", point.value), series: .value("连续区间", point.segment))
                        .foregroundStyle(point.color).lineStyle(StrokeStyle(lineWidth: 1.7)).interpolationMethod(.linear)
                }
                .chartYScale(domain: 0...(kind == "network" ? max(1, (values.map(\.value).max() ?? 1) * 1.15) : kind == "temperature" ? 120 : 100))
                .chartXScale(domain: Date().addingTimeInterval(-range)...Date())
                .chartXAxis { AxisMarks(values: .automatic(desiredCount: 5)) { _ in AxisGridLine().foregroundStyle(.primary.opacity(0.05)); AxisValueLabel(format: .dateTime.hour().minute()) } }
                .chartYAxis { AxisMarks(position: .leading, values: .automatic(desiredCount: 4)) }
                .frame(height: 190)
                HStack(spacing: 15) {
                    Label(kind == "network" ? "接收" : "CPU", systemImage: "circle.fill").foregroundStyle(kind == "temperature" ? .orange : .cyan)
                    Label(kind == "network" ? "发送" : "GPU", systemImage: "circle.fill").foregroundStyle(kind == "network" ? .purple : .green)
                    if kind == "resources" { Label("内存", systemImage: "circle.fill").foregroundStyle(.purple) }
                    Spacer(); Text(kind == "temperature" ? "°C" : kind == "network" ? "MiB/s" : "%")
                }.font(.system(size: 10)).foregroundStyle(.secondary)
            }
        }.padding(20).background(Color(nsColor: .controlBackgroundColor).opacity(0.65), in: RoundedRectangle(cornerRadius: 16))
    }
}

struct SampleFooter: View {
    let snapshot: Snapshot?
    let mode: String
    var body: some View {
        HStack(spacing: 5) {
            Image(systemName: "clock")
            if let snapshot { Text("采样 " + snapshot.sampledAt.formatted(date: .omitted, time: .standard)) }
            else { Text("尚未收到数据") }
            Spacer(minLength: 8); Text(mode)
        }.font(.system(size: 10)).foregroundStyle(.secondary)
    }
}
