package com.harlequinxd.eryndor;

/** Touch state shared between the UI thread (ControlsView) and the GL thread. */
final class Input {
    /** Virtual touch pad, -1..1. moveY > 0 = forward, moveX > 0 = strafe right. */
    volatile float moveX, moveY;

    private float lookX, lookY;

    /** Swipe deltas in dp, accumulated until the GL thread consumes them. */
    synchronized void addLook(float dx, float dy) {
        lookX += dx;
        lookY += dy;
    }

    synchronized void consumeLook(float[] out) {
        out[0] = lookX;
        out[1] = lookY;
        lookX = 0f;
        lookY = 0f;
    }
}
