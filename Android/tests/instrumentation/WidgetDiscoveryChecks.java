package cn.nodemon.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.os.Process;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Exercises launcher discovery and standard user confirmation, without force-binding a widget. */
final class WidgetDiscoveryChecks {
    static void run(Instrumentation instrumentation,Activity activity,JSONObject host,File output)throws Exception{
        Context context=instrumentation.getTargetContext();
        AppWidgetManager manager=AppWidgetManager.getInstance(context);
        PackageManager packages=context.getPackageManager();
        Set<String> found=new HashSet<>();
        List<AppWidgetProviderInfo> installed=manager.getInstalledProvidersForProfile(Process.myUserHandle());
        for(AppWidgetProviderInfo info:installed){
            if(!context.getPackageName().equals(info.provider.getPackageName()))continue;
            found.add(info.provider.getClassName());
            check((info.widgetCategory&AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)!=0,"provider missing home-screen category");
            check(info.previewImage!=0,"provider lacks a preview image");
            if(!info.provider.getClassName().equals(SummaryWidget.class.getName())){
                check(info.configure!=null,"device provider lacks configuration activity: "+info.provider);
                ActivityInfo configure=packages.getActivityInfo(info.configure,0);
                check(configure.enabled&&configure.exported,"renamed APK configuration activity cannot be opened");
            }
        }
        check(found.size()==MonitorWidget.PROVIDERS.length,"six providers not discoverable in current profile: "+found);
        for(Class<?> provider:MonitorWidget.PROVIDERS)check(found.contains(provider.getName()),"missing provider "+provider.getName());
        ResolveInfo launcher=packages.resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),PackageManager.MATCH_DEFAULT_ONLY);
        check(launcher!=null&&launcher.activityInfo!=null,"default launcher unavailable");
        String launcherPackage=launcher.activityInfo.packageName;
        check(manager.isRequestPinAppWidgetSupported(),"test launcher does not support standard widget pin requests");
        List<ResolveInfo> confirmation=packages.queryIntentActivities(new Intent(LauncherApps.ACTION_CONFIRM_PIN_APPWIDGET).setPackage(launcherPackage),0);
        check(!confirmation.isEmpty(),"launcher confirmation activity not discoverable");

        final String[] request={null};final Throwable[] failure={null};
        instrumentation.runOnMainSync(()->{try{request[0]=WidgetPinning.request(activity,0,host);}catch(Throwable e){failure[0]=e;}});
        if(failure[0]!=null)throw new AssertionError("standard pin request failed",failure[0]);
        check(request[0]!=null&&!request[0].isEmpty(),"pin request has no tracking token");
        check(!"added".equals(WidgetPinning.result(context,request[0]).optString("state")),"request return was incorrectly treated as success");

        UiAutomation automation=instrumentation.getUiAutomation();
        AccessibilityNodeInfo add=null;long deadline=SystemClock.elapsedRealtime()+12000;
        while(SystemClock.elapsedRealtime()<deadline){
            AccessibilityNodeInfo root=automation.getRootInActiveWindow();
            if(root!=null&&root.getPackageName()!=null&&launcherPackage.contentEquals(root.getPackageName()))add=findAddButton(root);
            if(add!=null)break;SystemClock.sleep(150);
        }
        check(add!=null,"standard launcher confirmation button did not appear");
        screenshot(automation,new File(output,"widget-pin-system-confirmation.png"));
        // A request accepted by the API is still unconfirmed until the user/launcher responds.
        long waitUntil=WidgetPinning.result(context,request[0]).optLong("requestedAt")+WidgetPinning.RESPONSE_WAIT_MILLIS+100;
        while(System.currentTimeMillis()<waitUntil)SystemClock.sleep(100);
        check("unconfirmed".equals(WidgetPinning.result(context,request[0]).optString("state")),"missing callback was incorrectly reported as added");
        check(add.performAction(AccessibilityNodeInfo.ACTION_CLICK),"launcher confirmation click failed");

        JSONObject result=null;deadline=SystemClock.elapsedRealtime()+12000;
        while(SystemClock.elapsedRealtime()<deadline){result=WidgetPinning.result(context,request[0]);if("added".equals(result.optString("state")))break;SystemClock.sleep(150);}
        check(result!=null&&"added".equals(result.optString("state")),"no verified successful callback: "+result);
        int id=result.optInt("widgetId",AppWidgetManager.INVALID_APPWIDGET_ID);
        AppWidgetProviderInfo added=manager.getAppWidgetInfo(id);
        check(id>0&&added!=null&&new ComponentName(context,CompactWidget.class).equals(added.provider),"callback ID does not refer to expected provider");
        String bound=context.getSharedPreferences("hosts",Context.MODE_PRIVATE).getString("widget_host_"+id,"");
        check(host.optString("id").equals(bound),"successful pin did not persist the selected host binding");

        try(android.os.ParcelFileDescriptor ignored=automation.executeShellCommand("input keyevent KEYCODE_HOME")){}
        SystemClock.sleep(1200);
        screenshot(automation,new File(output,"widget-pin-home-added.png"));
        // Leave this real launcher-owned test widget in place for the screenshot; uninstalling
        // the isolated test app removes it. Never clear launcher data or other users' widgets.
        instrumentation.runOnMainSync(()->activity.startActivity(new Intent(activity,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP)));
        SystemClock.sleep(400);
    }

    private static AccessibilityNodeInfo findAddButton(AccessibilityNodeInfo node){
        CharSequence text=node.getText();String value=text==null?"":text.toString().trim().toUpperCase(Locale.ROOT);
        if(node.isEnabled()&&node.isClickable()&&(value.equals("ADD")||value.equals("ADD AUTOMATICALLY")||value.equals("ADD WIDGET")||value.equals("添加")||value.equals("自动添加")||value.equals("添加到主屏幕")||value.equals("添加到桌面")))return node;
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);if(child==null)continue;AccessibilityNodeInfo result=findAddButton(child);if(result!=null)return result;}
        return null;
    }
    private static void screenshot(UiAutomation automation,File file)throws Exception{
        Bitmap bitmap=automation.takeScreenshot();
        if(bitmap==null){
            // Some old emulator graphics backends cannot serve UiAutomation screenshots.
            try(android.os.ParcelFileDescriptor pipe=automation.executeShellCommand("screencap -p "+file.getAbsolutePath());java.io.InputStream input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(pipe)){byte[] buffer=new byte[1024];while(input.read(buffer)!=-1){}}
            check(file.exists()&&file.length()>0,"system screenshot unavailable");return;
        }
        try(FileOutputStream stream=new FileOutputStream(file)){bitmap.compress(Bitmap.CompressFormat.PNG,100,stream);}finally{bitmap.recycle();}
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
