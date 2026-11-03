using System.Collections.ObjectModel;
using System.IO;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Threading;
using Forms = System.Windows.Forms;

namespace NodeMonitor;

public partial class MainWindow : Window {
    readonly ObservableCollection<HostConfig> hosts = new();
    readonly string settingsPath = Path.Combine(AppContext.BaseDirectory, "data", "windows-hosts.json");
    readonly MonitorClient client = new(AppContext.BaseDirectory);
    readonly DispatcherTimer timer = new() { Interval = TimeSpan.FromSeconds(5) };
    readonly Forms.NotifyIcon tray;
    WidgetWindow? widget;
    JsonObject? current;
    readonly Queue<double> history = new();
    bool busy, exiting;

    public MainWindow() {
        InitializeComponent();
        try { if (File.Exists(settingsPath)) foreach (var host in JsonSerializer.Deserialize<List<HostConfig>>(File.ReadAllText(settingsPath)) ?? []) hosts.Add(host); } catch { Status.Text = "主机配置无法读取，请重新添加。"; }
        Hosts.ItemsSource = hosts;
        var menu = new Forms.ContextMenuStrip(); menu.Items.Add("打开监控", null, (_, _) => Dispatcher.Invoke(ShowMain)); menu.Items.Add("桌面挂件", null, (_, _) => Dispatcher.Invoke(ShowWidget)); menu.Items.Add("退出", null, (_, _) => Dispatcher.Invoke(Exit));
        tray = new Forms.NotifyIcon { Icon = System.Drawing.SystemIcons.Application, Text = "节点监控", Visible = true, ContextMenuStrip = menu }; tray.DoubleClick += (_, _) => Dispatcher.Invoke(ShowMain);
        timer.Tick += async (_, _) => await RefreshAsync(); timer.Start(); IsVisibleChanged += (_, _) => AdjustPolling();
        if (hosts.Count > 0) Hosts.SelectedIndex = 0;
        Closing += (_, e) => { if (!exiting) { e.Cancel = true; Hide(); } };
    }
    void ShowMain() { Show(); WindowState = WindowState.Normal; Activate(); }
    void Exit() { exiting = true; timer.Stop(); widget?.Close(); tray.Visible = false; tray.Dispose(); client.Dispose(); System.Windows.Application.Current.Shutdown(); }
    void Save() { Directory.CreateDirectory(Path.GetDirectoryName(settingsPath)!); File.WriteAllText(settingsPath, JsonSerializer.Serialize(hosts, new JsonSerializerOptions { WriteIndented = true })); }
    void AddHost(object sender, RoutedEventArgs e) { var dialog = new HostDialog(null) { Owner = this }; if (dialog.ShowDialog() == true && dialog.Result != null) { hosts.Add(dialog.Result); Save(); Hosts.SelectedItem = dialog.Result; } }
    void EditHost(object sender, RoutedEventArgs e) { if (Hosts.SelectedItem is not HostConfig host) return; var dialog = new HostDialog(host) { Owner = this }; if (dialog.ShowDialog() != true) return; if (dialog.Remove) { hosts.Remove(host); Save(); if (hosts.Count > 0) Hosts.SelectedIndex = 0; return; } if (dialog.Result != null) { int index = hosts.IndexOf(host); hosts[index] = dialog.Result; Save(); Hosts.SelectedIndex = index; } }
    void ShowWidget(object sender, RoutedEventArgs e) => ShowWidget();
    void AdjustPolling() { timer.Interval = TimeSpan.FromSeconds(IsVisible || widget?.IsVisible == true ? 5 : 30); }
    void ShowWidget() { if (widget == null || !widget.IsLoaded) { widget = new WidgetWindow(); widget.IsVisibleChanged += (_, _) => AdjustPolling(); widget.Closed += (_, _) => { widget = null; AdjustPolling(); }; } widget.Update(Hosts.SelectedItem as HostConfig, current, Status.Text); widget.Show(); widget.Activate(); }
    async void HostChanged(object sender, SelectionChangedEventArgs e) { current = null; history.Clear(); ContentPanel.Children.Clear(); var host = Hosts.SelectedItem as HostConfig; HostTitle.Text = host?.Name ?? "选择或添加主机"; HostMeta.Text = host == null ? "支持 Ubuntu 和 Windows" : host.Mode == "tailnet" ? "远程 · 内置 Tailscale" : "局域网 / HTTPS"; LoginButton.Visibility = host?.Mode == "tailnet" ? Visibility.Visible : Visibility.Collapsed; DirectButton.Visibility = !string.IsNullOrEmpty(host?.DirectAddress) && !string.IsNullOrEmpty(host?.TailnetAddress) ? Visibility.Visible : Visibility.Collapsed; RemoteButton.Visibility = DirectButton.Visibility; DirectButton.IsEnabled = host?.Mode != "direct"; RemoteButton.IsEnabled = host?.Mode != "tailnet"; Status.Text = host == null ? "等待添加主机" : "正在连接…"; widget?.Update(host, current, Status.Text); await RefreshAsync(); }
    void DirectClick(object sender, RoutedEventArgs e) => SwitchMode("direct");
    void RemoteClick(object sender, RoutedEventArgs e) => SwitchMode("tailnet");
    void SwitchMode(string mode) { if (Hosts.SelectedItem is not HostConfig host || host.Mode == mode) return; var address = mode == "direct" ? host.DirectAddress : host.TailnetAddress; if (string.IsNullOrEmpty(address)) return; int index = hosts.IndexOf(host); hosts[index] = new HostConfig { Id = host.Id, Name = host.Name, Address = address, DirectAddress = host.DirectAddress, TailnetAddress = host.TailnetAddress, Mode = mode, AllowLan = host.AllowLan, ProtectedToken = host.ProtectedToken }; Save(); Hosts.SelectedIndex = index; }
    async void RefreshClick(object sender, RoutedEventArgs e) => await RefreshAsync();
    async void LoginClick(object sender, RoutedEventArgs e) {
        LoginButton.IsEnabled = false; Status.Text = "正在准备远程授权…";
        try {
            var url = await client.Login();
            if (url != null) { var uri = new Uri(url); if (uri.Scheme != "https" || uri.Host != "login.tailscale.com") throw new Exception("授权地址无效"); System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(uri.AbsoluteUri) { UseShellExecute = true }); Status.Text = "请在浏览器中授权此设备，然后刷新。"; }
            else { Status.Text = "远程网络已登录。"; await RefreshAsync(); }
        } catch { Status.Text = "远程授权失败，请检查网络后重试。"; }
        finally { LoginButton.IsEnabled = true; }
    }
    async Task RefreshAsync() {
        if (busy || Hosts.SelectedItem is not HostConfig host) return; busy = true;
        try {
            var snapshot = await client.Fetch(host); if (!ReferenceEquals(Hosts.SelectedItem, host)) return;
            current = snapshot; var cpu = ViewTools.Number(snapshot["cpu"]?["usagePercent"]); if (cpu.HasValue) { history.Enqueue(cpu.Value); while (history.Count > 60) history.Dequeue(); }
            HostMeta.Text = $"{ViewTools.Text(snapshot["host"]?["os"])} · {ViewTools.Text(snapshot["host"]?["arch"])} · {ViewTools.Text(snapshot["cpu"]?["cores"])} 线程";
            Status.Text = "● 在线  ·  " + (host.Mode == "tailnet" ? "远程连接" : "局域网 / HTTPS") + "  ·  采样 " + ViewTools.Sample(snapshot);
            ViewTools.Render(ContentPanel, snapshot, history, false); widget?.Update(host, snapshot, Status.Text);
        } catch (Exception ex) { if (!ReferenceEquals(Hosts.SelectedItem, host)) return; current = null; ContentPanel.Children.Clear(); Status.Text = "● 连接失败：" + ex.Message; widget?.Update(host, null, Status.Text); }
        finally { busy = false; }
    }
}

