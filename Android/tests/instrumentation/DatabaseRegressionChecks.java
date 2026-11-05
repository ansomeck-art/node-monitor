package cn.nodemon.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Device-side regressions using an isolated temporary host; no network access. */
final class DatabaseRegressionChecks {
    private static final String ID="qa-core-regression";
    private static final String[] KEYS={"interval","failures","offline","cpu","memory","gpu","disk","notification_level","enabled_cpu","enabled_memory","enabled_gpu","enabled_disk","enabled_offline"};

    static void run(Context context) throws Exception {
        HostStore hosts=new HostStore(context);
        JSONArray original=hosts.hosts();
        MonitorSettings settings=new MonitorSettings(context);
        Map<String,?> preferences=settings.prefs.getAll();
        MonitorDatabase database=MonitorDatabase.get(context);
        JSONObject host=new JSONObject().put("id",ID).put("name","核心回归测试专用")
                .put("mode","direct").put("address","http://127.0.0.1:1")
                .put("allowLan",false).put("token",hosts.protect("qa-only"));
        JSONArray configured=new JSONArray(original.toString());configured.put(host);
        try {
            hosts.save(configured);
            settings.put("interval",5);settings.put("failures",3);settings.put("offline",15);
            settings.put("cpu",80);settings.put("memory",85);settings.put("gpu",75);settings.put("disk",90);
            settings.put("notification_level",0);
            for(String rule:new String[]{"cpu","memory","gpu","disk","offline"})settings.put("enabled_"+rule,true);
            long base=System.currentTimeMillis()-3600000;

            database.forget(ID);
            for(int i=0;i<=12;i++)record(database,host,sample(base+i*5000,82,null),base+i*5000);
            check(database.active(ID)==1,"CPU continuous warning was not recorded");
            long eventId=scalar(database,"SELECT id FROM events WHERE host=? AND ended=0");
            JSONObject repeated=sample(base+60000,82,null);
            for(int i=0;i<20;i++)record(database,host,repeated,base+65000+i*1000);
            check(scalar(database,"SELECT COUNT(*) FROM samples WHERE host=?")==13,"duplicate samples were inserted");
            check(eventId==scalar(database,"SELECT id FROM events WHERE host=? AND ended=0"),"duplicate observations created a second event");
            record(database,host,sample(base+65000,75,null),base+90000);
            check(database.active(ID)==0,"valid CPU recovery did not close the event");
            check(detail(database).contains("75.0%"),"recovered event retained only its old high reading");

            database.forget(ID);
            for(int i=0;i<=3;i++)record(database,host,sample(base+i*15000,10,80d),base+i*15000);
            record(database,host,sample(base+50000,10,null),base+50000);
            record(database,host,sample(base+60000,10,80d),base+60000);
            check(database.active(ID)==0,"GPU disappearance incorrectly accumulated duration");
            for(int i=5;i<=8;i++)record(database,host,sample(base+i*15000,10,80d),base+i*15000);
            check(database.active(ID)==1,"GPU continuous observations did not trigger after restarting duration");
            long updated=scalar(database,"SELECT updated FROM events WHERE host=? AND ended=0");
            JSONObject delayed=sample(base+130000,10,80d);
            delayed.getJSONArray("gpus").getJSONObject(0).put("sampledAt",stamp(base+120000));
            record(database,host,delayed,base+130000);
            JSONObject olderGpu=sample(base+135000,10,80d);
            olderGpu.getJSONArray("gpus").getJSONObject(0).put("sampledAt",stamp(base+110000));
            record(database,host,olderGpu,base+135000);
            check(scalar(database,"SELECT updated FROM events WHERE host=? AND ended=0")==updated,"duplicate/older GPU timestamp changed the event time");
            record(database,host,delayed,base+140000);
            check(MonitorDatabase.sampleTime(database.cached(ID))==base+135000,"older response overwrote a newer cached snapshot");

            database.forget(ID);
            for(int i=0;i<=11;i++)record(database,host,sample(base+i*5000,82,null),base+i*5000);
            settings.put("cpu",85);
            record(database,host,sample(base+60000,82,null),base+60000);
            settings.put("cpu",80);
            record(database,host,sample(base+65000,82,null),base+65000);
            check(database.active(ID)==0,"restoring an older threshold reused an old candidate");

            database.forget(ID);
            for(int i=0;i<3;i++)database.record(host,null,"QA connection failure",0,base+i*5000);
            check(database.active(ID)==0,"offline event ignored its elapsed-time threshold");
            database.record(host,null,"QA connection failure",0,base+15000);
            check(database.active(ID)==1,"offline event did not meet combined count and time thresholds");
            record(database,host,sample(base+20000,10,null),base+20000);
            check(database.active(ID)==0,"connection recovery did not close offline event");
            check(detail(database).contains("重新可读取"),"offline recovery retained only the connection-failure message");

            database.forget(ID);
            long width=86400000L/180;
            long aligned=(System.currentTimeMillis()-3600000)/width*width+1000;
            record(database,host,sample(aligned,10,null),aligned);
            record(database,host,sample(aligned+5000,11,null),aligned+5000);
            record(database,host,sample(aligned+65000,12,null),aligned+65000);
            List<MonitorDatabase.Point> points=database.history(ID,86400000);
            check(points.size()==2&&points.get(1).breakBefore,"a gap inside one chart bucket was smoothed away");

            database.forget(ID);
            hosts.save(original);
            record(database,host,sample(base,10,null),base);
            check(database.cached(ID)==null,"a removed host was recreated by an in-flight response");
        } finally {
            database.forget(ID);
            hosts.save(original);
            SharedPreferences.Editor edit=settings.prefs.edit();
            for(String key:KEYS){Object value=preferences.get(key);if(value==null)edit.remove(key);else if(value instanceof Integer)edit.putInt(key,(Integer)value);else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);}
            edit.commit();
        }
    }

    private static void record(MonitorDatabase database,JSONObject host,JSONObject sample,long now){database.record(host,sample,null,12,now);}
    private static long scalar(MonitorDatabase database,String sql){try(Cursor cursor=database.getReadableDatabase().rawQuery(sql,new String[]{ID})){return cursor.moveToFirst()?cursor.getLong(0):0;}}
    private static String detail(MonitorDatabase database){try(Cursor cursor=database.getReadableDatabase().rawQuery("SELECT detail FROM events WHERE host=? ORDER BY id DESC LIMIT 1",new String[]{ID})){return cursor.moveToFirst()?cursor.getString(0):"";}}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static String stamp(long time){return Instant.ofEpochMilli(time).toString();}
    private static JSONObject sample(long time,double cpu,Double gpu) throws Exception {
        JSONArray gpus=new JSONArray();
        if(gpu!=null)gpus.put(new JSONObject().put("id","qa-gpu").put("name","QA GPU").put("sampledAt",stamp(time)).put("temperatureCelsius",gpu).put("usagePercent",0));
        return new JSONObject().put("schemaVersion",1).put("agentVersion","qa")
                .put("sampledAt",stamp(time)).put("sampleIntervalSeconds",5).put("gpuSampleIntervalSeconds",15)
                .put("host",new JSONObject().put("id",ID).put("name","QA"))
                .put("cpu",new JSONObject().put("usagePercent",cpu))
                .put("memory",new JSONObject().put("usagePercent",15))
                .put("gpus",gpus).put("disks",new JSONArray()).put("network",new JSONObject());
    }
}
