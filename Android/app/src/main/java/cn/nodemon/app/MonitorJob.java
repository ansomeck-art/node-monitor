package cn.nodemon.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Opportunistic background monitoring; Android chooses the actual execution time. */
public final class MonitorJob extends JobService {
    private static final int ID=9275;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private AtomicBoolean stopped=new AtomicBoolean();
    static void schedule(Context c) {
        JobScheduler scheduler=c.getSystemService(JobScheduler.class);
        if(!new MonitorSettings(c).background() || new HostStore(c).hosts().length()==0){scheduler.cancel(ID);return;}
        if(scheduler.getPendingJob(ID)!=null)return;
        scheduler.schedule(new JobInfo.Builder(ID,new ComponentName(c,MonitorJob.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15*60*1000L).setPersisted(true).build());
    }
    @Override public boolean onStartJob(JobParameters params) {
        if(MainActivity.visible || !new MonitorSettings(this).background())return false;
        AtomicBoolean cancelled=new AtomicBoolean();stopped=cancelled;
        worker.execute(()->{
            JSONArray hosts=new HostStore(this).hosts();MonitorDatabase db=MonitorDatabase.get(this);
            for(int i=0;i<hosts.length() && !cancelled.get();i++) {
                if(MainActivity.visible)break;
                JSONObject host=hosts.optJSONObject(i);if(host==null)continue;
                PollCoordinator.Lease lease=PollCoordinator.acquire(host.optString("id"));if(lease==null)continue;
                try{
                JSONObject sample=null;String failure=null;long start=android.os.SystemClock.elapsedRealtime();
                try{sample=MonitorClient.fetch(this,host);}catch(Exception | LinkageError e){failure="后台连接失败，请打开应用检查网络和授权";}
                if(cancelled.get())break;
                JSONObject current=null;JSONArray latest=new HostStore(this).hosts();for(int j=0;j<latest.length();j++)if(latest.optJSONObject(j)!=null && latest.optJSONObject(j).optString("id").equals(host.optString("id")))current=latest.optJSONObject(j);
                if(current==null || !current.toString().equals(host.toString()) || !new MonitorSettings(this).background())continue;
                db.record(host,sample,failure,android.os.SystemClock.elapsedRealtime()-start,System.currentTimeMillis());
                }finally{lease.close();}
            }
            if(!cancelled.get())MonitorWidget.updateAll(this);
            new Handler(Looper.getMainLooper()).post(()->{if(!cancelled.get())jobFinished(params,false);});
        });return true;
    }
    @Override public boolean onStopJob(JobParameters p){stopped.set(true);return false;}
    @Override public void onDestroy(){stopped.set(true);worker.shutdownNow();super.onDestroy();}
}
