package com.harlequinxd.eryndor;

/** World-building progress, written by the GL thread and read by the loading screen. */
final class Loading {
    private Loading() {}

    static volatile float progress;
    static volatile String stage = "Waking the realm";
    /** Told about every stage (the phone keeps a breadcrumb of it, for crash reports). */
    static volatile java.util.function.Consumer<String> listener;
    /** Set when something failed: the loading screen shows it instead of the bar. */
    static volatile String error;

    /** Report a stage and the overall fraction done (0..1). */
    static void set(String what, float p) {
        stage = what;
        java.util.function.Consumer<String> l = listener;
        if (l != null) l.accept(what);
        progress = Math.max(progress, Math.min(1f, p));
    }

    /** Progress within a stage that spans [from, to] of the whole bar. */
    static void within(float from, float to, float frac) {
        progress = Math.max(progress, from + (to - from) * Math.max(0f, Math.min(1f, frac)));
    }

    /** GPU name and limits, for crash reports. */
    static volatile String gpu = "";

    /** Something failed: keep the app alive and put the error on the loading screen. */
    static void fail(String where, Throwable e) {
        java.io.StringWriter sw = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(sw));
        String st = sw.toString();
        if (st.length() > 2500) st = st.substring(0, 2500);
        error = where + " during \"" + stage + "\":\n" + st + (gpu.isEmpty() ? "" : "\n" + gpu);
        java.util.function.Consumer<String> l = listener;
        if (l != null) l.accept("ok");          // shown now; don't report it again next launch
    }

    /** The game has run a few frames: the last launch didn't crash while loading. */
    static void running() {
        java.util.function.Consumer<String> l = listener;
        if (l != null) l.accept("ok");
    }
}