public sealed class WidgetWindow : Window {
    readonly TextBlock title = new() { FontSize = 16, FontWeight = FontWeights.Bold };
    readonly TextBlock status = new() { Foreground = Brushes.SlateGray, Margin = new Thickness(0, 4, 0, 8), TextWrapping = TextWrapping.Wrap };
    readonly StackPanel content = new();
    public WidgetWindow() { Title = "节点监控挂件"; Width = 330; Height = 480; MinHeight = 220; Topmost = true; WindowStartupLocation = WindowStartupLocation.CenterScreen; Background = new SolidColorBrush(Color.FromRgb(245, 248, 253)); var panel = new StackPanel { Margin = new Thickness(15) }; panel.Children.Add(title); panel.Children.Add(status); panel.Children.Add(content); Content = new ScrollViewer { Content = panel, VerticalScrollBarVisibility = ScrollBarVisibility.Auto }; }
    public void Update(HostConfig? host, JsonObject? snapshot, string state) { title.Text = host?.Name ?? "节点监控"; status.Text = state; content.Children.Clear(); if (snapshot != null) ViewTools.Render(content, snapshot, null, true); }
}

public static class ViewTools {
    public static string Text(JsonNode? n) => n?.ToString() ?? "—";
    public static double? Number(JsonNode? n) { if (n == null) return null; return double.TryParse(n.ToString(), System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var value) && double.IsFinite(value) ? value : null; }
    static string Percent(JsonNode? n) => Number(n) is double d ? $"{d:0}%" : "—";
    static string Temp(JsonNode? n) => Number(n) is double d ? $"{d:0}°C" : "—";
    static string Size(JsonNode? n) => Number(n) is double d ? $"{d / (1024*1024*1024):0.0} GiB" : "—";
    public static string Sample(JsonObject s) => DateTimeOffset.TryParse(Text(s["sampledAt"]), out var t) ? t.ToLocalTime().ToString("HH:mm:ss") : "—";
    static Brush Blue = new SolidColorBrush(Color.FromRgb(35, 128, 246)); static Brush Teal = new SolidColorBrush(Color.FromRgb(0, 172, 171)); static Brush Purple = new SolidColorBrush(Color.FromRgb(164, 80, 238));
    static void Row(StackPanel panel, string name, string sub, string value, double? progress, Brush color) {
        var box = new Border { Background = Brushes.White, CornerRadius = new CornerRadius(10), Padding = new Thickness(12, 9, 12, 9), Margin = new Thickness(0, 0, 0, 8) };
        var stack = new StackPanel(); var grid = new Grid(); grid.ColumnDefinitions.Add(new ColumnDefinition()); grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var label = new TextBlock { Text = name, Foreground = color, FontWeight = FontWeights.SemiBold, TextTrimming = TextTrimming.CharacterEllipsis }; var values = new TextBlock { Text = value, FontWeight = FontWeights.SemiBold }; Grid.SetColumn(values, 1); grid.Children.Add(label); grid.Children.Add(values); stack.Children.Add(grid);
        if (!string.IsNullOrWhiteSpace(sub)) stack.Children.Add(new TextBlock { Text = sub, FontSize = 11, Foreground = Brushes.SlateGray, TextTrimming = TextTrimming.CharacterEllipsis, Margin = new Thickness(0, 3, 0, 0) });
        stack.Children.Add(new ProgressBar { Value = Math.Clamp(progress ?? 0, 0, 100), IsEnabled = progress.HasValue, Foreground = color, Margin = new Thickness(0, 7, 0, 0) }); box.Child = stack; panel.Children.Add(box);
    }
    public static void Render(StackPanel panel, JsonObject s, IEnumerable<double>? history, bool compact) {
        panel.Children.Clear(); var cpu = s["cpu"]; var packages = cpu?["packages"] as JsonArray;
        if (packages != null && packages.Count > 0) { int i=0; foreach (var p in packages) { if (p == null) continue; i++; Row(panel, $"CPU {i}", Text(p["name"]), $"{Percent(p["usagePercent"])}    {Temp(p["temperatureCelsius"])}", Number(p["usagePercent"]), Blue); } if (packages.Count > 1) Row(panel, "CPU 总使用率", "", Percent(cpu?["usagePercent"]), Number(cpu?["usagePercent"]), Blue); }
        else Row(panel, "CPU", "", Percent(cpu?["usagePercent"]), Number(cpu?["usagePercent"]), Blue);
        if (s["gpus"] is JsonArray gpu && gpu.Count > 0) { int i=0; foreach (var g in gpu) { if (g == null) continue; i++; var fresh = MetricFreshness.GPU(Text(s["sampledAt"]), Text(g["sampledAt"]), Number(s["gpuSampleIntervalSeconds"])) ? g : null; Row(panel, $"GPU {i}", Text(g["name"]), $"{Percent(fresh?["usagePercent"])}    {Temp(fresh?["temperatureCelsius"])}", Number(fresh?["usagePercent"]), Teal); if (!compact && fresh?["memoryTotalBytes"] != null) { double? used=Number(fresh["memoryUsedBytes"]), total=Number(fresh["memoryTotalBytes"]); Row(panel, "显存", "", $"{Size(fresh["memoryUsedBytes"])} / {Size(fresh["memoryTotalBytes"])}", total > 0 ? used / total * 100 : null, Purple); } } }
        else Row(panel, "GPU", "驱动不支持或没有采集到", "—", null, Teal);
        var memory = s["memory"]; Row(panel, "内存", "", $"{Percent(memory?["usagePercent"])}    {Size(memory?["usedBytes"])} / {Size(memory?["totalBytes"])}", Number(memory?["usagePercent"]), Purple);
        if (compact) return;
        if (history != null) { var values=history.ToArray(); panel.Children.Add(new TextBlock { Text="CPU 使用率趋势 · 本次打开后", FontWeight=FontWeights.SemiBold, Margin=new Thickness(0, 8, 0, 6) }); var canvas = new Canvas { Height=80, Background=Brushes.White, ClipToBounds=true }; canvas.SizeChanged += (_,_) => { canvas.Children.Clear(); if (values.Length<2) return; var line = new System.Windows.Shapes.Polyline { Stroke=Blue, StrokeThickness=2 }; for (int i=0;i<values.Length;i++) line.Points.Add(new System.Windows.Point(i*canvas.ActualWidth/(values.Length-1), 78-Math.Clamp(values[i],0,100)*0.76)); canvas.Children.Add(line); }; panel.Children.Add(canvas); }
        var net=s["network"]; panel.Children.Add(new TextBlock { Text=$"网络  ↓ {Rate(net?["receiveBytesPerSecond"])}    ↑ {Rate(net?["sendBytesPerSecond"])}", Margin=new Thickness(0,12,0,8) });
        if (s["disks"] is JsonArray disks) foreach (var disk in disks) { var used=Number(disk?["usedBytes"]); var total=Number(disk?["totalBytes"]); Row(panel, "磁盘 " + Text(disk?["mount"]), Text(disk?["filesystem"]), $"{Size(disk?["usedBytes"])} / {Size(disk?["totalBytes"])}", total>0 ? used/total*100 : null, Blue); }
        if (s["warnings"] is JsonArray warnings) foreach (var warning in warnings) panel.Children.Add(new TextBlock { Text=Text(warning), Foreground=Brushes.SlateGray, TextWrapping=TextWrapping.Wrap, Margin=new Thickness(0,2,0,0) });
    }
    static string Rate(JsonNode? n) => Number(n) is double d ? $"{d/1024:0} KiB/s" : "—";
}
