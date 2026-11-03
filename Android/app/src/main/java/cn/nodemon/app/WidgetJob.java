package cn.nodemon.app;

import android.app.job.JobParameters;
import android.app.job.JobService;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class WidgetJob extends JobService {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    @Override public boolean onStartJob(JobParameters params) {
        worker.execute(() -> {
            JSONObject host = null;
            try { HostStore store = new HostStore(this); JSONArray list = store.hosts(); host = store.widgetHost(list); if (host == null) MonitorWidget.update(this, null, null, "点击添加主机"); else { JSONObject sample = MonitorClient.fetch(this, host); MonitorWidget.update(this, host, sample, "● 在线 · " + Metrics.sampleTime(sample)); } }
            catch (Exception e) { MonitorWidget.update(this, host, null, "● 离线 · 点击检查连接"); }
            finally { jobFinished(params, false); }
        });
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) { return false; }
    @Override public void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
}
