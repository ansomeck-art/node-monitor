package cn.nodemon.app;

public final class AlertTrackerTest {
    static void equal(int expected,int actual,String message){if(expected!=actual)throw new AssertionError(message+": "+actual);}
    public static void main(String[] args){
        AlertTracker t=new AlertTracker();
        equal(0,t.observe(82d,0,15000,80,90,60000),"first high sample");
        for(int n=1;n<=11;n++)equal(0,t.observe(82d,n*5000,15000,80,90,60000),"duration not reached");
        equal(1,t.observe(82d,60000,15000,80,90,60000),"continuous warning");
        equal(2,t.observe(95d,65000,15000,80,90,60000),"escalation");
        equal(2,t.observe(null,70000,15000,80,90,60000),"missing is not recovery");
        equal(2,t.observe(78d,75000,15000,80,90,60000),"hysteresis");
        equal(0,t.observe(75d,80000,15000,80,90,60000),"observed recovery");
        t=new AlertTracker();t.observe(99d,10000,15000,80,90,60000);
        for(int n=0;n<100;n++)equal(0,t.observe(99d,10000,15000,80,90,60000),"duplicates cannot trigger");
        equal(0,t.observe(99d,70000,15000,80,90,60000),"gap resets duration");
        t.observe(null,75000,15000,80,90,60000);
        equal(0,t.observe(99d,80000,15000,80,90,60000),"missing resets candidate");
        t.observe(79d,85000,15000,80,90,60000);
        equal(0,t.observe(99d,90000,15000,80,90,60000),"below threshold resets candidate");
        t=new AlertTracker();for(int n=0;n<=11;n++)t.observe(99d,n*5000,45000,80,90,60000);
        t.breakContinuity();
        equal(0,t.observe(99d,60000,45000,80,90,60000),"disappeared device breaks candidate");
        t.active=1;t.breakContinuity();
        equal(1,t.observe(null,65000,45000,80,90,60000),"disappeared device does not recover an active event");
        if(!MonitorSettings.inQuietHours(30,23*60,7*60)||MonitorSettings.inQuietHours(12*60,23*60,7*60)||!MonitorSettings.inQuietHours(12*60,11*60,13*60))throw new AssertionError("quiet hours");
        if(MonitorSettings.criticalThreshold("cpu",94)!=99)throw new AssertionError("critical CPU threshold must be reachable at 100%");
        if(MonitorSettings.criticalThreshold("memory",85)!=90||MonitorSettings.criticalThreshold("gpu",75)!=85)throw new AssertionError("critical threshold defaults");
        System.out.println("Alert continuity, deduplication, missing data, escalation, recovery and quiet hours passed.");
    }
}
