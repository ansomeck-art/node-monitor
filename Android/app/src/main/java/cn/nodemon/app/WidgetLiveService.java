package cn.nodemon.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** User-visible monitoring of the external computers selected by desktop widgets. */
public final class WidgetLiveService extends Service {
    static final String ACTION_STOP="cn.nodemon.app.STOP_WIDGET_LIVE";
    static final String CHANNEL="widget-live-monitor";
    static final int NOTIFICATION_ID=9276;
    private static final long INTERVAL=15000;
    private static volatile boolean running;
    private static volatile String error="";
    private static final AtomicLong updatedAt=new AtomicLong();

    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService workers=Executors.newFixedThreadPool(3);
    private final AtomicBoolean stopped=new AtomicBoolean();
    private final AtomicLong networkEpoch=new AtomicLong();
    private final AtomicBoolean tailnetNetworkDirty=new AtomicBoolean(true);
    private final Map<String,Long> observedNetworks=new ConcurrentHashMap<>();
    private ConnectivityManager connectivity;
    private boolean networkRegistered,batchActive;
    private long batchStarted;
    private int generation;
    private final Runnable tick=this::pollBatch;
    private final ConnectivityManager.NetworkCallback networks=new ConnectivityManager.NetworkCallback(){
        private void changed(){
            networkEpoch.incrementAndGet();tailnetNetworkDirty.set(true);
            handler.post(()->{if(!stopped.get()&&running&&!batchActive)requestTick();});
        }
        @Override public void onAvailable(Network network){changed();}
        @Override public void onLost(Network network){changed();}
        @Override public void onLinkPropertiesChanged(Network network,LinkProperties properties){changed();}
    };

    /** Call from a visible activity or an explicitly permitted user/widget action. */
    static boolean ensureRunning(Context context){
        Context app=context.getApplicationContext();
        if(!new MonitorSettings(app).liveWidgets()||MonitorWidget.count(app)==0){stop(app);return false;}
        if(running)return true;
        try {
            app.startForegroundService(new Intent(app,WidgetLiveService.class));
            return true;
        } catch(RuntimeException denied){
            // Includes Android 12+ background-start restrictions; a later visible
            // activity can try again. No alarm or battery exemption bypass is used.
            error="系统暂未允许启动，请打开应用后重试";
            return false;
        }
    }
    /** Stops this session without changing the user's saved preference. */
    static void stop(Context context){
        try{context.getApplicationContext().stopService(new Intent(context,WidgetLiveService.class));}
        catch(RuntimeException ignored){}
    }
    static boolean isRunning(){return running;}
    static long lastUpdate(){return updatedAt.get();}
    static String lastError(){return error;}
    static long intervalMillis(){return INTERVAL;}

