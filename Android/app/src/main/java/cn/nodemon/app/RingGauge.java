package cn.nodemon.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;
import java.util.function.BooleanSupplier;

/** An understated foreground-only gauge. The displayed value always comes from a sample. */
final class RingGauge extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Double target;
    private final double start;
    private final long started = SystemClock.uptimeMillis();
    private final boolean dark;
    private final BooleanSupplier active;
    private final float density, fontScale;
    private final DashPathEffect orbit;
    private int lastNumber = Integer.MIN_VALUE;
    private String lastText = "—";

    RingGauge(Context context, Double value, Double previous, boolean isDark, BooleanSupplier isActive) {
        super(context);
        target = value;
        start = previous == null ? (value == null ? 0 : value) : previous;
        dark = isDark;
        active = isActive;
        density = getResources().getDisplayMetrics().density;
        fontScale = getResources().getDisplayMetrics().scaledDensity;
        orbit = new DashPathEffect(new float[]{2 * density, 2.5f * density}, 0);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float outer = Math.min(cx, cy) - 3 * density;
        float radius = outer - 6 * density;
        long now = SystemClock.uptimeMillis();
        boolean animate = ValueAnimator.areAnimatorsEnabled() && active.getAsBoolean();
        double t = animate ? Math.min(1, (now - started) / 650d) : 1;
        t = 1 - Math.pow(1 - t, 3);
        double current = target == null ? 0 : start + (target - start) * t;
        float progress = (float) Math.max(0, Math.min(100, current));

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(density);
        paint.setColor(dark ? 0xff465248 : 0xffdce5d8);
        paint.setPathEffect(orbit);
        canvas.drawCircle(cx, cy, outer, paint);
        paint.setPathEffect(null);
        paint.setStrokeWidth(3.5f * density);
        paint.setColor(dark ? 0xff3e4940 : 0xffe8ede5);
        canvas.drawCircle(cx, cy, radius, paint);
        if (target != null && progress > 0) {
            float pulse = animate ? (float) (0.5 + 0.5 * Math.sin(now / 650d)) : 0.5f;
            paint.setColor(dark ? Color.rgb(143 + (int)(12 * pulse), 183 + (int)(10 * pulse), 112) : Color.rgb(49 + (int)(13 * pulse), 82 + (int)(14 * pulse), 51));
            paint.setStrokeWidth((3.5f + 0.65f * pulse) * density);
            paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawArc(cx-radius, cy-radius, cx+radius, cy+radius, -90, 3.6f * progress, false, paint);
        }
        float angle = animate ? (float) ((now % 14000L) * Math.PI * 2 / 14000d - Math.PI / 2) : (float)-Math.PI/2;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xffa6d957);
        canvas.drawCircle(cx + (float)Math.cos(angle)*outer, cy + (float)Math.sin(angle)*outer, 2.5f*density, paint);
        paint.setColor(dark ? 0xffedf3eb : 0xff202820);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextSize(10.5f * fontScale);
        paint.setTextAlign(Paint.Align.CENTER);
        int number = target == null ? Integer.MIN_VALUE : (int)Math.round(current);
        if (number != lastNumber) { lastNumber = number; lastText = number == Integer.MIN_VALUE ? "—" : number + "%"; }
        canvas.drawText(lastText, cx, cy - (paint.ascent() + paint.descent()) / 2, paint);
        if (animate && isShown() && getWindowVisibility() == VISIBLE) postInvalidateDelayed(100);
    }
}
