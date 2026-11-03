package cn.nodemon.app;

public final class MonitorClientTest {
    interface Checked { void run() throws Exception; }
    static void rejected(Checked test) throws Exception {
        try { test.run(); } catch (Exception expected) { return; }
        throw new AssertionError("unsafe address accepted");
    }
    public static void main(String[] args) throws Exception {
        MonitorClient.direct("127.0.0.1:9274", false);
        MonitorClient.direct("192.168.1.2:9274", true);
        MonitorClient.direct("https://example.test:9274", false);
        rejected(() -> MonitorClient.direct("192.168.1.2:9274", false));
        rejected(() -> MonitorClient.direct("8.8.8.8:9274", true));
        rejected(() -> MonitorClient.direct("http://example.test", true));
        rejected(() -> MonitorClient.direct("https://example.test/path", false));
        MonitorClient.tailnet("100.64.0.5:9274");
        MonitorClient.tailnet("server.example.ts.net:9274");
        rejected(() -> MonitorClient.tailnet("192.168.1.2:9274"));
        rejected(() -> MonitorClient.tailnet("https://example.test:9274"));
        System.out.println("Android connection address checks passed.");
    }
}
