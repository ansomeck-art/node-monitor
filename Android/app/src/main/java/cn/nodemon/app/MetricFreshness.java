package cn.nodemon.app;

import java.time.Duration;
import java.time.Instant;

final class MetricFreshness {
    static boolean gpu(String snapshotTime, String gpuTime, double intervalSeconds) {
        if (!Double.isFinite(intervalSeconds) || intervalSeconds <= 0) intervalSeconds = 15;
        try {
            double ageSeconds = Duration.between(Instant.parse(gpuTime), Instant.parse(snapshotTime)).toMillis() / 1000.0;
            return ageSeconds <= Math.max(15, intervalSeconds * 3);
        } catch (Exception e) { return false; }
    }
}
