import SwiftUI
import NodeMonitorCore

struct EditHostView: View {
    @ObservedObject var model: AppModel
    let existing: HostConfiguration?
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var group = "服务器"
    @State private var address = "https://"
    @State private var token = ""
    @State private var allowHTTP = false
    @State private var error: String?
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Label(existing == nil ? "添加服务器" : "编辑连接", systemImage: "server.rack").font(.title2.bold())
            Text("填写 Linux 采集端的地址和访问令牌。").font(.callout).foregroundStyle(.secondary)
            Form {
                TextField("名称", text: $name, prompt: Text("例如：Ubuntu 工作站"))
                TextField("分组", text: $group)
                TextField("采集端地址", text: $address, prompt: Text("https://server.example.com:9274"))
                SecureField("访问令牌", text: $token, prompt: Text(existing == nil ? "从服务器的 agent.token 文件复制" : "留空保留现有令牌"))
                Toggle("允许明文 HTTP（仅可信局域网或隧道）", isOn: $allowHTTP)
            }.formStyle(.grouped).frame(height: 225)
            Text("公网使用 HTTPS，或通过 SSH 隧道连接 127.0.0.1。令牌只保存在项目内的权限受限文件中。").font(.caption).foregroundStyle(.secondary)
            if let error { Text(error).font(.caption).foregroundStyle(.orange).fixedSize(horizontal: false, vertical: true) }
            HStack {
                Button("查看部署说明") { NSWorkspace.shared.open(model.paths.root.appendingPathComponent("docs/部署与使用.md")) }
                Spacer()
                Button("取消") { dismiss() }.keyboardShortcut(.cancelAction)
                Button(existing == nil ? "添加并连接" : "保存并连接") { save() }.buttonStyle(.borderedProminent).keyboardShortcut(.defaultAction).disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }.padding(26).frame(width: 540).onAppear {
            if let host = existing { name = host.name; group = host.group; address = host.address; allowHTTP = host.allowHTTP }
        }
    }
    private func save() {
        do {
            var host = existing ?? HostConfiguration(name: name, address: address)
            host.name = name.trimmingCharacters(in: .whitespacesAndNewlines)
            host.group = group.trimmingCharacters(in: .whitespacesAndNewlines); if host.group.isEmpty { host.group = "服务器" }
            host.address = address.trimmingCharacters(in: .whitespacesAndNewlines); host.allowHTTP = allowHTTP
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
