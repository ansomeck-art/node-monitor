package cn.nodemon.app;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationManager;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Real wall-clock foreground-service checks with a registered widget and loopback HTTP fixture. */
final class WidgetLiveChecks {
    interface Condition { boolean ready() throws Exception; }
    static void run(Instrumentation instrumentation,Activity activity,JSONObject host,File output)throws Exception{
        Context context=instrumentation.getTargetContext();MonitorSettings settings=new MonitorSettings(context);MonitorDatabase database=MonitorDatabase.get(context);String id=host.getString("id");
        AppWidgetManager manager=AppWidgetManager.getInstance(context);AppWidgetHost appHost=new AppWidgetHost(context,6826);AppWidgetHostView[] widgetView=new AppWidgetHostView[1];int widget=-1;JSONObject evidence=new JSONObject();
        settings.put("live_widgets",false);WidgetLiveService.stop(context);await(()->!WidgetLiveService.isRunning(),5000,"pre-existing live service did not stop");
        check(MonitorWidget.count(context)==0,"live test must run before the launcher pin fixture");appHost.deleteHost();
        try{
            widget=appHost.allocateAppWidgetId();Bundle options=new Bundle();options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,180);options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,180);
            check(manager.bindAppWidgetIdIfAllowed(widget,new ComponentName(context,CompactWidget.class),options),"live-test widget binding failed");new HostStore(context).bindWidget(widget,id);AppWidgetProviderInfo info=manager.getAppWidgetInfo(widget);int created=widget;
            instrumentation.runOnMainSync(()->{widgetView[0]=appHost.createView(context,created,info);appHost.startListening();});
            check(MainActivity.visible,"MainActivity was not actually foreground before service start");
            await(()->database.cached(id)!=null&&System.currentTimeMillis()-MonitorDatabase.sampleTime(database.cached(id))<10000,10000,"Main did not receive a fresh HTTP sample");
            long serviceBefore=WidgetLiveService.lastUpdate(),attemptsBefore=attempts(database,id);
            settings.put("live_widgets",true);final boolean[] accepted={false};instrumentation.runOnMainSync(()->accepted[0]=WidgetLiveService.ensureRunning(context));check(accepted[0],"foreground live-service request was rejected");
            await(WidgetLiveService::isRunning,5000,"live service did not start");await(()->foreground(context),5000,"service was not a real Android foreground service");
            StatusBarNotification notification=notification(context);check(notification!=null,"foreground notification missing");check((notification.getNotification().flags&Notification.FLAG_FOREGROUND_SERVICE)!=0,"notification does not belong to a foreground service");
            evidence.put("foregroundServiceVerified",true).put("notificationId",notification.getId()).put("notificationFlags",notification.getNotification().flags);
            // Let both normal Main polling and one service interval run while Main is visible.
            SystemClock.sleep(WidgetLiveService.intervalMillis()+1500);
            check(MainActivity.visible,"Main left foreground during coexistence check");check(attempts(database,id)>attemptsBefore,"Main foreground polling stopped unexpectedly");check(WidgetLiveService.lastUpdate()==serviceBefore,"live service duplicated Main's fresh foreground requests");
            evidence.put("mainForegroundNoDuplicateServiceFetch",true);

            // Hold the shared host lease across a real service interval after the Activity pauses.
            PollCoordinator.Lease held=null;long leaseDeadline=SystemClock.elapsedRealtime()+5000;
            while(held==null&&SystemClock.elapsedRealtime()<leaseDeadline){held=PollCoordinator.acquire(id);if(held==null)SystemClock.sleep(50);}
            check(held!=null,"could not acquire host lease for integration check");
            try{
                long blocked=attempts(database,id);instrumentation.runOnMainSync(()->activity.moveTaskToBack(true));await(()->!MainActivity.visible,5000,"Activity did not enter the real background");
                SystemClock.sleep(WidgetLiveService.intervalMillis()+1200);check(attempts(database,id)==blocked,"background worker ignored the shared host lease");
            }finally{held.close();}
            evidence.put("sharedLeaseBlocksBackgroundFetch",true);

