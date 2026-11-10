package com.harlequinxd.eryndor;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tree Builder: edit the ten tree species with sliders while the game renders the tree live
 * (per pixel, exactly as in the world). Drag on the preview to orbit, pinch to zoom.
 */
final class TreeBuilderView extends FrameLayout {

    interface Host {
        void closeBuilder();
    }

    private final GameRenderer renderer;
    private final Input input;
    private final Host host;
    private final float dp;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean regrowQueued;

    private int slot = 0;
    private TreeSpec spec;
    private String group = TreeSpec.GROUPS[0];

    private final TextView nameView, status;
    private final LinearLayout rows, tabs;
    private final View touchArea;

    TreeBuilderView(Context ctx, GameRenderer renderer, Input input, Host host) {
        super(ctx);
        this.renderer = renderer;
        this.input = input;
        this.host = host;
        dp = ctx.getResources().getDisplayMetrics().density;

        // ---- left: the live preview (orbit / zoom)
        touchArea = new View(ctx);
        touchArea.setOnTouchListener(new OrbitTouch());
        addView(touchArea, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // ---- right: the editing panel
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(px(10), px(8), px(10), px(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(205, 18, 16, 14));
        bg.setCornerRadius(px(10));
        panel.setBackground(bg);
        LayoutParams plp = new LayoutParams(0, LayoutParams.MATCH_PARENT, Gravity.END);
        addView(panel, plp);
        post(() -> {
            plp.width = (int) (getWidth() * 0.48f);
            panel.setLayoutParams(plp);
        });

        LinearLayout head = row(ctx);
        head.addView(button(ctx, "◀ World", v -> close()));
        head.addView(button(ctx, "‹", v -> pick(slot - 1)));
        nameView = label(ctx, "", 16, true);
        nameView.setGravity(Gravity.CENTER);
        head.addView(nameView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(button(ctx, "›", v -> pick(slot + 1)));
        panel.addView(head);

        LinearLayout actions = row(ctx);
        actions.addView(button(ctx, "Save", v -> save()));
        actions.addView(button(ctx, "Reset", v -> reset()));
        actions.addView(button(ctx, "New seed", v -> {
            spec.set(TreeSpec.SEED, new Random().nextInt(9999));
            rebuildRows();
            regrow();
        }));
        actions.addView(button(ctx, "Copy text", v -> copy()));
        panel.addView(actions);

        HorizontalScrollView tabScroll = new HorizontalScrollView(ctx);
        tabs = row(ctx);
        tabScroll.addView(tabs);
        panel.addView(tabScroll);

        ScrollView scroll = new ScrollView(ctx);
        rows = new LinearLayout(ctx);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        panel.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        status = label(ctx, "Drag the preview to orbit, pinch to zoom.", 12, false);
        status.setTextColor(Color.argb(190, 220, 210, 190));
        panel.addView(status);

        pick(0);
    }

    // ------------------------------------------------------------------ actions
    private void pick(int s) {
        slot = (s + TreeLibrary.SLOTS) % TreeLibrary.SLOTS;
        spec = renderer.library.specs[slot].copy();
        renderer.previewSlot = slot;
        renderer.previewScreenX = -0.48f;
        renderer.orbitDist = Math.max(8f, spec.get(TreeSpec.HEIGHT) * 1.6f);
        nameView.setText((slot + 1) + ". " + spec.name);
        rebuildTabs();
        rebuildRows();
    }

    /** Phone Back button: leave the builder. */
    void back() {
        close();
    }

    private void close() {
        renderer.previewSlot = -1;
        renderer.previewScreenX = 0f;
        host.closeBuilder();
    }

    private void save() {
        renderer.library.regrow(slot, spec);
        renderer.library.save(slot);
        renderer.reshadow();
        status.setText("Saved " + spec.name + ". Trees of this kind in the world now use it.");
    }

    private void reset() {
        renderer.library.resetToPreset(slot);
        spec = renderer.library.specs[slot].copy();
        rebuildRows();
        renderer.reshadow();
        status.setText("Back to the original " + spec.name + ".");
    }

    private void copy() {
        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(spec.name, spec.toText()));
        status.setText("Copied " + spec.name + " as text (paste it into a slot file or the repo).");
    }

    /** Regrow off the UI thread; collapses bursts of slider moves into one rebuild. */
    private void regrow() {
        if (regrowQueued) return;
        regrowQueued = true;
        final TreeSpec snapshot = spec.copy();
        final int s = slot;
        worker.execute(() -> {
            renderer.library.regrow(s, snapshot);
            post(() -> {                       // back on the UI thread: catch any newer slider values
                regrowQueued = false;
                if (s == slot && !snapshot.toText().equals(spec.toText())) regrow();
            });
        });
    }

    // ------------------------------------------------------------------ UI building
    private void rebuildTabs() {
        tabs.removeAllViews();
        for (String g : TreeSpec.GROUPS) {
            Button b = button(getContext(), g, v -> {
                group = g;
                rebuildTabs();
                rebuildRows();
            });
            if (g.equals(group)) b.setTextColor(Color.rgb(236, 205, 130));
            tabs.addView(b);
        }
    }

    private void rebuildRows() {
        rows.removeAllViews();
        Context ctx = getContext();
        for (int i = 0; i < TreeSpec.PARAMS.length; i++) {
            TreeSpec.Param p = TreeSpec.PARAMS[i];
            if (!p.group.equals(group)) continue;
            final int idx = i;
            LinearLayout line = row(ctx);
            TextView name = label(ctx, p.label, 13, false);
            TextView value = label(ctx, show(idx), 13, true);
            value.setGravity(Gravity.END);
            line.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            line.addView(value);
            rows.addView(line);
            SeekBar bar = new SeekBar(ctx);
            int steps = p.whole ? Math.max(1, Math.round(p.max - p.min)) : 1000;
            bar.setMax(steps);
            bar.setProgress(Math.round((spec.get(idx) - p.min) / (p.max - p.min) * steps));
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    spec.set(idx, p.min + (p.max - p.min) * progress / (float) steps);
                    value.setText(show(idx));
                    regrow();
                }
                @Override public void onStartTrackingTouch(SeekBar sb) { }
                @Override public void onStopTrackingTouch(SeekBar sb) { }
            });
            rows.addView(bar);
        }
    }

    private String show(int i) {
        TreeSpec.Param p = TreeSpec.PARAMS[i];
        if (p.choices.length > 0) return p.choices[Math.max(0, Math.min(p.choices.length - 1, spec.whole(i)))];
        if (p.whole) return String.valueOf(spec.whole(i));
        return String.format(Locale.US, "%.2f", spec.get(i));
    }

    private LinearLayout row(Context ctx) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private TextView label(Context ctx, String text, int sp, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(Color.rgb(236, 226, 205));
        t.setTypeface(Typeface.create(Typeface.SERIF, bold ? Typeface.BOLD : Typeface.NORMAL));
        t.setPadding(px(4), px(2), px(4), px(2));
        return t;
    }

    private Button button(Context ctx, String text, View.OnClickListener l) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(Color.rgb(236, 226, 205));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(px(36));
        b.setPadding(px(10), px(4), px(10), px(4));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.argb(150, 70, 54, 36));
        g.setCornerRadius(px(8));
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(px(3), px(3), px(3), px(3));
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private int px(float v) {
        return Math.round(v * dp);
    }

    /** Drag to orbit (through the shared look input), pinch to zoom. */
    private final class OrbitTouch implements View.OnTouchListener {
        private float lastX, lastY, pinch0, dist0;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = e.getX();
                    lastY = e.getY();
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (e.getPointerCount() == 2) {
                        pinch0 = spread(e);
                        dist0 = renderer.orbitDist;
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (e.getPointerCount() >= 2 && pinch0 > 0) {
                        renderer.orbitDist = Math.max(3f, Math.min(60f, dist0 * pinch0 / Math.max(1f, spread(e))));
                    } else if (e.getPointerCount() == 1) {
                        input.addLook((e.getX() - lastX) / dp, -(e.getY() - lastY) / dp);
                        lastX = e.getX();
                        lastY = e.getY();
                    }
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    pinch0 = 0;
                    int keep = e.getActionIndex() == 0 ? 1 : 0;   // the finger still down
                    lastX = e.getX(keep);
                    lastY = e.getY(keep);
                    break;
                default:
                    break;
            }
            return true;
        }

        private float spread(MotionEvent e) {
            float dx = e.getX(0) - e.getX(1), dy = e.getY(0) - e.getY(1);
            return (float) Math.sqrt(dx * dx + dy * dy);
        }
    }
}
