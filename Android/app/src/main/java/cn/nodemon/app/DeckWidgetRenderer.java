package cn.nodemon.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Bitmap typography and plots; small launcher-owned overlays supply decorative motion. */
final class DeckWidgetRenderer {
    static final int COMPACT=0,SUMMARY=1,WIDE=2,DASHBOARD=3,RECTANGLE=4,CIRCLE=5;
    static final class State {
        JSONObject host,snapshot;
        boolean online,stale,muted,refreshing;
        int activeAlerts,onlineHosts,totalHosts,offlineHosts,intervalSeconds=5;
        String alertTitle="";
        String refreshMode="系统定时更新";
        MonitorDatabase.Summary summary;
        List<MonitorDatabase.Point> history=Collections.emptyList();
        long now=System.currentTimeMillis();
    }

    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.SUBPIXEL_TEXT_FLAG);
    private final Canvas canvas;
    private final float w,h;
    private final State state;
    private final int ink,muted,track,accent,pale,surface,gpuThreshold;
    private static final int WARNING=0xffc38a20,ERROR=0xffd85b50;
    private static final Typeface SANS=Typeface.create("sans-serif",Typeface.NORMAL);
    private static final Typeface MEDIUM=Typeface.create("sans-serif-medium",Typeface.NORMAL);
    private static final Typeface MONO=Typeface.create("monospace",Typeface.NORMAL);

    private DeckWidgetRenderer(Context context,Canvas canvas,float w,float h,State state){
        this.canvas=canvas;this.w=w;this.h=h;this.state=state;
        ink=context.getColor(R.color.deck_ink);muted=context.getColor(R.color.deck_muted);
        track=context.getColor(R.color.deck_track);accent=context.getColor(R.color.deck_accent);
        pale=context.getColor(R.color.deck_pale);surface=context.getColor(R.color.deck_surface);gpuThreshold=new MonitorSettings(context).threshold("gpu");
    }

    static Bitmap render(Context context,int type,State state,int widthDp,int heightDp){
        int width=Math.max(90,widthDp),height=Math.max(60,heightDp);
        float density=Math.min(2f,context.getResources().getDisplayMetrics().density);
        float scale=Math.min(density,1024f/Math.max(width,height));
        Bitmap bitmap=Bitmap.createBitmap(Math.max(1,Math.round(width*scale)),Math.max(1,Math.round(height*scale)),Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(bitmap);c.scale(scale,scale);
        DeckWidgetRenderer r=new DeckWidgetRenderer(context,c,width,height,state);
        if(type==CIRCLE)r.circle();else{
            r.panel(type==SUMMARY);
            switch(type){case COMPACT:r.compact();break;case SUMMARY:r.summary();break;case DASHBOARD:r.dashboard();break;case RECTANGLE:r.rectangle();break;default:r.wide();}
        }
        return bitmap;
    }

    /** Only the orbiting highlight moves; the measured arc and its number remain stationary. */
    static Bitmap rotor(Context context,State state,int sizeDp){
        int size=Math.max(90,sizeDp);float scale=Math.min(2f,context.getResources().getDisplayMetrics().density);
        Bitmap b=Bitmap.createBitmap(Math.round(size*scale),Math.round(size*scale),Bitmap.Config.ARGB_8888);
        if(!state.online||state.stale)return b;
        Canvas c=new Canvas(b);c.scale(scale,scale);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        int color=context.getColor(R.color.deck_accent);float center=size/2f,radius=center-16;
        p.setColor((color&0x00ffffff)|0x22000000);c.drawCircle(center,center-radius,7,p);
        p.setColor((color&0x00ffffff)|0x99000000);c.drawCircle(center,center-radius,2.8f,p);
        return b;
    }

    static Double cpuPeak(State state){
        if(!state.online)return null;JSONObject cpu=object(state.snapshot,"cpu");JSONArray a=cpu==null?null:cpu.optJSONArray("packages");Double peak=null;
        if(a!=null&&a.length()>0)for(int i=0;i<a.length();i++){Double n=Metrics.number(a.optJSONObject(i),"usagePercent");if(n==null)return null;if(peak==null||n>peak)peak=n;}
        return peak==null?Metrics.number(cpu,"usagePercent"):peak;
    }
    static String description(int type,State state){
        if(type==SUMMARY){String success=state.summary==null||state.summary.attempts==0?"未提供":String.format(Locale.ROOT,"%.1f%%",100d*state.summary.success/state.summary.attempts);return "全部节点，"+state.onlineHosts+" 台在线，"+state.activeAlerts+" 条告警，"+state.offlineHosts+" 台离线，采样成功率 "+success;}
        String name=state.host==null?"未选择主机":state.host.optString("name","监控主机");
        if(!state.online)return name+"，无最新监控数据";
        StringBuilder out=new StringBuilder(name).append(state.stale?"，缓存快照":"，在线");
        Double peak=cpuPeak(state);out.append("，CPU ").append(peak==null?"未提供":String.format(Locale.ROOT,"%.0f%%",peak));
        if(type!=CIRCLE){JSONArray gpus=state.snapshot==null?null:state.snapshot.optJSONArray("gpus");if(gpus!=null)for(int i=0;i<Math.min(2,gpus.length());i++){JSONObject gpu=Metrics.freshGpu(state.snapshot,gpus.optJSONObject(i));out.append("，GPU ").append(i+1).append(" 使用率 ").append(Metrics.percent(gpu,"usagePercent")).append("，温度 ").append(Metrics.temp(gpu,"temperatureCelsius"));}out.append("，内存 ").append(Metrics.percent(object(state.snapshot,"memory"),"usagePercent"));}
        out.append("，").append(state.activeAlerts).append(" 条告警");return out.toString();
    }
    private static JSONObject object(JSONObject o,String key){return o==null?null:o.optJSONObject(key);}
    private JSONArray array(String key){return state.snapshot==null?null:state.snapshot.optJSONArray(key);}
    private Double number(JSONObject o,String key){return state.online?Metrics.number(o,key):null;}
    private String name(){return state.host==null?"选择监控主机":state.host.optString("name","监控主机");}
    private String sampleTime(){return state.snapshot==null?"—":Metrics.sampleTime(state.snapshot).replaceAll(":\\d{2}$","");}
    private String connection(){return state.host!=null&&state.host.optString("mode").equals("tailnet")?"Tailscale":"直连";}
    private String cpuCaption(){JSONObject c=object(state.snapshot,"cpu");JSONArray a=c==null?null:c.optJSONArray("packages");return a!=null&&a.length()>1?"CPU 峰值":"CPU 使用率";}
    private String pct(Double n){return n==null?"—":String.format(Locale.ROOT,"%.0f%%",n);}
    private String temp(JSONObject g){Double n=number(g,"temperatureCelsius");return n==null?"—":String.format(Locale.ROOT,"%.0f°C",n);}
    private String size(JSONObject o,String key){Double n=number(o,key);return n==null?"—":String.format(Locale.ROOT,"%.1f",n/1073741824d);}
    private Double memory(){return number(object(state.snapshot,"memory"),"usagePercent");}
    private Double disk(){
        if(!state.online)return null;JSONArray disks=array("disks");double total=0,used=0;
        if(disks!=null)for(int i=0;i<disks.length();i++){JSONObject d=disks.optJSONObject(i);Double t=Metrics.number(d,"totalBytes"),u=Metrics.number(d,"usedBytes");if(t!=null&&u!=null&&t>0){total+=t;used+=u;}}
        return total==0?null:100*used/total;
    }
    private JSONObject cpu(int index){JSONObject cpu=object(state.snapshot,"cpu");JSONArray a=cpu==null?null:cpu.optJSONArray("packages");return a!=null&&a.length()>0?a.optJSONObject(index):index==0?cpu:null;}
    private JSONObject gpu(int index){JSONArray a=array("gpus");return a==null?null:Metrics.freshGpu(state.snapshot,a.optJSONObject(index));}
    private int statusColor(){return state.host==null?muted:!state.online?ERROR:state.activeAlerts>0?WARNING:state.stale?muted:accent;}
    private int alpha(int color,int alpha){return(color&0x00ffffff)|(alpha<<24);}

    private void panel(boolean global){
        p.setStyle(Paint.Style.FILL);p.setColor(alpha(surface,238));canvas.drawRoundRect(0,0,w,h,23,23,p);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(.7f);p.setColor(alpha(Color.WHITE,105));canvas.drawRoundRect(.5f,.5f,w-.5f,h-.5f,22.5f,22.5f,p);p.setStyle(Paint.Style.FILL);
        boolean disconnected=global?state.offlineHosts>0:state.host!=null&&!state.online;
        if(disconnected||state.activeAlerts>0){p.setColor(disconnected?ERROR:WARNING);Path edge=new Path();edge.moveTo(20,0);edge.lineTo(w-20,0);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2.5f);p.setStrokeCap(Paint.Cap.ROUND);canvas.drawPath(edge,p);p.setStyle(Paint.Style.FILL);}
    }
    private void text(String s,float x,float baseline,float size,int color,boolean bold,float maxWidth){
        if(s==null)s="";p.setShader(null);p.setColor(color);p.setTypeface(bold?MEDIUM:SANS);p.setTextSize(size);p.setTextAlign(Paint.Align.LEFT);p.setStyle(Paint.Style.FILL);
        if(maxWidth>0&&p.measureText(s)>maxWidth){String ellipsis="…";int end=p.breakText(s,true,Math.max(0,maxWidth-p.measureText(ellipsis)),null);s=s.substring(0,Math.min(s.length(),end))+ellipsis;}
        canvas.drawText(s,x,baseline,p);
    }
    private void value(String s,float x,float baseline,float size,int color){p.setColor(color);p.setTypeface(MONO);p.setTextSize(size);p.setTextAlign(Paint.Align.LEFT);p.setStyle(Paint.Style.FILL);canvas.drawText(s,x,baseline,p);}
    private void right(String s,float x,float y,float size,int color){p.setTypeface(MONO);p.setTextSize(size);float width=p.measureText(s);value(s,x-width,y,size,color);}
    private void line(float x,float y,float right){p.setColor(track);p.setStrokeWidth(.7f);canvas.drawLine(x,y,right,y,p);}
    private void dot(float x,float y,int color){p.setColor(alpha(color,35));canvas.drawCircle(x,y,6.5f,p);p.setColor(color);canvas.drawCircle(x,y,3.1f,p);}
    private void header(boolean logo){header(logo,w-(logo?75:107));}
    private void header(boolean logo,float nameWidth){
        dot(16,17,statusColor());text(name(),28,22,12.5f,ink,true,nameWidth);
        if(logo)logo(w-33,9);else right(sampleTime(),w-13,21,9,muted);
    }
    private void logo(float x,float y){
        p.setColor(accent);canvas.drawRoundRect(x,y,x+22,y+22,6,6,p);p.setStyle(Paint.Style.STROKE);p.setColor(Color.WHITE);p.setStrokeWidth(1.1f);canvas.drawRoundRect(x+5,y+5,x+17,y+10,1.5f,1.5f,p);canvas.drawRoundRect(x+5,y+12,x+17,y+17,1.5f,1.5f,p);canvas.drawLine(x+8,y+7.5f,x+9,y+7.5f,p);canvas.drawLine(x+8,y+14.5f,x+9,y+14.5f,p);p.setStyle(Paint.Style.FILL);
    }
    private void bar(float x,float y,float width,float height,Double value,int color){
        p.setColor(track);canvas.drawRoundRect(x,y,x+width,y+height,height/2,height/2,p);
        if(value!=null&&value>0){float end=x+width*(float)Math.max(0,Math.min(100,value))/100;
            p.setShader(new LinearGradient(x,y,x+width,y,color,alpha(color,190),Shader.TileMode.CLAMP));canvas.drawRoundRect(x,y,Math.max(x+height,end),y+height,height/2,height/2,p);p.setShader(null);}
    }

    private void compact(){
        header(true);float pad=14,cell=(w-pad*2-18)/4;float footer=h-25;float chartTop=h*.58f,chartBottom=h-42;
        float labelX=Math.min(w-59,Math.max(80,w*.60f));
        Double peak=cpuPeak(state);value(pct(peak),pad,Math.min(h*.40f,75),Math.min(35,w*.19f),state.online?ink:muted);text(state.online?cpuCaption():state.stale?"缓存快照":"未连接",labelX,Math.min(h*.40f-1,74),9.5f,state.online?muted:ERROR,false,w-pad-labelX);
        for(int i=0;i<4;i++){
            JSONObject metric=i<2?cpu(i):gpu(i-2);Double n=number(metric,"usagePercent");float x=pad+i*(cell+6);
            value(n==null?"—":String.format(Locale.ROOT,"%.0f",n),x,chartTop-7,11.5f,n==null?muted:ink);
            p.setColor(track);canvas.drawRoundRect(x,chartTop,x+cell,chartBottom,5,5,p);
            if(n!=null&&n>0){float fill=Math.max(2,(chartBottom-chartTop)*(float)Math.min(100,n)/100);p.setShader(new LinearGradient(x,chartBottom-fill,x,chartBottom,accent,alpha(accent,190),Shader.TileMode.CLAMP));canvas.drawRoundRect(x,chartBottom-fill,x+cell,chartBottom,5,5,p);p.setShader(null);}else if(n!=null){p.setColor(muted);canvas.drawRoundRect(x,chartBottom-1.5f,x+cell,chartBottom,1,1,p);}
            text((i<2?"CPU":"GPU")+(i%2+1),x,chartBottom+12,8.5f,muted,false,cell+2);
        }
        line(pad,footer,w-pad);text("内存 "+pct(memory()),pad,h-13,8.5f,muted,false,w*.33f);text("磁盘 "+pct(disk()),w*.43f,h-13,8.5f,muted,false,w*.30f);right(sampleTime(),w-pad,h-13,8.5f,muted);
    }

    private void summary(){
        dot(16,17,state.totalHosts==0?muted:state.offlineHosts>0?ERROR:state.activeAlerts>0?WARNING:accent);text("全部节点",28,22,13,ink,true,w-72);logo(w-33,9);
        value(""+state.onlineHosts,14,73,39,ink);text("台在线",45+(state.onlineHosts>=10?22:0),67,11,muted,false,52);
        String status=state.totalHosts==0?"尚未添加":state.offlineHosts>0?state.offlineHosts+" 台离线":state.activeAlerts>0?"有待处理告警":state.onlineHosts<state.totalHosts?"等待采样":"全部正常";
        int color=state.offlineHosts>0?ERROR:state.activeAlerts>0?WARNING:accent;
        dot(w-65,66,color);text(status,w-56,69,9,color,false,49);
        float y=h*.57f,x1=14,x2=w*.37f,x3=w*.71f;
        text("告警",x1,y,10,muted,false,40);text("采样成功",x2,y,9.5f,muted,false,56);text("离线",x3,y,10,muted,false,40);
        value(""+state.activeAlerts,x1,y+23,14,state.activeAlerts>0?WARNING:ink);
        String success=state.summary==null||state.summary.attempts==0?"—":String.format(Locale.ROOT,"%.1f%%",100d*state.summary.success/state.summary.attempts);
        value(success,x2,y+23,13,ink);value(""+state.offlineHosts,x3,y+23,14,state.offlineHosts>0?ERROR:muted);
        line(14,h-39,w-14);text(new SimpleDateFormat("HH:mm",Locale.ROOT).format(new Date(state.now)),14,h-17,10,muted,false,60);right(state.refreshMode,w-14,h-17,8.5f,muted);
    }

    private void metricRow(String name,JSONObject data,float x,float y,float width,boolean temperature){
        Double n=number(data,"usagePercent"),heat=number(data,"temperatureCelsius");boolean hot=name.startsWith("GPU")&&heat!=null&&heat>gpuThreshold;
        text(name,x,y+3,9.3f,muted,false,38);float start=x+41,barWidth=Math.max(7,width-118);
        bar(start,y-3,barWidth,5,n,hot?WARNING:accent);right(pct(n),x+width-(temperature?41:0),y+3,9.5f,n==null?muted:ink);
        if(temperature)right(temp(data),x+width,y+3,8.6f,hot?WARNING:muted);
    }
    private float wy(float y){return 42+(y-42)*Math.min(1,(h-42)/115);}
    private void wide(){
        header(false,state.activeAlerts>0?85:w-120);float left=14,right=w-14,middle=w*.60f;
        String subtitle=state.online?(state.stale?"缓存 · 上次采样 "+sampleTime():connection()+" · 实时快照"):state.stale?"缓存 · 最近 "+sampleTime():"采集端未响应";
        text(subtitle,28,35,8.4f,state.online?muted:ERROR,false,w*.48f-28);
        if(state.activeAlerts>0){float badgeX=Math.min(w-145,121);p.setColor(alpha(WARNING,26));canvas.drawRoundRect(badgeX,8,badgeX+83,25,8,8,p);text(state.alertTitle.isEmpty()?state.activeAlerts+" 条待处理":state.alertTitle,badgeX+6,20,7.7f,WARNING,true,71);}
        text("CPU 1 / 2",left,wy(49),8.3f,muted,false,middle-left-8);metricRow("CPU1",cpu(0),left,wy(63),middle-left-10,true);metricRow("CPU2",cpu(1),left,wy(81),middle-left-10,true);
        line(left,wy(92),middle-8);text("GPU 1 / 2",left,wy(106),8.3f,muted,false,middle-left-8);metricRow("GPU1",gpu(0),left,wy(120),middle-left-10,true);metricRow("GPU2",gpu(1),left,wy(138),middle-left-10,true);
        float chartX=middle+3,chartW=right-chartX;
        text("趋势 · 1 小时",chartX,wy(49),8.2f,muted,false,chartW);drawChart(chartX,wy(55),chartW,wy(97)-wy(55),state.online);
        text("━ CPU 平均",chartX,wy(110),7.5f,accent,false,chartW*.6f);right("内存",right,wy(110),7.5f,muted);
        smallMetric("内存",memory(),chartX,wy(125),chartW);smallMetric("磁盘",disk(),chartX,wy(141),chartW);
        if(!state.online){p.setColor(alpha(surface,220));canvas.drawRoundRect(chartX,wy(53),right,wy(101),5,5,p);p.setPathEffect(new DashPathEffect(new float[]{3,3},0));p.setColor(track);p.setStrokeWidth(1);canvas.drawLine(chartX+3,wy(69),right-3,wy(69),p);p.setPathEffect(null);text("无新数据 · "+sampleTime(),chartX,wy(88),8,muted,false,chartW);}
        if(h>159)text(state.online?(state.stale?"缓存快照":"采样 "+sampleTime()):"最近采样 "+sampleTime()+" · 点击检查连接",left,h-9,7.4f,muted,false,w-28);
    }
    private void smallMetric(String name,Double value,float x,float y,float width){text(name,x,y,8.3f,muted,false,25);bar(x+28,y-5,Math.max(15,width-62),4,value,accent);right(pct(value),x+width,y,8.5f,value==null?muted:ink);}

    private void dashboard(){
        header(false);boolean dense=h<330;if(!dense)text(state.online?(connection()+" · "+(state.stale?"缓存快照":"在线")):"采集端未响应",28,35,8.8f,state.online?muted:ERROR,false,w-58);
        float pad=14,inner=w-28,half=(inner-15)/2;
        text("CPU 1 / 2 · 负载均值 "+load(),pad,dense?39:50,8.8f,muted,false,inner);
        metricRow("CPU1",cpu(0),pad,dense?55:66,half,true);metricRow("CPU2",cpu(1),pad+half+15,dense?55:66,half,true);
        float chartTop=dense?80:96,chartBottom=dense?chartTop+Math.max(30,(h-280)*.5f+30):Math.max(137,h*.47f);text("使用率趋势 · 1 小时",pad,dense?72:87,9,muted,false,inner/2);right("CPU 平均 / 内存",w-pad,dense?72:87,8,muted);
        drawChart(pad,chartTop,inner,chartBottom-chartTop,state.online);
        float gpuTop=chartBottom+(dense?18:27);line(pad,chartBottom+(dense?6:11),w-pad);text("GPU 1 / 2 · 显存各自独立",pad,gpuTop-4,8.8f,muted,false,inner);
        float gpuHeight=Math.max(56,h-gpuTop-103);gpuCard(gpu(0),"GPU1",pad,gpuTop+7,half,gpuHeight);gpuCard(gpu(1),"GPU2",pad+half+15,gpuTop+7,half,gpuHeight);
        float noticeY=h-81;int color=!state.online?ERROR:state.activeAlerts>0?WARNING:accent;
        p.setColor(alpha(color,25));canvas.drawRoundRect(pad,noticeY,w-pad,noticeY+22,8,8,p);
        String notice=!state.online?"采集端未响应 · 点击检查连接":state.activeAlerts>0?(state.alertTitle.isEmpty()?state.activeAlerts+" 条告警待处理":state.alertTitle):"所有指标正常 · 内存 "+pct(memory())+" · 磁盘 "+pct(disk());
        text(notice,pad+9,noticeY+14,8.8f,color,false,inner-18);
        if(state.muted)text("系统通知已暂时静音",pad,h-48,7.5f,muted,false,inner);
    }
    private String load(){JSONArray a=object(state.snapshot,"cpu")==null?null:object(state.snapshot,"cpu").optJSONArray("loadAverage");return !state.online||a==null||a.length()==0?"—":String.format(Locale.ROOT,"%.2f",a.optDouble(0));}
    private void gpuCard(JSONObject gpu,String name,float x,float y,float width,float height){
        p.setColor(alpha(track,80));canvas.drawRoundRect(x,y,x+width,y+height,10,10,p);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(.7f);p.setColor(track);canvas.drawRoundRect(x,y,x+width,y+height,10,10,p);p.setStyle(Paint.Style.FILL);
        text(name,x+10,y+16,10,ink,true,width-55);String temp=temp(gpu);Double heat=number(gpu,"temperatureCelsius");int temperatureColor=heat!=null&&heat>gpuThreshold?WARNING:accent;p.setColor(heat!=null&&heat>gpuThreshold?alpha(WARNING,24):pale);canvas.drawRoundRect(x+width-43,y+6,x+width-8,y+23,5,5,p);right(temp,x+width-13,y+18,8.6f,temperatureColor);
        value(pct(number(gpu,"usagePercent")),x+10,y+36,19,state.online?ink:muted);text(size(gpu,"memoryUsedBytes")+" / "+size(gpu,"memoryTotalBytes")+" GiB 显存",x+10,y+height-9,8,muted,false,width-20);
    }

    private void rectangle(){
        header(false);Double peak=cpuPeak(state);float baseline=Math.max(54,h*.61f);value(pct(peak),15,baseline,29,state.online?ink:muted);text(cpuCaption(),88,baseline-2,12,muted,false,w-102);
        String detail=state.online?"GPU "+pct(number(gpu(0),"usagePercent"))+" / "+pct(number(gpu(1),"usagePercent")):state.stale?"缓存 · 上次采样 "+sampleTime():"采集端未响应";
        text(detail,15,h-13,10.5f,state.online?muted:ERROR,false,w-30);
    }
    private void circle(){
        float size=Math.min(w,h),cx=w/2,cy=h/2,r=size/2-16;
        p.setColor(alpha(surface,238));canvas.drawCircle(cx,cy,size/2-1,p);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(Math.max(8,size*.065f));p.setColor(track);canvas.drawCircle(cx,cy,r,p);
        Double peak=cpuPeak(state);if(peak!=null&&peak>0){p.setColor(state.activeAlerts>0?WARNING:accent);p.setStrokeCap(Paint.Cap.ROUND);canvas.drawArc(new RectF(cx-r,cy-r,cx+r,cy+r),-90,(float)Math.min(100,peak)*3.6f,false,p);}
        p.setStyle(Paint.Style.FILL);String value=pct(peak);p.setTypeface(MONO);p.setTextSize(size*.23f);p.setColor(state.online?ink:muted);p.setTextAlign(Paint.Align.CENTER);canvas.drawText(value,cx,cy+size*.045f,p);p.setTypeface(SANS);p.setTextSize(size*.105f);p.setColor(muted);canvas.drawText(state.online?cpuCaption():"未连接",cx,cy+size*.23f,p);p.setTextAlign(Paint.Align.LEFT);
    }

    private void drawChart(float x,float y,float width,float height,boolean online){
        for(int i=0;i<3;i++){p.setColor(track);p.setStrokeWidth(.6f);canvas.drawLine(x,y+height*i/2,x+width,y+height*i/2,p);}
        List<MonitorDatabase.Point> points=state.history==null?Collections.emptyList():state.history;
        if(points.isEmpty()){text("等待采样记录",x+4,y+height*.58f,8.5f,muted,false,width-8);return;}
        for(int series=1;series>=0;series--){Path segment=new Path();boolean started=false;long last=0;float firstX=0,lastX=0,lastY=0;int count=0;
            for(MonitorDatabase.Point point:points){Double number=series==0?point.cpu:point.memory;long stamp=point.time;
                if(number==null||stamp<state.now-3600000L||stamp>state.now){if(started){chartSegment(segment,firstX,lastX,y+height,series,online,count);started=false;count=0;}continue;}
                float px=x+Math.max(0,Math.min(1,(stamp-(state.now-3600000L))/3600000f))*width,py=y+height-(float)Math.max(0,Math.min(100,number))*height/100;
                if(started&&(point.breakBefore||stamp-last>point.gap)){chartSegment(segment,firstX,lastX,y+height,series,online,count);started=false;count=0;}
                if(!started){segment=new Path();segment.moveTo(px,py);firstX=px;started=true;}else segment.lineTo(px,py);
                last=stamp;lastX=px;lastY=py;count++;
            }
            if(started){chartSegment(segment,firstX,lastX,y+height,series,online,count);if(series==0){p.setColor(online?accent:muted);canvas.drawCircle(lastX,lastY,2,p);}}
        }
    }
    private void chartSegment(Path path,float firstX,float lastX,float bottom,int series,boolean online,int count){
        int color=online?(series==0?accent:muted):muted;
        if(series==0&&count>1){Path area=new Path(path);area.lineTo(lastX,bottom);area.lineTo(firstX,bottom);area.close();p.setColor(alpha(color,22));p.setStyle(Paint.Style.FILL);canvas.drawPath(area,p);}
        p.setColor(color);p.setStrokeWidth(series==0?1.6f:1f);p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);canvas.drawPath(path,p);p.setStyle(Paint.Style.FILL);
    }
}
