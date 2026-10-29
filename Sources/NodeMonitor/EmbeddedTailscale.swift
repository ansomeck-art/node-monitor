import AppKit
import Combine
import Foundation
import NodeMonitorCore

@MainActor final class EmbeddedTailscaleManager: ObservableObject {
    let bridge: EmbeddedTailscale
    @Published var message = "尚未连接；首次使用需登录授权。"
    @Published var busy = false
    @Published var connected = false
    private var watchTask: Task<Void, Never>?

    init(root: URL) { bridge = EmbeddedTailscale(root: root) }
    func refresh() {
        Task { await refreshNow() }
    }
    private func refreshNow() async {
        do {
            let state = TailscaleState.fromBackendState(try await bridge.status())
            connected = state == .connected
            switch state {
            case .connected: message = "本机已授权；服务器需要加入同一网络。"
            case .needsLogin: message = "尚未登录，点击右侧按钮授权。"
            case .needsApproval: message = "等待 Tailscale 管理员批准此设备。"
            case .starting: message = "正在连接，请稍候。"
            case .stopped: message = "远程连接已停止，请重新登录。"
            case .unknown: message = "准备远程连接中；首次使用请登录。"
            }
        } catch { connected = false; message = error.localizedDescription }
    }
    func login() {
        guard !busy else { return }
        busy = true
        message = "正在准备授权，请稍候。"
        Task {
            defer { busy = false }
            do {
                if let url = try await bridge.login() {
                    guard NSWorkspace.shared.open(url) else { throw NSError(domain: "NodeMonitor", code: 1, userInfo: [NSLocalizedDescriptionKey: "无法打开浏览器，请检查系统设置。"]) }
                    message = "请在浏览器中完成授权，然后返回此处。"
                }
                watchTask?.cancel()
                watchTask = Task { [weak self] in
                    for _ in 0..<150 {
                        guard let self, !Task.isCancelled else { return }
                        await self.refreshNow()
                        if self.connected { return }
                        try? await Task.sleep(nanoseconds: 2_000_000_000)
                    }
                }
            } catch { message = error.localizedDescription }
        }
    }
    func stop() {
        watchTask?.cancel(); watchTask = nil; connected = false
        message = "尚未连接；首次使用需登录授权。"
        Task { await bridge.stop() }
    }
}
