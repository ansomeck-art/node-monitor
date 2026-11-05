package cn.nodemon.app;

/** Continuous observations only: repeats, missing values and long gaps never add duration. */
final class AlertTracker {
    long since=-1,last=-1;
    int active;
    void breakContinuity() { since=-1; }
    // Returns the new level: 0 recovered/normal, 1 warning, 2 critical.
    int observe(Double value,long time,long maxGap,int warning,int critical,long duration) {
        if(time<=last) return active;
        if(value==null || !Double.isFinite(value)) { since=-1; last=time; return active; }
        if(last>=0 && time-last>maxGap) since=-1;
        last=time;
        if(value>warning) {
            if(since<0) since=time;
            if(time-since>=duration) active=Math.max(active,value>critical?2:1);
        } else {
            since=-1;
            if(value<=warning-5) active=0;
        }
        return active;
    }
}
