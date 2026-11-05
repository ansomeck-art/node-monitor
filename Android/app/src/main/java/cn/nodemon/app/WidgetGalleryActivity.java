package cn.nodemon.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** A visual, in-app entry point to the launcher's standard widget pin flow. */
public final class WidgetGalleryActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private HostStore store;
    private MonitorDatabase database;
    private MonitorSettings settings;
    private JSONArray hosts;
    private String selected="",pending="",renderedStatus="";
    private int requestedType=DeckWidgetRenderer.WIDE;
    private boolean foreground,dark;
    private int bg,card,ink,muted,line,accent,pale;
    private LinearLayout body,statusPanel;
    private ScrollView scroller;
    private TextView statusTitle,statusMessage,liveStatus,liveToggle;
    private Context previewContext;
    private final Runnable poll=new Runnable(){@Override public void run(){if(!foreground||pending.isEmpty())return;if(updateStatus())handler.postDelayed(this,800);}};
    private static final String[] DESCRIPTIONS={
        "CPU 与 GPU 四格速览，随手查看当前负载。",
        "汇总全部主机的在线、告警和采集情况。",
        "处理器、显卡、趋势与内存，一张卡看全。",
        "完整硬件状态，带刷新与通知静音按钮。",
        "轻巧横向配件，保留关键数值和状态。",
        "用进度圆环显示当前 CPU 使用率。"
    };

    @Override public void onCreate(Bundle saved){
        settings=new MonitorSettings(this);dark=settings.dark(this);setTheme(dark?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(saved);store=new HostStore(this);database=MonitorDatabase.get(this);hosts=store.hosts();
        selected=saved==null?getIntent().getStringExtra(MonitorWidget.EXTRA_HOST_ID):saved.getString("host","");if(selected==null)selected="";
        pending=saved==null?"":saved.getString("pending","");requestedType=saved==null?DeckWidgetRenderer.WIDE:saved.getInt("requestedType",DeckWidgetRenderer.WIDE);ensureSelected();colors();render();
    }
    @Override protected void onSaveInstanceState(Bundle state){state.putString("host",selected);state.putString("pending",pending);state.putInt("requestedType",requestedType);super.onSaveInstanceState(state);}
    @Override protected void onResume(){super.onResume();foreground=true;WidgetBackgroundController.ensure(this);hosts=store.hosts();ensureSelected();handler.removeCallbacks(poll);if(!pending.isEmpty())handler.post(poll);handler.postDelayed(this::refreshLiveControls,700);}
    @Override protected void onPause(){foreground=false;handler.removeCallbacks(poll);super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);super.onDestroy();}
    private void colors(){
        bg=Color.parseColor(dark?"#101311":"#FAFAF8");card=Color.parseColor(dark?"#191D1A":"#FFFFFF");ink=Color.parseColor(dark?"#E4E9E5":"#212925");muted=Color.parseColor(dark?"#929D95":"#7E898E");line=Color.parseColor(dark?"#30382F":"#E5E9E2");accent=Color.parseColor(dark?"#80C9AA":"#3B8D74");pale=Color.parseColor(dark?"#22392F":"#E8F0EB");
        getWindow().setStatusBarColor(bg);getWindow().setNavigationBarColor(bg);getWindow().getDecorView().setSystemUiVisibility(dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        Configuration config=new Configuration(getResources().getConfiguration());config.uiMode=(config.uiMode&~Configuration.UI_MODE_NIGHT_MASK)|(dark?Configuration.UI_MODE_NIGHT_YES:Configuration.UI_MODE_NIGHT_NO);previewContext=createConfigurationContext(config);
    }
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private TextView text(String value,float size,int color,boolean bold){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setIncludeFontPadding(false);v.setLineSpacing(dp(3),1);if(bold)v.setTypeface(Typeface.create("sans-serif-medium",0));return v;}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private void space(LinearLayout parent,int size){parent.addView(new View(this),new LinearLayout.LayoutParams(1,dp(size)));}
    private void weight(LinearLayout parent,View child){parent.addView(child,new LinearLayout.LayoutParams(0,-2,1));}
    private void tap(View view,Runnable action){view.setClickable(true);view.setFocusable(true);view.setForeground(new RippleDrawable(android.content.res.ColorStateList.valueOf(dark?0x2280c9aa:0x183b8d74),null,shape(Color.WHITE,16)));view.setOnClickListener(v->action.run());}
    private TextView button(String name,boolean primary,Runnable action){TextView v=text(name,13,primary?(dark?0xff153b2b:Color.WHITE):accent,true);v.setGravity(Gravity.CENTER);v.setMinHeight(dp(44));v.setPadding(dp(12),dp(11),dp(12),dp(11));v.setBackground(shape(primary?accent:pale,22));tap(v,action);return v;}
    private LinearLayout surface(){LinearLayout v=column();v.setPadding(dp(16),dp(16),dp(16),dp(16));GradientDrawable d=shape(card,22);d.setStroke(dp(.8f),line);v.setBackground(d);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(16);body.addView(v,p);return v;}
    private JSONObject chosen(){for(int i=0;i<hosts.length();i++){JSONObject h=hosts.optJSONObject(i);if(h!=null&&selected.equals(h.optString("id")))return h;}return null;}
    private void ensureSelected(){if(chosen()==null){JSONObject first=hosts.optJSONObject(0);selected=first==null?"":first.optString("id");}}

    private void render(){
        LinearLayout root=column();root.setBackgroundColor(bg);setContentView(root);
        if(android.os.Build.VERSION.SDK_INT>=35){root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets safe=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());v.setPadding(safe.left,safe.top,safe.right,safe.bottom);return insets;});root.requestApplyInsets();}
        LinearLayout heading=row();heading.setPadding(dp(18),dp(14),dp(20),dp(14));TextView back=button("‹",false,this::finish);back.setContentDescription("返回");heading.addView(back,new LinearLayout.LayoutParams(dp(44),dp(44)));LinearLayout title=column();title.setPadding(dp(12),0,0,0);title.addView(text("桌面挂件",22,ink,true));space(title,5);title.addView(text("选好样式，直接添加到主屏",11,muted,false));weight(heading,title);root.addView(heading);
        scroller=new ScrollView(this);scroller.setVerticalScrollBarEnabled(false);scroller.setClipToPadding(false);body=column();body.setPadding(dp(18),dp(4),dp(18),dp(24));scroller.addView(body);root.addView(scroller,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout hostPanel=surface();LinearLayout hostRow=row();LinearLayout hostInfo=column();hostInfo.addView(text("设备挂件显示的主机",11,muted,false));space(hostInfo,7);TextView name=text(chosen()==null?"尚未添加主机":chosen().optString("name"),15,ink,true);name.setSingleLine();name.setEllipsize(TextUtils.TruncateAt.END);hostInfo.addView(name);weight(hostRow,hostInfo);TextView change=button(chosen()==null?"添加主机":"更换",false,chosen()==null?this::openMain:this::selectHost);hostRow.addView(change);hostPanel.addView(hostRow);space(hostPanel,10);hostPanel.addView(text("“全部节点”汇总所有主机，其余挂件可分别选择主机。",11,muted,false));
        LinearLayout live=surface();LinearLayout liveRow=row();LinearLayout liveInfo=column();liveInfo.addView(text("桌面实时刷新",14,ink,true));space(liveInfo,5);liveStatus=text(WidgetBackgroundController.status(this),11,muted,false);liveInfo.addView(liveStatus);weight(liveRow,liveInfo);liveToggle=button(settings.liveWidgets()?"暂停":"开启",!settings.liveWidgets(),()->{WidgetBackgroundController.setEnabled(this,!settings.liveWidgets());render();handler.postDelayed(this::refreshLiveControls,700);});liveRow.addView(liveToggle);live.addView(liveRow);space(live,9);live.addView(text("开启后保留一条运行通知，App 退到后台也会每 15 秒更新挂件。",11,muted,false));space(live,10);live.addView(button("小米省电 / 通知设置",false,()->WidgetBackgroundController.systemSettings(this)));
        renderedStatus="";statusPanel=surface();statusTitle=text("",14,accent,true);statusMessage=text("",12,muted,false);statusPanel.addView(statusTitle);space(statusPanel,8);statusPanel.addView(statusMessage);statusPanel.setVisibility(pending.isEmpty()?View.GONE:View.VISIBLE);updateStatus();
        int known=0;try{List<AppWidgetProviderInfo> providers=WidgetPinning.providers(this);known=providers.size();}catch(RuntimeException ignored){}
        TextView intro=text(known==6?"6 款样式已就绪":"选择你喜欢的样式",12,muted,false);body.addView(intro);space(body,14);
        for(int type=0;type<MonitorWidget.PROVIDERS.length;type++)widgetCard(type);
        LinearLayout help=surface();help.addView(text("添加后在桌面直接查看",14,ink,true));space(help,8);help.addView(text("点击“添加到桌面”后，在系统确认窗口中完成添加。锁屏配件也可放在主屏；能否放到锁屏取决于手机系统。",12,muted,false));space(help,12);help.addView(button("打开应用系统设置",false,()->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())))));
    }

    private void widgetCard(int type){
        LinearLayout card=surface();LinearLayout heading=row();weight(heading,text(MonitorWidget.NAMES[type],16,ink,true));TextView add=button("添加到桌面",true,()->request(type));heading.addView(add);card.addView(heading);space(card,9);card.addView(text(DESCRIPTIONS[type],12,muted,false));space(card,14);
        FrameLayout holder=new FrameLayout(this);holder.setBackground(shape(dark?0xff111813:0xfff1f4ee,18));holder.setPadding(dp(10),dp(12),dp(10),dp(12));card.addView(holder,new LinearLayout.LayoutParams(-1,-2));
        TextView caption=text("",10,muted,false);space(card,10);card.addView(caption);showPreview(holder,caption,type,0);
        if(type==DeckWidgetRenderer.WIDE){space(card,12);LinearLayout states=row();String[] names={"当前状态","告警示例","离线示例"};for(int i=0;i<names.length;i++){int variant=i;TextView b=button(names[i],false,()->showPreview(holder,caption,type,variant));if(i>0){View gap=new View(this);states.addView(gap,new LinearLayout.LayoutParams(dp(7),1));}weight(states,b);}card.addView(states);}
    }

    private void showPreview(FrameLayout holder,TextView caption,int type,int variant){
        DeckWidgetRenderer.State state=realState(type);boolean example=state==null||variant>0;if(example)state=exampleState();
        if(variant==1){state.activeAlerts=1;state.alertTitle="GPU 2 温度偏高 · 样式示例";try{state.snapshot.getJSONArray("gpus").getJSONObject(1).put("temperatureCelsius",settings.threshold("gpu")+3);}catch(Exception ignored){}}
        if(variant==2){state.online=false;state.stale=false;state.activeAlerts=0;state.alertTitle="";state.offlineHosts=1;}
        int available=Math.max(220,Math.min(340,(int)(getResources().getDisplayMetrics().widthPixels/getResources().getDisplayMetrics().density)-88));
        int width=type<2?180:type==5?160:Math.min(320,available);int height=type<2?180:type==3?width:type==4?Math.round(width/2.4f):type==5?160:Math.round(width*170f/360f);
        holder.removeAllViews();RemoteViews remote=MonitorWidget.view(previewContext,type,state,width,height,0);View preview=remote.apply(previewContext,holder);FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(dp(width),dp(height),Gravity.CENTER);holder.addView(preview,p);holder.setMinimumHeight(dp(height+24));
        View touch=new View(this);touch.setBackgroundColor(Color.TRANSPARENT);touch.setContentDescription("添加"+MonitorWidget.NAMES[type]+"到桌面");tap(touch,()->request(type));holder.addView(touch,new FrameLayout.LayoutParams(-1,-1));
        caption.setText(example?"样式示例 · 数值为演示，添加后由所选主机采样更新":state.snapshot!=null&&state.snapshot.optBoolean("demo")?"演示采集数据 · 最近采样 "+Metrics.sampleTime(state.snapshot):state.stale?"缓存快照 · 最近采样 "+Metrics.sampleTime(state.snapshot):type==1?"全部节点的真实汇总":"真实监控数据 · 最近采样 "+Metrics.sampleTime(state.snapshot));
    }

    private DeckWidgetRenderer.State realState(int type){
        JSONObject host=chosen();if(type!=1&&host==null)return null;DeckWidgetRenderer.State s=new DeckWidgetRenderer.State();s.host=type==1?null:host;s.totalHosts=hosts.length();s.intervalSeconds=settings.interval();s.refreshMode=WidgetLiveService.isRunning()?"15 秒实时刷新":settings.liveWidgets()?"等待实时刷新":"系统定时更新";boolean hasData=false;
        for(int i=0;i<hosts.length();i++){JSONObject h=hosts.optJSONObject(i);if(h==null)continue;JSONObject cache=database.cached(h.optString("id"));int failures=database.failures(h.optString("id"));if(cache!=null){hasData=true;if(failures==0&&fresh(cache))s.onlineHosts++;}if(failures>=settings.failures())s.offlineHosts++;}
        if(type==1&&!hasData)return null;String id=host==null?"":host.optString("id");s.snapshot=type==1?null:database.cached(id);if(type!=1&&s.snapshot==null)return null;
        s.online=type==1?s.onlineHosts>0:database.failures(id)==0;s.stale=type!=1&&s.online&&!fresh(s.snapshot);s.muted=type!=1&&store.snoozed(id);s.activeAlerts=database.active(type==1?"":id);s.summary=database.summary(type==1?"":id);s.history=type==1?new ArrayList<>():database.history(id,3600000);
        for(MonitorDatabase.Event event:database.events())if(event.ended==0&&(type==1||event.host.equals(id))){s.alertTitle=event.title;break;}return s;
    }
    private boolean fresh(JSONObject s){return System.currentTimeMillis()-MonitorDatabase.sampleTime(s)<=Math.max(30000,s.optDouble("sampleIntervalSeconds",5)*3000);}
    private DeckWidgetRenderer.State exampleState(){
        DeckWidgetRenderer.State state=new DeckWidgetRenderer.State();state.online=true;state.onlineHosts=1;state.totalHosts=1;state.intervalSeconds=5;state.summary=new MonitorDatabase.Summary();state.summary.attempts=1000;state.summary.success=999;state.summary.peak=42d;state.history=new ArrayList<>();
        try{long gib=1073741824L;String stamp=Instant.now().toString();state.host=new JSONObject().put("name","双卡服务器 · 样式示例").put("mode","tailnet");JSONObject cpu=new JSONObject().put("usagePercent",21.5).put("loadAverage",new JSONArray(new double[]{.42,.38,.31}));JSONArray packages=new JSONArray();for(int i=0;i<2;i++)packages.put(new JSONObject().put("usagePercent",21+i).put("temperatureCelsius",46));cpu.put("packages",packages);JSONArray gpus=new JSONArray();for(int i=0;i<2;i++)gpus.put(new JSONObject().put("name","RTX 2080 Ti").put("sampledAt",stamp).put("usagePercent",0).put("temperatureCelsius",34+i).put("memoryUsedBytes",(long)(.2*gib)).put("memoryTotalBytes",22*gib));state.snapshot=new JSONObject().put("demo",true).put("sampledAt",stamp).put("gpuSampleIntervalSeconds",15).put("cpu",cpu).put("gpus",gpus).put("memory",new JSONObject().put("usagePercent",15).put("usedBytes",27*gib).put("totalBytes",(long)(184.5*gib))).put("disks",new JSONArray().put(new JSONObject().put("usedBytes",38*gib).put("totalBytes",492*gib)));
            long now=System.currentTimeMillis();for(int i=0;i<90;i++){MonitorDatabase.Point p=new MonitorDatabase.Point();p.time=now-3600000L+i*40000;p.cpu=21+Math.sin(i*.2)*2+(i>58&&i<72?(i-58)*1.1:0);p.memory=15d;p.gap=120000;state.history.add(p);}
        }catch(Exception ignored){}return state;
    }

    private void request(int type){
        if(type!=DeckWidgetRenderer.SUMMARY&&chosen()==null){new AlertDialog.Builder(this).setTitle("先添加一台主机").setMessage("添加服务器后，就能把它的状态放到桌面。你仍可在这里浏览全部样式。").setPositiveButton("添加主机",(d,w)->openMain()).setNegativeButton("继续浏览",null).show();return;}
        pending="";requestedType=type;handler.removeCallbacks(poll);
        try{pending=WidgetPinning.request(this,type,type==DeckWidgetRenderer.SUMMARY?null:chosen());statusPanel.setVisibility(View.VISIBLE);updateStatus();scroller.smoothScrollTo(0,0);if(foreground)handler.postDelayed(poll,800);}catch(Exception e){statusPanel.setVisibility(View.VISIBLE);statusTitle.setText("暂时未能添加");statusTitle.setTextColor(0xffc58a19);statusMessage.setText(e.getMessage()==null?"桌面未接收添加请求，请检查系统设置后重试。":e.getMessage());statusActions(true);scroller.smoothScrollTo(0,0);}
    }
    /** Returns true while waiting for the launcher's success callback. */
    private boolean updateStatus(){
        if(statusPanel==null||pending.isEmpty())return false;
        JSONObject result=WidgetPinning.result(this,pending);String state=result.optString("state","requested");String signature=pending+"/"+state+"/"+result.optString("message");if(signature.equals(renderedStatus))return state.equals("requested")||state.equals("unconfirmed");renderedStatus=signature;statusPanel.setVisibility(View.VISIBLE);while(statusPanel.getChildCount()>3)statusPanel.removeViewAt(statusPanel.getChildCount()-1);
        if(state.equals("added")){WidgetBackgroundController.ensure(this);handler.postDelayed(this::refreshLiveControls,700);statusTitle.setText("已添加到桌面");statusTitle.setTextColor(accent);statusMessage.setText(result.optString("message","桌面已确认添加，挂件会显示所选主机的数据。"));space(statusPanel,12);statusPanel.addView(button("查看桌面",true,this::openHome));return false;}
        if(state.equals("removed")){statusTitle.setText("挂件已从桌面移除");statusTitle.setTextColor(muted);statusMessage.setText(result.optString("message","这个挂件已不在桌面上，可以重新添加。"));statusActions(true);return false;}
        if(state.equals("unsupported")){statusTitle.setText("当前桌面未提供直接添加");statusTitle.setTextColor(0xffc58a19);statusMessage.setText(result.optString("message","当前桌面不支持系统直接添加接口，可检查应用权限后重试，或从桌面的小部件入口添加。"));statusActions(true);return false;}
        if(state.equals("error")||result.optBoolean("error",false)){statusTitle.setText("暂时未能添加");statusTitle.setTextColor(0xffc58a19);statusMessage.setText(result.optString("message","桌面未完成添加，请重试或检查系统设置。"));statusActions(true);return false;}
        if(state.equals("unconfirmed")){statusTitle.setText("桌面尚未确认添加");statusTitle.setTextColor(0xffc58a19);statusMessage.setText(result.optString("message","尚未收到桌面确认。你可以继续完成系统确认，或重新请求添加。"));statusActions(true);return true;}
        statusTitle.setText("等待桌面确认");statusTitle.setTextColor(accent);statusMessage.setText(result.optString("message","请在系统窗口中确认添加。若没有弹出窗口，可点击样式重试；这里不会把请求误报为已添加。"));return true;
    }
    private void statusActions(boolean retry){while(statusPanel.getChildCount()>3)statusPanel.removeViewAt(statusPanel.getChildCount()-1);space(statusPanel,12);if(retry){statusPanel.addView(button("重新请求添加",true,()->request(requestedType)));space(statusPanel,8);}statusPanel.addView(button("打开应用信息",false,()->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())))));space(statusPanel,8);statusPanel.addView(button("返回桌面",false,this::openHome));}
    private void refreshLiveControls(){if(!foreground)return;if(liveStatus!=null)liveStatus.setText(WidgetBackgroundController.status(this));if(liveToggle!=null)liveToggle.setText(settings.liveWidgets()?"暂停":"开启");}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==WidgetBackgroundController.NOTIFICATION_REQUEST){WidgetLiveService.ensureRunning(this);render();}}
    private void selectHost(){String[] names=new String[hosts.length()];for(int i=0;i<names.length;i++)names[i]=hosts.optJSONObject(i).optString("name");new AlertDialog.Builder(this).setTitle("选择显示主机").setItems(names,(d,w)->{selected=hosts.optJSONObject(w).optString("id");render();}).setNegativeButton("取消",null).show();}
    private void openMain(){startActivity(new Intent(this,MainActivity.class).putExtra("open_devices",true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));}
    private void openHome(){try{startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}catch(RuntimeException e){statusMessage.setText("请按手机主屏键查看刚添加的挂件。");}}
}
