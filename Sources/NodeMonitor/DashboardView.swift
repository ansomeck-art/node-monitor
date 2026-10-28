import SwiftUI
import NodeMonitorCore

struct DashboardView: View {
    @ObservedObject var model: AppModel
    @State private var removal: HostConfiguration?
    private var groups: [String] { Array(Set(model.configuration.hosts.map(\.group))).sorted() }
    var body: some View {
        NavigationSplitView {
            VStack(spacing: 0) {
                HStack(spacing: 9) {
                    Image(systemName: "server.rack").font(.title2).foregroundStyle(.cyan)
                    VStack(alignment: .leading, spacing: 3) {
                        Text("节点监控").font(.headline)
                        Text("\(model.configuration.hosts.count) 台服务器").font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                }.padding(20)
                List(selection: Binding<UUID?>(get: { model.selectedHost?.id }, set: { if let id = $0 { model.select(id) } })) {
                    ForEach(groups, id: \.self) { group in
                        Section(group) {
                            ForEach(model.configuration.hosts.filter { $0.group == group }) { host in
                                let runtime = model.runtimes[host.id] ?? HostRuntime()
                                HStack(spacing: 9) {
                                    Image(systemName: host.demo ? "sparkles.rectangle.stack" : "server.rack").foregroundStyle(host.demo ? .orange : .secondary)
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(host.name).lineLimit(1).font(.system(size: 12, weight: .medium))
                                        Text(runtime.snapshot.map { "CPU " + MetricFormat.percent($0.cpu.usagePercent) + " · GPU " + MetricFormat.percent($0.gpuUsage) } ?? runtime.status(cadence: model.cadence)).font(.system(size: 10)).foregroundStyle(.secondary).lineLimit(1)
                                    }
                                    Spacer(minLength: 0)
                                    Circle().fill(host.demo ? .orange : runtime.healthy(cadence: model.cadence) ? .green : .gray).frame(width: 5, height: 5)
                                }.padding(.vertical, 6).tag(host.id)
                                    .contextMenu {
                                        Button("编辑连接") { model.editingHost = host }.disabled(host.demo)
                                        Button(host.widget.visible ? "隐藏桌面挂件" : "显示桌面挂件") { model.toggleWidget(host.id) }
                                        Divider()
                                        Button("移除主机…", role: .destructive) { removal = host }
                                    }
                            }
                        }
                    }
                }.listStyle(.sidebar)
                Divider()
                HStack {
                    Button { model.addHostVisible = true } label: { Label("添加主机", systemImage: "plus") }.buttonStyle(.plain)
                    Spacer()
                    Button { model.showPreferences?() } label: { Image(systemName: "gearshape") }.buttonStyle(.plain)
                }.padding(16)
            }.navigationSplitViewColumnWidth(min: 200, ideal: 220, max: 280)
        } detail: {
            if let host = model.selectedHost {
                HostDetailView(model: model, host: host, runtime: model.runtimes[host.id] ?? HostRuntime())
            } else {
                VStack(spacing: 22) {
                    Image(systemName: "waveform.path.ecg.rectangle").font(.system(size: 62, weight: .light)).foregroundStyle(.cyan)
                    VStack(spacing: 8) {
                        Text("服务器状态，抬眼就能看见。").font(.system(size: 26, weight: .semibold))
                        Text("Linux 采集端 → Mac 菜单栏、桌面挂件与监控台").foregroundStyle(.secondary)
                    }
                    HStack(spacing: 12) {
                        Button("添加服务器") { model.addHostVisible = true }.buttonStyle(.borderedProminent).controlSize(.large)
                        Button(model.startingDemo ? "正在启动…" : "先体验演示") { Task { await model.startDemo() } }.controlSize(.large).disabled(model.startingDemo)
                    }
                    Text("演示使用明确标记的模拟数据；不会扫描局域网或修改服务器。").font(.caption).foregroundStyle(.tertiary)
                }.frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .background(Color(nsColor: .windowBackgroundColor))
        .toolbar {
            ToolbarItemGroup {
                Button { model.restartPolling() } label: { Label("刷新", systemImage: "arrow.clockwise") }
                if let host = model.selectedHost {
                    Button { model.toggleWidget(host.id) } label: { Label("桌面挂件", systemImage: host.widget.visible ? "rectangle.inset.filled" : "rectangle.on.rectangle") }
                    Button { model.editingHost = host } label: { Label("编辑连接", systemImage: "slider.horizontal.3") }.disabled(host.demo)
                }
            }
        }
        .sheet(isPresented: $model.addHostVisible) { EditHostView(model: model, existing: nil) }
        .sheet(item: $model.editingHost) { host in EditHostView(model: model, existing: host) }
        .confirmationDialog("移除“\(removal?.name ?? "")”及其本地历史和令牌？", isPresented: Binding(get: { removal != nil }, set: { if !$0 { removal = nil } }), titleVisibility: .visible) {
            Button("移除", role: .destructive) { if let removal { model.deleteHost(removal.id) }; removal = nil }
            Button("取消", role: .cancel) { removal = nil }
        }
        .alert("节点监控", isPresented: Binding(get: { model.message != nil }, set: { if !$0 { model.message = nil } })) { Button("好") { model.message = nil } } message: { Text(model.message ?? "") }
    }
}

private struct HostDetailView: View {
    @ObservedObject var model: AppModel
    let host: HostConfiguration
    let runtime: HostRuntime
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 7) {
                        Text(host.name).font(.system(size: 25, weight: .semibold))
                        Text(runtime.snapshot.map { "\($0.host.os)  ·  \($0.cpu.cores) 核  ·  \($0.host.arch)" } ?? host.address).font(.system(size: 12)).foregroundStyle(.secondary)
                    }
                    Spacer()
                    VStack(alignment: .trailing, spacing: 8) {
                        StatusBadge(runtime: runtime, cadence: model.cadence)
                        Text(model.modeText).font(.caption).foregroundStyle(.secondary)
                    }.padding(.top, 6)
                }
                if host.demo || runtime.snapshot?.demo == true {
                    Label("演示模式 · 以下均为模拟读数，用于体验界面和验证采集通信。", systemImage: "sparkles")
                        .font(.caption).foregroundStyle(.orange).padding(11).frame(maxWidth: .infinity, alignment: .leading).background(.orange.opacity(0.08), in: RoundedRectangle(cornerRadius: 9))
                }
                if let error = runtime.error {
                    Label(error + (runtime.snapshot == nil ? "" : " 现有读数来自上次成功采样。"), systemImage: "wifi.exclamationmark").font(.callout).foregroundStyle(.orange).padding(12).frame(maxWidth: .infinity, alignment: .leading).background(.orange.opacity(0.08), in: RoundedRectangle(cornerRadius: 9))
                }
                if let sample = runtime.snapshot {
                    if !sample.isFresh(at: Date(), pollingInterval: model.cadence) || sample.isClockSkewed {
                        Label(sample.isClockSkewed ? "服务器时钟偏差超过 5 分钟，请校准时间。" : "采集端返回的数据已过期，请检查采样进程。", systemImage: "clock.badge.exclamationmark").font(.caption).foregroundStyle(.orange)
                    }
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 12), count: 2), spacing: 10) {
                        ForEach(sample.hardwareReadings) { reading in HardwareStrip(reading: reading) }
                    }.opacity(runtime.error == nil ? 1 : 0.6)
                    HStack(spacing: 14) {
                        Label("内存", systemImage: "memorychip").foregroundStyle(.secondary)
                        UsageBar(percent: sample.memory.usagePercent, color: .purple)
                        Text("\(MetricFormat.percent(sample.memory.usagePercent)) · \(MetricFormat.bytes(sample.memory.usedBytes)) / \(MetricFormat.bytes(sample.memory.totalBytes))").monospacedDigit().fixedSize()
                    }.font(.system(size: 12))
                    HStack {
                        Text("运行 \(MetricFormat.uptime(sample.host.uptimeSeconds))").font(.caption).foregroundStyle(.secondary)
                        if let load = sample.cpu.loadAverage, load.count == 3 { Text("· 负载 " + load.map { String(format: "%.2f", $0) }.joined(separator: " / ")).font(.caption).foregroundStyle(.secondary) }
                        Spacer()
                        Picker("历史范围", selection: $model.rangeSeconds) {
                            Text("15 分钟").tag(900.0); Text("1 小时").tag(3600.0); Text("24 小时").tag(86400.0); Text("7 天").tag(604800.0)
                        }.labelsHidden().frame(width: 115).onChange(of: model.rangeSeconds) { _, _ in model.refreshHistory() }
                    }
                    HistoryChart(points: model.chartPoints, range: model.rangeSeconds, gapThreshold: max(15, model.cadence * 3))
                    if !sample.gpus.isEmpty {
                        VStack(alignment: .leading, spacing: 14) {
                            Label("显卡 · \(sample.gpus.count) 张", systemImage: "display").font(.headline)
                            ForEach(sample.gpus) { gpu in
                                VStack(alignment: .leading, spacing: 10) {
                                    HStack { Text(gpu.name).font(.system(size: 12, weight: .medium)); Spacer(); Text(gpu.status == "partial" ? "部分指标未提供" : "采样 " + gpu.sampledAt.formatted(date: .omitted, time: .standard)).font(.system(size: 10)).foregroundStyle(.secondary) }
                                    HStack(spacing: 22) {
                                        GPUField(title: "使用率", value: MetricFormat.percent(gpu.usagePercent), color: .green)
                                        GPUField(title: "温度", value: MetricFormat.temperature(gpu.temperatureCelsius), color: .orange)
                                        GPUField(title: "显存", value: "\(MetricFormat.bytes(gpu.memoryUsedBytes)) / \(MetricFormat.bytes(gpu.memoryTotalBytes))", color: .purple)
                                        GPUField(title: "功耗", value: gpu.powerWatts.map { String(format: "%.0f W", $0) } ?? "未提供", color: .secondary)
                                        GPUField(title: "风扇", value: MetricFormat.percent(gpu.fanPercent), color: .secondary)
                                        Spacer(minLength: 0)
                                    }
                                    if !gpu.isFresh(at: Date(), interval: sample.gpuSampleIntervalSeconds ?? 15) { Text("该显卡的读数已过期").font(.caption).foregroundStyle(.orange) }
                                }
                                if gpu.id != sample.gpus.last?.id { Divider() }
                            }
                        }.padding(20).background(.background.opacity(0.65), in: RoundedRectangle(cornerRadius: 16))
                    } else {
                        Label(gpuReason(sample.capabilities["gpu"]), systemImage: "display.trianglebadge.exclamationmark").font(.caption).foregroundStyle(.secondary)
                    }
                    HStack(alignment: .top, spacing: 18) {
                        VStack(alignment: .leading, spacing: 14) {
                            Label("磁盘", systemImage: "internaldrive").font(.headline)
                            ForEach(sample.disks) { disk in
                                VStack(alignment: .leading, spacing: 8) {
                                    HStack { Text(disk.mount).font(.system(size: 12, weight: .medium)); Spacer(); Text(MetricFormat.percent(disk.usagePercent)).font(.caption).monospacedDigit() }
                                    ProgressView(value: (disk.usagePercent ?? 0) / 100).tint(.cyan)
                                    Text("\(MetricFormat.bytes(disk.usedBytes)) / \(MetricFormat.bytes(disk.totalBytes)) · 可用 \(MetricFormat.bytes(disk.availableBytes))").font(.system(size: 10)).foregroundStyle(.secondary)
                                }
                            }
                            if sample.disks.isEmpty { Text("磁盘数据未提供").font(.caption).foregroundStyle(.secondary) }
                            Divider()
                            Text("读 \(MetricFormat.rate(sample.network.diskReadBytesPerSecond))  写 \(MetricFormat.rate(sample.network.diskWriteBytesPerSecond))").font(.caption).monospacedDigit().foregroundStyle(.secondary)
                        }.padding(20).frame(maxWidth: .infinity, alignment: .leading).background(.background.opacity(0.65), in: RoundedRectangle(cornerRadius: 16))
                        VStack(alignment: .leading, spacing: 14) {
                            Label("网络", systemImage: "network").font(.headline)
                            HStack { GPUField(title: "↓ 接收", value: MetricFormat.rate(sample.network.receiveBytesPerSecond), color: .cyan); Spacer(); GPUField(title: "↑ 发送", value: MetricFormat.rate(sample.network.sendBytesPerSecond), color: .purple) }
                            DisclosureGroup("网卡明细") {
                                ForEach(sample.network.interfaces) { item in
                                    HStack { Text(item.name + (item.virtual ? " · 虚拟" : "")); Spacer(); Text("↓\(MetricFormat.rate(item.receiveBytesPerSecond)) ↑\(MetricFormat.rate(item.sendBytesPerSecond))") }.font(.system(size: 10)).foregroundStyle(.secondary).padding(.top, 4)
                                }
                            }.font(.caption)
                        }.padding(20).frame(maxWidth: .infinity, alignment: .leading).background(.background.opacity(0.65), in: RoundedRectangle(cornerRadius: 16))
                    }
                    DisclosureGroup("传感器 · \(sample.sensors.count) 项") {
                        ForEach(sample.sensors) { sensor in
                            HStack { Text(sensor.name); Text(sensor.kind).foregroundStyle(.tertiary); Spacer(); Text(MetricFormat.temperature(sensor.celsius)).monospacedDigit() }.font(.caption).padding(.vertical, 3).help(sensor.source)
                        }
                        if sample.memoryTemperature == nil { Text("内存温度未提供：需要主板和内存暴露相应传感器。").font(.caption).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .leading).padding(.top, 6) }
                    }.padding(16).background(.background.opacity(0.65), in: RoundedRectangle(cornerRadius: 12))
                    ForEach(sample.warnings.filter { !sample.demo || !$0.contains("演示") }, id: \.self) { Text($0).font(.caption).foregroundStyle(.orange) }
                    SampleFooter(snapshot: sample, mode: "Agent \(sample.agentVersion) · 本地保存 \(model.configuration.retentionDays) 天")
                } else {
                    ContentUnavailableView("等待采集端", systemImage: "antenna.radiowaves.left.and.right", description: Text(runtime.error ?? "正在建立连接，数据将在这里显示。"))
                        .frame(maxWidth: .infinity, minHeight: 360)
                }
                EventList(events: model.events.filter { $0.hostID == host.id })
            }.padding(26).frame(maxWidth: 1280)
        }
    }
    private func gpuReason(_ reason: String?) -> String {
        switch reason { case "disabled": return "GPU 采集已关闭"; case "error": return "GPU 查询失败，请检查驱动和权限"; case "unavailable": return "检测到显卡，但指标不可读取"; default: return "主机未提供 GPU 指标" }
    }
}

