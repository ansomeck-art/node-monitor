package cn.nodemon.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;

/** Device-side integration and visual tests. Fixtures are explicitly labelled simulated. */
public final class UiSmokeInstrumentation extends Instrumentation {
    private static final String HOST="qa-fixture";
    private Activity activity;
    private JSONObject host;
    private MonitorDatabase database;
    private File output;
    private FixtureServer fixture;
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    @Override public void onStart(){
        Bundle results=new Bundle();
        try {
            Context target=getTargetContext();output=new File(target.getExternalFilesDir(null),"qa");if(!output.exists()&&!output.mkdirs())throw new AssertionError("QA directory");
            fixture=new FixtureServer();seed(target);DatabaseRegressionChecks.run(target);checkWidgetLifecycle(target);
            JSONObject live=MonitorClient.fetch(target,host);check(live.optBoolean("demo"),"fixture endpoint must identify simulated data");
            for(String theme:new String[]{"light","dark"}){
                new MonitorSettings(target).put("theme",theme);
                Intent launch=new Intent().setClassName(target.getPackageName(),"cn.nodemon.app.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity=startActivitySync(launch);waitForIdleSync();
                if(theme.equals("light")){
                    WidgetLiveChecks.run(this,activity,host,output);
                    WidgetDiscoveryChecks.run(this,activity,host,output);
                    SystemClock.sleep(500);
                }
                runOnMainSync(()->{try{field("foreground").setBoolean(activity,false);((Handler)field("handler").get(activity)).removeCallbacks((Runnable)field("tick").get(activity));snapshots().put(HOST,live);}catch(Exception e){throw new RuntimeException(e);}});
                visit(theme+"-01-devices","devices",0,"节点监控");
                visit(theme+"-02-hardware","details",0,"处理器");
                visit(theme+"-03-storage","details",1,"系统内存");
                visit(theme+"-04-trends","details",2,"使用率趋势");
                visit(theme+"-05-alerts","alerts",0,"告警中心");
                visit(theme+"-06-settings","settings",0,"设置");
                if(theme.equals("light"))captureNumberMotion(live);
                captureWidgets(theme,target,live);
                captureGallery(theme);
                runOnMainSync(()->activity.finish());waitForIdleSync();
            }
            results.putString("stream","PASS: snapshot fetch; SQLite and alert regressions; real foreground widget service with two 15s background cycles, notification/stop/removal and shared lease checks; six app pages and eight widget states in both themes; gallery previews; provider discovery and real launcher pin confirmation/host binding; native motion frames. Simulated fixtures only.\n");
            results.putString("screenshots",output.getAbsolutePath());fixture.close();finish(Activity.RESULT_OK,results);
        } catch(Throwable failure){
            if(fixture!=null)fixture.close();
            results.putString("stream",android.util.Log.getStackTraceString(failure));finish(Activity.RESULT_CANCELED,results);
        }
    }
    private static final class FixtureServer implements AutoCloseable {
        final ServerSocket socket;
        int sequence;
        volatile boolean closed;
        FixtureServer()throws Exception{
            socket=new ServerSocket(9274,8,InetAddress.getByName("127.0.0.1"));Thread worker=new Thread(()->{
                while(!closed)try(Socket connection=socket.accept()){
                    connection.setSoTimeout(5000);BufferedReader reader=new BufferedReader(new InputStreamReader(connection.getInputStream(),StandardCharsets.UTF_8));boolean authorized=false;String line;
                    while((line=reader.readLine())!=null&&!line.isEmpty())if(line.equalsIgnoreCase("Authorization: Bearer qa-fixture-token"))authorized=true;
                    JSONObject payload=authorized?sample(System.currentTimeMillis()):null;
                    if(payload!=null){int usage=10+(++sequence%50);payload.getJSONObject("cpu").put("usagePercent",usage+.5);payload.getJSONObject("cpu").getJSONArray("packages").getJSONObject(0).put("usagePercent",usage);payload.getJSONObject("cpu").getJSONArray("packages").getJSONObject(1).put("usagePercent",usage+1);}
                    byte[] body=(authorized?payload.toString():"{\"error\":\"unauthorized\"}").getBytes(StandardCharsets.UTF_8);
                    String head="HTTP/1.1 "+(authorized?"200 OK":"401 Unauthorized")+"\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: "+body.length+"\r\n\r\n";
                    connection.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));connection.getOutputStream().write(body);connection.getOutputStream().flush();
                }catch(Exception e){if(!closed)android.util.Log.e("NodeMonitorQA","fixture",e);}
            },"qa-simulated-agent");worker.setDaemon(true);worker.start();
        }
        @Override public void close(){closed=true;try{socket.close();}catch(Exception ignored){}}
    }
    private Field field(String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f;}
    @SuppressWarnings("unchecked") private Map<String,JSONObject> snapshots()throws Exception{return (Map<String,JSONObject>)field("snapshots").get(activity);}
    private void visit(String file,String page,int tab,String expected)throws Exception{
        runOnMainSync(()->{try{field("page").set(activity,page);field("selected").set(activity,HOST);field("detailTab").setInt(activity,tab);Method render=MainActivity.class.getDeclaredMethod("render");render.setAccessible(true);render.invoke(activity);}catch(Exception e){throw new RuntimeException(e);}});
        SystemClock.sleep(900);waitForIdleSync();
        check(contains(activity.getWindow().getDecorView(),expected),"missing page text: "+expected);capture(file);
    }
    private boolean contains(View view,String text){if(view instanceof TextView&&((TextView)view).getText().toString().contains(text))return true;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(contains(group.getChildAt(i),text))return true;}return false;}
    private void capture(String name)throws Exception{
        final Bitmap[] image=new Bitmap[1];runOnMainSync(()->{View view=activity.getWindow().getDecorView();image[0]=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(image[0]));});
        try(FileOutputStream stream=new FileOutputStream(new File(output,name+".png"))){image[0].compress(Bitmap.CompressFormat.PNG,100,stream);}image[0].recycle();
    }
    private void captureNumberMotion(JSONObject sample)throws Exception{
        visit("motion-start","devices",0,"节点监控");JSONObject changed=new JSONObject(sample.toString());changed.getJSONObject("cpu").put("usagePercent",63);
        runOnMainSync(()->{try{snapshots().put(HOST,changed);Method render=MainActivity.class.getDeclaredMethod("render");render.setAccessible(true);render.invoke(activity);}catch(Exception e){throw new RuntimeException(e);}});
        for(int i=0;i<8;i++){SystemClock.sleep(90);capture("motion-"+i);}
    }
    private void captureGallery(String theme)throws Exception{
        ActivityMonitor monitor=addMonitor("cn.nodemon.app.WidgetGalleryActivity",null,false);
        runOnMainSync(()->activity.startActivity(new Intent().setClassName(getTargetContext().getPackageName(),"cn.nodemon.app.WidgetGalleryActivity").putExtra(MonitorWidget.EXTRA_HOST_ID,HOST)));
        Activity gallery=waitForMonitorWithTimeout(monitor,15000);removeMonitor(monitor);check(gallery!=null,"gallery activity did not open");
        SystemClock.sleep(700);final Bitmap[] image=new Bitmap[1];runOnMainSync(()->{View decor=gallery.getWindow().getDecorView();image[0]=Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),Bitmap.Config.ARGB_8888);decor.draw(new Canvas(image[0]));});
        try(FileOutputStream stream=new FileOutputStream(new File(output,"widget-gallery-"+theme+".png"))){image[0].compress(Bitmap.CompressFormat.PNG,100,stream);}image[0].recycle();
        // Capture the native catalogue as a long screenshot, without replacing the real widget views.
        final Bitmap[] catalogue=new Bitmap[1];runOnMainSync(()->{try{Field field=WidgetGalleryActivity.class.getDeclaredField("body");field.setAccessible(true);View content=(View)field.get(gallery);catalogue[0]=Bitmap.createBitmap(content.getWidth(),content.getHeight(),Bitmap.Config.ARGB_8888);content.draw(new Canvas(catalogue[0]));}catch(Exception e){throw new RuntimeException(e);}});
        try(FileOutputStream stream=new FileOutputStream(new File(output,"widget-gallery-catalogue-"+theme+".png"))){catalogue[0].compress(Bitmap.CompressFormat.PNG,100,stream);}catalogue[0].recycle();
        if(theme.equals("light")){
            String token="qa-unsupported-launcher";getTargetContext().getSharedPreferences("widget-pin-requests",Context.MODE_PRIVATE).edit().putString(token,new JSONObject().put("state","unsupported").put("requestedAt",System.currentTimeMillis()).put("message","测试场景：桌面不支持直接添加。尚未添加挂件。").toString()).commit();
            runOnMainSync(()->{try{Field pending=WidgetGalleryActivity.class.getDeclaredField("pending");pending.setAccessible(true);pending.set(gallery,token);Method update=WidgetGalleryActivity.class.getDeclaredMethod("updateStatus");update.setAccessible(true);update.invoke(gallery);Field title=WidgetGalleryActivity.class.getDeclaredField("statusTitle");title.setAccessible(true);check(((TextView)title.get(gallery)).getText().toString().equals("当前桌面未提供直接添加"),"unsupported launcher incorrectly claimed success");}catch(Exception e){throw new RuntimeException(e);}});
            final Bitmap[] unsupported=new Bitmap[1];runOnMainSync(()->{View decor=gallery.getWindow().getDecorView();unsupported[0]=Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),Bitmap.Config.ARGB_8888);decor.draw(new Canvas(unsupported[0]));});try(FileOutputStream stream=new FileOutputStream(new File(output,"widget-gallery-unsupported.png"))){unsupported[0].compress(Bitmap.CompressFormat.PNG,100,stream);}unsupported[0].recycle();getTargetContext().getSharedPreferences("widget-pin-requests",Context.MODE_PRIVATE).edit().remove(token).commit();
        }
        runOnMainSync(gallery::finish);SystemClock.sleep(200);
    }
    private void captureWidgets(String theme,Context target,JSONObject sample)throws Exception{
        Configuration config=new Configuration(target.getResources().getConfiguration());config.uiMode=(config.uiMode&~Configuration.UI_MODE_NIGHT_MASK)|(theme.equals("dark")?Configuration.UI_MODE_NIGHT_YES:Configuration.UI_MODE_NIGHT_NO);Context themed=target.createConfigurationContext(config);
        int[] types={0,1,2,2,2,3,4,5};int[] widths={180,180,360,360,360,360,240,160},heights={180,180,170,170,170,360,100,160};
        for(int i=0;i<types.length;i++){
            DeckWidgetRenderer.State state=new DeckWidgetRenderer.State();state.host=host;state.snapshot=new JSONObject(sample.toString());state.online=i!=4;state.stale=false;state.onlineHosts=1;state.totalHosts=1;state.offlineHosts=0;state.intervalSeconds=5;state.history=database.history(HOST,3600000);state.summary=database.summary(HOST);state.activeAlerts=i==3||i==5?1:0;state.alertTitle=state.activeAlerts>0?"GPU 2 温度偏高":"";state.now=System.currentTimeMillis();
            if(i==5)state.snapshot.getJSONArray("gpus").getJSONObject(0).put("usagePercent",96);
            final int type=types[i],width=widths[i],height=heights[i],number=i;final View[] widget=new View[1];
            runOnMainSync(()->{RemoteViews remote=MonitorWidget.view(themed,type,state,width,height,100+number);FrameLayout stage=new FrameLayout(activity);stage.setBackgroundColor(theme.equals("dark")?0xff101311:0xfff4f5f1);widget[0]=remote.apply(themed,stage);float density=activity.getResources().getDisplayMetrics().density;FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(Math.round(width*density),Math.round(height*density),android.view.Gravity.CENTER);stage.addView(widget[0],p);activity.setContentView(stage);});
            // Launcher-owned ViewFlipper animations need not make the event queue idle.
            SystemClock.sleep(400);final Bitmap[] image=new Bitmap[1];runOnMainSync(()->{image[0]=Bitmap.createBitmap(widget[0].getWidth(),widget[0].getHeight(),Bitmap.Config.ARGB_8888);widget[0].draw(new Canvas(image[0]));});
            try(FileOutputStream stream=new FileOutputStream(new File(output,theme+"-widget-"+(i+1)+".png"))){image[0].compress(Bitmap.CompressFormat.PNG,100,stream);}image[0].recycle();
            if(theme.equals("light")&&(i==0||i==7))for(int frame=0;frame<10;frame++){
                SystemClock.sleep(180);final Bitmap[] motion=new Bitmap[1];runOnMainSync(()->{motion[0]=Bitmap.createBitmap(widget[0].getWidth(),widget[0].getHeight(),Bitmap.Config.ARGB_8888);widget[0].draw(new Canvas(motion[0]));});
                try(FileOutputStream stream=new FileOutputStream(new File(output,"widget-motion-"+i+"-"+frame+".png"))){motion[0].compress(Bitmap.CompressFormat.PNG,100,stream);}motion[0].recycle();
            }
        }
    }
    private void checkWidgetLifecycle(Context context)throws Exception{
        AppWidgetHost appHost=new AppWidgetHost(context,6720);AppWidgetManager manager=AppWidgetManager.getInstance(context);HostStore store=new HostStore(context);int[] ids=new int[MonitorWidget.PROVIDERS.length];
        appHost.deleteHost();
        try {
            for(int i=0;i<ids.length;i++){
                ids[i]=appHost.allocateAppWidgetId();Bundle options=new Bundle();options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,i<2?180:360);options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,i==3?360:180);
                check(manager.bindAppWidgetIdIfAllowed(ids[i],new ComponentName(context,MonitorWidget.PROVIDERS[i]),options),"provider binding "+i);AppWidgetProviderInfo info=manager.getAppWidgetInfo(ids[i]);check(info!=null&&info.provider.getClassName().equals(MonitorWidget.PROVIDERS[i].getName()),"provider metadata "+i);if(i!=1){store.bindWidget(ids[i],HOST);check(MonitorWidget.state(context,i,ids[i]).host.optString("id").equals(HOST),"per-widget host binding");}
            }
            check(MonitorWidget.count(context)==6,"all six providers counted");store.snooze(HOST,0);Intent mute=new Intent(MonitorWidget.ACTION_MUTE).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,ids[3]);MonitorWidget.handleAction(context,mute);check(store.snoozed(HOST),"dashboard mute action");MonitorWidget.handleAction(context,mute);check(!store.snoozed(HOST),"dashboard unmute action");
            MonitorWidget.handleAction(context,new Intent(MonitorWidget.ACTION_MUTE).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,999999));check(!store.snoozed(HOST),"invalid widget cannot mute");
            android.content.SharedPreferences actions=context.getSharedPreferences("widget-actions",Context.MODE_PRIVATE);actions.edit().remove("last_refresh").commit();MonitorWidget.handleAction(context,new Intent(MonitorWidget.ACTION_REFRESH).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,ids[3]));check(actions.getLong("last_refresh",0)>0,"dashboard refresh action accepted");
        } finally {appHost.deleteHost();for(int id:ids)if(id>0)store.forgetWidget(id);store.snooze(HOST,0);}
    }
    private void seed(Context context)throws Exception{
        HostStore store=new HostStore(context);host=new JSONObject().put("id",HOST).put("name","双卡服务器 · 演示").put("mode","direct").put("address","http://127.0.0.1:9274").put("directAddress","http://127.0.0.1:9274").put("tailnetAddress","").put("allowLan",false).put("token",store.protect("qa-fixture-token"));store.save(new JSONArray().put(host));
        MonitorSettings settings=new MonitorSettings(context);settings.put("notification_level",0);settings.put("background",false);settings.put("live_widgets",false);settings.put("interval",5);settings.put("failures",3);settings.put("offline",15);
        database=MonitorDatabase.get(context);database.forget(HOST);long now=System.currentTimeMillis();
        for(int i=0;i<=360;i++){long t=now-3600000+i*10000L;JSONObject s=sample(t);double cpu=i<220?21+Math.sin(i*.13)*2:i<270?22+(i-220)*.42:28+Math.sin(i*.15)*1.2;s.getJSONObject("cpu").put("usagePercent",cpu);s.getJSONObject("memory").put("usagePercent",i>=120&&i<135?92:15);s.getJSONArray("gpus").getJSONObject(1).put("temperatureCelsius",i>=345?78:35);database.record(host,s,null,12,t);}
        JSONObject current=sample(now+1);long before=count("samples");database.record(host,current,null,12,now);long unique=count("samples");database.record(host,current,null,12,now);check(unique==before+1&&count("samples")==unique,"same timestamp must not duplicate history");
        long failures=now-90000;database.record(host,null,"模拟断线",0,failures);database.record(host,null,"模拟断线",0,failures+5000);check(database.failures(HOST)==2,"failure counter");database.record(host,null,"模拟断线",0,failures+20000);check(database.events().stream().anyMatch(e->e.ended==0&&e.title.contains("无法连接")),"offline event after both thresholds");database.record(host,sample(now+2),null,12,now+2);check(database.failures(HOST)==0,"success resets failures");
        for(int i=0;i<8;i++){JSONObject s=sample(now-70000+i*10000);s.getJSONArray("gpus").getJSONObject(1).put("temperatureCelsius",78);database.record(host,s,null,12,now-70000+i*10000);}
        check(database.cached(HOST)!=null,"snapshot persists");check(!database.history(HOST,3600000).isEmpty(),"history query");check(database.events().stream().anyMatch(e->e.ended>0&&"recovered".equals(e.resolution)),"recovered events persist");
    }
    private long count(String table){try(Cursor c=database.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM "+table,null)){c.moveToFirst();return c.getLong(0);}}
    private void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static JSONObject sample(long time)throws Exception{
        String stamp=Instant.ofEpochMilli(time).toString();long gib=1073741824L;
        JSONObject cpu=new JSONObject().put("usagePercent",21.5).put("cores",48).put("loadAverage",new JSONArray(new double[]{.42,.38,.31})).put("packages",new JSONArray().put(new JSONObject().put("id","cpu0").put("usagePercent",21).put("temperatureCelsius",46)).put(new JSONObject().put("id","cpu1").put("usagePercent",22).put("temperatureCelsius",46)));
        JSONObject memory=new JSONObject().put("usedBytes",27*gib).put("totalBytes",(long)(184.5*gib)).put("availableBytes",(long)(157.5*gib)).put("cachedBytes",(long)(12.4*gib)).put("usagePercent",15).put("swapUsedBytes",0).put("swapTotalBytes",2*gib);
        JSONArray gpus=new JSONArray();for(int i=0;i<2;i++)gpus.put(new JSONObject().put("id","gpu"+i).put("name","NVIDIA RTX 2080 Ti").put("usagePercent",0).put("temperatureCelsius",i==0?34:78).put("memoryUsedBytes",(long)(.2*gib)).put("memoryTotalBytes",22*gib).put("sampledAt",stamp));
        JSONArray disks=new JSONArray();String[] mounts={"/","/boot","/data"};double[] total={491.6,7.8,1350.5},used={37.9,.6,468.1};for(int i=0;i<3;i++)disks.put(new JSONObject().put("id","disk"+i).put("mount",mounts[i]).put("totalBytes",(long)(total[i]*gib)).put("usedBytes",(long)(used[i]*gib)));
        JSONObject network=new JSONObject().put("receiveBytesPerSecond",85*1024).put("sendBytesPerSecond",23*1024).put("diskReadBytesPerSecond",12*1048576).put("diskWriteBytesPerSecond",3*1048576).put("dropPercent",0).put("interfaces",new JSONArray().put(new JSONObject().put("name","eth0").put("virtual",false).put("receivedBytes",118*gib).put("sentBytes",(long)(41.2*gib))));
        JSONArray processes=new JSONArray();String[] names={"python3 train.py","nginx","dockerd","tailscaled","sshd"};double[] usage={42.1,1.4,.9,.3,.1},ram={8.2,.3,.6,.1,.05};for(int i=0;i<5;i++)processes.put(new JSONObject().put("pid",51201+i).put("name",names[i]).put("cpuPercent",usage[i]).put("memoryBytes",(long)(ram[i]*gib)));
        JSONArray services=new JSONArray();for(String name:new String[]{"docker","nginx","tailscaled","redis"})services.put(new JSONObject().put("name",name).put("state",name.equals("redis")?"inactive":"active"));
        return new JSONObject().put("schemaVersion",1).put("agentVersion","qa-fixture").put("sampledAt",stamp).put("sampleIntervalSeconds",5).put("gpuSampleIntervalSeconds",15).put("demo",true).put("host",new JSONObject().put("os","Ubuntu 26.04 LTS · 模拟").put("uptimeSeconds",6*86400+4*3600)).put("cpu",cpu).put("memory",memory).put("gpus",gpus).put("disks",disks).put("network",network).put("processes",processes).put("services",services).put("warnings",new JSONArray());
    }
}
