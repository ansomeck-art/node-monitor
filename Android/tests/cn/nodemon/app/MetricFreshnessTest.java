package cn.nodemon.app;

public final class MetricFreshnessTest {
    public static void main(String[] args) {
        if (!MetricFreshness.gpu("2026-11-03T00:00:45Z", "2026-11-03T00:00:00Z", 15)) throw new AssertionError("GPU cache boundary");
        if (MetricFreshness.gpu("2026-11-03T00:00:46Z", "2026-11-03T00:00:00Z", 15)) throw new AssertionError("expired GPU cache");
        if (MetricFreshness.gpu("2026-11-03T00:00:46Z", "", 15)) throw new AssertionError("missing GPU timestamp");
        System.out.println("Android GPU cache checks passed.");
    }
}