struct GPUField: View {
    let title: String
    let value: String
    let color: Color
    var body: some View {
        VStack(alignment: .leading, spacing: 5) { Text(title).font(.system(size: 10)).foregroundStyle(.secondary); Text(value).font(.system(size: 13, weight: .medium)).monospacedDigit().foregroundStyle(color).lineLimit(1).minimumScaleFactor(0.7) }
    }
}

struct EventList: View {
    let events: [AlertEvent]
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("最近事件", systemImage: "bell").font(.headline)
            if events.isEmpty { Text("暂无事件。持续高温、高占用、磁盘不足或失联时会在这里记录。").font(.caption).foregroundStyle(.secondary) }
            ForEach(Array(events.prefix(15))) { event in
                HStack {
                    Image(systemName: event.recovered ? "checkmark.circle.fill" : "exclamationmark.circle.fill").foregroundStyle(event.recovered ? .green : .orange)
                    Text(event.message).font(.caption)
                    Spacer()
                    Text(event.date.formatted(date: .abbreviated, time: .shortened)).font(.system(size: 10)).foregroundStyle(.secondary)
                }
            }
        }.padding(18).frame(maxWidth: .infinity, alignment: .leading).background(.background.opacity(0.65), in: RoundedRectangle(cornerRadius: 14))
    }
}
