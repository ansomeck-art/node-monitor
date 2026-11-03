using NodeMonitor;

static void Check(bool value, string message) { if (!value) throw new Exception(message); }
static void Rejected(Action test, string message) { try { test(); } catch (ArgumentException) { return; } throw new Exception(message); }
Check(MonitorClient.ValidateDirect("127.0.0.1:9274", false).AbsolutePath == "/v1/snapshot", "loopback endpoint");
Check(MonitorClient.ValidateDirect("192.168.1.2:9274", true).Scheme == "http", "explicit LAN endpoint");
Check(MonitorClient.ValidateDirect("https://example.test:9274", false).Scheme == "https", "HTTPS endpoint");
Rejected(() => MonitorClient.ValidateDirect("192.168.1.2:9274", false), "LAN HTTP must require opt in");
Rejected(() => MonitorClient.ValidateDirect("8.8.8.8:9274", true), "public HTTP must be rejected");
Rejected(() => MonitorClient.ValidateDirect("http://example.test", true), "HTTP hostname must be rejected");
Rejected(() => MonitorClient.ValidateDirect("https://example.test/path", false), "paths must be rejected");
Rejected(() => MonitorClient.ValidateDirect("https://u:p@example.test", false), "URL credentials must be rejected");
MonitorClient.ValidateTailnet("100.64.0.5:9274");
MonitorClient.ValidateTailnet("server.example.ts.net:9274");
Rejected(() => MonitorClient.ValidateTailnet("192.168.1.2:9274"), "private LAN IP is not tailnet");
Rejected(() => MonitorClient.ValidateTailnet("https://example.test:9274"), "non tailnet hostname must be rejected");
Check(MetricFreshness.GPU("2026-11-03T00:00:45Z", "2026-11-03T00:00:00Z", 15), "GPU cache boundary");
Check(!MetricFreshness.GPU("2026-11-03T00:00:46Z", "2026-11-03T00:00:00Z", 15), "expired GPU cache");
Check(!MetricFreshness.GPU("2026-11-03T00:00:46Z", null, 15), "missing GPU timestamp");
Console.WriteLine("Windows connection address and GPU cache checks passed.");
