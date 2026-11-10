package com.harlequinxd.eryndor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/**
 * Touch overlay: floating touch pad on the left 45% of the screen to walk, swipe anywhere else to
 * look. Two small buttons (top right) cycle the weather and toggle fast time for testing.
 */
final class ControlsView extends View {

    private final Input input;
    private final SkyState sky;
    private final float dp;

    private int moveId = -1, lookId = -1;
    private float baseX, baseY, knobX, knobY, lastX, lastY;

    private final RectF btnWeather = new RectF(), btnTime = new RectF();
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btn = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);

    ControlsView(Context ctx, Input input, SkyState sky) {
        super(ctx);
        this.input = input;
        this.sky = sky;
        dp = ctx.getResources().getDisplayMetrics().density;

        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2.5f * dp);
        ring.setColor(Color.argb(110, 255, 240, 220));
        knob.setColor(Color.argb(120, 255, 240, 220));
        btn.setColor(Color.argb(120, 30, 20, 12));
        text.setColor(Color.argb(230, 255, 236, 205));
        text.setTextSize(13 * dp);
        text.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        hint.setColor(Color.argb(70, 255, 240, 220));
        hint.setTextSize(11 * dp);
        hint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        hint.setTextAlign(Paint.Align.CENTER);
    }

    private float padRadius() {
        return 62 * dp;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float bw = 128 * dp, bh = 34 * dp, m = 12 * dp;
        btnWeather.set(w - m - bw, m, w - m, m + bh);
        btnTime.set(w - 2 * m - 2 * bw, m, w - 2 * m - bw, m + bh);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int id = e.getPointerId(idx);
                float x = e.getX(idx), y = e.getY(idx);
                if (btnWeather.contains(x, y)) { sky.requestNextWeather(); break; }
                if (btnTime.contains(x, y)) { sky.fastTime = !sky.fastTime; break; }
                if (x < getWidth() * 0.45f && moveId == -1) {
                    moveId = id;
                    baseX = knobX = x;
                    baseY = knobY = y;
                } else if (lookId == -1) {
                    lookId = id;
                    lastX = x;
                    lastY = y;
                }
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int id = e.getPointerId(i);
                    float x = e.getX(i), y = e.getY(i);
                    if (id == moveId) {
                        float dx = x - baseX, dy = y - baseY, r = padRadius();
                        float len = (float) Math.sqrt(dx * dx + dy * dy);
                        if (len > r) {          // drag the pad along so it never feels stuck
                            baseX = x - dx / len * r;
                            baseY = y - dy / len * r;
                            dx = x - baseX;
                            dy = y - baseY;
                        }
                        knobX = x;
                        knobY = y;
                        float dead = 0.12f;
                        float mx = dx / r, my = -dy / r;
                        float m = (float) Math.sqrt(mx * mx + my * my);
                        if (m < dead) { mx = 0; my = 0; }
                        else { float k = (m - dead) / (1f - dead) / m; mx *= k; my *= k; }
                        input.moveX = mx;
                        input.moveY = my;
                    } else if (id == lookId) {
                        input.addLook((x - lastX) / dp, (y - lastY) / dp);
                        lastX = x;
                        lastY = y;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                release(e.getPointerId(idx));
                break;
            case MotionEvent.ACTION_CANCEL:
                release(moveId);
                release(lookId);
                break;
            default:
                break;
        }
        invalidate();
        return true;
    }

    private void release(int id) {
        if (id == -1) return;
        if (id == moveId) {
            moveId = -1;
            input.moveX = 0;
            input.moveY = 0;
        }
        if (id == lookId) lookId = -1;
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();

        if (!sky.worldReady) {
            c.drawColor(Color.rgb(10, 12, 18));
            drawTitle(c, w, h, 255);
            Paint p = new Paint(hint);
            p.setColor(Color.argb(170, 200, 210, 220));
            p.setTextSize(13 * dp);
            c.drawText("Painting the realm…", w / 2f, h * 0.78f, p);
            postInvalidateDelayed(200);
            return;
        }
        if (readyAt == 0) readyAt = System.currentTimeMillis();
        float since = (System.currentTimeMillis() - readyAt) / 1000f;
        if (since < 6f) {                       // title lingers over the scene, then fades
            int alpha = (int) (255 * Math.max(0f, Math.min(1f, (6f - since) / 2f)));
            drawTitle(c, w, h, alpha);
            postInvalidateDelayed(16);
        }

        float r = padRadius();
        if (moveId != -1) {
            c.drawCircle(baseX, baseY, r, ring);
            c.drawCircle(knobX, knobY, 26 * dp, knob);
        } else {
            float hx = 34 * dp + r, hy = h - 30 * dp - r;
            c.drawCircle(hx, hy, r, hintRing());
            c.drawText("WALK", hx, hy + 4 * dp, hint);
            c.drawText("SWIPE TO LOOK", w * 0.75f, h - 26 * dp, hint);
        }

        // buttons + clock
        int wi = sky.weather;
        drawButton(c, btnWeather, SkyState.WEATHER_NAMES[wi]);
        drawButton(c, btnTime, sky.fastTime ? "Time ×20" : "Time ×1");
        float hr = sky.hour;
        int hh = (int) hr, mm = (int) ((hr - hh) * 60f);
        String clock = String.format(Locale.US, "%02d:%02d", hh, mm);
        text.setTextAlign(Paint.Align.RIGHT);
        c.drawText(clock, btnTime.left - 12 * dp, btnTime.centerY() + 5 * dp, text);

        postInvalidateDelayed(moveId != -1 || lookId != -1 ? 16 : 500);
    }

    private long readyAt;
    private Paint titlePaint, subPaint, linePaint;

    private void drawTitle(Canvas c, int w, int h, int alpha) {
        if (titlePaint == null) {
            titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            titlePaint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
            titlePaint.setTextAlign(Paint.Align.CENTER);
            titlePaint.setTextSize(34 * dp);
            titlePaint.setLetterSpacing(0.06f);
            subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            subPaint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.ITALIC));
            subPaint.setTextAlign(Paint.Align.CENTER);
            subPaint.setTextSize(20 * dp);
            subPaint.setLetterSpacing(0.35f);
            linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            linePaint.setStrokeWidth(1.2f * dp);
        }
        float cy = h * 0.40f;
        titlePaint.setShadowLayer(6 * dp, 0, 2 * dp, Color.argb(alpha, 0, 0, 0));
        titlePaint.setColor(Color.argb(alpha, 236, 205, 130));
        c.drawText("Chronicles of Eryndor", w / 2f, cy, titlePaint);
        linePaint.setColor(Color.argb(alpha * 3 / 4, 236, 205, 130));
        float lw = 110 * dp, ly = cy + 16 * dp;
        c.drawLine(w / 2f - lw, ly, w / 2f - 14 * dp, ly, linePaint);
        c.drawLine(w / 2f + 14 * dp, ly, w / 2f + lw, ly, linePaint);
        c.drawCircle(w / 2f, ly, 3 * dp, linePaint);
        subPaint.setShadowLayer(5 * dp, 0, 2 * dp, Color.argb(alpha, 0, 0, 0));
        subPaint.setColor(Color.argb(alpha, 190, 215, 220));
        c.drawText("HOLLOWSPIRE", w / 2f, cy + 48 * dp, subPaint);
    }

    private Paint hintRingPaint;

    private Paint hintRing() {
        if (hintRingPaint == null) {
            hintRingPaint = new Paint(ring);
            hintRingPaint.setColor(Color.argb(45, 255, 240, 220));
        }
        return hintRingPaint;
    }

    private void drawButton(Canvas c, RectF r, String label) {
        c.drawRoundRect(r, 8 * dp, 8 * dp, btn);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText(label, r.centerX(), r.centerY() + 5 * dp, text);
    }
}
