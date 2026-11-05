package cn.nodemon.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;

public final class WidgetConfigureActivity extends Activity {
    @Override public void onCreate(Bundle state){
        super.onCreate(state);setResult(RESULT_CANCELED);int widget=getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID);
        if(widget==AppWidgetManager.INVALID_APPWIDGET_ID){finish();return;}
        android.appwidget.AppWidgetProviderInfo info=AppWidgetManager.getInstance(this).getAppWidgetInfo(widget);
        if(info==null||!getPackageName().equals(info.provider.getPackageName())){finish();return;}
        HostStore store=new HostStore(this);JSONArray hosts=store.hosts();
        if(hosts.length()==0){new AlertDialog.Builder(this).setTitle("先添加一台主机").setMessage("打开节点监控添加服务器，再回到桌面添加挂件。").setPositiveButton("打开应用",(d,w)->{startActivity(new Intent(this,MainActivity.class));finish();}).setOnCancelListener(d->finish()).show();return;}
        String[] names=new String[hosts.length()];for(int i=0;i<hosts.length();i++){JSONObject h=hosts.optJSONObject(i);names[i]=h==null?"主机":h.optString("name");}
        new AlertDialog.Builder(this).setTitle("选择挂件显示的主机").setItems(names,(d,w)->{store.bindWidget(widget,hosts.optJSONObject(w).optString("id"));MonitorWidget.updateAll(this);MonitorWidget.schedule(this);setResult(RESULT_OK,new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,widget));finish();}).setOnCancelListener(d->finish()).show();
    }
}
