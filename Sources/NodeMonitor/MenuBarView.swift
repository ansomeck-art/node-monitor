import SwiftUI
import NodeMonitorCore

struct MenuBarView: View {
    @ObservedObject var model: AppModel
    var body: some View {
        ScrollView(.vertical) {
            VStack(alignment: .leading, spacing: 16) {
                HStack {
                    Label("节点监控", systemImage: "server.rack").font(.headline)
                    Spacer()
                    Button { model.showPreferences?() } label: { Image(systemName: "gearshape") }.buttonStyle(.plain).help("设置")
                }
                if model.configuration.hosts.isEmpty {
                    VStack(spacing: 12) {
                        Image(systemName: "network").font(.system(size: 36)).foregroundStyle(.tint)
                        Text("把服务器的状态，放到眼前。").font(.headline)
                        Text("添加 Linux 采集端，或先体验演示。 ").font(.caption).foregroundStyle(.secondary)
                        Button("添加服务器") { model.showDashboard?(); model.addHostVisible = true }.buttonStyle(.borderedProminent)
                        Button(model.startingDemo ? "启动演示中…" : "查看演示") { Task { await model.startDemo() } }.disabled(model.startingDemo)
                    }.frame(maxWidth: .infinity).padding(.vertical, 24)
                } else if let host = model.selectedHost {
                    HStack {
                        Picker("主机", selection: Binding(get: { host.id }, set: { model.select($0) })) {
                            ForEach(model.configuration.hosts) { Text($0.name).tag($0.id) }
                        }.labelsHidden().frame(maxWidth: .infinity, alignment: .leading)
                        StatusBadge(runtime: model.selectedRuntime, cadence: model.cadence)
                    }
                    let snapshot = model.selectedRuntime.snapshot
                    if let snapshot {
                        VStack(spacing: 8) {
                            ForEach(snapshot.hardwareReadings) { reading in HardwareStrip(reading: reading) }
                        }
                    } else {
                        Text("等待硬件信息…").font(.caption).foregroundStyle(.secondary)
                    }
                    if let snapshot {
                        HStack {
                            Label("内存", systemImage: "memorychip").foregroundStyle(.secondary)
                            Spacer()
                            Text(snapshot.memory.totalBytes > 0 ? "\(MetricFormat.bytes(snapshot.memory.usedBytes)) / \(MetricFormat.bytes(snapshot.memory.totalBytes))" : "未提供").monospacedDigit()
                        }.font(.system(size: 12))
                        HStack { Label("网络", systemImage: "network").foregroundStyle(.secondary); Spacer(); Text("↓ \(MetricFormat.rate(snapshot.network.receiveBytesPerSecond))  ↑ \(MetricFormat.rate(snapshot.network.sendBytesPerSecond))").monospacedDigit() }.font(.system(size: 11))
                    }
                    if let error = model.selectedRuntime.error { Text(error).font(.caption).foregroundStyle(.orange).fixedSize(horizontal: false, vertical: true) }
                    SampleFooter(snapshot: snapshot, mode: model.modeText)
                    Divider()
                    HStack {
                        Button { model.toggleWidget(host.id) } label: { Label(host.widget.visible ? "隐藏挂件" : "固定到桌面", systemImage: "rectangle.on.rectangle") }
                        Spacer()
                        Button("打开监控台") { model.showDashboard?() }.buttonStyle(.borderedProminent)
                    }
                }
                HStack {
                    Text("原生轻量 · 数据保存在项目内").font(.system(size: 10)).foregroundStyle(.tertiary)
                    Spacer()
                    Button("退出") { NSApplication.shared.terminate(nil) }.font(.caption).buttonStyle(.plain).foregroundStyle(.secondary)
                }
            }.padding(18).frame(maxWidth: .infinity, alignment: .leading)
        }.background(.regularMaterial)
    }
}
