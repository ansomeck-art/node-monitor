import SwiftUI
import NodeMonitorCore

struct EditHostView: View {
    @ObservedObject var model: AppModel
    @ObservedObject private var tailscale: EmbeddedTailscaleManager
    let existing: HostConfiguration?
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var group = "服务器"
    @State private var address = ""
    @State private var remoteAddress = ""
    @State private var connectionMode: ConnectionMode = .local
    @State private var token = ""
    @State private var allowHTTP = false
    @State private var error: String?
    init(model: AppModel, existing: HostConfiguration?) {
        self.model = model; self.existing = existing; self.tailscale = model.tailscale
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(spacing: 12) {
                Image(systemName: "server.rack").font(.system(size: 20, weight: .medium)).foregroundStyle(.blue)
                    .frame(width: 44, height: 44).background(.blue.opacity(0.10), in: RoundedRectangle(cornerRadius: 12))
                VStack(alignment: .leading, spacing: 4) {
                    Text(existing == nil ? "添加服务器" : "编辑服务器连接").font(.system(size: 20, weight: .semibold))
                    Text("在局域网或远程网络中查看服务器状态").font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Label(connectionMode == .local ? "局域网直连" : "加密远程", systemImage: connectionMode == .local ? "network" : "lock.shield").font(.caption.weight(.medium)).foregroundStyle(.blue)
                    .padding(.horizontal, 10).padding(.vertical, 6).background(.blue.opacity(0.08), in: Capsule())
            }

            VStack(alignment: .leading, spacing: 7) {
                Text("连接方式").font(.caption.weight(.medium)).foregroundStyle(.secondary)
                Picker("连接方式", selection: $connectionMode) {
                    Text("局域网连接").tag(ConnectionMode.local)
                    Text("远程连接").tag(ConnectionMode.remote)
                }.pickerStyle(.segmented).labelsHidden()
            }

            HStack(spacing: 10) {
                Label("Mac", systemImage: "laptopcomputer")
                Image(systemName: "arrow.left.arrow.right").foregroundStyle(.blue)
                VStack(alignment: .leading, spacing: 3) {
                    Text(connectionMode == .local ? "局域网直连" : "Tailscale 私有网络").fontWeight(.semibold)
                    Text(connectionMode == .local ? "同一网络中通过服务器内网地址连接" : "网络能力已集成，无需另装客户端").font(.caption2).foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: "arrow.left.arrow.right").foregroundStyle(.blue)
                Label("服务器", systemImage: "server.rack")
            }.font(.caption).padding(14).frame(maxWidth: .infinity)
                .background(.blue.opacity(0.06), in: RoundedRectangle(cornerRadius: 12))

            if connectionMode == .remote {
                VStack(spacing: 0) {
                    HStack(spacing: 10) {
                        Image(systemName: "laptopcomputer").foregroundStyle(.blue).frame(width: 24)
                        VStack(alignment: .leading, spacing: 3) {
                            Text("这台 Mac").font(.caption.weight(.semibold))
                            Text(tailscale.message).font(.caption2).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button(tailscale.connected ? "已授权" : "登录并授权") { tailscale.login() }.disabled(tailscale.busy || tailscale.connected)
                        Button { tailscale.refresh() } label: { Image(systemName: "arrow.clockwise") }.help("刷新本程序的远程连接状态")
                    }.padding(12)
                    Divider().padding(.horizontal, 12)
                    HStack(spacing: 10) {
                        Image(systemName: "server.rack").foregroundStyle(.blue).frame(width: 24)
                        VStack(alignment: .leading, spacing: 3) {
                            Text("Ubuntu 服务器").font(.caption.weight(.semibold))
                            Text("采集端需要授权并加入同一网络。接入状态以实际连接为准。").font(.caption2).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button("配置步骤") { NSWorkspace.shared.open(model.paths.root.appendingPathComponent("docs/Tailscale远程连接.md")) }
                    }.padding(12)
                }.background(.quaternary.opacity(0.45), in: RoundedRectangle(cornerRadius: 10))
            }

            VStack(alignment: .leading, spacing: 14) {
                HStack(alignment: .top, spacing: 14) {
                    field("服务器名称") { TextField("Ubuntu 工作站", text: $name) }
                    field("分组") { TextField("服务器", text: $group) }
                }
                if connectionMode == .local {
                    field("服务器局域网地址") {
                        TextField("http://192.168.1.2:9274", text: $address)
                    }
                } else {
                    field("服务器的远程设备地址") {
                        TextField("100.x.x.x:9274 或 设备名.网络名.ts.net:9274", text: $remoteAddress)
                    }
                }
                field("访问令牌") {
                    SecureField(existing == nil ? "从服务器的 agent.token 文件复制" : "留空保留现有令牌", text: $token)
                }
                if connectionMode == .local {
                    Toggle("允许明文 HTTP（仅可信局域网或隧道）", isOn: $allowHTTP).toggleStyle(.checkbox)
                }
            }.font(.callout)
            Text(connectionMode == .local ? "局域网模式仅使用上方地址。明文 HTTP 请仅在可信局域网使用。" : "通过浏览器授权本程序；不需要单独安装 Tailscale。传输仅经过加密私有网络。")
                .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            if let error { Text(error).font(.caption).foregroundStyle(.orange).fixedSize(horizontal: false, vertical: true) }
            HStack {
                Button(connectionMode == .local ? "局域网连接说明" : "远程连接说明") {
                    NSWorkspace.shared.open(model.paths.root.appendingPathComponent(connectionMode == .local ? "docs/部署与使用.md" : "docs/Tailscale远程连接.md"))
                }
                Spacer()
                Button("取消") { dismiss() }.keyboardShortcut(.cancelAction)
                Button(existing == nil ? "添加并连接" : "保存并连接") { save() }.buttonStyle(.borderedProminent).keyboardShortcut(.defaultAction).disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }.padding(26).frame(width: 600).onAppear {
            if let host = existing {
                name = host.name; group = host.group; address = host.address; allowHTTP = host.allowHTTP
                remoteAddress = host.remoteAddress ?? ""; connectionMode = host.selectedConnectionMode
            }
            if connectionMode == .remote { tailscale.refresh() }
        }
        .onChange(of: connectionMode) { _, value in if value == .remote { tailscale.refresh() } }
        .onDisappear { model.stopRemoteIfUnused() }
    }
    private func field<Content: View>(_ label: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(label).font(.caption.weight(.medium)).foregroundStyle(.secondary)
            content().textFieldStyle(.roundedBorder).controlSize(.large)
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
    private func save() {
        do {
            var host = existing ?? HostConfiguration(name: name, address: address)
            host.name = name.trimmingCharacters(in: .whitespacesAndNewlines)
            host.group = group.trimmingCharacters(in: .whitespacesAndNewlines); if host.group.isEmpty { host.group = "服务器" }
            host.address = address.trimmingCharacters(in: .whitespacesAndNewlines); host.allowHTTP = allowHTTP
            let remote = remoteAddress.trimmingCharacters(in: .whitespacesAndNewlines)
            host.remoteAddress = remote.isEmpty ? nil : remote
            host.connectionMode = connectionMode; host.allowInsecureFallback = nil
            try model.saveHost(host, token: token); token = ""; dismiss()
        } catch { self.error = error.localizedDescription }
    }
}

struct PreferencesView: View {
    @ObservedObject var model: AppModel
    @State private var tab = "general"
    var body: some View {
        TabView(selection: $tab) {
            Form {
                Section("显示") {
                    Picker("菜单栏内容", selection: preference(\.menuMetric)) {
                        Text("每颗 CPU + 每张 GPU").tag("summary"); Text("每颗 CPU").tag("cpu"); Text("每张 GPU").tag("gpu"); Text("每颗 CPU 温度").tag("temperature")
                    }
                    Picker("外观", selection: preference(\.appearance)) {
                        Text("跟随系统").tag("system"); Text("浅色").tag("light"); Text("深色").tag("dark")
                    }
                    Toggle("节能模式（所有界面每 60 秒更新）", isOn: preference(\.energySaver))
                    Text("通常在界面或挂件可见时每 5 秒读取，后台每 60 秒读取。服务器基础采样独立运行，GPU 默认每 15 秒查询。").font(.caption).foregroundStyle(.secondary)
                }
                Section("本地数据") {
                    Picker("历史保留", selection: preference(\.retentionDays)) { Text("1 天").tag(1); Text("7 天").tag(7); Text("30 天").tag(30) }
                    Button("在 Finder 中打开项目数据目录") { NSWorkspace.shared.open(model.paths.data) }
                    Text("设置、凭证与 SQLite 历史均保存在项目的 data 文件夹中。Mac 退出或休眠期间，本地历史会留下空档。").font(.caption).foregroundStyle(.secondary)
                }
                Section {
                    HStack { Text("节点监控 0.1.0"); Spacer(); Button("使用说明") { NSWorkspace.shared.open(model.paths.root.appendingPathComponent("README.md")) } }
                    Text("纯原生界面 · 自有 Go 采集端 · 不需要 Docker 或 Hub").font(.caption).foregroundStyle(.secondary)
                }
            }.formStyle(.grouped).tabItem { Label("通用", systemImage: "gearshape") }.tag("general")
            Form {
                Section("告警规则") {
                    Toggle("记录持续异常与恢复事件", isOn: threshold(\.enabled))
                    thresholdRow("CPU 使用率", value: threshold(\.cpu), unit: "%", range: 50...100)
                    thresholdRow("内存使用率", value: threshold(\.memory), unit: "%", range: 50...100)
                    thresholdRow("CPU 温度", value: threshold(\.cpuTemperature), unit: "°C", range: 50...110)
                    thresholdRow("GPU 温度", value: threshold(\.gpuTemperature), unit: "°C", range: 50...110)
                    thresholdRow("磁盘使用率", value: threshold(\.disk), unit: "%", range: 60...99)
                    Picker("持续时间", selection: threshold(\.durationSeconds)) { Text("30 秒").tag(30.0); Text("60 秒").tag(60.0); Text("120 秒").tag(120.0) }
                    Picker("失联提醒", selection: threshold(\.offlineSeconds)) { Text("60 秒").tag(60.0); Text("120 秒").tag(120.0); Text("5 分钟").tag(300.0) }
                }
                Section("系统通知") {
                    if model.configuration.notifications {
                        Toggle("发送系统通知", isOn: preference(\.notifications))
                    } else { Button("启用系统通知…") { Task { await model.requestNotifications() } } }
                    Text("恢复线比触发线低 5 个百分点或 5°C；同一指标通知冷却 5 分钟。采样中断会重新计算持续时间。应用退出后无法发出本地提醒。").font(.caption).foregroundStyle(.secondary)
                }
            }.formStyle(.grouped).tabItem { Label("提醒", systemImage: "bell") }.tag("alerts")
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Text("桌面实时挂件").font(.headline)
                    Text("挂件由应用持续显示，可拖动到任意屏幕。锁定后忽略鼠标操作，可在这里解锁。").font(.caption).foregroundStyle(.secondary)
                    ForEach(model.configuration.hosts) { host in
                        VStack(alignment: .leading, spacing: 12) {
                            HStack { Text(host.name).font(.headline); Spacer(); Toggle("显示", isOn: widget(host.id, \.visible)).toggleStyle(.switch) }
                            HStack { Toggle("锁定位置", isOn: widget(host.id, \.locked)); Spacer(); Toggle("始终置顶", isOn: widget(host.id, \.alwaysOnTop)); Spacer(); Toggle("紧凑尺寸", isOn: widget(host.id, \.compact)) }.font(.caption)
                            HStack { Text("透明度").font(.caption); Slider(value: widget(host.id, \.opacity), in: 0.5...1); Text("\(Int(host.widget.opacity * 100))%").font(.caption).frame(width: 35) }
                        }.padding(16).background(.quaternary.opacity(0.4), in: RoundedRectangle(cornerRadius: 12))
                    }
                    if model.configuration.hosts.isEmpty { Text("添加服务器后可配置桌面挂件。").foregroundStyle(.secondary) }
                }.padding(24)
            }.tabItem { Label("挂件", systemImage: "rectangle.on.rectangle") }.tag("widgets")
        }.padding(12).frame(width: 620, height: 580)
    }
    private func preference<T>(_ key: WritableKeyPath<AppConfiguration, T>) -> Binding<T> {
        Binding(get: { model.configuration[keyPath: key] }, set: { value in model.updatePreferences { $0[keyPath: key] = value } })
    }
    private func threshold<T>(_ key: WritableKeyPath<AlertThresholds, T>) -> Binding<T> {
        Binding(get: { model.configuration.thresholds[keyPath: key] }, set: { value in model.updatePreferences { $0.thresholds[keyPath: key] = value } })
    }
    private func widget<T>(_ id: UUID, _ key: WritableKeyPath<WidgetSettings, T>) -> Binding<T> {
        Binding(get: { (model.configuration.hosts.first { $0.id == id }?.widget ?? WidgetSettings())[keyPath: key] }, set: { value in model.updateWidget(id) { $0[keyPath: key] = value } })
    }
    private func thresholdRow(_ title: String, value: Binding<Double>, unit: String, range: ClosedRange<Double>) -> some View {
        HStack { Text(title); Spacer(); Stepper(value: value, in: range, step: 1) { Text("\(Int(value.wrappedValue)) \(unit)").monospacedDigit().frame(width: 65, alignment: .trailing) } }
    }
}
