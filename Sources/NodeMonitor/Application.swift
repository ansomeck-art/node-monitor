import AppKit
import Combine
import SwiftUI
import NodeMonitorCore

@main struct NodeMonitorMain {
    @MainActor static func main() {
        let application = NSApplication.shared
        let delegate = ApplicationDelegate()
        application.delegate = delegate
        application.setActivationPolicy(.accessory)
        application.run()
        withExtendedLifetime(delegate) {}
    }
}

@MainActor final class ApplicationDelegate: NSObject, NSApplicationDelegate, NSPopoverDelegate, NSWindowDelegate {
    private var model: AppModel?
    private var statusItem: NSStatusItem?
    private let popover = NSPopover()
    private var dashboard: NSWindow?
    private var preferences: NSWindow?
    private var panels: [UUID: NSPanel] = [:]
    private var widgetSizes: [UUID: NSSize] = [:]
    private var subscription: AnyCancellable?
    private var workspaceObservers: [NSObjectProtocol] = []

    func applicationDidFinishLaunching(_ notification: Notification) {
        do {
            let root = try projectRoot()
            let model = try AppModel(root: root); self.model = model
            model.showDashboard = { [weak self] in self?.openDashboard() }
            model.showPreferences = { [weak self] in self?.openPreferences() }
            model.widgetsChanged = { [weak self] in self?.syncWidgets() }
            let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
            statusItem = item
            item.button?.target = self; item.button?.action = #selector(togglePopover)
            item.button?.font = .monospacedSystemFont(ofSize: 11, weight: .medium)
            item.button?.image = NSImage(systemSymbolName: "server.rack", accessibilityDescription: "节点监控")
            item.button?.imagePosition = .imageLeading
            popover.behavior = .transient; popover.delegate = self; popover.animates = false
            subscription = model.objectWillChange.sink { [weak self] _ in DispatchQueue.main.async { self?.updateStatus(); self?.applyAppearance() } }
            installMenu()
            updateStatus(); syncWidgets(); applyAppearance(); model.start()
            let center = NSWorkspace.shared.notificationCenter
            workspaceObservers.append(center.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main) { [weak self] _ in Task { @MainActor in self?.model?.restartPolling() } })
            workspaceObservers.append(center.addObserver(forName: NSWorkspace.willSleepNotification, object: nil, queue: .main) { [weak self] _ in Task { @MainActor in self?.model?.suspend() } })
            if ProcessInfo.processInfo.arguments.contains("--demo") { Task { await model.startDemo() } }
            if ProcessInfo.processInfo.arguments.contains("--show-window") || model.configuration.hosts.isEmpty { openDashboard() }
        } catch {
            let alert = NSAlert(); alert.messageText = "无法启动节点监控"; alert.informativeText = error.localizedDescription; alert.runModal()
            NSApplication.shared.terminate(nil)
        }
    }
    func applicationWillTerminate(_ notification: Notification) {
        model?.stop()
        for observer in workspaceObservers { NSWorkspace.shared.notificationCenter.removeObserver(observer) }
    }
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }
    @objc private func togglePopover() {
        guard let button = statusItem?.button, let model else { return }
        if popover.isShown { popover.performClose(nil) }
        else {
            // Give AppKit a stable viewport before it positions the popover. Live
            // metrics and error messages must not resize it around its old centre.
            let screen = button.window?.screen ?? NSScreen.main
            let visible = screen?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1440, height: 900)
            let size = NSSize(width: min(390, max(1, visible.width - 32)), height: min(500, max(1, visible.height - 32)))
            let controller = NSHostingController(rootView: MenuBarView(model: model).frame(width: size.width, height: size.height))
            controller.sizingOptions = []
            controller.preferredContentSize = size
            controller.view.setFrameSize(size)
            popover.contentViewController = controller
            popover.contentSize = size
            NSApplication.shared.activate(ignoringOtherApps: true)
            popover.show(relativeTo: button.bounds, of: button, preferredEdge: button.isFlipped ? .maxY : .minY)
            keepPopoverOnScreen()
        }
    }
    func popoverDidShow(_ notification: Notification) {
        model?.popoverVisible = true; model?.visibilityChanged()
        keepPopoverOnScreen()
    }
    func popoverDidClose(_ notification: Notification) { model?.popoverVisible = false; model?.visibilityChanged() }
    private func keepPopoverOnScreen() {
        guard let window = popover.contentViewController?.view.window,
              let screen = statusItem?.button?.window?.screen ?? window.screen ?? NSScreen.main else { return }
        let visible = screen.visibleFrame.insetBy(dx: 6, dy: 6)
        let frame = window.frame
        let origin = NSPoint(x: max(visible.minX, min(frame.minX, visible.maxX - frame.width)),
                             y: max(visible.minY, min(frame.minY, visible.maxY - frame.height)))
        if origin != frame.origin { window.setFrameOrigin(origin) }
    }
    private func updateStatus() {
        guard let model, let button = statusItem?.button else { return }
        guard let host = model.selectedHost else { button.title = " 节点监控"; button.toolTip = "添加服务器或体验演示"; return }
        let state = model.runtimes[host.id] ?? HostRuntime()
        let sample = state.snapshot
        let prefix = state.error != nil ? "⚠︎ " : sample?.demo == true ? "演示 " : ""
        let text = sample?.menuStatus(metric: model.configuration.menuMetric) ?? "等待采样"
        button.title = " " + prefix + (text.isEmpty ? "GPU 未提供" : text)
        let devices = sample?.hardwareReadings.map { "\($0.title) · \($0.name) · \(MetricFormat.percent($0.usagePercent)) · \(MetricFormat.temperature($0.temperatureCelsius))C" }.joined(separator: "\n") ?? ""
        button.toolTip = "\(host.name) · \(state.status(cadence: model.cadence)) · \(model.modeText)\n\(devices)"
        if model.configuration.hosts.contains(where: { $0.widget.visible && widgetSizes[$0.id] != WidgetView.preferredSize(sample: model.runtimes[$0.id]?.snapshot, compact: $0.widget.compact) }) {
            syncWidgets()
        }
    }
    private func openDashboard() {
        guard let model else { return }; popover.performClose(nil)
        if dashboard == nil {
            let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1180, height: 820), styleMask: [.titled, .closable, .miniaturizable, .resizable], backing: .buffered, defer: false)
            window.title = "节点监控"; window.minSize = NSSize(width: 1000, height: 650); window.isReleasedWhenClosed = false
            window.titlebarAppearsTransparent = true; window.toolbarStyle = .unified
            window.contentView = NSHostingView(rootView: DashboardView(model: model)); window.delegate = self
            window.center(); dashboard = window
        }
        dashboard?.makeKeyAndOrderFront(nil); NSApplication.shared.activate(ignoringOtherApps: true)
        model.detailVisible = true; model.visibilityChanged()
    }
    private func openPreferences() {
        guard let model else { return }; popover.performClose(nil)
        if preferences == nil {
            let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 644, height: 604), styleMask: [.titled, .closable], backing: .buffered, defer: false)
            window.title = "节点监控设置"; window.isReleasedWhenClosed = false; window.contentView = NSHostingView(rootView: PreferencesView(model: model)); window.delegate = self; window.center(); preferences = window
        }
        preferences?.makeKeyAndOrderFront(nil); NSApplication.shared.activate(ignoringOtherApps: true)
        model.preferencesVisible = true; model.visibilityChanged()
    }
    func windowWillClose(_ notification: Notification) {
        guard let window = notification.object as? NSWindow, let model else { return }
        if window === dashboard { model.detailVisible = false }
        if window === preferences { model.preferencesVisible = false }
        model.visibilityChanged()
    }
    func windowDidMiniaturize(_ notification: Notification) { if (notification.object as? NSWindow) === dashboard { model?.detailVisible = false; model?.visibilityChanged() } }
    func windowDidDeminiaturize(_ notification: Notification) { if (notification.object as? NSWindow) === dashboard { model?.detailVisible = true; model?.visibilityChanged() } }
    func windowDidMove(_ notification: Notification) {
        guard let panel = notification.object as? NSPanel, let id = panels.first(where: { $0.value === panel })?.key else { return }
        let frame = NSStringFromRect(panel.frame)
        if model?.configuration.hosts.first(where: { $0.id == id })?.widget.frame != frame { model?.updateWidget(id, action: { $0.frame = frame }, refresh: false) }
    }
    private func syncWidgets() {
        guard let model else { return }
        let visible = Set(model.configuration.hosts.filter { $0.widget.visible }.map(\.id))
        for id in Array(panels.keys) where !visible.contains(id) { panels[id]?.orderOut(nil); panels.removeValue(forKey: id); widgetSizes.removeValue(forKey: id) }
        for (index, host) in model.configuration.hosts.filter({ $0.widget.visible }).enumerated() {
            let panel: NSPanel
            if let existing = panels[host.id] { panel = existing }
            else {
                panel = NSPanel(contentRect: .zero, styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
                panel.title = "节点监控挂件 · " + host.name
                panel.isOpaque = false; panel.backgroundColor = .clear; panel.hasShadow = true; panel.isMovableByWindowBackground = true
                panel.hidesOnDeactivate = false; panel.isReleasedWhenClosed = false
                panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]; panel.isRestorable = false
                panel.contentView = NSHostingView(rootView: WidgetView(model: model, hostID: host.id)); panels[host.id] = panel; panel.delegate = self
            }
            let size = WidgetView.preferredSize(sample: model.runtimes[host.id]?.snapshot, compact: host.widget.compact)
            if widgetSizes[host.id] != size || panel.frame.width == 0 {
                var frame = host.widget.frame.map(NSRectFromString) ?? NSRect(x: (NSScreen.main?.visibleFrame.maxX ?? 1200) - size.width - 28, y: (NSScreen.main?.visibleFrame.maxY ?? 800) - size.height - 28 - CGFloat(index * 32), width: size.width, height: size.height)
                frame.origin.y += frame.height - size.height
                frame.size = size; frame = clamped(frame)
                widgetSizes[host.id] = size
                panel.setFrame(frame, display: false)
            }
            panel.level = host.widget.alwaysOnTop ? .floating : .normal
            panel.alphaValue = max(0.5, min(1, host.widget.opacity)); panel.ignoresMouseEvents = host.widget.locked
            panel.orderFrontRegardless()
        }
        applyAppearance()
    }
    private func clamped(_ frame: NSRect) -> NSRect {
        if NSScreen.screens.contains(where: { $0.visibleFrame.intersects(frame) && $0.visibleFrame.intersection(frame).width > 100 && $0.visibleFrame.intersection(frame).height > 80 }) { return frame }
        let visible = NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1440, height: 900)
        return NSRect(x: visible.maxX - frame.width - 24, y: visible.maxY - frame.height - 24, width: frame.width, height: frame.height)
    }
    private func applyAppearance() {
        let style = model?.configuration.appearance ?? "system"
        let appearance: NSAppearance? = style == "dark" ? NSAppearance(named: .darkAqua) : style == "light" ? NSAppearance(named: .aqua) : nil
        NSApplication.shared.appearance = appearance
    }
    private func installMenu() {
        let main = NSMenu(); let application = NSMenu(); let item = NSMenuItem(); item.submenu = application; main.addItem(item)
        let open = NSMenuItem(title: "打开监控台", action: #selector(openFromMenu), keyEquivalent: "0"); open.target = self; application.addItem(open)
        let panel = NSMenuItem(title: "打开菜单栏面板", action: #selector(togglePopover), keyEquivalent: "1"); panel.target = self; application.addItem(panel)
        let settings = NSMenuItem(title: "设置…", action: #selector(settingsFromMenu), keyEquivalent: ","); settings.target = self; application.addItem(settings)
        application.addItem(.separator()); application.addItem(withTitle: "退出节点监控", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        let edit = NSMenuItem(); let editMenu = NSMenu(title: "编辑"); edit.submenu = editMenu; main.addItem(edit)
        for (title, selector, key) in [("剪切", "cut:", "x"), ("复制", "copy:", "c"), ("粘贴", "paste:", "v"), ("全选", "selectAll:", "a")] { editMenu.addItem(withTitle: title, action: Selector(selector), keyEquivalent: key) }
        NSApplication.shared.mainMenu = main
    }
    @objc private func openFromMenu() { openDashboard() }
    @objc private func settingsFromMenu() { openPreferences() }
    private func projectRoot() throws -> URL {
        let args = ProcessInfo.processInfo.arguments
        if let index = args.firstIndex(of: "--project-root"), args.indices.contains(index + 1) {
            let candidate = URL(fileURLWithPath: args[index + 1], isDirectory: true)
            if FileManager.default.fileExists(atPath: candidate.appendingPathComponent(".nodemon-project").path) { return candidate }
        }
        var candidate = Bundle.main.bundleURL.deletingLastPathComponent()
        for _ in 0..<5 {
            if FileManager.default.fileExists(atPath: candidate.appendingPathComponent(".nodemon-project").path) { return candidate }
            candidate.deleteLastPathComponent()
        }
        throw NSError(domain: "NodeMonitor", code: 1, userInfo: [NSLocalizedDescriptionKey: "请在完整项目文件夹中运行 build/节点监控.app。程序需要找到 .nodemon-project 标记，避免把数据写到其他目录。"])
    }
}
