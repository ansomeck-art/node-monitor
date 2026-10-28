import SwiftUI
import NodeMonitorCore

struct WidgetView: View {
    @ObservedObject var model: AppModel
    let hostID: UUID
    private var host: HostConfiguration? { model.configuration.hosts.first { $0.id == hostID } }
    private var runtime: HostRuntime { model.runtimes[hostID] ?? HostRuntime() }

    static func preferredSize(sample: Snapshot?, compact: Bool) -> NSSize {
        let readings = sample?.hardwareReadings ?? []
        let rows = readings.isEmpty ? 56 : readings.reduce(CGFloat.zero) { $0 + HardwareStrip.height(isGPU: $1.isGPU, compact: compact) } + CGFloat(max(0, readings.count - 1)) * 8
        return NSSize(width: compact ? 306 : 346, height: (compact ? 120 : 162) + rows)
    }

    var body: some View {
        if let host {
            let sample = runtime.snapshot
            let compact = host.widget.compact
            let size = Self.preferredSize(sample: sample, compact: compact)
            VStack(alignment: .leading, spacing: compact ? 10 : 12) {
                HStack(spacing: 8) {
                    HStack(spacing: 8) {
                        Image(systemName: host.demo ? "sparkles" : "server.rack")
                            .font(.system(size: 12)).foregroundStyle(host.demo ? .orange : .blue)
                            .frame(width: 28, height: 28).background(.blue.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
                        VStack(alignment: .leading, spacing: 3) {
                            Text(host.name).font(.system(size: 12, weight: .semibold)).lineLimit(1)
                            HStack(spacing: 5) {
                                Circle().fill(runtime.healthy(cadence: model.cadence) ? .green : .orange).frame(width: 5, height: 5)
                                Text(runtime.status(cadence: model.cadence))
                                if let sample {
                                    Text("·")
                                    Text(sample.cpu.packages?.isEmpty == false ? "\(sample.cpuReadings.count) CPU · \(sample.gpus.count) GPU" : "CPU 整机 · \(sample.gpus.count) GPU")
                                }
                            }.font(.system(size: 9)).foregroundStyle(.secondary).lineLimit(1)
                        }
                        Spacer(minLength: 0)
                    }.overlay(WindowDragArea()).help("拖动标题可移动挂件")
                    Button { model.showDashboard?(); model.select(host.id) } label: { Image(systemName: "arrow.up.right.square") }.buttonStyle(.plain).help("打开监控台")
                    Button { model.toggleWidget(host.id) } label: { Image(systemName: "xmark") }.buttonStyle(.plain).help("隐藏挂件")
                }.frame(height: compact ? 28 : 32)

                VStack(spacing: 8) {
                    if let sample {
                        ForEach(sample.hardwareReadings) { reading in HardwareStrip(reading: reading, compact: compact) }
                    } else {
                        Text("等待硬件信息…").font(.caption).foregroundStyle(.secondary).frame(maxWidth: .infinity, minHeight: 56)
                    }
                }.opacity(runtime.error == nil ? 1 : 0.5)

                VStack(spacing: compact ? 4 : 6) {
                    HStack {
                        Label("内存", systemImage: "memorychip").foregroundStyle(.secondary)
                        Spacer()
                        Text(MetricFormat.percent(sample?.memory.usagePercent)).fontWeight(.medium)
                        Text("\(MetricFormat.bytes(sample?.memory.usedBytes)) / \(MetricFormat.bytes(sample?.memory.totalBytes))").foregroundStyle(.secondary)
                    }.font(.system(size: 10)).monospacedDigit()
                    UsageBar(percent: sample?.memory.usagePercent, color: .purple)
                }.frame(height: compact ? 22 : 24)
                if !compact {
                    HStack {
                        Text("↓ \(MetricFormat.rate(sample?.network.receiveBytesPerSecond))")
                        Spacer()
                        Text("↑ \(MetricFormat.rate(sample?.network.sendBytesPerSecond))")
                    }.font(.system(size: 10)).monospacedDigit().foregroundStyle(.secondary).frame(height: 14)
                }
                SampleFooter(snapshot: sample, mode: host.demo ? "演示 · \(Int(model.cadence))s" : "\(Int(model.cadence))s").frame(height: 12)
            }
            .padding(compact ? 14 : 16)
            .frame(width: size.width, height: size.height, alignment: .top)
            .background {
                RoundedRectangle(cornerRadius: 18).fill(Color(nsColor: .windowBackgroundColor).opacity(0.96))
                RoundedRectangle(cornerRadius: 18).fill(.thinMaterial).opacity(0.12)
            }
            .overlay(RoundedRectangle(cornerRadius: 18).stroke(.primary.opacity(0.07), lineWidth: 1))
            .contextMenu {
                Button(compact ? "标准尺寸" : "紧凑尺寸") { model.updateWidget(host.id) { $0.compact.toggle() } }
                Button(host.widget.alwaysOnTop ? "取消置顶" : "始终置顶") { model.updateWidget(host.id) { $0.alwaysOnTop.toggle() } }
                Button("锁定位置（在设置中解锁）") { model.updateWidget(host.id) { $0.locked = true } }
                Divider(); Button("隐藏挂件") { model.toggleWidget(host.id) }
            }
        }
    }
}

// Keep the title draggable while the adjacent buttons remain interactive.
private struct WindowDragArea: NSViewRepresentable {
    func makeNSView(context: Context) -> DragView { DragView() }
    func updateNSView(_ nsView: DragView, context: Context) {}
    final class DragView: NSView {
        override var mouseDownCanMoveWindow: Bool { true }
        override func mouseDown(with event: NSEvent) { window?.performDrag(with: event) }
    }
}
