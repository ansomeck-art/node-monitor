package cn.nodemon.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Bundle;
import android.os.Build;
import android.graphics.Insets;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private HostStore store; private JSONArray hosts; private final Map<String,JSONObject> snapshots = new HashMap<>(); private final Map<String,String> errors = new HashMap<>(); private final Map<String,ArrayDeque<Float>> history = new HashMap<>(); private final Set<String> inFlight = new HashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper()); private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private String page="devices", selected=""; private boolean foreground, destroyed, dark;
    private JSONArray discovered = new JSONArray(); private String discoveryStatus="登录 Tailscale 后，自动查找已安装采集端的服务器。"; private boolean discovering, resumeDiscovery, loggingIn;
    private ConnectivityManager connectivity; private boolean networkCallbackRegistered;
    private final Runnable notifyNetworkChange = () -> { if(!destroyed) executor.execute(()->{ try { TailnetBridge.request(this,"network_changed",null,null); } catch(Exception | LinkageError ignored) {} }); };
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        private void changed() { handler.removeCallbacks(notifyNetworkChange); handler.postDelayed(notifyNetworkChange,400); }
        @Override public void onAvailable(Network network) { changed(); }
        @Override public void onLost(Network network) { changed(); }
        @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) { changed(); }
    };
    private int bg, card, text, muted, track; private final int cyan=0xff75a54f, blue=0xff668e50, purple=0xff8caf72, green=0xff76ad44;
    private final Map<String,Double> ringValues = new HashMap<>();
    private LinearLayout root, body, header; private ScrollView scroller; private String renderedPage="";
    private final Runnable tick = new Runnable() { @Override public void run() { if (!foreground) return; refresh(); handler.postDelayed(this, 5000); } };
    private int dp(float n) { return Math.round(getResources().getDisplayMetrics().density*n); }
    @Override public void onCreate(Bundle state) { super.onCreate(state); requestWindowFeature(Window.FEATURE_NO_TITLE); store=new HostStore(this); hosts=store.hosts(); dark=store.dark(); connectivity=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE); if(connectivity!=null) try { connectivity.registerDefaultNetworkCallback(networkCallback); networkCallbackRegistered=true; } catch(RuntimeException ignored) {} openWidgetHost(getIntent()); render(); }
    @Override public void onResume() { super.onResume(); foreground=true; handler.removeCallbacks(tick); handler.post(tick); if(resumeDiscovery && page.equals("discover")) {resumeDiscovery=false;scanRemote();} }
    @Override public void onPause() { foreground=false; handler.removeCallbacks(tick); super.onPause(); }
    @Override public void onDestroy() { destroyed=true; handler.removeCallbacks(notifyNetworkChange); if(networkCallbackRegistered) try { connectivity.unregisterNetworkCallback(networkCallback); } catch(RuntimeException ignored) {} executor.shutdownNow(); super.onDestroy(); }
    private void colors() {
        bg=Color.parseColor(dark?"#141a15":"#ffffff"); card=Color.parseColor(dark?"#1c241d":"#ffffff");
        text=Color.parseColor(dark?"#edf3ea":"#202820"); muted=Color.parseColor(dark?"#9aa99a":"#8b9688"); track=Color.parseColor(dark?"#39483b":"#e9eee7");
        getWindow().setStatusBarColor(bg); getWindow().setNavigationBarColor(bg);
        getWindow().getDecorView().setSystemUiVisibility(dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }
    private GradientDrawable shape(int color, float radius) { GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private LinearLayout column() { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private TextView label(String s, float sp, int color, boolean bold) { TextView t=new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color); if (bold) t.setTypeface(Typeface.DEFAULT,Typeface.BOLD); return t; }
    private TextView action(String s, Runnable tap) { TextView t=label(s,14,blue,true); t.setGravity(Gravity.CENTER); t.setPadding(dp(12),dp(8),dp(12),dp(8)); t.setBackground(shape(dark?0xff29362a:0xfff3f6ef,18)); t.setOnClickListener(v->tap.run()); return t; }
    private void space(LinearLayout l, int h) { View v=new View(this); l.addView(v,new LinearLayout.LayoutParams(1,dp(h))); }
    private void weight(LinearLayout r, View v) { r.addView(v,new LinearLayout.LayoutParams(0,-2,1)); }
    private JSONObject host(String id) { for (int i=0;i<hosts.length();i++) { JSONObject h=hosts.optJSONObject(i); if(h!=null && id.equals(h.optString("id"))) return h; } return null; }
    private void render() {
        if (destroyed) return; String viewKey=page+":"+selected; int previousScroll=scroller!=null && renderedPage.equals(viewKey)?scroller.getScrollY():0; renderedPage=viewKey; colors(); root=column(); root.setBackgroundColor(bg); setContentView(root);
        if(Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets safe=insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(safe.left,safe.top,safe.right,safe.bottom); return insets;
            });
            root.requestApplyInsets();
        }
        header=row(); header.setPadding(dp(22),dp(20),dp(22),dp(12)); root.addView(header);
        scroller=new ScrollView(this); scroller.setFillViewport(true); body=column(); body.setPadding(dp(20),dp(4),dp(20),dp(14)); scroller.addView(body); root.addView(scroller,new LinearLayout.LayoutParams(-1,0,1));
        if(page.equals("devices")) devices(); else if(page.equals("details")) details(); else if(page.equals("discover")) discoveryPage(); else settings();
        if(page.equals("devices") || page.equals("settings")) {
            LinearLayout nav=row(); nav.setPadding(dp(32),dp(8),dp(32),dp(8)); nav.setBackgroundColor(card);
            weight(nav,navItem("◉  设备",page.equals("devices"),()->{page="devices";render();}));
            weight(nav,navItem("⚙  设置",page.equals("settings"),()->{page="settings";render();})); root.addView(nav);
        } else if(page.equals("details") && host(selected)!=null) {
            JSONObject widgetHost=host(selected); TextView add=label("＋   添加桌面挂件",14,dark?0xff182119:Color.WHITE,true);
            add.setGravity(Gravity.CENTER); add.setBackground(shape(dark?0xffc4e6a7:0xff222c22,32));
            add.setOnClickListener(v->pinWidget(widgetHost));
            LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,dp(48)); ap.setMargins(dp(20),dp(4),dp(20),dp(10)); root.addView(add,ap);
        }
        if(previousScroll>0) { ScrollView restoring=scroller; restoring.post(()->restoring.scrollTo(0,previousScroll)); }
    }
    private void spaceHorizontal(LinearLayout l,int w) { l.addView(new View(this),new LinearLayout.LayoutParams(dp(w),1)); }
    private LinearLayout card() { LinearLayout c=column(); c.setPadding(dp(16),dp(16),dp(16),dp(16)); GradientDrawable surface=shape(card,22); surface.setStroke(dp(1),dark?0xff344235:0xffe7ece3); c.setBackground(surface); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.bottomMargin=dp(14); body.addView(c,lp); return c; }
    private void title(String title,String subtitle) { LinearLayout t=column(); TextView heading=label(title,21,text,true); heading.setSingleLine(true); heading.setEllipsize(TextUtils.TruncateAt.END); t.addView(heading); if(subtitle!=null) { TextView sub=label(subtitle,12,muted,false); sub.setPadding(0,dp(5),0,0); t.addView(sub); } weight(header,t); }
    private TextView navItem(String name, boolean active, Runnable tap) {
        TextView item=label(name,13,active?text:muted,active); item.setGravity(Gravity.CENTER);
        item.setPadding(dp(10),dp(11),dp(10),dp(11)); item.setOnClickListener(v->tap.run()); return item;
    }
    private void divider(LinearLayout target) { View v=new View(this); v.setBackgroundColor(track); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(1)); lp.setMargins(0,dp(12),0,dp(12)); target.addView(v,lp); }
    private RingGauge ring(String key, JSONObject value, String name) {
        Double current=Metrics.number(value,"usagePercent"), previous=ringValues.get(key);
        if(current!=null) ringValues.put(key,current); else ringValues.remove(key);
        RingGauge gauge=new RingGauge(this,current,previous,dark,()->foreground && !destroyed);
        gauge.setContentDescription(name+"，使用率 "+Metrics.percent(value,"usagePercent")+"，温度 "+Metrics.temp(value,"temperatureCelsius"));
        return gauge;
    }
    private void sectionHeading(LinearLayout target,String name,String count) {
        LinearLayout line=row(); weight(line,label("●  "+name,13,text,true)); line.addView(label(count,11,muted,false)); target.addView(line); space(target,8);
    }
    private String shortGpu(JSONObject g) {
        String name=g==null?"显卡":g.optString("name","显卡");
        return name.replace("NVIDIA GeForce ","").replace("NVIDIA ","").replace("AMD Radeon ","");
    }
    private LinearLayout homeGauge(String key,String name,String sub,JSONObject value) {
        LinearLayout item=row(); RingGauge gauge=ring(key,value,name); item.addView(gauge,new LinearLayout.LayoutParams(dp(58),dp(58))); spaceHorizontal(item,6);
        LinearLayout info=column(); TextView nameLabel=label(name,12,text,true); nameLabel.setSingleLine(true); info.addView(nameLabel);
        TextView subLabel=label(sub,10,muted,false); subLabel.setSingleLine(true); subLabel.setEllipsize(TextUtils.TruncateAt.END); info.addView(subLabel);
        TextView temp=label(Metrics.temp(value,"temperatureCelsius"),11,muted,true); temp.setPadding(0,dp(3),0,0); info.addView(temp); weight(item,info); return item;
    }
    private void homeGrid(LinearLayout target,String hostId,JSONArray values,JSONObject snapshot,boolean gpu) {
        LinearLayout line=null; int displayed=0;
        for(int i=0;i<values.length();i++) {
            JSONObject value=values.optJSONObject(i); if(value==null) continue;
            if(displayed%2==0) { line=row(); target.addView(line); }
            JSONObject shown=gpu?Metrics.freshGpu(snapshot,value):value;
            String kind=gpu?"GPU":"CPU", label=kind+" "+(i+1);
            String sub=gpu?shortGpu(value):"处理器";
            weight(line,homeGauge(hostId+":"+kind+":"+i,label,sub,shown)); displayed++;
            if(displayed%2==0) space(target,8);
        }
        if(displayed%2==1 && line!=null) weight(line,new View(this));
    }
    private void devices() {
        int online=0; for(int i=0;i<hosts.length();i++) { JSONObject h=hosts.optJSONObject(i); if(h!=null && snapshots.containsKey(h.optString("id"))) online++; }
        title("节点监控",online+" / "+hosts.length()+" 台设备在线"); header.addView(action("＋",this::addChoice));
        if(hosts.length()==0) { LinearLayout empty=card(); empty.addView(label("连接你的第一台主机",19,text,true)); space(empty,8); empty.addView(label("添加已运行采集端的 Ubuntu 或 Windows 主机。",13,muted,false)); space(empty,15); empty.addView(action("添加主机",this::addChoice)); return; }
        for(int i=0;i<hosts.length();i++) {
            JSONObject h=hosts.optJSONObject(i); if(h==null) continue; String id=h.optString("id"); JSONObject s=snapshots.get(id);
            LinearLayout intro=column(); intro.setPadding(dp(2),dp(8),dp(2),dp(12)); body.addView(intro);
            LinearLayout status=row(); weight(status,label(s!=null?"● 设备在线":"● "+(errors.containsKey(id)?"连接失败":"等待连接"),12,s!=null?green:muted,true));
            if(s!=null)status.addView(label("最近更新 "+Metrics.sampleTime(s),10,muted,false)); intro.addView(status); space(intro,11);
            LinearLayout identity=row(); LinearLayout info=column(); TextView name=label(h.optString("name"),19,text,true); name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END); info.addView(name);
            JSONObject server=s==null?null:s.optJSONObject("host"); info.addView(label((h.optString("mode").equals("tailnet")?"远程网络":"局域网")+(server==null?"":"  ·  "+server.optString("os")),11,muted,false)); weight(identity,info); identity.addView(label("↗",22,muted,false)); intro.addView(identity);
            Runnable open=()->{selected=id;page="details";render();}; intro.setOnClickListener(v->open.run());
            LinearLayout c=card(); c.setOnClickListener(v->open.run());
            LinearLayout top=row(); weight(top,label("硬件概览",15,text,true)); top.addView(label("实时状态",11,muted,false)); c.addView(top); space(c,16);
            if(s!=null) {
                JSONObject cpu=s.optJSONObject("cpu"); JSONArray packages=cpu==null?null:cpu.optJSONArray("packages");
                if(packages!=null && packages.length()>0) {sectionHeading(c,"CPU",packages.length()+" 颗处理器"); homeGrid(c,id,packages,s,false);}
                else {sectionHeading(c,"CPU","总体"); LinearLayout one=row(); weight(one,homeGauge(id+":CPU:total","CPU 总体","处理器",cpu)); weight(one,new View(this)); c.addView(one);}
                divider(c); JSONArray gpus=s.optJSONArray("gpus"); sectionHeading(c,"GPU",gpus==null?"0 张显卡":gpus.length()+" 张显卡");
                if(gpus!=null && gpus.length()>0) homeGrid(c,id,gpus,s,true); else c.addView(label("当前没有可用的显卡读数",12,muted,false));
                divider(c); LinearLayout memory=row(); weight(memory,label("▦  内存",12,muted,false)); memory.addView(label(Metrics.size(s.optJSONObject("memory"),"usedBytes")+" / "+Metrics.size(s.optJSONObject("memory"),"totalBytes"),12,text,true)); c.addView(memory);
                space(c,12); LinearLayout footer=row(); weight(footer,label("每个硬件独立显示",10,muted,false)); footer.addView(label("查看详情 ↗",10,muted,false)); c.addView(footer);
            } else c.addView(label(errors.getOrDefault(id,"正在连接…"),12,muted,false));
        }
    }
    private void addChoice() { new AlertDialog.Builder(this).setTitle("添加主机").setItems(new String[]{"登录 Tailscale，自动查找", "局域网 / 手动添加"},(dialog,which)->{if(which==0){page="discover";discovered=new JSONArray();render();scanRemote();}else hostDialog(null);}).show(); }
    private void discoveryPage() {
        header.addView(action("‹",()->{page="devices";render();})); spaceHorizontal(header,12); title("自动添加主机","Tailscale 远程连接");
        LinearLayout intro=card(); intro.addView(label("登录并查找服务器",17,text,true)); space(intro,8); intro.addView(label(discoveryStatus,13,muted,false)); space(intro,12);
        LinearLayout buttons=row(); weight(buttons,action("登录 Tailscale",this::login)); spaceHorizontal(buttons,10); weight(buttons,action(discovering?"正在查找…":"重新查找",this::scanRemote)); intro.addView(buttons);
        for(int i=0;i<discovered.length();i++) { JSONObject peer=discovered.optJSONObject(i); if(peer==null) continue; LinearLayout item=card(); item.addView(label(peer.optString("name","服务器"),17,text,true)); space(item,6); item.addView(label(peer.optBoolean("ownerAuth")?"已发现监控服务 · 点击添加":peer.optString("access").equals("token")?"已发现监控服务 · 需要升级或授权":"已发现监控服务 · 暂时无法确认访问状态",12,muted,false)); space(item,10); item.addView(action("选择这台服务器",()->addDiscovered(peer))); }
        LinearLayout manual=card(); manual.addView(action("无法发现？手动添加",()->hostDialog(null)));
    }
    private void scanRemote() {
        if(discovering) return; discovering=true; discoveryStatus="正在连接远程网络并查找服务器…"; if(page.equals("discover"))render();
        executor.execute(()->{ JSONArray found=new JSONArray(); String message; try { JSONObject status=TailnetBridge.request(this,"status",null,null); if(!"Running".equals(status.optString("state"))) message="请点击“登录 Tailscale”，使用服务器所在账号完成授权，然后返回应用。"; else { JSONObject response=TailnetBridge.request(this,"discover",null,null); JSONArray values=response.optJSONArray("nodes"); if(values!=null)found=values; message=found.length()==0?"没有找到可连接的监控服务。请确认服务器已启动采集端和远程监听后重试。":"找到 "+found.length()+" 台服务器，请选择。"; } } catch(Exception | LinkageError e) {message="暂时无法查找，请检查网络和 Tailscale 授权后重试。";} JSONArray result=found;String note=message;handler.post(()->{discovering=false;if(destroyed)return;discovered=result;discoveryStatus=note;if(page.equals("discover"))render();}); });
    }
    private void addDiscovered(JSONObject peer) {
        if(peer.optBoolean("ownerAuth")) { saveDiscovered(peer, ""); return; }
        if(!peer.optString("access").equals("token")) {Toast.makeText(this,"暂时无法确认服务器的访问状态，请重新查找",Toast.LENGTH_LONG).show();return;}
        EditText token=new EditText(this); token.setSingleLine(); token.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD); token.setHint("采集端令牌");
        new AlertDialog.Builder(this).setTitle(peer.optString("name","服务器")).setMessage("这台服务器尚未启用 Tailscale 账号认证。请先升级采集端并启用该功能；如果已有采集端令牌，也可以在此填写一次。地址已自动识别。").setView(token).setNegativeButton("稍后",null).setPositiveButton("使用令牌添加",(d,w)->{String value=token.getText().toString();if(value.isEmpty()){Toast.makeText(this,"请先填写令牌，或升级服务器后重新查找",Toast.LENGTH_LONG).show();return;}saveDiscovered(peer,value);}).show();
    }
    private void saveDiscovered(JSONObject peer,String token) {
        try { String address=peer.getString("address"); MonitorClient.tailnet(address); for(int i=0;i<hosts.length();i++){JSONObject prior=hosts.optJSONObject(i);if(prior!=null && address.equals(prior.optString("tailnetAddress"))){JSONObject updated=new JSONObject(prior.toString());updated.put("address",address);updated.put("mode","tailnet");updated.put("token",store.protect(token));hosts.put(i,updated);store.save(hosts);selected=updated.optString("id");snapshots.remove(selected);errors.remove(selected);page="details";render();refresh();MonitorWidget.schedule(this);return;}} JSONObject host=new JSONObject();host.put("id",UUID.randomUUID().toString());host.put("name",peer.optString("name","服务器"));host.put("address",address);host.put("directAddress","");host.put("tailnetAddress",address);host.put("mode","tailnet");host.put("allowLan",false);host.put("token",store.protect(token));hosts.put(host);store.save(hosts);selected=host.getString("id");page="details";render();refresh();MonitorWidget.schedule(this); } catch(Exception e) {Toast.makeText(this,"无法添加服务器，请重试",Toast.LENGTH_SHORT).show();}
    }
    private void openWidgetHost(Intent intent) {
        if(intent==null)return; String id=intent.getStringExtra(MonitorWidget.EXTRA_HOST_ID);
        if(id!=null && host(id)!=null) {selected=id;page="details";}
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); openWidgetHost(intent); render(); if(foreground)refresh(); }
    private void pinWidget(JSONObject h) {
        store.setWidgetHost(h.optString("id")); JSONObject snapshot=snapshots.get(h.optString("id"));
        MonitorWidget.update(this,h,snapshot,snapshot==null?"等待更新":"在线 · "+Metrics.sampleTime(snapshot)); MonitorWidget.schedule(this);
        AppWidgetManager manager=AppWidgetManager.getInstance(this);
        try {
            if(manager.isRequestPinAppWidgetSupported() && manager.requestPinAppWidget(new ComponentName(this,MonitorWidget.class),null,null)) {
                Toast.makeText(this,"请在桌面弹窗中确认添加挂件",Toast.LENGTH_LONG).show(); return;
            }
        } catch(RuntimeException ignored) { }
        new AlertDialog.Builder(this).setTitle("添加桌面挂件").setMessage("已选择此主机。当前桌面不支持直接添加，请长按手机桌面，选择“添加小部件 / 挂件”，找到“节点监控”并拖到桌面。已有挂件会显示此主机。").setPositiveButton("知道了",null).show();
    }
    private void detailGauge(LinearLayout target,String key,String name,String sub,JSONObject value,boolean gpu) {
        LinearLayout line=row(); line.setPadding(0,dp(5),0,dp(5)); line.addView(ring(key,value,name),new LinearLayout.LayoutParams(dp(54),dp(54))); spaceHorizontal(line,12);
        LinearLayout info=column(); LinearLayout top=row(); TextView labelName=label(name,13,text,true); top.addView(labelName); spaceHorizontal(top,8);
        if(gpu) {TextView model=label(sub,11,muted,false); model.setSingleLine(true); model.setEllipsize(TextUtils.TruncateAt.END); weight(top,model);} else weight(top,new View(this));
        top.addView(label(Metrics.temp(value,"temperatureCelsius"),13,text,true)); info.addView(top);
        if(gpu) {
            space(info,5); LinearLayout memory=row(); weight(memory,label("显存",10,muted,false)); memory.addView(label(Metrics.size(value,"memoryUsedBytes")+" / "+Metrics.size(value,"memoryTotalBytes"),10,muted,true)); info.addView(memory); space(info,4);
            Double used=Metrics.number(value,"memoryUsedBytes"), total=Metrics.number(value,"memoryTotalBytes"); progress(info,total!=null && total>0 && used!=null?100*used/total:null);
        } else {space(info,4);info.addView(label("处理器使用率 · 温度",11,muted,false));}
        weight(line,info); target.addView(line); divider(target);
    }
    private void progress(LinearLayout target, Double value) {
        android.widget.ProgressBar bar=new android.widget.ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); bar.setMax(1000); bar.setProgress(value==null?0:(int)(Math.max(0,Math.min(100,value))*10));
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xffa8ca81)); bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(track)); target.addView(bar,new LinearLayout.LayoutParams(-1,dp(4)));
    }
    private void details() {
        JSONObject h=host(selected); if(h==null) {page="devices";render();return;}
        header.addView(action("‹",()->{page="devices";render();})); spaceHorizontal(header,10); title(h.optString("name"),h.optString("mode").equals("tailnet")?"远程网络  ·  Tailscale":"局域网 / HTTPS"); header.addView(action("编辑",()->hostDialog(h)));
        JSONObject s=snapshots.get(selected);
        if(s==null) {
            LinearLayout offline=card(); offline.addView(label("● 连接中 / 离线",16,text,true)); space(offline,7); offline.addView(label(errors.getOrDefault(selected,"正在连接…"),12,muted,false)); space(offline,10); offline.addView(action("重新连接",this::refresh));
            if(h.optString("mode").equals("tailnet")) {space(offline,8);offline.addView(action("登录远程网络",this::login));} return;
        }
        LinearLayout state=row(); state.setPadding(0,dp(12),0,dp(12)); weight(state,label("●  在线",13,green,true)); state.addView(label("采样 "+Metrics.sampleTime(s),11,muted,false)); body.addView(state); divider(body);
        JSONObject cpu=s.optJSONObject("cpu"); JSONArray packages=cpu==null?null:cpu.optJSONArray("packages");
        sectionHeading(body,"处理器",packages!=null && packages.length()>0?packages.length()+" 颗 CPU":"总体");
        if(packages!=null && packages.length()>0) { for(int i=0;i<packages.length();i++) {JSONObject p=packages.optJSONObject(i);if(p!=null)detailGauge(body,selected+":CPU:"+i,"CPU "+(i+1),"",p,false);} }
        else detailGauge(body,selected+":CPU:total","CPU 总体","",cpu,false);
        JSONArray gpus=s.optJSONArray("gpus"); sectionHeading(body,"显卡",gpus==null?"0 张 GPU":gpus.length()+" 张 GPU");
        if(gpus==null || gpus.length()==0) {body.addView(label("当前没有可用的 GPU 读数",12,muted,false));divider(body);}
        else for(int i=0;i<gpus.length();i++) {JSONObject g=gpus.optJSONObject(i);if(g!=null)detailGauge(body,selected+":GPU:"+i,"GPU "+(i+1),shortGpu(g),Metrics.freshGpu(s,g),true);}
        JSONObject memory=s.optJSONObject("memory"); sectionHeading(body,"系统内存",Metrics.percent(memory,"usagePercent")+" 已使用");
        LinearLayout mem=row(); weight(mem,label(Metrics.size(memory,"usedBytes")+" / "+Metrics.size(memory,"totalBytes"),12,muted,false)); mem.addView(label(Metrics.percent(memory,"usagePercent"),12,text,true)); body.addView(mem); space(body,8); progress(body,Metrics.number(memory,"usagePercent")); divider(body);
        JSONObject net=s.optJSONObject("network"); sectionHeading(body,"网络","当前速率"); body.addView(label("↓ 下行  "+rate(net,"receiveBytesPerSecond")+"      ↑ 上行  "+rate(net,"sendBytesPerSecond"),12,text,true)); divider(body);
        LinearLayout chart=column(); body.addView(chart); chart.addView(label("CPU 使用率趋势",13,text,true)); space(chart,7); chart.addView(new TrendView(history.get(selected)),new LinearLayout.LayoutParams(-1,dp(70))); chart.addView(label("仅显示打开应用后的数据",10,muted,false));
        JSONArray disks=s.optJSONArray("disks"); if(disks!=null && disks.length()>0) { divider(body); sectionHeading(body,"磁盘",""); for(int i=0;i<disks.length();i++) {JSONObject d=disks.optJSONObject(i);if(d==null)continue;Double total=Metrics.number(d,"totalBytes"),used=Metrics.number(d,"usedBytes");metric(body,d.optString("mount"),Metrics.size(d,"usedBytes"),Metrics.size(d,"totalBytes"),total!=null && total>0 && used!=null?100*used/total:null,green);} }
        JSONArray warnings=s.optJSONArray("warnings"); if(warnings!=null && warnings.length()>0) {divider(body);for(int i=0;i<warnings.length();i++)body.addView(label(warnings.optString(i),11,muted,false));}
        if(!h.optString("directAddress").isEmpty() && !h.optString("tailnetAddress").isEmpty()) {divider(body);LinearLayout choice=row();boolean remote=h.optString("mode").equals("tailnet");weight(choice,action(remote?"局域网":"✓ 局域网",()->switchMode(h,false)));spaceHorizontal(choice,10);weight(choice,action(remote?"✓ 远程":"远程",()->switchMode(h,true)));body.addView(choice);}
    }
    private String rate(JSONObject n,String key) { Double value=Metrics.number(n,key); return value==null?"—":String.format(java.util.Locale.ROOT,"%.0f KiB/s",value/1024); }
    private void metric(LinearLayout target,String name,String value,String extra,Double progress,int color) {
        LinearLayout line=row(); TextView left=label(name,13,text,false); line.addView(left,new LinearLayout.LayoutParams(dp(72),-2)); TextView val=label(value,13,text,true); line.addView(val); if(extra!=null && !extra.isEmpty()) {TextView e=label("  "+extra,11,muted,false); e.setGravity(Gravity.RIGHT); weight(line,e);} target.addView(line); space(target,6);
        android.widget.ProgressBar bar=new android.widget.ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); bar.setMax(1000); bar.setProgress(progress==null?0:(int)(Math.max(0,Math.min(100,progress))*10)); bar.setProgressTintList(android.content.res.ColorStateList.valueOf(color)); bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(track)); target.addView(bar,new LinearLayout.LayoutParams(-1,dp(5))); space(target,12);
    }
    private void settings() { title("设置","让监控保持轻盈"); LinearLayout appearance=card(); appearance.addView(label("界面外观",17,text,true)); space(appearance,7); appearance.addView(label("选择适合你的显示模式",13,muted,false)); space(appearance,12); LinearLayout choices=row(); weight(choices,action("深色",()->{dark=true;store.setDark(true);render();})); spaceHorizontal(choices,10); weight(choices,action("浅色",()->{dark=false;store.setDark(false);render();})); appearance.addView(choices); LinearLayout about=card(); about.addView(label("更新与桌面挂件",17,text,true)); space(about,8); about.addView(label("应用在前台时约每 5 秒更新，圆环轻微运动并反映真实采样。桌面挂件由 Android 安排后台更新，通常不少于 30 分钟；挂件圆环保持静态以节省电量，点击可打开主机详情并刷新。",13,muted,false)); }
    private void refresh() {
        for(int i=0;i<hosts.length();i++) { JSONObject h=hosts.optJSONObject(i); if(h==null)continue; String id=h.optString("id"); if(inFlight.contains(id))continue; inFlight.add(id); final JSONObject copy; try { copy=new JSONObject(h.toString()); } catch(Exception ex) {inFlight.remove(id);continue;}
            executor.execute(()->{ JSONObject sample=null; String failure=null; try{sample=MonitorClient.fetch(this,copy);}catch(Exception e){failure=e.getMessage()==null?"连接失败":e.getMessage();} JSONObject result=sample; String error=failure;
                handler.post(()->{inFlight.remove(id); JSONObject latest=host(id); if(destroyed || latest==null)return; if(!latest.toString().equals(copy.toString())) { if(foreground)refresh(); return; } if(result!=null) {snapshots.put(id,result); errors.remove(id); Double cpu=Metrics.number(result.optJSONObject("cpu"),"usagePercent"); if(cpu!=null){ArrayDeque<Float> q=history.computeIfAbsent(id,k->new ArrayDeque<>());q.add(cpu.floatValue());while(q.size()>60)q.removeFirst();}} else {snapshots.remove(id);errors.put(id,error);} if(foreground)render(); JSONObject widgetHost=store.widgetHost(hosts); if(widgetHost!=null && id.equals(widgetHost.optString("id"))) MonitorWidget.update(this,copy,result,result!=null?"● 在线 · "+Metrics.sampleTime(result):"● 离线 · 点击检查连接"); });
            });
        }
    }
    private void login() {
        if(loggingIn)return; loggingIn=true; Toast.makeText(this,"正在准备授权…",Toast.LENGTH_SHORT).show();
        executor.execute(()->{ try {
            JSONObject result=TailnetBridge.request(this,"login",null,null); String url=result.optString("url","");
            handler.post(()->{ loggingIn=false; if(destroyed)return;
                if(url.isEmpty()) {Toast.makeText(this,"远程网络已登录",Toast.LENGTH_SHORT).show();if(page.equals("discover"))scanRemote();else refresh();return;}
                Uri uri=Uri.parse(url); if(!"https".equals(uri.getScheme()) || !"login.tailscale.com".equals(uri.getHost()) || uri.getUserInfo()!=null) {Toast.makeText(this,"授权地址无效",Toast.LENGTH_LONG).show();return;}
                try { startActivity(new Intent(Intent.ACTION_VIEW,uri)); resumeDiscovery=page.equals("discover"); Toast.makeText(this,"请完成授权后返回应用",Toast.LENGTH_LONG).show(); }
                catch(ActivityNotFoundException | SecurityException e) { new AlertDialog.Builder(this).setTitle("无法打开授权页面").setMessage("请先安装或启用浏览器，然后重新登录。 ").setPositiveButton("知道了",null).show(); }
            });
        } catch(Exception | LinkageError e) {
            String message=e instanceof TailnetBridge.RemoteError?e.getMessage():e instanceof LinkageError?"远程组件无法加载，请确认手机使用 64 位 ARM 处理器。":"无法准备授权，请检查网络后重试。";
            handler.post(()->{loggingIn=false;if(!destroyed)new AlertDialog.Builder(this).setTitle("无法登录 Tailscale").setMessage(message).setPositiveButton("知道了",null).show();});
        } });
    }
    private void switchMode(JSONObject current, boolean remote) { try { JSONObject item=new JSONObject(current.toString()); item.put("mode",remote?"tailnet":"direct"); item.put("address",item.getString(remote?"tailnetAddress":"directAddress")); for(int i=0;i<hosts.length();i++) if(hosts.optJSONObject(i)!=null && hosts.optJSONObject(i).optString("id").equals(item.optString("id"))) {hosts.put(i,item);break;} store.save(hosts); snapshots.remove(item.optString("id"));errors.remove(item.optString("id"));render();refresh();MonitorWidget.schedule(this); } catch(Exception e){Toast.makeText(this,"无法切换连接",Toast.LENGTH_SHORT).show();} }
    private void hostDialog(JSONObject existing) {
        LinearLayout fields=column(); fields.setPadding(dp(22),dp(5),dp(22),dp(5)); EditText name=new EditText(this), direct=new EditText(this), remoteAddress=new EditText(this), token=new EditText(this); name.setSingleLine(); direct.setSingleLine(); remoteAddress.setSingleLine(); token.setSingleLine(); token.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD); name.setHint("主机名称"); direct.setHint("局域网 / HTTPS 地址（可留空）"); remoteAddress.setHint("Tailscale 地址（可留空）"); token.setHint(existing==null?"采集端令牌":"令牌（留空保留原令牌）");
        Spinner mode=new Spinner(this); mode.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"局域网 / HTTPS","远程 Tailscale"})); CheckBox allow=new CheckBox(this); allow.setText("允许可信局域网 HTTP（数据不加密）");
        if(existing!=null) {name.setText(existing.optString("name")); boolean remote=existing.optString("mode").equals("tailnet");direct.setText(existing.optString("directAddress",remote?"":existing.optString("address")));remoteAddress.setText(existing.optString("tailnetAddress",remote?existing.optString("address"):""));mode.setSelection(remote?1:0);allow.setChecked(existing.optBoolean("allowLan"));}
        fields.addView(name);fields.addView(mode);fields.addView(direct);fields.addView(remoteAddress);fields.addView(token);fields.addView(allow);
        AlertDialog.Builder b=new AlertDialog.Builder(this).setTitle(existing==null?"添加主机":"编辑主机").setView(fields).setNegativeButton("取消",null).setPositiveButton("保存",null);
        if(existing!=null) b.setNeutralButton("删除",(dialog,which)->new AlertDialog.Builder(this).setMessage("删除此主机的本地配置？").setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{JSONArray next=new JSONArray();for(int i=0;i<hosts.length();i++){JSONObject h=hosts.optJSONObject(i);if(h!=null && !h.optString("id").equals(existing.optString("id")))next.put(h);}hosts=next;store.save(hosts);selected="";page="devices";MonitorWidget.schedule(this);render();}).show());
        AlertDialog dialog=b.create(); dialog.setOnShowListener(x->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try {String n=name.getText().toString().trim(), local=direct.getText().toString().trim(), far=remoteAddress.getText().toString().trim(), secret=token.getText().toString(); boolean remote=mode.getSelectedItemPosition()==1; String a=remote?far:local; if(n.isEmpty() || a.isEmpty() || (existing==null && secret.isEmpty()))throw new Exception("请填写名称、当前连接方式的地址和令牌"); if(!local.isEmpty())MonitorClient.direct(local,allow.isChecked()); if(!far.isEmpty())MonitorClient.tailnet(far); JSONObject item=new JSONObject();item.put("id",existing==null?UUID.randomUUID().toString():existing.getString("id"));item.put("name",n);item.put("address",a);item.put("directAddress",local);item.put("tailnetAddress",far);item.put("mode",remote?"tailnet":"direct");item.put("allowLan",allow.isChecked());item.put("token",secret.isEmpty()?existing.getString("token"):store.protect(secret)); boolean replaced=false; if(existing!=null)for(int i=0;i<hosts.length();i++) if(hosts.optJSONObject(i)!=null && hosts.optJSONObject(i).optString("id").equals(existing.optString("id"))) {hosts.put(i,item);replaced=true;break;} if(!replaced)hosts.put(item);store.save(hosts);snapshots.remove(item.getString("id"));errors.remove(item.getString("id")); selected=item.getString("id");page="details";dialog.dismiss();render();refresh();MonitorWidget.schedule(this);}catch(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();} })); dialog.show();
    }
    @Override public void onBackPressed() { if(!page.equals("devices")) {page="devices";render();} else super.onBackPressed(); }
    final class TrendView extends View { final Float[] values; final Paint paint=new Paint(3); TrendView(ArrayDeque<Float> v){super(MainActivity.this); values=v==null?new Float[0]:v.toArray(new Float[0]);} @Override protected void onDraw(Canvas c){super.onDraw(c);paint.setColor(track);paint.setStrokeWidth(dp(1));for(int i=0;i<3;i++)c.drawLine(0,getHeight()*i/2f,getWidth(),getHeight()*i/2f,paint);if(values.length<2)return;Path path=new Path();for(int i=0;i<values.length;i++){float x=i*(getWidth()-1f)/(values.length-1), y=getHeight()-Math.max(0,Math.min(100,values[i]))*(getHeight()-2)/100f;if(i==0)path.moveTo(x,y);else path.lineTo(x,y);}paint.setColor(blue);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));c.drawPath(path,paint);paint.setStyle(Paint.Style.FILL);} }
}