    @Override public void onCreate(){
        super.onCreate();
        // The foreground promotion happens before database work or networking.
        try {
            NotificationManager manager=getSystemService(NotificationManager.class);
            if(manager==null)throw new IllegalStateException("No notification service");
            manager.createNotificationChannel(new NotificationChannel(CHANNEL,"桌面挂件实时刷新",NotificationManager.IMPORTANCE_LOW));
            Notification notice=notification("正在准备桌面实时刷新");
            if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION_ID,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(NOTIFICATION_ID,notice);
            running=true;error="";
        } catch(RuntimeException denied){
            error="系统未允许后台实时刷新，请打开应用检查设置";
            stopped.set(true);running=false;stopSelf();return;
        }
        connectivity=getSystemService(ConnectivityManager.class);
        if(connectivity!=null)try{connectivity.registerDefaultNetworkCallback(networks);networkRegistered=true;}catch(RuntimeException ignored){}
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&ACTION_STOP.equals(intent.getAction())){
            new MonitorSettings(this).put("live_widgets",false);
            halt();return START_NOT_STICKY;
        }
        if(stopped.get()||!new MonitorSettings(this).liveWidgets()||MonitorWidget.count(this)==0){halt();return START_NOT_STICKY;}
        requestTick();
        // Normal process reclamation may restart this service. User force-stop
        // and the notification's explicit stop action are respected by Android.
        return START_STICKY;
    }

    private void requestTick(){
        if(stopped.get()||batchActive)return;
        handler.removeCallbacks(tick);
        handler.postDelayed(tick,Math.max(0,INTERVAL-(SystemClock.elapsedRealtime()-batchStarted)));
    }

    private boolean hasNetwork(){
        if(connectivity==null)return true;
        try{return connectivity.getActiveNetwork()!=null;}catch(RuntimeException ignored){return true;}
    }

    /** A summary widget needs every host; other widgets only need their bindings. */
    static List<JSONObject> targetHosts(Context context){
        HostStore store=new HostStore(context);JSONArray configured=store.hosts();
        AppWidgetManager manager=AppWidgetManager.getInstance(context);
        int[] summaries=manager.getAppWidgetIds(new ComponentName(context,SummaryWidget.class));
        Map<String,JSONObject> hosts=new LinkedHashMap<>();
        if(summaries.length>0){
            for(int i=0;i<configured.length();i++){JSONObject host=configured.optJSONObject(i);if(host!=null)hosts.put(host.optString("id"),host);}
        } else {
            for(Class<?> provider:MonitorWidget.PROVIDERS){
                if(provider==SummaryWidget.class)continue;
                for(int widget:manager.getAppWidgetIds(new ComponentName(context,provider))){JSONObject host=store.widgetHost(configured,widget);if(host!=null)hosts.put(host.optString("id"),host);}
            }
        }
        return new ArrayList<>(hosts.values());
    }

    private void pollBatch(){
        if(stopped.get())return;
        if(!new MonitorSettings(this).liveWidgets()||MonitorWidget.count(this)==0){halt();return;}
        if(batchActive)return;
        if(MainActivity.visible){
            error="";MonitorWidget.updateAll(this);showStatus("应用前台中，共享实时采样");
            handler.postDelayed(tick,INTERVAL);return;
        }
        List<JSONObject> hosts=targetHosts(this);
        if(hosts.isEmpty()){error="挂件尚未绑定主机";halt();return;}
        if(!hasNetwork()){
            error="网络已断开，连接恢复后继续";
            // No observation is different from an observed server failure.
            workers.execute(()->{MonitorDatabase db=MonitorDatabase.get(this);for(JSONObject host:hosts)db.breakObservations(host.optString("id"));});
            observedNetworks.clear();MonitorWidget.updateAll(this);showStatus(error);
            handler.postDelayed(tick,INTERVAL);return;
        }
        batchActive=true;batchStarted=SystemClock.elapsedRealtime();int batch=++generation;
        AtomicInteger remaining=new AtomicInteger(hosts.size()),successes=new AtomicInteger(),failures=new AtomicInteger();
        for(JSONObject host:hosts)workers.execute(()->{
            try{pollHost(host,successes,failures);}
            finally{if(remaining.decrementAndGet()==0)handler.post(()->finishBatch(batch,hosts.size(),successes.get(),failures.get()));}
        });
    }

    private void pollHost(JSONObject host,AtomicInteger successes,AtomicInteger failures){
        if(stopped.get()||!new MonitorSettings(this).liveWidgets())return;
        String id=host.optString("id");PollCoordinator.Lease lease=PollCoordinator.acquire(id);if(lease==null)return;
        try {
            MonitorDatabase db=MonitorDatabase.get(this);long network=networkEpoch.get();
            Long prior=observedNetworks.get(id);if(prior==null||prior!=network)db.breakObservations(id);
            if(host.optString("mode").equals("tailnet")&&tailnetNetworkDirty.compareAndSet(true,false))try{TailnetBridge.request(this,"network_changed",null,null);}catch(Exception|LinkageError ignored){}
            if(!hasNetwork()){db.breakObservations(id);return;}
            JSONObject sample=null;String failure=null;long started=SystemClock.elapsedRealtime();
            try{sample=MonitorClient.fetch(this,host);}catch(Exception|LinkageError unavailable){failure="桌面实时连接失败，请检查采集端、网络与授权";}
            if(stopped.get()||!new MonitorSettings(this).liveWidgets())return;
            long currentNetwork=networkEpoch.get();
            if(currentNetwork!=network)db.breakObservations(id);
            observedNetworks.put(id,currentNetwork);
            if(sample==null&&(!hasNetwork()||currentNetwork!=network)){
                db.breakObservations(id);return;
            }
            // A host can be edited or deleted while its request is in flight.
            boolean current=false;JSONArray configured=new HostStore(this).hosts();
            for(int i=0;i<configured.length();i++)if(configured.optJSONObject(i)!=null&&configured.optJSONObject(i).toString().equals(host.toString())){current=true;break;}
            if(!current)return;
            db.record(host,sample,failure,SystemClock.elapsedRealtime()-started,System.currentTimeMillis(),(int)(INTERVAL/1000));
            if(sample!=null){successes.incrementAndGet();updatedAt.accumulateAndGet(System.currentTimeMillis(),Math::max);}else failures.incrementAndGet();
        } catch(RuntimeException unavailable){
            failures.incrementAndGet();
        } finally {lease.close();}
    }

    private void finishBatch(int batch,int hosts,int successes,int failures){
        if(stopped.get()||batch!=generation)return;batchActive=false;
        if(!new MonitorSettings(this).liveWidgets()||MonitorWidget.count(this)==0){halt();return;}
        error=failures==0?"":failures==hosts?"暂时无法刷新主机，正在重试":"部分主机暂时无法刷新（"+failures+" 台）";
        MonitorWidget.updateAll(this);
        String status=error;
        if(status.isEmpty())status="每约 15 秒刷新 "+hosts+" 台主机"+(updatedAt.get()>0?" · 最近 "+new SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(new Date(updatedAt.get())):"");
        showStatus(status);
        handler.removeCallbacks(tick);handler.postDelayed(tick,Math.max(250,INTERVAL-(SystemClock.elapsedRealtime()-batchStarted)));
    }

    private Notification notification(String status){
        Intent open=new Intent(this,MainActivity.class).setAction("cn.nodemon.app.OPEN_LIVE_WIDGETS").putExtra("open_devices",true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent tap=PendingIntent.getActivity(this,NOTIFICATION_ID,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Intent stop=new Intent(this,WidgetLiveService.class).setAction(ACTION_STOP);
        PendingIntent end=PendingIntent.getService(this,NOTIFICATION_ID,stop,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder=new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.notification_icon)
                .setContentTitle("桌面挂件实时刷新中").setContentText(status).setContentIntent(tap)
                .setCategory(Notification.CATEGORY_SERVICE).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PRIVATE).setColor(0xff3B8D71)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause,"停止实时刷新",end).build());
        if(Build.VERSION.SDK_INT>=31)builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return builder.build();
    }
    private void showStatus(String status){
        if(stopped.get())return;NotificationManager manager=getSystemService(NotificationManager.class);
        if(manager!=null)try{manager.notify(NOTIFICATION_ID,notification(status));}catch(RuntimeException ignored){}
    }
    private void halt(){
        stopped.set(true);running=false;handler.removeCallbacksAndMessages(null);
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
    }
    @Override public void onTimeout(int startId,int foregroundServiceType){error="系统要求暂停后台刷新，请重新打开应用";halt();}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){
        stopped.set(true);running=false;handler.removeCallbacksAndMessages(null);
        if(networkRegistered&&connectivity!=null)try{connectivity.unregisterNetworkCallback(networks);}catch(RuntimeException ignored){}
        workers.shutdownNow();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
}
