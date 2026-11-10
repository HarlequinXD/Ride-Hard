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

    private final RectF btnWeather = new RectF(), btnTime = new RectF(), btnTrees = new RectF();
    /** Opens the Tree Builder; set by MainActivity. */
    Runnable onTrees;
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
        btnTrees.set(m, m, m + 110 * dp, m + bh);
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
                if (btnTrees.contains(x, y) && sky.worldReady && onTrees != null) { onTrees.run(); break; }
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
        String err = Loading.error;
        if (err != null) {                          // something failed in this run: show it, stop
            c.drawColor(Color.rgb(10, 12, 18));
            drawReport(c, w, h, "Something went wrong (copied to the clipboard - paste it to Claude):\n" + err, err);
            postInvalidateDelayed(500);
            return;
        }

        if (!sky.worldReady) {
            drawLoading(c, w, h);
            String last = CrashLog.report;
            if (last != null) drawReport(c, w, h, "The last run crashed (copied to the clipboard - paste it to Claude):\n" + last, last);
            postInvalidateDelayed(50);
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
        drawButton(c, btnTrees, "\u2618 Trees");
        float hr = sky.hour;
        int hh = (int) hr, mm = (int) ((hr - hh) * 60f);
        String clock = String.format(Locale.US, "%02d:%02d", hh, mm);
        text.setTextAlign(Paint.Align.RIGHT);
        c.drawText(clock, btnTime.left - 12 * dp, btnTime.centerY() + 5 * dp, text);

        postInvalidateDelayed(moveId != -1 || lookId != -1 ? 16 : 500);
    }

    private Paint reportPaint, reportBg;
    private String copied;

    /** A crash report across the top of the screen, small and monospaced; copied once. */
    private void drawReport(Canvas c, int w, int h, String text, String copy) {
        if (reportPaint == null) {
            reportPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            reportPaint.setTypeface(Typeface.MONOSPACE);
            reportPaint.setTextSize(9 * dp);
            reportPaint.setColor(Color.rgb(255, 200, 170));
            reportBg = new Paint();
            reportBg.setColor(Color.argb(225, 30, 8, 8));
        }
        if (!copy.equals(copied)) {
            copied = copy;
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Eryndor crash", copy));
            } catch (Throwable ignored) { }
        }
        String[] lines = text.split("\n");
        float lh = 11 * dp;
        int max = Math.min(lines.length, (int) (h * 0.62f / lh));
        c.drawRect(0, 0, w, 8 * dp + max * lh, reportBg);
        for (int i = 0; i < max; i++) {
            String ln = lines[i].replace('\t', ' ');
            int fit = reportPaint.breakText(ln, true, w - 16 * dp, null);
            c.drawText(ln, 0, fit, 8 * dp, 4 * dp + (i + 1) * lh - 2 * dp, reportPaint);
        }
    }

    private Paint barPaint, barFill, stagePaint;
    private final RectF bar = new RectF();
    private float shownProgress;

    /** Loading screen: title, a real progress bar and what is being built right now. */
    private void drawLoading(Canvas c, int w, int h) {
        if (barPaint == null) {
            barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            barPaint.setStyle(Paint.Style.STROKE);
            barPaint.setStrokeWidth(1.5f * dp);
            barPaint.setColor(Color.argb(200, 236, 205, 130));
            barFill = new Paint(Paint.ANTI_ALIAS_FLAG);
            barFill.setColor(Color.argb(230, 214, 176, 96));
            stagePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            stagePaint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.ITALIC));
            stagePaint.setTextAlign(Paint.Align.CENTER);
            stagePaint.setTextSize(14 * dp);
            stagePaint.setColor(Color.argb(220, 200, 212, 222));
        }
        c.drawColor(Color.rgb(10, 12, 18));
        drawTitle(c, w, h, 255);
        float target = Loading.progress;
        shownProgress += (target - shownProgress) * 0.35f;          // glide, never jump
        float bw = Math.min(w * 0.55f, 420 * dp), bh = 10 * dp;
        float by = h * 0.72f;
        bar.set(w / 2f - bw / 2f, by, w / 2f + bw / 2f, by + bh);
        RectF fill = new RectF(bar.left + 2 * dp, bar.top + 2 * dp,
                bar.left + 2 * dp + (bar.width() - 4 * dp) * Math.max(0f, Math.min(1f, shownProgress)), bar.bottom - 2 * dp);
        c.drawRoundRect(fill, 3 * dp, 3 * dp, barFill);
        c.drawRoundRect(bar, 5 * dp, 5 * dp, barPaint);
        String pct = Math.round(Math.min(1f, shownProgress) * 100f) + "%";
        c.drawText(Loading.stage + "…   " + pct, w / 2f, by + bh + 26 * dp, stagePaint);
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
