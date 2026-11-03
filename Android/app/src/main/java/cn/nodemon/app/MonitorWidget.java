package cn.nodemon.app;

import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.widget.RemoteViews;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

public final class MonitorWidget extends AppWidgetProvider {
    static final int JOB_ID = 9274;
    static final String EXTRA_HOST_ID = "widget_host_id";
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        HostStore store=new HostStore(context); JSONObject host=store.widgetHost(store.hosts());
        update(context,host,null,host==null?"点击添加主机":"等待更新"); schedule(context);
    }
    static void schedule(Context context) {
        if (AppWidgetManager.getInstance(context).getAppWidgetIds(new ComponentName(context, MonitorWidget.class)).length == 0) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, WidgetJob.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setOverrideDeadline(10_000).build();
        ((JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE)).schedule(job);
    }
    private static Bitmap gauge(Context context, Double value) {
        float scale=context.getResources().getDisplayMetrics().density; int size=Math.round(44*scale);
        Bitmap bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888); Canvas canvas=new Canvas(bitmap); Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        float center=size/2f, outer=center-3*scale, radius=outer-5*scale;
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(scale); p.setColor(0xffdce5d8); p.setPathEffect(new DashPathEffect(new float[]{2*scale,2.5f*scale},0)); canvas.drawCircle(center,center,outer,p);
        p.setPathEffect(null); p.setStrokeWidth(3*scale); p.setColor(0xffe8ede5); canvas.drawCircle(center,center,radius,p);
        if(value!=null && value>0) {p.setColor(0xff355b38);p.setStrokeCap(Paint.Cap.ROUND);canvas.drawArc(new RectF(center-radius,center-radius,center+radius,center+radius),-90,(float)(3.6*Math.max(0,Math.min(100,value))),false,p);}
        p.setStyle(Paint.Style.FILL); p.setColor(0xffa6d957); canvas.drawCircle(center,center-outer,2.5f*scale,p);
        p.setTextAlign(Paint.Align.CENTER); p.setTypeface(Typeface.create(Typeface.DEFAULT,Typeface.BOLD)); p.setTextSize(10*context.getResources().getDisplayMetrics().scaledDensity); p.setColor(0xff202820);
        canvas.drawText(value==null?"—":String.format(Locale.ROOT,"%.0f%%",value),center,center-(p.ascent()+p.descent())/2,p); return bitmap;
    }
    private static void slot(Context context,RemoteViews view,int ring,int title,int temp,String name,JSONObject value) {
        view.setImageViewBitmap(ring,gauge(context,Metrics.number(value,"usagePercent"))); view.setTextViewText(title,name); view.setTextViewText(temp,Metrics.temp(value,"temperatureCelsius"));
        view.setContentDescription(ring,name+"，使用率 "+Metrics.percent(value,"usagePercent")+"，温度 "+Metrics.temp(value,"temperatureCelsius"));
    }
    static RemoteViews buildView(Context context, JSONObject host, JSONObject data, String status) {
        RemoteViews view = new RemoteViews(context.getPackageName(), R.layout.widget);
        view.setTextViewText(R.id.widget_title, host == null ? "节点监控" : host.optString("name"));
        JSONObject cpu=data==null?null:data.optJSONObject("cpu"); JSONArray packages=cpu==null?null:cpu.optJSONArray("packages"), gpus=data==null?null:data.optJSONArray("gpus");
        JSONObject cpu1=packages!=null && packages.length()>0?packages.optJSONObject(0):cpu, cpu2=packages!=null && packages.length()>1?packages.optJSONObject(1):null;
        JSONObject gpu1=gpus!=null && gpus.length()>0?Metrics.freshGpu(data,gpus.optJSONObject(0)):null, gpu2=gpus!=null && gpus.length()>1?Metrics.freshGpu(data,gpus.optJSONObject(1)):null;
        slot(context,view,R.id.widget_cpu_1_ring,R.id.widget_cpu_1_name,R.id.widget_cpu_1_temp,packages!=null && packages.length()>0?"CPU 1":"CPU 总体",cpu1);
        slot(context,view,R.id.widget_cpu_2_ring,R.id.widget_cpu_2_name,R.id.widget_cpu_2_temp,"CPU 2",cpu2);
        slot(context,view,R.id.widget_gpu_1_ring,R.id.widget_gpu_1_name,R.id.widget_gpu_1_temp,"GPU 1",gpu1);
        slot(context,view,R.id.widget_gpu_2_ring,R.id.widget_gpu_2_name,R.id.widget_gpu_2_temp,"GPU 2",gpu2);
        int more=(packages==null?0:Math.max(0,packages.length()-2))+(gpus==null?0:Math.max(0,gpus.length()-2));
        view.setTextViewText(R.id.widget_status,status+(more>0?" · +"+more+" 更多":""));
        view.setTextColor(R.id.widget_status,status.contains("离线")?0xffa86555:status.contains("在线")?0xff5d8750:0xff708268);
        JSONObject memory=data==null?null:data.optJSONObject("memory");
        view.setTextViewText(R.id.widget_memory,"内存  "+Metrics.size(memory,"usedBytes")+" / "+Metrics.size(memory,"totalBytes"));
        Double memoryUsage=Metrics.number(memory,"usagePercent");
        if(memoryUsage==null) { Double used=Metrics.number(memory,"usedBytes"),total=Metrics.number(memory,"totalBytes"); if(used!=null && total!=null && total>0) memoryUsage=100*used/total; }
        view.setProgressBar(R.id.widget_memory_progress,1000,memoryUsage==null?0:(int)(Math.max(0,Math.min(100,memoryUsage))*10),false);
        Intent launch = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if(host!=null)launch.putExtra(EXTRA_HOST_ID,host.optString("id"));
        PendingIntent pending = PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        view.setOnClickPendingIntent(R.id.widget_root, pending);
        return view;
    }
    static void update(Context context, JSONObject host, JSONObject data, String status) {
        AppWidgetManager.getInstance(context).updateAppWidget(new ComponentName(context, MonitorWidget.class), buildView(context,host,data,status));
    }
}
