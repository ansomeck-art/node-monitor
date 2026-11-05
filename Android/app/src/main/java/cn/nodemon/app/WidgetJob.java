package cn.nodemon.app;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WidgetJob extends JobService {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();private AtomicBoolean stopped=new AtomicBoolean();
    @Override public boolean onStartJob(JobParameters params){
        if(MonitorWidget.count(this)==0)return false;
        AtomicBoolean cancelled=new AtomicBoolean();stopped=cancelled;
        worker.execute(()->{
            HostStore store=new HostStore(this);JSONArray hosts=store.hosts();MonitorDatabase db=MonitorDatabase.get(this);
            for(int i=0;i<hosts.length()&&!cancelled.get();i++){
                JSONObject host=hosts.optJSONObject(i);if(host==null)continue;String id=host.optString("id");JSONObject cached=db.cached(id);
                if(MainActivity.visible&&cached!=null&&System.currentTimeMillis()-MonitorDatabase.sampleTime(cached)<15000)continue;
                PollCoordinator.Lease lease=PollCoordinator.acquire(id);if(lease==null)continue;
                try{
                JSONObject sample=null;String error=null;long start=SystemClock.elapsedRealtime();try{sample=MonitorClient.fetch(this,host);}catch(Exception | LinkageError e){error="挂件连接失败，请打开应用检查网络与授权";}
                if(cancelled.get())break;db.record(host,sample,error,SystemClock.elapsedRealtime()-start,System.currentTimeMillis());
                }finally{lease.close();}
            }
            if(!cancelled.get())MonitorWidget.updateAll(this);new Handler(Looper.getMainLooper()).post(()->{if(!cancelled.get())jobFinished(params,false);});
        });return true;
    }
    @Override public boolean onStopJob(JobParameters p){stopped.set(true);return false;}
    @Override public void onDestroy(){stopped.set(true);worker.shutdownNow();super.onDestroy();}
}
