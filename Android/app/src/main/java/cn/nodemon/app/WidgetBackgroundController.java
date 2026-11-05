package cn.nodemon.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

/** User-visible controls for the persistent widget connection. */
final class WidgetBackgroundController {
    static final int NOTIFICATION_REQUEST=9280;
    static void ensure(Activity activity){
        MonitorSettings settings=new MonitorSettings(activity);
        if(!settings.liveWidgets()||MonitorWidget.count(activity)==0)return;
        if(Build.VERSION.SDK_INT>=33&&activity.checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=PackageManager.PERMISSION_GRANTED&&!settings.prefs.getBoolean("live_notification_requested",false)){
            settings.put("live_notification_requested",true);
            activity.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},NOTIFICATION_REQUEST);
        }
        WidgetLiveService.ensureRunning(activity);
    }
    static void setEnabled(Activity activity,boolean enabled){
        new MonitorSettings(activity).put("live_widgets",enabled);
        if(enabled){if(MonitorWidget.count(activity)==0)Toast.makeText(activity,"添加第一个挂件后会自动开始实时刷新",Toast.LENGTH_LONG).show();else ensure(activity);}
        else WidgetLiveService.stop(activity);
    }
    static String status(Activity activity){
        if(!new MonitorSettings(activity).liveWidgets())return "已暂停";
        if(MonitorWidget.count(activity)==0)return "添加挂件后启动";
        return WidgetLiveService.isRunning()?"运行中 · 15 秒":"等待启动";
    }
    static void systemSettings(Activity activity){
        android.widget.LinearLayout content=new android.widget.LinearLayout(activity);content.setOrientation(android.widget.LinearLayout.VERTICAL);int pad=Math.round(22*activity.getResources().getDisplayMetrics().density);content.setPadding(pad,pad/2,pad,pad/2);
        android.widget.TextView explanation=new android.widget.TextView(activity);explanation.setText("开启后，App 在后台也会每 15 秒读取服务器并更新挂件，通知栏会显示运行状态。\n\n小米若停止后台刷新：在应用信息中将省电策略设为“无限制”；如果有后台自启动选项，可允许本应用自启动。系统强行停止应用后，需要重新打开。");explanation.setTextSize(14);content.addView(explanation);
        String[] choices={"通知设置","省电设置","应用信息（小米省电 / 自启动）"};
        for(int i=0;i<choices.length;i++){final int which=i;android.widget.Button button=new android.widget.Button(activity);button.setText(choices[i]);content.addView(button,new android.widget.LinearLayout.LayoutParams(-1,-2));button.setOnClickListener(v->{
            Intent intent=which==0?new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,activity.getPackageName()):which==1?new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS):new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+activity.getPackageName()));
            try{activity.startActivity(intent);}catch(RuntimeException e){try{activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+activity.getPackageName())));}catch(RuntimeException ignored){Toast.makeText(activity,"请在手机设置中打开节点监控的应用信息",Toast.LENGTH_LONG).show();}}
        });}
        new AlertDialog.Builder(activity).setTitle("桌面实时刷新设置").setView(content).setNegativeButton("关闭",null).show();
    }
}
