using System.Windows;
using System.Windows.Controls;

namespace NodeMonitor;

public sealed class HostDialog : Window {
    readonly TextBox name = new(), directAddress = new(), tailnetAddress = new();
    readonly PasswordBox token = new();
    readonly ComboBox connection = new();
    readonly CheckBox allowLan = new() { Content = "允许在可信局域网使用 HTTP（数据不加密）", Margin = new Thickness(0, 10, 0, 0) };
    public HostConfig? Result { get; private set; }
    public bool Remove { get; private set; }
    readonly HostConfig? existing;

    public HostDialog(HostConfig? host) {
        existing = host; Title = host == null ? "添加主机" : "编辑主机"; Width = 465; Height = 545; WindowStartupLocation = WindowStartupLocation.CenterOwner; ResizeMode = ResizeMode.NoResize;
        var panel = new StackPanel { Margin = new Thickness(22) };
        void Add(string label, System.Windows.UIElement control) { panel.Children.Add(new TextBlock { Text = label, Margin = new Thickness(0, 8, 0, 5) }); panel.Children.Add(control); }
        connection.Items.Add("局域网 / HTTPS"); connection.Items.Add("远程 Tailscale");
        connection.SelectedIndex = host?.Mode == "tailnet" ? 1 : 0;
        name.Text = host?.Name ?? ""; directAddress.Text = !string.IsNullOrEmpty(host?.DirectAddress) ? host.DirectAddress : host?.Mode == "direct" ? host.Address : ""; tailnetAddress.Text = !string.IsNullOrEmpty(host?.TailnetAddress) ? host.TailnetAddress : host?.Mode == "tailnet" ? host.Address : ""; allowLan.IsChecked = host?.AllowLan ?? false;
        Add("主机名称", name); Add("当前连接方式", connection); Add("局域网 / HTTPS 地址（可留空）", directAddress); Add("Tailscale 地址（可留空）", tailnetAddress); Add("采集端令牌（留空保留原令牌）", token); panel.Children.Add(allowLan);
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = System.Windows.HorizontalAlignment.Right, Margin = new Thickness(0, 12, 0, 0) };
        if (host != null) { var remove = new Button { Content = "删除主机" }; remove.Click += (_, _) => { if (System.Windows.MessageBox.Show("删除此主机的本地配置？", "节点监控", MessageBoxButton.YesNo) == MessageBoxResult.Yes) { Remove = true; DialogResult = true; } }; buttons.Children.Add(remove); }
        var cancel = new Button { Content = "取消", IsCancel = true }; var save = new Button { Content = "保存", IsDefault = true };
        save.Click += (_, _) => Save(); buttons.Children.Add(cancel); buttons.Children.Add(save); panel.Children.Add(buttons); Content = panel;
    }
    void Save() {
        var mode = connection.SelectedIndex == 1 ? "tailnet" : "direct";
        var secret = token.Password;
        var direct = directAddress.Text.Trim(); var remote = tailnetAddress.Text.Trim(); var selectedAddress = mode == "tailnet" ? remote : direct;
        if (string.IsNullOrWhiteSpace(name.Text) || string.IsNullOrWhiteSpace(selectedAddress) || (string.IsNullOrEmpty(secret) && existing == null)) { System.Windows.MessageBox.Show("请填写名称、当前连接方式的地址和令牌。", "节点监控"); return; }
        try {
            if (direct != "") MonitorClient.ValidateDirect(direct, allowLan.IsChecked == true);
            if (remote != "") MonitorClient.ValidateTailnet(remote);
            Result = new HostConfig { Id = existing?.Id ?? Guid.NewGuid().ToString("N"), Name = name.Text.Trim(), Address = selectedAddress, DirectAddress = direct, TailnetAddress = remote, Mode = mode, AllowLan = allowLan.IsChecked == true, ProtectedToken = string.IsNullOrEmpty(secret) ? existing!.ProtectedToken : Secret.Protect(secret) };
            DialogResult = true;
        } catch (Exception e) { System.Windows.MessageBox.Show(e.Message, "无法保存"); }
    }
}
