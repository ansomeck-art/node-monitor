using System.Diagnostics;
using System.Net;
using System.Net.Http;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace NodeMonitor;

public sealed class HostConfig {
    public string Id { get; set; } = ""; public string Name { get; set; } = ""; public string Address { get; set; } = ""; public string DirectAddress { get; set; } = ""; public string TailnetAddress { get; set; } = ""; public string Mode { get; set; } = "direct"; public bool AllowLan { get; set; } public string ProtectedToken { get; set; } = "";
}
public static class Secret {
    [StructLayout(LayoutKind.Sequential)] struct Blob { public int Size; public IntPtr Data; }
    [DllImport("crypt32.dll", SetLastError = true, CharSet = CharSet.Unicode)] static extern bool CryptProtectData(ref Blob input, string? description, IntPtr entropy, IntPtr reserved, IntPtr prompt, int flags, out Blob output);
    [DllImport("crypt32.dll", SetLastError = true)] static extern bool CryptUnprotectData(ref Blob input, IntPtr description, IntPtr entropy, IntPtr reserved, IntPtr prompt, int flags, out Blob output);
    [DllImport("kernel32.dll")] static extern IntPtr LocalFree(IntPtr memory);
    static byte[] Transform(byte[] input, bool encrypt) {
        var blob = new Blob { Size = input.Length, Data = Marshal.AllocHGlobal(input.Length) };
        Marshal.Copy(input, 0, blob.Data, input.Length); Blob output = default;
        try {
            bool ok = encrypt ? CryptProtectData(ref blob, null, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, 1, out output) : CryptUnprotectData(ref blob, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, 1, out output);
            if (!ok) throw new CryptographicException("Windows 无法保护或读取令牌");
            var result = new byte[output.Size]; Marshal.Copy(output.Data, result, 0, output.Size); return result;
        } finally { for (int i = 0; i < input.Length; i++) Marshal.WriteByte(blob.Data, i, 0); Marshal.FreeHGlobal(blob.Data); if (output.Data != IntPtr.Zero) LocalFree(output.Data); }
    }
    public static string Protect(string value) { var raw = Encoding.UTF8.GetBytes(value); try { return Convert.ToBase64String(Transform(raw, true)); } finally { CryptographicOperations.ZeroMemory(raw); } }
    public static string Unprotect(string value) { var raw = Transform(Convert.FromBase64String(value), false); try { return Encoding.UTF8.GetString(raw); } finally { CryptographicOperations.ZeroMemory(raw); } }
}
public sealed class MonitorClient : IDisposable {
    readonly string stateDir;
    readonly HttpClient http = new(new HttpClientHandler { AllowAutoRedirect = false }) { Timeout = TimeSpan.FromSeconds(10) };
    readonly SemaphoreSlim bridgeLock = new(1, 1);
    Process? bridge;
    public MonitorClient(string root) { stateDir = Path.Combine(root, "data", "tailscale-windows"); }
    public static Uri ValidateDirect(string input, bool allowLan) {
        if (!input.Contains("://")) input = "http://" + input;
        if (!Uri.TryCreate(input, UriKind.Absolute, out var uri) || (uri.Scheme != "http" && uri.Scheme != "https") || !string.IsNullOrEmpty(uri.UserInfo) || !string.IsNullOrEmpty(uri.Query) || !string.IsNullOrEmpty(uri.Fragment) || (uri.AbsolutePath != "/" && uri.AbsolutePath != "")) throw new ArgumentException("请输入没有路径或参数的 HTTP(S) 地址。");
        if (uri.Scheme == "http") {
            if (!IPAddress.TryParse(uri.Host, out var ip)) { if (uri.Host != "localhost") throw new ArgumentException("HTTP 只允许本机或私有 IP，避免令牌泄漏。"); }
            else if (!IPAddress.IsLoopback(ip) && !(allowLan && Private(ip))) throw new ArgumentException("HTTP 仅限明确允许的可信局域网私有 IP。");
        }
        return new UriBuilder(uri) { Path = "/v1/snapshot" }.Uri;
    }
    static bool Private(IPAddress ip) {
        var b = ip.GetAddressBytes(); if (ip.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork) return b[0] == 10 || (b[0] == 172 && b[1] >= 16 && b[1] <= 31) || (b[0] == 192 && b[1] == 168) || (b[0] == 169 && b[1] == 254);
        return ip.IsIPv6LinkLocal || (b[0] & 0xfe) == 0xfc;
    }
    public static void ValidateTailnet(string address) {
        if (!address.Contains("://")) address = "http://" + address;
        if (!Uri.TryCreate(address, UriKind.Absolute, out var uri) || (uri.Scheme != "http" && uri.Scheme != "https") || uri.UserInfo != "" || uri.Query != "" || uri.Fragment != "" || (uri.AbsolutePath != "/" && uri.AbsolutePath != "")) throw new ArgumentException("请输入 Tailscale IP 或 .ts.net 地址。");
        if (IPAddress.TryParse(uri.Host, out var ip)) { var b = ip.GetAddressBytes(); if (!(b.Length == 4 && b[0] == 100 && b[1] >= 64 && b[1] <= 127) && !(b.Length == 16 && b[0] == 0xfd && b[1] == 0x7a && b[2] == 0x11 && b[3] == 0x5c && b[4] == 0xa1 && b[5] == 0xe0)) throw new ArgumentException("远程地址必须属于 Tailscale 网络。"); }
        else if (!uri.Host.EndsWith(".ts.net", StringComparison.OrdinalIgnoreCase)) throw new ArgumentException("远程地址必须是 Tailscale IP 或 .ts.net 地址。");
    }
    async Task<JsonObject> Bridge(string action, string? address = null, string? token = null) {
        await bridgeLock.WaitAsync();
        try {
            if (bridge == null || bridge.HasExited) {
                var exe = Path.Combine(AppContext.BaseDirectory, "nodemon-tailscale-bridge.exe");
                if (!File.Exists(exe)) throw new Exception("缺少内置远程连接组件，请重新构建安装包。");
                var info = new ProcessStartInfo(exe) { UseShellExecute = false, RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true, CreateNoWindow = true };
                info.ArgumentList.Add("--state-dir"); info.ArgumentList.Add(stateDir); info.ArgumentList.Add("--hostname"); info.ArgumentList.Add("node-monitor-windows");
                bridge = Process.Start(info) ?? throw new Exception("远程连接组件无法启动");
                _ = bridge.StandardError.ReadToEndAsync();
            }
            var id = Guid.NewGuid().ToString("N");
            await bridge.StandardInput.WriteLineAsync(JsonSerializer.Serialize(new { id, action, address, token })); await bridge.StandardInput.FlushAsync();
            var line = await bridge.StandardOutput.ReadLineAsync().WaitAsync(TimeSpan.FromSeconds(action == "login" ? 45 : 20));
            if (line == null) throw new Exception("远程连接组件已退出");
            var result = JsonNode.Parse(line)?.AsObject() ?? throw new Exception("远程组件响应无效");
            if ((string?)result["id"] != id) throw new Exception("远程组件响应不匹配");
            if (result["error"] is not null) throw new Exception("远程连接失败：" + (string?)result["error"]);
            return result;
        } catch { if (bridge != null) { try { if (!bridge.HasExited) bridge.Kill(); } catch { } bridge.Dispose(); bridge = null; } throw; }
        finally { bridgeLock.Release(); }
    }
    public async Task<JsonObject> Fetch(HostConfig host) {
        var token = Secret.Unprotect(host.ProtectedToken);
        JsonNode? result;
        if (host.Mode == "tailnet") { ValidateTailnet(host.Address); result = (await Bridge("fetch", host.Address, token))["data"]?.DeepClone(); }
        else {
            var uri = ValidateDirect(host.Address, host.AllowLan);
            using var req = new HttpRequestMessage(HttpMethod.Get, uri); req.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", token);
            using var response = await http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead);
            if (!response.IsSuccessStatusCode) throw new Exception($"采集端返回 HTTP {(int)response.StatusCode}");
            if (response.Content.Headers.ContentLength > 1_048_576) throw new Exception("数据过大");
            var stream = await response.Content.ReadAsStreamAsync(); using var buffer = new MemoryStream(); var chunk = new byte[8192]; int n;
            while ((n = await stream.ReadAsync(chunk)) > 0) { if (buffer.Length + n > 1_048_576) throw new Exception("数据过大"); buffer.Write(chunk, 0, n); }
            result = JsonNode.Parse(buffer.ToArray());
        }
        var snapshot = result?.AsObject() ?? throw new Exception("数据格式无效");
        if ((int?)snapshot["schemaVersion"] != 1) throw new Exception("采集端协议版本不兼容");
        return snapshot;
    }
    public async Task<string?> Login() => (string?)(await Bridge("login"))["url"];
    public void Dispose() { http.Dispose(); if (bridge != null) { try { bridge.StandardInput.Close(); if (!bridge.WaitForExit(1000)) bridge.Kill(); } catch { } bridge.Dispose(); } bridgeLock.Dispose(); }
}
