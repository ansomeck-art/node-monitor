package cn.nodemon.app;

import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MonitorWidget extends AppWidgetProvider {
    static final int JOB_ID = 9274;
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) { schedule(context); }
    static void schedule(Context context) {
        if (AppWidgetManager.getInstance(context).getAppWidgetIds(new ComponentName(context, MonitorWidget.class)).length == 0) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, WidgetJob.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setOverrideDeadline(10_000).build();
        ((JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE)).schedule(job);
    }
    static void update(Context context, JSONObject host, JSONObject data, String status) {
        RemoteViews view = new RemoteViews(context.getPackageName(), R.layout.widget);
        view.setTextViewText(R.id.widget_title, host == null ? "节点监控" : host.optString("name"));
        view.setTextViewText(R.id.widget_status, status);
        String text = "添加主机后显示监控数据";
        if (data != null) {
            StringBuilder result = new StringBuilder(); JSONObject cpu = data.optJSONObject("cpu");
            JSONArray packages = cpu == null ? null : cpu.optJSONArray("packages");
            if (packages != null && packages.length() > 0) for (int i=0; i<Math.min(2, packages.length()); i++) { JSONObject p=packages.optJSONObject(i); if (p!=null) result.append("CPU ").append(i+1).append("  ").append(Metrics.percent(p,"usagePercent")).append("  ").append(Metrics.temp(p,"temperatureCelsius")).append('\n'); }
            else result.append("CPU  ").append(Metrics.percent(cpu,"usagePercent")).append('\n');
            JSONArray gpus=data.optJSONArray("gpus"); if (gpus!=null) for (int i=0; i<Math.min(2,gpus.length()); i++) { JSONObject g=gpus.optJSONObject(i); if (g!=null) { JSONObject fresh=Metrics.freshGpu(data,g); result.append("GPU ").append(i+1).append("  ").append(Metrics.percent(fresh,"usagePercent")).append("  ").append(Metrics.temp(fresh,"temperatureCelsius")).append('\n'); } }
            result.append("内存  ").append(Metrics.percent(data.optJSONObject("memory"),"usagePercent")); text=result.toString();
        } else if (host != null) text = "点击打开并检查连接";
        view.setTextViewText(R.id.widget_metrics, text);
        Intent launch = new Intent(context, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        view.setOnClickPendingIntent(R.id.widget_root, pending);
        AppWidgetManager.getInstance(context).updateAppWidget(new ComponentName(context, MonitorWidget.class), view);
    }
}
