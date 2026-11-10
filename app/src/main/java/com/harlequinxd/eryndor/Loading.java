package com.harlequinxd.eryndor;

/** World-building progress, written by the GL thread and read by the loading screen. */
final class Loading {
    private Loading() {}

    static volatile float progress;
    static volatile String stage = "Waking the realm";

    /** Report a stage and the overall fraction done (0..1). */
    static void set(String what, float p) {
        stage = what;
        progress = Math.max(progress, Math.min(1f, p));
    }

    /** Progress within a stage that spans [from, to] of the whole bar. */
    static void within(float from, float to, float frac) {
        progress = Math.max(progress, from + (to - from) * Math.max(0f, Math.min(1f, frac)));
    }
}
