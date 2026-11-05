package cn.nodemon.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import java.util.Calendar;

final class MonitorSettings {
    final SharedPreferences prefs;
    MonitorSettings(Context context) { prefs=context.getSharedPreferences("monitor-settings",Context.MODE_PRIVATE); }
    int interval() { return prefs.getInt("interval",5); }
    int failures() { return prefs.getInt("failures",3); }
    int retention() { return prefs.getInt("retention",30); }
    int offlineSeconds() { return prefs.getInt("offline",15); }
    int threshold(String rule) { return prefs.getInt(rule,rule.equals("cpu")?80:rule.equals("memory")?85:rule.equals("gpu")?75:90); }
    int critical(String rule) { return criticalThreshold(rule,threshold(rule)); }
    static int criticalThreshold(String rule,int warning) { return Math.min(rule.equals("gpu")?120:99,warning+(rule.equals("memory")||rule.equals("disk")?5:10)); }
    boolean enabled(String rule) { return prefs.getBoolean("enabled_"+rule,true); }
    boolean background() { return prefs.getBoolean("background",false); }
    boolean liveWidgets() { return prefs.getBoolean("live_widgets",true); }
    String theme() { return prefs.getString("theme","system"); }
    boolean dark(Context context) { return theme().equals("dark") || theme().equals("system") && (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES; }
    int notificationLevel() { return prefs.getInt("notification_level",0); }
    boolean quiet() { return prefs.getBoolean("quiet",false); }
    int quietStart() { return prefs.getInt("quiet_start",23*60); }
    int quietEnd() { return prefs.getInt("quiet_end",7*60); }
    boolean quietNow() { Calendar c=Calendar.getInstance(); return quiet() && inQuietHours(c.get(Calendar.HOUR_OF_DAY)*60+c.get(Calendar.MINUTE),quietStart(),quietEnd()); }
    static boolean inQuietHours(int now,int start,int end) { return start==end || (start<end?now>=start && now<end:now>=start || now<end); }
    void put(String key,int value) { prefs.edit().putInt(key,value).apply(); }
    void put(String key,boolean value) { prefs.edit().putBoolean(key,value).apply(); }
    void put(String key,String value) { prefs.edit().putString(key,value).apply(); }
}
