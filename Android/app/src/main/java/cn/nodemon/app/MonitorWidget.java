package cn.nodemon.app;

import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shared lifecycle for six independently selectable, per-host widget providers. */
public class MonitorWidget extends AppWidgetProvider {
    static final int JOB_ID=9274;
    static final String EXTRA_HOST_ID="widget_host_id";
    static final String ACTION_REFRESH="cn.nodemon.app.WIDGET_REFRESH",ACTION_MUTE="cn.nodemon.app.WIDGET_MUTE",ACTION_PINNED="cn.nodemon.app.WIDGET_PINNED";
    static final Class<?>[] PROVIDERS={CompactWidget.class,SummaryWidget.class,MonitorWidget.class,DashboardWidget.class,LockRectWidget.class,LockCircleWidget.class};
    static final String[] NAMES={"设备速览 · 2×2","全部节点 · 2×2","状态全景 · 4×2","控制面板 · 4×4","矩形配件","圆形配件"};
    private static final ExecutorService rendering=Executors.newSingleThreadExecutor();
    private static final java.util.concurrent.atomic.AtomicBoolean renderQueued=new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicBoolean renderDirty=new java.util.concurrent.atomic.AtomicBoolean();
    static int count(Context c){int total=0;AppWidgetManager m=AppWidgetManager.getInstance(c);for(Class<?> p:PROVIDERS)total+=m.getAppWidgetIds(new ComponentName(c,p)).length;return total;}
    @Override public void onUpdate(Context c,AppWidgetManager manager,int[] ids){updateAll(c);schedule(c);}
    @Override public void onAppWidgetOptionsChanged(Context c,AppWidgetManager manager,int id,Bundle options){updateAll(c);}
    @Override public void onDeleted(Context c,int[] ids){HostStore store=new HostStore(c);for(int id:ids)store.forgetWidget(id);updateAll(c);}
    @Override public void onDisabled(Context c){if(count(c)==0){c.getSystemService(JobScheduler.class).cancel(JOB_ID);WidgetLiveService.stop(c);}}
    @Override public void onReceive(Context c,Intent intent){
        super.onReceive(c,intent);
    }
    static void handleAction(Context c,Intent intent){
        String action=intent.getAction();if(!ACTION_REFRESH.equals(action)&&!ACTION_MUTE.equals(action)&&!ACTION_PINNED.equals(action))return;
        int id=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,-1);AppWidgetProviderInfo info=AppWidgetManager.getInstance(c).getAppWidgetInfo(id);
        if(info==null||!info.provider.getPackageName().equals(c.getPackageName()))return;
        if(ACTION_PINNED.equals(action)){
            HostStore store=new HostStore(c);JSONArray hosts=store.hosts();String hostId=intent.getStringExtra(EXTRA_HOST_ID);for(int i=0;i<hosts.length();i++)if(hosts.optJSONObject(i)!=null&&hosts.optJSONObject(i).optString("id").equals(hostId)){store.bindWidget(id,hostId);updateAll(c);break;}
        }else if(ACTION_REFRESH.equals(action)){
            android.content.SharedPreferences p=c.getSharedPreferences("widget-actions",Context.MODE_PRIVATE);long now=System.currentTimeMillis();if(now-p.getLong("last_refresh",0)<5000)return;p.edit().putLong("last_refresh",now).apply();WidgetLiveService.ensureRunning(c);schedule(c);
        }else{
            HostStore store=new HostStore(c);JSONObject host=store.widgetHost(store.hosts(),id);if(host!=null){String hostId=host.optString("id");store.snooze(hostId,store.snoozed(hostId)?0:System.currentTimeMillis()+1800000L);updateAll(c);}
        }
    }
    static void schedule(Context c){if(count(c)==0)return;JobScheduler scheduler=c.getSystemService(JobScheduler.class);scheduler.schedule(new JobInfo.Builder(JOB_ID,new ComponentName(c,WidgetJob.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setOverrideDeadline(1000).build());}
    static void update(Context c,JSONObject host,JSONObject data,String status){updateAll(c);}
    static void updateAll(Context context){Context c=context.getApplicationContext();if(count(c)==0)return;renderDirty.set(true);if(!renderQueued.compareAndSet(false,true))return;rendering.execute(()->{
        try{do{renderDirty.set(false);AppWidgetManager m=AppWidgetManager.getInstance(c);for(int type=0;type<PROVIDERS.length;type++)for(int id:m.getAppWidgetIds(new ComponentName(c,PROVIDERS[type])))try{m.updateAppWidget(id,buildView(c,type,id,m.getAppWidgetOptions(id)));}catch(RuntimeException ignored){/* Launcher can remove a widget during rendering. */}}while(renderDirty.get());}
        finally{renderQueued.set(false);if(renderDirty.get())updateAll(c);}
    });}
    static DeckWidgetRenderer.State state(Context c,int type,int widgetId){
        HostStore store=new HostStore(c);JSONArray hosts=store.hosts();MonitorDatabase db=MonitorDatabase.get(c);DeckWidgetRenderer.State s=new DeckWidgetRenderer.State();
        s.host=type==1?null:store.widgetHost(hosts,widgetId);s.totalHosts=hosts.length();s.intervalSeconds=new MonitorSettings(c).interval();s.refreshMode=WidgetLiveService.isRunning()?"15 秒实时刷新":new MonitorSettings(c).liveWidgets()?"等待实时刷新":"系统定时更新";
        int offlineFailures=new MonitorSettings(c).failures();for(int i=0;i<hosts.length();i++){JSONObject h=hosts.optJSONObject(i);if(h==null)continue;String id=h.optString("id");JSONObject cache=db.cached(id);int failures=db.failures(id);if(cache!=null&&failures==0&&fresh(cache))s.onlineHosts++;else if(failures>=offlineFailures)s.offlineHosts++;}
        String hostId=s.host==null?"":s.host.optString("id");s.snapshot=s.host==null?null:db.cached(hostId);
        s.online=type==1?s.onlineHosts>0:s.snapshot!=null&&db.failures(hostId)==0;s.stale=type!=1&&s.online&&!fresh(s.snapshot);s.activeAlerts=db.active(hostId);s.muted=s.host!=null&&store.snoozed(hostId);s.summary=db.summary(hostId);s.history=s.host==null?java.util.Collections.emptyList():db.history(hostId,3600000);
        for(MonitorDatabase.Event event:db.events())if(event.ended==0&&(type==1||event.host.equals(hostId))){s.alertTitle=event.title;break;}
        return s;
    }
    private static boolean fresh(JSONObject s){return System.currentTimeMillis()-MonitorDatabase.sampleTime(s)<=Math.max(30000,s.optDouble("sampleIntervalSeconds",5)*3000);}
    static RemoteViews buildView(Context c,int type,int widgetId,Bundle options){
        int width=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,type<2?180:type==5?160:360);
        int height=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,type<2?180:type==3?360:type==4?100:type==5?160:170);
        if(width<=0)width=type<2?180:360;if(height<=0)height=type==3?360:180;
        return view(c,type,state(c,type,widgetId),Math.max(100,width),Math.max(70,height),widgetId);
    }
    static RemoteViews view(Context c,int type,DeckWidgetRenderer.State s,int width,int height,int widgetId){
        RemoteViews v=new RemoteViews(c.getPackageName(),type==5?R.layout.deck_circle:R.layout.deck_widget);
        v.setInt(R.id.deck_root,"setBackgroundColor",android.graphics.Color.TRANSPARENT);v.setImageViewBitmap(R.id.deck_content,DeckWidgetRenderer.render(c,type,s,width,height));
        v.setContentDescription(R.id.deck_content,DeckWidgetRenderer.description(type,s));
        boolean motion=MonitorViews.motion()&&s.online&&!s.stale;
        if(type==5){android.graphics.Bitmap rotor=DeckWidgetRenderer.rotor(c,s,Math.min(width,height));v.setImageViewBitmap(R.id.deck_rotor_a,rotor);v.setImageViewBitmap(R.id.deck_rotor_b,rotor);v.setViewVisibility(R.id.deck_rotor,motion?View.VISIBLE:View.GONE);if(motion)v.setDisplayedChild(R.id.deck_rotor,1);}
        else{
            boolean animate=MonitorViews.motion()&&(type==1?s.onlineHosts>0:motion);int dot=type==1&&s.offlineHosts>0?R.drawable.deck_error_dot:s.activeAlerts>0?R.drawable.deck_warning_dot:R.drawable.deck_live_dot;v.setImageViewResource(R.id.deck_dot_a,dot);v.setImageViewResource(R.id.deck_dot_b,dot);v.setViewVisibility(R.id.deck_pulse,animate?View.VISIBLE:View.GONE);if(animate)v.setDisplayedChild(R.id.deck_pulse,1);v.setViewVisibility(R.id.deck_glint,animate?View.VISIBLE:View.GONE);if(animate)v.setDisplayedChild(R.id.deck_glint,1);
            v.setViewVisibility(R.id.deck_actions,type==3?View.VISIBLE:View.GONE);if(type==3){v.setTextViewText(R.id.deck_mute,s.muted?"恢复通知":"静音 30 分钟");v.setOnClickPendingIntent(R.id.deck_mute,broadcast(c,widgetId,ACTION_MUTE));v.setOnClickPendingIntent(R.id.deck_update,broadcast(c,widgetId,ACTION_REFRESH));}v.setOnClickPendingIntent(R.id.deck_refresh,broadcast(c,widgetId,ACTION_REFRESH));
        }
        Intent launch=new Intent(c,MainActivity.class).setAction("cn.nodemon.app.OPEN_WIDGET."+widgetId).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);if(s.host!=null)launch.putExtra(EXTRA_HOST_ID,s.host.optString("id"));else launch.putExtra("open_devices",true);v.setOnClickPendingIntent(R.id.deck_root,PendingIntent.getActivity(c,widgetId,launch,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));return v;
    }
    private static PendingIntent broadcast(Context c,int id,String action){Intent intent=new Intent(c,WidgetActionReceiver.class).setAction(action).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id);return PendingIntent.getBroadcast(c,id*10+(ACTION_MUTE.equals(action)?1:2),intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
}
