package cn.nodemon.app;

import android.app.Activity;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Standard launcher pin requests; accepted requests and verified additions are distinct states. */
final class WidgetPinning {
    private static final String PREFS="widget-pin-requests";
    static final long RESPONSE_WAIT_MILLIS=20000;
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}

    static List<AppWidgetProviderInfo> providers(Context c){
        List<AppWidgetProviderInfo> own=new ArrayList<>();
        try{for(AppWidgetProviderInfo info:AppWidgetManager.getInstance(c).getInstalledProvidersForProfile(Process.myUserHandle())){
            if(!info.provider.getPackageName().equals(c.getPackageName()))continue;
            for(Class<?> type:MonitorWidget.PROVIDERS)if(info.provider.getClassName().equals(type.getName())){own.add(info);break;}
        }}catch(RuntimeException ignored){}
        return own;
    }
    static boolean supported(Context c){try{return AppWidgetManager.getInstance(c).isRequestPinAppWidgetSupported();}catch(RuntimeException e){return false;}}
    static String launcher(Context c){
        try{ResolveInfo info=c.getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),0);return info==null||info.activityInfo==null?"":info.activityInfo.packageName;}catch(RuntimeException e){return "";}
    }

    static synchronized String request(Activity activity,int type,JSONObject host)throws Exception{
        if(type<0||type>=MonitorWidget.PROVIDERS.length)throw new IllegalArgumentException("挂件样式无效，请重新选择。");
        String hostId=type==1?"":host==null?"":host.optString("id");
        if(type!=1&&!configured(activity,hostId))throw new IllegalArgumentException("请先选择一台已添加的主机。");
        ComponentName provider=new ComponentName(activity,MonitorWidget.PROVIDERS[type]);
        boolean registered=false;for(AppWidgetProviderInfo info:providers(activity))if(info.provider.equals(provider)){registered=true;break;}
        if(!registered)throw new IllegalStateException("系统尚未识别此挂件。请确认安装的是本次更新版本，然后重新打开应用。");
        long now=System.currentTimeMillis();prune(activity,now);
        // One pending flow at a time for the same style and host, without silently flooding the launcher.
        for(String key:prefs(activity).getAll().keySet()){
            JSONObject previous=read(activity,key);
            if("requested".equals(previous.optString("state"))&&previous.optInt("type",-1)==type&&hostId.equals(previous.optString("hostId"))&&now-previous.optLong("requestedAt")<RESPONSE_WAIT_MILLIS)return key;
        }
        String token=UUID.randomUUID().toString();JSONObject record=new JSONObject();record.put("state","requested");record.put("type",type);record.put("provider",provider.flattenToString());record.put("hostId",hostId);record.put("requestedAt",now);record.put("launcher",launcher(activity));
        JSONArray before=new JSONArray();for(int id:AppWidgetManager.getInstance(activity).getAppWidgetIds(provider))before.put(id);record.put("before",before);
        record.put("message","正在等待桌面确认；请在系统提示中完成添加。");save(activity,token,record);
        if(!supported(activity)){
            record.put("state","unsupported");record.put("message","当前桌面未开放应用内添加挂件。样式已就绪，可以打开应用信息检查桌面相关权限，或从桌面已找到的安卓小部件入口添加。");save(activity,token,record);return token;
        }
        Intent callback=new Intent(activity,WidgetActionReceiver.class).setAction(MonitorWidget.ACTION_PINNED).setData(Uri.parse("nodemon://pin-result/"+token));
        int flags=PendingIntent.FLAG_UPDATE_CURRENT;if(Build.VERSION.SDK_INT>=31)flags|=PendingIntent.FLAG_MUTABLE;
        PendingIntent completed=PendingIntent.getBroadcast(activity,0,callback,flags);
        Bundle extras=new Bundle();
        // A real rendered preview helps launchers that do not inflate the provider's initial layout here.
        DeckWidgetRenderer.State preview=preview(activity,type,host);
        int width=type<2?180:type==4?240:type==5?160:360;
        int height=type<2?180:type==3?360:type==4?100:type==5?160:170;
        extras.putParcelable(AppWidgetManager.EXTRA_APPWIDGET_PREVIEW,MonitorWidget.view(activity,type,preview,width,height,0));
        try{
            if(!AppWidgetManager.getInstance(activity).requestPinAppWidget(provider,extras,completed)){
                record.put("state","unsupported");record.put("message","桌面没有接受添加请求。尚未添加挂件；可以检查当前默认桌面及应用信息后重试。");save(activity,token,record);
            }
        }catch(RuntimeException e){record.put("state","error");record.put("message","无法发起桌面添加，请保持此页面在前台，并检查默认桌面后重试。");save(activity,token,record);}
        return token;
    }

    static synchronized JSONObject result(Context c,String token){
        JSONObject record=read(c,token);
        if(!record.has("state")){put(record,"state","error");put(record,"message","没有找到这次添加请求，请重新选择样式。");return record;}
        if("added".equals(record.optString("state"))){
            int id=record.optInt("widgetId",-1);AppWidgetProviderInfo info=AppWidgetManager.getInstance(c).getAppWidgetInfo(id);
            if(info==null||!info.provider.flattenToString().equals(record.optString("provider"))){put(record,"state","removed");put(record,"message","这个挂件已不在桌面上，可以重新添加。");}
        }else if("requested".equals(record.optString("state"))&&System.currentTimeMillis()-record.optLong("requestedAt")>=RESPONSE_WAIT_MILLIS){
            put(record,"state","unconfirmed");put(record,"message","尚未收到桌面的添加确认，因此没有标记为已添加。如果小米没有弹窗，可以打开应用信息检查桌面相关权限，再返回重试；也可先到桌面查看。");
        }
        return record;
    }

    /** Returns false only for callbacks created by the older application version. */
    static synchronized boolean complete(Context c,Intent callback){
        Uri data=callback.getData();if(data==null||!"nodemon".equals(data.getScheme())||!"pin-result".equals(data.getHost()))return false;
        String token=data.getLastPathSegment();JSONObject record=read(c,token);if(!record.has("state"))return true;
        int id=callback.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID);
        ComponentName expected=ComponentName.unflattenFromString(record.optString("provider"));AppWidgetManager manager=AppWidgetManager.getInstance(c);AppWidgetProviderInfo info=id<0?null:manager.getAppWidgetInfo(id);
        if(expected==null||!expected.getPackageName().equals(c.getPackageName())||info==null||!expected.equals(info.provider))return true;
        boolean owned=false;for(int actual:manager.getAppWidgetIds(expected))if(actual==id){owned=true;break;}
        if(!owned)return true;
        if("added".equals(record.optString("state"))&&record.optInt("widgetId")==id)return true;
        JSONArray before=record.optJSONArray("before");if(before!=null)for(int i=0;i<before.length();i++)if(before.optInt(i,-1)==id)return true;
        String hostId=record.optString("hostId");boolean found=hostId.isEmpty()||configured(c,hostId);
        if(!hostId.isEmpty()&&found)new HostStore(c).bindWidget(id,hostId);
        put(record,"state","added");put(record,"widgetId",id);put(record,"confirmedAt",System.currentTimeMillis());put(record,"hostBound",found);
        put(record,"message",found?"已添加到桌面。":"挂件已添加；原主机已移除，请在挂件设置中重新选择主机。");save(c,token,record);MonitorWidget.updateAll(c);MonitorWidget.schedule(c);return true;
    }

    static DeckWidgetRenderer.State preview(Context c,int type,JSONObject host){
        DeckWidgetRenderer.State s=new DeckWidgetRenderer.State();MonitorDatabase db=MonitorDatabase.get(c);JSONArray hosts=new HostStore(c).hosts();s.host=type==1?null:host;s.totalHosts=hosts.length();s.intervalSeconds=new MonitorSettings(c).interval();s.refreshMode=WidgetLiveService.isRunning()?"15 秒实时刷新":new MonitorSettings(c).liveWidgets()?"等待实时刷新":"系统定时更新";s.now=System.currentTimeMillis();
        for(int i=0;i<hosts.length();i++){JSONObject h=hosts.optJSONObject(i);if(h==null)continue;String id=h.optString("id");JSONObject sample=db.cached(id);int failures=db.failures(id);if(sample!=null&&failures==0&&s.now-MonitorDatabase.sampleTime(sample)<Math.max(30000,sample.optDouble("sampleIntervalSeconds",5)*3000))s.onlineHosts++;else if(failures>=new MonitorSettings(c).failures())s.offlineHosts++;}
        String id=s.host==null?"":s.host.optString("id");s.snapshot=s.host==null?null:db.cached(id);s.online=type==1?s.onlineHosts>0:s.snapshot!=null&&db.failures(id)==0;s.stale=type!=1&&s.online&&s.now-MonitorDatabase.sampleTime(s.snapshot)>=Math.max(30000,s.snapshot.optDouble("sampleIntervalSeconds",5)*3000);s.activeAlerts=db.active(id);s.summary=db.summary(id);s.history=s.host==null?java.util.Collections.emptyList():db.history(id,3600000);
        for(MonitorDatabase.Event e:db.events())if(e.ended==0&&(type==1||id.equals(e.host))){s.alertTitle=e.title;break;}return s;
    }
    private static boolean configured(Context c,String id){if(id.isEmpty())return false;JSONArray hosts=new HostStore(c).hosts();for(int i=0;i<hosts.length();i++){JSONObject h=hosts.optJSONObject(i);if(h!=null&&id.equals(h.optString("id")))return true;}return false;}
    private static JSONObject read(Context c,String token){try{return new JSONObject(prefs(c).getString(token==null?"":token,"{}"));}catch(Exception e){return new JSONObject();}}
    private static void save(Context c,String token,JSONObject record){prefs(c).edit().putString(token,record.toString()).commit();}
    private static void put(JSONObject o,String key,Object value){try{o.put(key,value);}catch(Exception ignored){}}
    private static void prune(Context c,long now){SharedPreferences.Editor edit=prefs(c).edit();for(String key:prefs(c).getAll().keySet())if(now-read(c,key).optLong("requestedAt")>86400000L)edit.remove(key);edit.apply();}
}
