package cn.nodemon.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import org.json.JSONObject;

final class MonitorNotifications {
    static final String CHANNEL="monitor-alerts";
    static void send(Context context,JSONObject host,MonitorDatabase.Event event) {
        MonitorSettings settings=new MonitorSettings(context);
        if(settings.notificationLevel()==0 || settings.notificationLevel()==2 && event.level<2 || settings.quietNow() || new HostStore(context).snoozed(host.optString("id")))return;
        if(event.rule!=null&&!settings.enabled(event.rule.split(":")[0]))return;
        boolean present=false;org.json.JSONArray configured=new HostStore(context).hosts();for(int i=0;i<configured.length();i++)if(configured.optJSONObject(i)!=null&&configured.optJSONObject(i).toString().equals(host.toString())){present=true;break;}if(!present)return;
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=PackageManager.PERMISSION_GRANTED)return;
        NotificationManager manager=context.getSystemService(NotificationManager.class);if(manager==null)return;
        try {
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,"监控告警",NotificationManager.IMPORTANCE_DEFAULT));
        if(!manager.areNotificationsEnabled())return;
        Intent intent=new Intent(context,MainActivity.class).setAction("cn.nodemon.app.OPEN_ALERTS").putExtra("open_alerts",true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent tap=PendingIntent.getActivity(context,1,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(context,CHANNEL).setSmallIcon(R.drawable.notification_icon).setContentTitle(host.optString("name")+" · "+event.title).setContentText(event.detail).setStyle(new Notification.BigTextStyle().bigText(event.detail)).setContentIntent(tap).setAutoCancel(true).build();
        manager.notify("monitor-alert",(int)event.id,notification);
        } catch(RuntimeException ignored) { /* A permission/channel change must not discard recorded monitoring data. */ }
    }
    static void cancel(Context context,long id){NotificationManager manager=context.getSystemService(NotificationManager.class);if(manager!=null)try{manager.cancel("monitor-alert",(int)id);}catch(RuntimeException ignored){}}
}
