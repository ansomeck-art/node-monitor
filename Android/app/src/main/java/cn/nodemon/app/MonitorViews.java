package cn.nodemon.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class MonitorViews {
    static boolean motion(){return ValueAnimator.areAnimatorsEnabled();}
    abstract static class Drawing extends View {
        final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);final float density;
        ValueAnimator animation;
        Drawing(Context c){super(c);density=getResources().getDisplayMetrics().density;}
        float d(float n){return density*n;}
        void animateValue(long duration,java.util.function.Consumer<Float> update){if(!motion()){update.accept(1f);return;}animation=ValueAnimator.ofFloat(0,1);animation.setDuration(duration);animation.setInterpolator(new DecelerateInterpolator());animation.addUpdateListener(a->{update.accept((float)a.getAnimatedValue());invalidate();});animation.start();}
        @Override protected void onDetachedFromWindow(){if(animation!=null)animation.cancel();super.onDetachedFromWindow();}
    }
    static final class Progress extends Drawing {
        final int accent,track;final Double value;float shown;
        Progress(Context c,Double value,Double before,int accent,int track){super(c);this.value=value;this.accent=accent;this.track=track;float end=value==null?0:Math.max(0,Math.min(100,value.floatValue()));float start=before==null?end:Math.max(0,Math.min(100,before.floatValue()));shown=start;if(start!=end)animateValue(650,f->shown=start+(end-start)*f);setContentDescription(value==null?"未提供读数":String.format(Locale.ROOT,"%.1f%%",value));}
        @Override protected void onDraw(Canvas c){float h=getHeight(),r=h/2; p.setColor(track);c.drawRoundRect(0,0,getWidth(),h,r,r,p);if(value!=null&&shown>0){p.setShader(new LinearGradient(0,0,getWidth(),0,accent,(accent&0xffffff)|0xcc000000,Shader.TileMode.CLAMP));c.drawRoundRect(0,0,Math.max(h,getWidth()*shown/100),h,r,r,p);p.setShader(null);}}
    }
    static final class Dot extends Drawing {
        final int color;final boolean live;float pulse;
        Dot(Context c,int color,boolean live){super(c);this.color=color;this.live=live;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        private void run(){if(animation!=null){animation.cancel();animation=null;}if(!live||getWindowVisibility()!=VISIBLE||!motion())return;animation=ValueAnimator.ofFloat(0,1);animation.setDuration(1800);animation.setRepeatCount(ValueAnimator.INFINITE);animation.setRepeatMode(ValueAnimator.REVERSE);animation.addUpdateListener(a->{pulse=(float)a.getAnimatedValue();invalidate();});animation.start();}
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();run();}
        @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(visibility==VISIBLE)run();else if(animation!=null)animation.cancel();}
        @Override protected void onDraw(Canvas c){float x=getWidth()/2f,y=getHeight()/2f;p.setColor(color);p.setAlpha(live?(int)(25+25*pulse):20);c.drawCircle(x,y,d(5+1.1f*pulse),p);p.setAlpha(255);c.drawCircle(x,y,d(3.1f),p);}
    }
    static final class Icon extends Drawing {
        final String kind;final int color;
        Icon(Context c,String kind,int color){super(c);this.kind=kind;this.color=color;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        private void line(Canvas c,float... xy){Path path=new Path();path.moveTo(xy[0],xy[1]);for(int i=2;i<xy.length;i+=2)path.lineTo(xy[i],xy[i+1]);c.drawPath(path,p);}
        private void box(Canvas c,float x,float y,float w,float h,float r){c.drawRoundRect(x,y,x+w,y+h,r,r,p);}
        @Override protected void onDraw(Canvas c){c.save();float scale=Math.min(getWidth(),getHeight())/24f;c.translate((getWidth()-24*scale)/2,(getHeight()-24*scale)/2);c.scale(scale,scale);p.setColor(color);p.setStrokeWidth(1.7f);p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
            switch(kind){
                case "server":box(c,3,3,18,7,2);box(c,3,14,18,7,2);line(c,7,6.5f,7.1f,6.5f);line(c,7,17.5f,7.1f,17.5f);line(c,12,6.5f,17,6.5f);line(c,12,17.5f,17,17.5f);break;
                case "bell":{Path q=new Path();q.moveTo(5,17);q.cubicTo(9,12,4,5,12,4);q.cubicTo(20,5,15,12,19,17);q.close();c.drawPath(q,p);c.drawArc(10,18,14,22,0,180,false,p);break;}
                case "gear":c.drawCircle(12,12,3,p);c.drawCircle(12,12,7,p);for(int i=0;i<8;i++){c.save();c.rotate(i*45,12,12);line(c,12,2,12,5);c.restore();}break;
                case "plus":line(c,12,5,12,19);line(c,5,12,19,12);break;
                case "back":line(c,14,6,8,12,14,18);break;
                case "chevron":line(c,9,7,14,12,9,17);break;
                case "search":c.drawCircle(10,10,6,p);line(c,15,15,21,21);break;
                case "cpu":box(c,6,6,12,12,3);box(c,9,9,6,6,1);for(int i=8;i<=16;i+=4){line(c,i,3,i,6);line(c,i,18,i,21);line(c,3,i,6,i);line(c,18,i,21,i);}break;
                case "gpu":box(c,3,6,18,12,3);c.drawCircle(10,12,3,p);line(c,16,10,18,10);line(c,16,14,18,14);break;
                case "memory":box(c,3,7,18,10,2);line(c,7,10,10,10);line(c,14,10,17,10);line(c,7,17,7,20);line(c,12,17,12,20);line(c,17,17,17,20);break;
                case "disk":box(c,4,10,16,10,2);line(c,4,10,7,4,17,4,20,10);line(c,14,15,17,15);break;
                case "grid":for(int x=4;x<=14;x+=10)for(int y=4;y<=14;y+=10)box(c,x,y,6,6,1);break;
                case "down":line(c,12,4,12,20);line(c,7,15,12,20,17,15);break;
                case "up":line(c,12,20,12,4);line(c,7,9,12,4,17,9);break;
                default:line(c,5,12,10,17,20,6);
            }p.setStyle(Paint.Style.FILL);c.restore();}
    }
    static final class Chart extends Drawing {
        final List<MonitorDatabase.Point> points;final int accent,secondary,muted,track,surface;final boolean mini;final String field;final long start,end;final int warning;
        float reveal=1;int touched=-1;
        Chart(Context c,List<MonitorDatabase.Point> points,long range,boolean mini,String field,int accent,int secondary,int muted,int track,int surface,int warning,boolean changed){super(c);this.points=points;this.mini=mini;this.field=field;this.accent=accent;this.secondary=secondary;this.muted=muted;this.track=track;this.surface=surface;this.warning=warning;end=System.currentTimeMillis();start=end-range;if(changed&&points.size()>1){reveal=0;animateValue(700,f->reveal=f);}setContentDescription((field.equals("cpu")?"CPU 与内存":"网络")+"历史趋势，"+points.size()+" 个采样点。拖动查看读数。");}
        Double value(MonitorDatabase.Point point,int series){if(field.equals("rx"))return point.rx;if(field.equals("tx"))return point.tx;return series==0?point.cpu:point.memory;}
        float x(long time,float left,float right){return left+Math.max(0,Math.min(1,(time-start)/(float)(end-start)))*(right-left);}
        @Override protected void onDraw(Canvas c){
            float left=mini?0:d(28),right=getWidth()-(mini?0:d(8)),top=mini?d(3):d(14),bottom=getHeight()-(mini?d(3):d(27));float max=100;
            if(!field.equals("cpu")){max=1;for(MonitorDatabase.Point point:points){Double v=value(point,0);if(v!=null)max=Math.max(max,v.floatValue()*1.15f);}}
            p.setTypeface(android.graphics.Typeface.create("sans-serif",0));p.setTextSize(d(9));
            if(!mini){for(int i=0;i<=4;i++){float y=bottom-(bottom-top)*i/4;p.setColor(track);p.setStrokeWidth(d(.7f));c.drawLine(left,y,right,y,p);p.setColor(muted);p.setTextAlign(Paint.Align.RIGHT);c.drawText(""+i*25,left-d(6),y+d(3),p);}p.setTextAlign(Paint.Align.LEFT);c.drawText(new SimpleDateFormat("HH:mm",Locale.ROOT).format(new Date(start)),left,bottom+d(18),p);p.setTextAlign(Paint.Align.RIGHT);c.drawText("现在",right,bottom+d(18),p);
                float y=bottom-(bottom-top)*warning/100;p.setColor(0xffc28a2b);p.setStrokeWidth(d(.8f));p.setPathEffect(new DashPathEffect(new float[]{d(3),d(4)},0));c.drawLine(left,y,right,y,p);p.setPathEffect(null);p.setTextSize(d(8));c.drawText("警告线 "+warning+"%",right,y-d(5),p);
            }
            for(int series=mini||!field.equals("cpu")?0:1;series>=0;series--){
                Path path=new Path(),area=new Path();boolean drawing=false;long previous=0;float lastX=0,lastY=0;int valid=0;int color=series==0?accent:secondary;
                for(int i=0;i<points.size();i++){
                    MonitorDatabase.Point point=points.get(i);Double value=value(point,series);
                    boolean broken=value==null||point.breakBefore||drawing&&point.time-previous>point.gap;
                    if(broken&&drawing){if(series==0)fillSegment(c,area,lastX,bottom,top,color);drawing=false;area=new Path();}
                    if(value==null)continue;
                    float px=x(point.time,left,right),py=bottom-Math.min(1,Math.max(0,value.floatValue()/max))*(bottom-top);
                    if(i==points.size()-1&&drawing&&reveal<1){px=lastX+(px-lastX)*reveal;py=lastY+(py-lastY)*reveal;}
                    if(!drawing){path.moveTo(px,py);area.moveTo(px,bottom);area.lineTo(px,py);drawing=true;}else{path.lineTo(px,py);area.lineTo(px,py);}
                    previous=point.time;lastX=px;lastY=py;valid++;
                }
                if(drawing&&series==0)fillSegment(c,area,lastX,bottom,top,color);
                if(valid>0){p.setColor(color);p.setAlpha(255);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d(series==0?1.9f:1.3f));c.drawPath(path,p);p.setStyle(Paint.Style.FILL);p.setColor(color);if(series==0)c.drawCircle(lastX,lastY,d(2.5f),p);}
            }
            if(points.isEmpty()){p.setColor(muted);p.setTextSize(d(mini?10:12));p.setTextAlign(Paint.Align.CENTER);c.drawText("等待采样记录",getWidth()/2f,(top+bottom)/2,p);}
            if(!mini && touched>=0 && touched<points.size()){MonitorDatabase.Point q=points.get(touched);float px=x(q.time,left,right);p.setColor(muted);p.setStrokeWidth(d(.7f));c.drawLine(px,top,px,bottom,p);String txt=new SimpleDateFormat("HH:mm:ss",Locale.ROOT).format(new Date(q.time))+"  CPU "+(q.cpu==null?"—":String.format(Locale.ROOT,"%.1f%%",q.cpu))+"  内存 "+(q.memory==null?"—":String.format(Locale.ROOT,"%.1f%%",q.memory));p.setTextSize(d(10));p.setTextAlign(Paint.Align.LEFT);float width=p.measureText(txt)+d(16);float bx=Math.max(left,Math.min(right-width,px-width/2));p.setColor(surface);c.drawRoundRect(bx,top,bx+width,top+d(27),d(6),d(6),p);p.setColor(accent);c.drawText(txt,bx+d(8),top+d(17),p);}
        }
        private void fillSegment(Canvas c,Path area,float lastX,float bottom,float top,int color){area.lineTo(lastX,bottom);area.close();p.setStyle(Paint.Style.FILL);p.setShader(new LinearGradient(0,top,0,bottom,color,0x00ffffff,Shader.TileMode.CLAMP));p.setAlpha(45);c.drawPath(area,p);p.setShader(null);p.setAlpha(255);}
        @Override public boolean onTouchEvent(MotionEvent event){if(mini||points.isEmpty())return super.onTouchEvent(event);if(event.getAction()==MotionEvent.ACTION_UP||event.getAction()==MotionEvent.ACTION_CANCEL){getParent().requestDisallowInterceptTouchEvent(false);performClick();return true;}getParent().requestDisallowInterceptTouchEvent(true);float left=d(28),right=getWidth()-d(8);long time=start+(long)((event.getX()-left)/(right-left)*(end-start));long best=Long.MAX_VALUE;for(int i=0;i<points.size();i++){long delta=Math.abs(points.get(i).time-time);if(delta<best){best=delta;touched=i;}}invalidate();return true;}
        @Override public boolean performClick(){super.performClick();return true;}
    }
}