            String renderedBefore=widgetDescription(instrumentation,widgetView[0]);long firstBefore=MonitorDatabase.sampleTime(database.cached(id));long countBefore=attempts(database,id);
            await(()->MonitorDatabase.sampleTime(database.cached(id))>firstBefore,WidgetLiveService.intervalMillis()+8000,"first background live interval did not update the cache");long first=MonitorDatabase.sampleTime(database.cached(id));
            await(()->MonitorDatabase.sampleTime(database.cached(id))>first,WidgetLiveService.intervalMillis()+8000,"second background live interval did not update the cache");long second=MonitorDatabase.sampleTime(database.cached(id));
            check(!MainActivity.visible,"Activity unexpectedly resumed during background polling");check(attempts(database,id)>=countBefore+2,"two background HTTP observations were not recorded");check(WidgetLiveService.lastUpdate()>=second,"service did not report its persisted update");
            long elapsed=second-first;check(elapsed>=10000&&elapsed<=23000,"live samples were not updated on the expected 15s cadence: "+elapsed);
            evidence.put("firstBackgroundSample",first).put("secondBackgroundSample",second).put("intervalMillis",elapsed).put("attemptsAdded",attempts(database,id)-countBefore);
            await(()->widgetDescription(instrumentation,widgetView[0]).equals(DeckWidgetRenderer.description(0,MonitorWidget.state(context,0,created))),5000,"registered widget view did not receive the latest cached values");
            check(!renderedBefore.isEmpty()&&!renderedBefore.equals(widgetDescription(instrumentation,widgetView[0])),"widget values stayed static despite changing HTTP fixture metrics");
            evidence.put("actualWidgetRemoteViewsUpdated",true);

            // The actual notification action must disable the preference as well as stop the service.
            notification=notification(context);Notification.Action stop=null;if(notification!=null&&notification.getNotification().actions!=null)for(Notification.Action action:notification.getNotification().actions)if(action.title!=null&&(action.title.toString().contains("停止")||action.title.toString().contains("暂停"))){stop=action;break;}
            check(stop!=null,"foreground notification has no stop action");stop.actionIntent.send();await(()->!settings.liveWidgets()&&!WidgetLiveService.isRunning(),5000,"notification stop action did not disable and stop live refresh");await(()->notification(context)==null,5000,"stop action left a foreground notification behind");evidence.put("notificationStopAction",true);

            resume(instrumentation,activity);settings.put("live_widgets",true);instrumentation.runOnMainSync(()->WidgetLiveService.ensureRunning(context));await(WidgetLiveService::isRunning,5000,"live restart failed");
            settings.put("live_widgets",false);WidgetLiveService.stop(context);await(()->!WidgetLiveService.isRunning()&&notification(context)==null,5000,"disabling live refresh did not stop the service");evidence.put("switchOffStopsService",true);

            settings.put("live_widgets",true);instrumentation.runOnMainSync(()->WidgetLiveService.ensureRunning(context));await(WidgetLiveService::isRunning,5000,"live service did not restart for removal check");
            appHost.deleteHost();new HostStore(context).forgetWidget(widget);widget=-1;await(()->MonitorWidget.count(context)==0,5000,"widget removal did not reach the system");await(()->!WidgetLiveService.isRunning(),5000,"service kept running after the last widget was removed");await(()->notification(context)==null,5000,"widget removal left a foreground notification behind");evidence.put("lastWidgetRemovalStopsService",true);
            evidence.put("result","PASS").put("androidApi",android.os.Build.VERSION.SDK_INT).put("fixture","loopback-only simulated Agent; real elapsed time");write(output,evidence);
        }finally{
            settings.put("live_widgets",false);WidgetLiveService.stop(context);appHost.stopListening();appHost.deleteHost();if(widget>0)new HostStore(context).forgetWidget(widget);resume(instrumentation,activity);
        }
    }
    private static void resume(Instrumentation i,Activity activity)throws Exception{i.runOnMainSync(()->activity.startActivity(new Intent(activity,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP)));await(()->MainActivity.visible,5000,"MainActivity failed to resume");}
    private static String widgetDescription(Instrumentation i,AppWidgetHostView view){String[] text={""};i.runOnMainSync(()->{View image=view.findViewById(R.id.deck_content);if(image!=null&&image.getContentDescription()!=null)text[0]=image.getContentDescription().toString();});return text[0];}
    private static long attempts(MonitorDatabase db,String host){try(Cursor c=db.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM attempts WHERE host=?",new String[]{host})){c.moveToFirst();return c.getLong(0);}}
    private static boolean foreground(Context context){for(ActivityManager.RunningServiceInfo s:context.getSystemService(ActivityManager.class).getRunningServices(100))if(s.service.getClassName().equals(WidgetLiveService.class.getName()))return s.foreground;return false;}
    private static StatusBarNotification notification(Context c){for(StatusBarNotification n:c.getSystemService(NotificationManager.class).getActiveNotifications())if(n.getId()==WidgetLiveService.NOTIFICATION_ID)return n;return null;}
    private static void await(Condition condition,long timeout,String message)throws Exception{long end=SystemClock.elapsedRealtime()+timeout;while(SystemClock.elapsedRealtime()<end){if(condition.ready())return;SystemClock.sleep(100);}check(condition.ready(),message+"; service error="+WidgetLiveService.lastError());}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void write(File output,JSONObject evidence)throws Exception{try(FileOutputStream stream=new FileOutputStream(new File(output,"widget-live-results.json"))){stream.write(evidence.toString(2).getBytes(StandardCharsets.UTF_8));}}
}
