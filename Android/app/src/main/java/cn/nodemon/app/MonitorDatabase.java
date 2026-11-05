package cn.nodemon.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

final class MonitorDatabase extends SQLiteOpenHelper {
    private static MonitorDatabase instance;
    static synchronized MonitorDatabase get(Context c) { if(instance==null)instance=new MonitorDatabase(c.getApplicationContext()); return instance; }
    private final Context context;
    private final Map<String,AlertTracker> trackers=new HashMap<>();
    private long lastPruned;
    private MonitorDatabase(Context c) { super(c,"monitor.db",null,1); context=c; setWriteAheadLoggingEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE samples(host TEXT, time INTEGER, cpu REAL, cpu2 REAL, memory REAL, rx REAL, tx REAL, gap INTEGER, PRIMARY KEY(host,time))");
        db.execSQL("CREATE TABLE attempts(host TEXT,time INTEGER,ok INTEGER)");
        db.execSQL("CREATE INDEX attempts_host_time ON attempts(host,time)");
        db.execSQL("CREATE INDEX attempts_time ON attempts(time)");
        db.execSQL("CREATE INDEX samples_time ON samples(time)");
        db.execSQL("CREATE TABLE cache(host TEXT PRIMARY KEY,snapshot TEXT,failures INTEGER DEFAULT 0,firstFailure INTEGER DEFAULT 0,latency INTEGER,error TEXT)");
        db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT,host TEXT,rule TEXT,title TEXT,detail TEXT,level INTEGER,started INTEGER,updated INTEGER,ended INTEGER DEFAULT 0,resolution TEXT DEFAULT '')");
        db.execSQL("CREATE INDEX events_host_time ON events(host,started)");
        db.execSQL("CREATE UNIQUE INDEX events_active ON events(host,rule) WHERE ended=0");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int from,int to) { }
    static long sampleTime(JSONObject s) { try{return Instant.parse(s.getString("sampledAt")).toEpochMilli();}catch(Exception e){return 0;} }
    synchronized JSONObject cached(String host) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT snapshot FROM cache WHERE host=?",new String[]{host})) { if(c.moveToFirst() && !c.isNull(0))return new JSONObject(c.getString(0)); }catch(Exception ignored){} return null;
    }
    synchronized int failures(String host) { return (int)scalar("SELECT failures FROM cache WHERE host=?",new String[]{host}); }
    synchronized long latency(String host) { return scalar("SELECT latency FROM cache WHERE host=?",new String[]{host}); }
    synchronized String error(String host) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT error FROM cache WHERE host=?",new String[]{host})){return c.moveToFirst()&&!c.isNull(0)?c.getString(0):"";}
    }
    private long scalar(String sql,String[] args) { try(Cursor c=getReadableDatabase().rawQuery(sql,args)){return c.moveToFirst()?c.getLong(0):0;} }
    private static void number(ContentValues v,String key,Double n){if(n==null)v.putNull(key);else v.put(key,n);}
    synchronized void record(JSONObject host,JSONObject s,String error,long latency,long now) {
        record(host,s,error,latency,now,new MonitorSettings(context).interval());
    }
    synchronized void record(JSONObject host,JSONObject s,String error,long latency,long now,int pollSeconds) {
        JSONArray configured=new HostStore(context).hosts();boolean present=false;
        for(int i=0;i<configured.length();i++)if(configured.optJSONObject(i)!=null&&configured.optJSONObject(i).toString().equals(host.toString())){present=true;break;}
        if(!present)return;
        String id=host.optString("id"); MonitorSettings settings=new MonitorSettings(context); SQLiteDatabase db=getWritableDatabase();
        List<Event> notifications=new ArrayList<>(); db.beginTransaction();
        try {
            ContentValues attempt=new ContentValues();attempt.put("host",id);attempt.put("time",now);attempt.put("ok",s==null?0:1);db.insert("attempts",null,attempt);
            db.execSQL("INSERT OR IGNORE INTO cache(host) VALUES(?)",new Object[]{id});
            if(s!=null) {
                ContentValues cache=new ContentValues();JSONObject previous=cached(id);if(previous==null||sampleTime(s)>=sampleTime(previous))cache.put("snapshot",s.toString());cache.put("failures",0);cache.put("firstFailure",0);cache.put("latency",latency);cache.put("error","");db.update("cache",cache,"host=?",new String[]{id});
                long offlineEvent=closeEvent(id,"offline",now,"recovered");
                if(offlineEvent>0)eventDetail(offlineEvent,"采集端已重新可读取，最近快照采样于 "+Metrics.sampleTime(s)+"。离线事件已恢复。");
                long t=sampleTime(s); ContentValues v=new ContentValues();v.put("host",id);v.put("time",t);
                JSONObject cpu=s.optJSONObject("cpu");JSONArray packages=cpu==null?null:cpu.optJSONArray("packages");
                number(v,"cpu",Metrics.number(cpu,"usagePercent"));number(v,"cpu2",packages!=null&&packages.length()>1?Metrics.number(packages.optJSONObject(1),"usagePercent"):null);
                number(v,"memory",Metrics.number(s.optJSONObject("memory"),"usagePercent"));
                number(v,"rx",Metrics.number(s.optJSONObject("network"),"receiveBytesPerSecond"));number(v,"tx",Metrics.number(s.optJSONObject("network"),"sendBytesPerSecond"));
                long gap=(long)(Math.max(Math.max(1,pollSeconds),s.optDouble("sampleIntervalSeconds",5))*3000);v.put("gap",Math.max(15000,gap));
                long inserted=db.insertWithOnConflict("samples",null,v,SQLiteDatabase.CONFLICT_IGNORE);
                if(inserted!=-1) {
                    Set<String> observed=new HashSet<>();observed.add("cpu");observed.add("memory");
                    evaluate(id,"cpu","CPU 使用率偏高",Metrics.number(cpu,"usagePercent"),t,Math.max(15000,gap),settings,notifications);
                    evaluate(id,"memory","内存使用率偏高",Metrics.number(s.optJSONObject("memory"),"usagePercent"),t,Math.max(15000,gap),settings,notifications);
                    JSONArray gpus=s.optJSONArray("gpus");
                    if(gpus!=null) for(int i=0;i<gpus.length();i++){JSONObject g=gpus.optJSONObject(i);if(g==null)continue;String key="gpu:"+g.optString("id",String.valueOf(i));observed.add(key);JSONObject fresh=Metrics.freshGpu(s,g);evaluate(id,key,"GPU "+(i+1)+" 温度偏高",Metrics.number(fresh,"temperatureCelsius"),fresh==null?t:sampleTime(g),(long)Math.max(15000,s.optDouble("gpuSampleIntervalSeconds",15)*3000),settings,notifications);}
                    JSONArray disks=s.optJSONArray("disks");
                    if(disks!=null)for(int i=0;i<disks.length();i++){JSONObject d=disks.optJSONObject(i);if(d==null)continue;String key="disk:"+d.optString("id",d.optString("mount"));observed.add(key);evaluate(id,key,"磁盘 "+d.optString("mount")+" 空间不足",Metrics.ratio(d,"usedBytes","totalBytes"),t,Math.max(15000,gap),settings,notifications);}
                    // A removed GPU/mount breaks observation continuity, while an existing
                    // alert remains unresolved until that device supplies a valid recovery.
                    String prefix=id+"/";
                    for(Map.Entry<String,AlertTracker> entry:trackers.entrySet())if(entry.getKey().startsWith(prefix)){
                        String identity=entry.getKey().substring(prefix.length());int cut=identity.lastIndexOf('/');
                        if(cut>0&&!observed.contains(identity.substring(0,cut)))entry.getValue().breakContinuity();
                    }
                }
            } else {
                int failures=failures(id)+1;long first=scalar("SELECT firstFailure FROM cache WHERE host=?",new String[]{id});if(first==0)first=now;
                ContentValues c=new ContentValues();c.put("failures",failures);c.put("firstFailure",first);c.put("error",error==null?"连接失败":error);db.update("cache",c,"host=?",new String[]{id});
                trackers.keySet().removeIf(k->k.startsWith(id+"/"));
                if(settings.enabled("offline") && failures>=settings.failures() && now-first>=settings.offlineSeconds()*1000L) {
                    Event changed=upsertEvent(id,"offline","采集端无法连接","连续 "+failures+" 次连接失败。请检查采集端、网络和授权；这不代表服务器已关机。",1,first,now);
                    if(changed!=null)notifications.add(changed);
                }
            }
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
        for(Event event:notifications)MonitorNotifications.send(context,host,event);
        prune(settings.retention(),now);
    }
    private void evaluate(String host,String key,String title,Double value,long time,long gap,MonitorSettings settings,List<Event> notices) {
        String rule=key.split(":")[0]; if(!settings.enabled(rule)){closeEvent(host,key,time,"disabled");trackers.keySet().removeIf(k->k.startsWith(host+"/"+key+"/"));return;}
        String prefix=host+"/"+key+"/",identity=prefix+settings.threshold(rule);
        // Changing a rule starts a fresh observation window, including changing it back.
        trackers.keySet().removeIf(k->k.startsWith(prefix)&&!k.equals(identity));AlertTracker tracker=trackers.get(identity);
        if(tracker==null){tracker=new AlertTracker();tracker.active=(int)scalar("SELECT level FROM events WHERE host=? AND rule=? AND ended=0",new String[]{host,key});trackers.put(identity,tracker);}
        if(time<=tracker.last)return;
        int before=tracker.active;int level=tracker.observe(value,time,gap,settings.threshold(rule),settings.critical(rule),60000);
        if(before>0 && level==0){
            long recovered=closeEvent(host,key,time,"recovered");String unit=rule.equals("gpu")?"°C":"%";
            if(recovered>0)eventDetail(recovered,String.format(java.util.Locale.ROOT,"已回落至 %.1f%s，警告阈值 > %d%s；读数回落至 %d%s 或以下，事件已恢复。",value,unit,settings.threshold(rule),unit,settings.threshold(rule)-5,unit));
        }
        else if(level>0 && value!=null) {
            String unit=rule.equals("gpu")?"°C":"%";
            Event event=upsertEvent(host,key,title,String.format(java.util.Locale.ROOT,"最近读数 %.1f%s，警告阈值 > %d%s；持续观察 1 分钟触发，回落 5%s 后恢复。",value,unit,settings.threshold(rule),unit,unit),level,tracker.since<0?time:tracker.since,time);
            if(event!=null)notices.add(event);
        }
    }
    private Event upsertEvent(String host,String rule,String title,String detail,int level,long start,long now) {
        SQLiteDatabase db=getWritableDatabase();long id=0;int before=0;
        try(Cursor c=db.rawQuery("SELECT id,level FROM events WHERE host=? AND rule=? AND ended=0",new String[]{host,rule})){if(c.moveToFirst()){id=c.getLong(0);before=c.getInt(1);}}
        ContentValues v=new ContentValues();v.put("detail",detail);v.put("updated",Math.max(now,id==0?now:scalar("SELECT updated FROM events WHERE id=?",new String[]{""+id})));v.put("level",Math.max(before,level));
        if(id==0){v.put("host",host);v.put("rule",rule);v.put("title",title);v.put("started",start);id=db.insertOrThrow("events",null,v);}else db.update("events",v,"id=?",new String[]{""+id});
        if(before>=level)return null;Event e=new Event();e.id=id;e.host=host;e.rule=rule;e.title=title;e.detail=detail;e.level=level;return e;
    }
    private void eventDetail(long id,String detail){ContentValues value=new ContentValues();value.put("detail",detail);getWritableDatabase().update("events",value,"id=?",new String[]{""+id});}
    private long closeEvent(String host,String rule,long now,String reason) {
        long id=scalar("SELECT id FROM events WHERE host=? AND rule=? AND ended=0",new String[]{host,rule});
        getWritableDatabase().execSQL("UPDATE events SET ended=MAX(?,started),updated=MAX(?,updated),resolution=? WHERE host=? AND rule=? AND ended=0",new Object[]{now,now,reason,host,rule});
        if(id>0)MonitorNotifications.cancel(context,id);
        return id;
    }
    synchronized void disableRule(String rule) {
        List<Long> ids=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT id FROM events WHERE ended=0 AND (rule=? OR rule LIKE ?)",new String[]{rule,rule+":%"})){while(c.moveToNext())ids.add(c.getLong(0));}
        long now=System.currentTimeMillis();getWritableDatabase().execSQL("UPDATE events SET ended=MAX(?,started),updated=MAX(?,updated),resolution='disabled' WHERE ended=0 AND (rule=? OR rule LIKE ?)",new Object[]{now,now,rule,rule+":%"});trackers.keySet().removeIf(identity->{int cut=identity.indexOf('/');String key=identity.substring(cut+1);return key.startsWith(rule+"/")||key.startsWith(rule+":");});
        for(long id:ids)MonitorNotifications.cancel(context,id);
    }
    synchronized void breakObservations(String host){String prefix=host+"/";for(Map.Entry<String,AlertTracker> entry:trackers.entrySet())if(entry.getKey().startsWith(prefix))entry.getValue().breakContinuity();}
    synchronized void forget(String host) {try(Cursor c=getReadableDatabase().rawQuery("SELECT id FROM events WHERE host=?",new String[]{host})){while(c.moveToNext())MonitorNotifications.cancel(context,c.getLong(0));}for(String table:new String[]{"samples","cache","attempts","events"})getWritableDatabase().delete(table,"host=?",new String[]{host});trackers.keySet().removeIf(k->k.startsWith(host+"/"));}
    synchronized void prune(int days,long now) {
        if(now-lastPruned<3600000)return;lastPruned=now;long cut=now-days*86400000L;
        getWritableDatabase().delete("samples","time<?",new String[]{""+cut});getWritableDatabase().delete("attempts","time<?",new String[]{""+cut});getWritableDatabase().delete("events","ended>0 AND ended<?",new String[]{""+cut});
    }
    synchronized void pruneNow(int days){lastPruned=0;prune(days,System.currentTimeMillis());}
    static final class Point {long time,gap;boolean breakBefore;Double cpu,cpu2,memory,rx,tx;}
    synchronized List<Point> history(String host,long range) {
        long end=System.currentTimeMillis(),start=end-range,width=Math.max(1000,range/180);List<Point> out=new ArrayList<>();
        // Detect gaps before averaging. SQL GROUP BY alone loses an outage when
        // it lands inside one wide bucket, particularly in a 24-hour chart.
        try(Cursor c=getReadableDatabase().rawQuery("SELECT time,cpu,cpu2,memory,rx,tx,gap FROM samples WHERE host=? AND time>=? AND time<=? ORDER BY time",new String[]{host,""+start,""+end})){
            HistoryBucket bucket=null;long previous=-1,previousGap=0;
            while(c.moveToNext()){
                long time=c.getLong(0),gap=c.getLong(6),index=time/width;
                boolean broken=previous>=0&&time-previous>Math.max(gap,previousGap);
                if(bucket==null||bucket.index!=index||broken){if(bucket!=null)out.add(bucket.point(width));bucket=new HistoryBucket(index,broken);}
                bucket.time=time;bucket.gap=Math.max(bucket.gap,gap);for(int i=0;i<5;i++){Double value=read(c,i+1);if(value!=null){bucket.sum[i]+=value;bucket.count[i]++;}}
                previous=time;previousGap=gap;
            }
            if(bucket!=null)out.add(bucket.point(width));
        }return out;
    }
    private static final class HistoryBucket {
        final long index;final boolean broken;long time,gap;final double[] sum=new double[5];final int[] count=new int[5];
        HistoryBucket(long index,boolean broken){this.index=index;this.broken=broken;}
        Double average(int i){return count[i]==0?null:sum[i]/count[i];}
        Point point(long width){Point p=new Point();p.time=time;p.gap=Math.max(width*2,gap);p.breakBefore=broken;p.cpu=average(0);p.cpu2=average(1);p.memory=average(2);p.rx=average(3);p.tx=average(4);return p;}
    }
    private static Double read(Cursor c,int column){return c.isNull(column)?null:c.getDouble(column);}
    static final class Summary {long attempts,success,alerts,recovered;Double peak;long peakTime;}
    synchronized Summary summary(String host){
        Summary s=new Summary();long start=System.currentTimeMillis()-86400000L;String filter=host.isEmpty()?"":" AND host=?";String[] args=host.isEmpty()?new String[]{""+start}:new String[]{""+start,host};
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*),SUM(ok) FROM attempts WHERE time>=?"+filter,args)){if(c.moveToFirst()){s.attempts=c.getLong(0);s.success=c.getLong(1);}}
        try(Cursor c=getReadableDatabase().rawQuery("SELECT cpu,time FROM samples WHERE time>=? AND cpu IS NOT NULL"+filter+" ORDER BY cpu DESC LIMIT 1",args)){if(c.moveToFirst()){s.peak=c.getDouble(0);s.peakTime=c.getLong(1);}}
        s.alerts=scalar("SELECT COUNT(*) FROM events WHERE started>=?"+filter,args);s.recovered=scalar("SELECT COUNT(*) FROM events WHERE ended>=? AND resolution='recovered'"+filter,args);return s;
    }
    static final class Event {long id,started,updated,ended;String host,rule,title,detail,resolution;int level;}
    synchronized List<Event> events(){List<Event> events=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT id,host,title,detail,level,started,updated,ended,resolution,rule FROM events WHERE ended=0 OR updated>=? ORDER BY (ended=0) DESC,updated DESC LIMIT 200",new String[]{""+(System.currentTimeMillis()-7*86400000L)})){while(c.moveToNext()){Event e=new Event();e.id=c.getLong(0);e.host=c.getString(1);e.title=c.getString(2);e.detail=c.getString(3);e.level=c.getInt(4);e.started=c.getLong(5);e.updated=c.getLong(6);e.ended=c.getLong(7);e.resolution=c.getString(8);e.rule=c.getString(9);events.add(e);}}return events;}
    synchronized int active(String host){return (int)scalar("SELECT COUNT(*) FROM events WHERE ended=0"+(host.isEmpty()?"":" AND host=?"),host.isEmpty()?null:new String[]{host});}
}
