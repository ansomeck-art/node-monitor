namespace NodeMonitor;

public static class MetricFreshness {
    public static bool GPU(string? snapshotTime, string? gpuTime, double? intervalSeconds) {
        if (!DateTimeOffset.TryParse(snapshotTime, out var snapshot) || !DateTimeOffset.TryParse(gpuTime, out var gpu)) return false;
        double interval = intervalSeconds is double seconds && double.IsFinite(seconds) && seconds > 0 ? seconds : 15;
        return (snapshot - gpu).TotalSeconds <= Math.Max(15, interval * 3);
    }
}
