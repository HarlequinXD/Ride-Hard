package com.harlequinxd.eryndor;

/**
 * The far land: a coarse map of the land out to 2 km each way, so the ground no longer ends where
 * the detailed 512 m window does. 256 x 256 texels of 16 m around you, each RGBA:
 * r = ground height (0..HEIGHT_MAX), g = woodland (raised as a canopy), b = river water,
 * a = conifer (darker woods). Rebuilt on a background thread whenever you have walked 512 m.
 */
final class FarLand {
    static final int N = 256;
    static final float CELL = 16f;
    static final float SIZE = N * CELL;                  // 4096 m
    private static final float SNAP = 512f;

    /** Where the texture on the GPU is (its corner); NaN before the first upload. */
    double cornerX = Double.NaN, cornerZ = Double.NaN;
    private volatile byte[] fresh;
    private volatile double freshX, freshZ;
    private double wantX = Double.NaN, wantZ = Double.NaN;
    private volatile boolean busy;

    /** Build the map around a point right now (loading). */
    void buildNow(double px, double pz) {
        wantX = snap(px) - SIZE / 2;
        wantZ = snap(pz) - SIZE / 2;
        fresh = build(wantX, wantZ);
        freshX = wantX;
        freshZ = wantZ;
    }

    /** Start a rebuild in the background if you have moved far enough. */
    void update(double px, double pz) {
        double cx = snap(px) - SIZE / 2, cz = snap(pz) - SIZE / 2;
        if (busy || (cx == wantX && cz == wantZ)) return;
        wantX = cx;
        wantZ = cz;
        busy = true;
        Thread t = new Thread(() -> {
            try {
                byte[] b = build(cx, cz);
                freshX = cx;
                freshZ = cz;
                fresh = b;
            } finally {
                busy = false;
            }
        }, "far-land");
        t.setPriority(Thread.MIN_PRIORITY);
        t.setDaemon(true);
        t.start();
    }

    /** A finished map waiting for upload (cleared once taken), or null. */
    byte[] take() {
        byte[] b = fresh;
        if (b == null) return null;
        fresh = null;
        cornerX = freshX;
        cornerZ = freshZ;
        return b;
    }

    private static double snap(double v) {
        return Math.floor(v / SNAP + 0.5) * SNAP;
    }

    static byte[] build(double x0, double z0) {
        byte[] b = new byte[N * N * 4];
        float hm = WorldTextures.HEIGHT_MAX;
        for (int j = 0; j < N; j++) {
            float wz = (float) (z0 + (j + 0.5) * CELL);
            for (int i = 0; i < N; i++) {
                float wx = (float) (x0 + (i + 0.5) * CELL);
                float h = WorldTextures.terrainAt(wx, wz);
                float f = WorldTextures.forestAt(wx, wz);
                float wood = Math.max(0f, Math.min(1f, (f - 0.52f) / 0.15f));
                float water = Math.abs(WorldTextures.riverOff(wx, wz)) < WorldTextures.riverHalfWidth(wx) + 6f ? 1f : 0f;
                if (water > 0f) wood = 0f;
                int o = (j * N + i) * 4;
                b[o] = (byte) Math.round(Math.max(0f, Math.min(1f, h / hm)) * 255f);
                b[o + 1] = (byte) Math.round(wood * 255f);
                b[o + 2] = (byte) (water > 0f ? 255 : 0);
                b[o + 3] = (byte) (WorldTextures.coniferAt(wx, wz) ? 255 : 0);
            }
        }
        return b;
    }
}
