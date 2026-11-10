package com.harlequinxd.eryndor;

/**
 * Leaf texture sheet: 8 shapes side by side, 128 x 128 each (1024 x 128 total). Colour channels
 * hold neutral detail (veins, edge shading, mottling, blossom centres) that the shader multiplies by
 * the species' leaf colour; alpha is the leaf's outline. Within a tile, x runs from the stalk (0) to
 * the tip (1) and y across the leaf; the shader scales the width by the shape's aspect ratio.
 */
final class LeafAtlas {
    static final int TILE = 128, COUNT = 8, W = TILE * COUNT, H = TILE;

    private LeafAtlas() {}

    /** Single-leaf aspect ratios used to draw each leaf of a sprig. */
    private static final float[] ONE_LEAF = {0.55f, 0.62f, 0.95f, 0.70f, 0.22f, 0.40f, 1.0f, 0.85f};

    static int[] build() {
        int[] px = new int[W * H];
        for (int shape = 0; shape < COUNT; shape++) {
            float asp = TreeModel.LEAF_ASPECT[shape];          // card aspect
            for (int ty = 0; ty < TILE; ty++)
                for (int tx = 0; tx < TILE; tx++) {
                    float cov = 0f, cr = 0f, cg = 0f, cb = 0f;
                    for (int sy = 0; sy < 3; sy++)
                        for (int sx = 0; sx < 3; sx++) {
                            float x = (tx + (sx + 0.5f) / 3f) / TILE;                     // 0 stem base .. 1 tip
                            float y = ((ty + (sy + 0.5f) / 3f) / TILE - 0.5f) * asp;      // physical across
                            float[] c = sprig(shape, x, y);
                            if (c != null) { cov += 1f; cr += c[0]; cg += c[1]; cb += c[2]; }
                        }
                    int a = Math.round(cov / 9f * 255f);
                    int r = 0, g = 0, b = 0;
                    if (cov > 0) {
                        r = Math.round(Math.min(1f, cr / cov) * 255f);
                        g = Math.round(Math.min(1f, cg / cov) * 255f);
                        b = Math.round(Math.min(1f, cb / cov) * 255f);
                    }
                    px[ty * W + shape * TILE + tx] = (a << 24) | (r << 16) | (g << 8) | b;
                }
        }
        return px;
    }

    /** A sprig: a stem carrying several leaves (or needle sprays / flowers) of one shape. */
    private static float[] sprig(int shape, float x, float y) {
        float[] hit = null;
        if (shape == 6) {                                         // blossom: a cluster of flowers
            float[][] fl = {{0.30f, -0.12f, 0.30f}, {0.55f, 0.14f, 0.34f}, {0.72f, -0.10f, 0.30f}, {0.45f, -0.02f, 0.26f}, {0.85f, 0.10f, 0.24f}, {0.22f, 0.15f, 0.22f}};
            for (int k = 0; k < fl.length; k++) {
                float s = fl[k][2];
                float[] c = sample(6, (x - fl[k][0]) / s + 0.5f, (y - fl[k][1]) / s, 1f);
                if (c != null) hit = tint(c, 0.92f + 0.08f * k / fl.length);
            }
            if (hit == null && Math.abs(y) < 0.008f && x < 0.85f) hit = new float[]{0.45f, 0.38f, 0.30f};
            return hit;
        }
        // stem
        if (Math.abs(y) < 0.010f * (1.2f - x) && x < 0.92f) hit = new float[]{0.55f, 0.50f, 0.36f};
        int n = 7;
        float len = shape == 4 ? 0.42f : (shape == 5 ? 0.38f : 0.30f);
        float spread = shape == 4 ? 0.35f : (shape == 5 ? 0.9f : 0.85f);   // angle from the stem, radians
        for (int k = 0; k < n; k++) {
            boolean tip = k == n - 1;
            float sx = 0.12f + k * 0.092f;
            float ang = tip ? 0f : ((k & 1) == 0 ? spread : -spread) * (1f - 0.25f * k / n);
            float ca = (float) Math.cos(ang), sa = (float) Math.sin(ang);
            float dx = x - sx, dy = y;
            float lx = (dx * ca + dy * sa) / len, ly = (-dx * sa + dy * ca) / len;
            if (lx < 0f || lx > 1f) continue;
            float[] c = sample(shape, lx, ly, ONE_LEAF[shape]);
            if (c != null) hit = tint(c, 0.88f + 0.24f * hash(k, shape));
        }
        return hit;
    }

    private static float[] tint(float[] c, float k) {
        return new float[]{c[0] * k, c[1] * k, c[2] * k};
    }

    private static float hash(float x, float y) {
        int h = Float.floatToIntBits(x * 127.1f + y * 311.7f);
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / (float) 0x1000000;
    }

    private static float noise(float x, float y) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y);
        float fx = x - xi, fy = y - yi;
        fx = fx * fx * (3 - 2 * fx);
        fy = fy * fy * (3 - 2 * fy);
        float a = hash(xi, yi), b = hash(xi + 1, yi), c = hash(xi, yi + 1), d = hash(xi + 1, yi + 1);
        return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy;
    }

    /** Returns rgb detail at (x, y) or null outside the leaf. */
    private static float[] sample(int shape, float x, float y, float asp) {
        float half = 0.5f * asp;              // full half-width in physical units
        float ay = Math.abs(y);
        float stalk = 0.10f;
        // stalk (petiole) for broad leaves
        boolean onStalk = shape != 5 && shape != 6 && x < stalk && ay < 0.012f;
        float hw;                             // allowed half-width at x (physical)
        switch (shape) {
            case 0: {   // oval
                float u = (x - stalk) / (1 - stalk);
                hw = u <= 0 ? 0 : half * (float) Math.pow(Math.sin(Math.PI * u), 0.75);
                break;
            }
            case 1: {   // oak: deep rounded lobes
                float u = (x - stalk) / (1 - stalk);
                float base = u <= 0 ? 0 : (float) Math.pow(Math.sin(Math.PI * Math.min(1f, u * 1.05f)), 0.55);
                float lobes = 0.55f + 0.45f * (float) Math.sqrt(Math.max(0, Math.cos(u * Math.PI * 2 * 3.5 + 0.6)) * 0.85 + 0.15);
                hw = half * base * lobes;
                break;
            }
            case 2: {   // maple: five pointed lobes around a centre
                float cx = 0.42f, dx = x - cx;
                double th = Math.atan2(y, dx);
                float best = 0f;
                double[] lobe = {0, 1.15, -1.15, 2.2, -2.2};
                for (double l : lobe) {
                    double d = th - l;
                    d = Math.atan2(Math.sin(d), Math.cos(d));
                    float c = (float) Math.max(0, Math.cos(d * 1.6));
                    best = Math.max(best, (float) Math.pow(c, 3) * (l == 0 ? 1f : (Math.abs(l) > 2 ? 0.7f : 0.92f)));
                }
                float rr = 0.56f * (0.32f + 0.68f * best);
                rr *= 1f - 0.06f * (float) Math.abs(Math.sin(th * 18));                     // serration
                float dist = (float) Math.sqrt(dx * dx + y * y);
                if (dist < rr && !(x < 0.1f)) return shade(shape, x, y, dist / rr, asp);
                return onStalkOrNull(onStalk, x);
            }
            case 3: {   // birch: ovate-triangular with a serrated edge
                float u = (x - stalk) / (1 - stalk);
                hw = u <= 0 ? 0 : half * 1.9f * (float) Math.pow(u, 0.45) * (float) Math.pow(1 - u, 0.95);
                hw *= 1f - 0.10f * (float) Math.abs(Math.sin(u * 55));
                break;
            }
            case 4: {   // willow: long lance
                float u = (x - 0.05f) / 0.95f;
                hw = u <= 0 ? 0 : half * (float) Math.pow(Math.sin(Math.PI * u), 1.2);
                break;
            }
            case 5: {   // needle sprig: fan of thin needles
                for (int k = 0; k < 13; k++) {
                    float ang = -0.50f + k / 12f * 1.0f;
                    float len = 0.80f + 0.2f * hash(k, 3);
                    float ca = (float) Math.cos(ang), sa = (float) Math.sin(ang);
                    float along = x * ca + y * sa, across = -x * sa + y * ca;
                    if (along > 0.02f && along < len && Math.abs(across) < 0.018f * (1.1f - along / len)) {
                        float l = 0.75f + 0.35f * along / len + 0.08f * (hash(k, 9) - 0.5f);
                        return new float[]{l, l, l};
                    }
                }
                if (x < 0.06f && ay < 0.02f) return new float[]{0.55f, 0.48f, 0.40f};
                return null;
            }
            case 6: {   // blossom: five rounded petals
                float cx = 0.5f, dx = x - cx;
                double th = Math.atan2(y, dx);
                float rr = 0.48f * (0.50f + 0.50f * (float) Math.pow(Math.abs(Math.cos(2.5 * th)), 0.45));
                float dist = (float) Math.sqrt(dx * dx + y * y);
                if (dist > rr) return null;
                if (dist < 0.08f) {                                   // stamens
                    float h = hash((float) Math.floor(x * 60), (float) Math.floor(y * 60));
                    return h > 0.5f ? new float[]{1.0f, 0.85f, 0.35f} : new float[]{0.85f, 0.65f, 0.30f};
                }
                float vein = 0.92f + 0.08f * (float) Math.cos(th * 25);
                float l = (0.82f + 0.18f * dist / rr) * vein;
                return new float[]{l, l * 0.98f, l * 0.99f};
            }
            default: {  // heart (poplar)
                float u = (x - stalk) / (1 - stalk);
                hw = u <= 0 ? 0 : half * (float) Math.pow(Math.sin(Math.PI * Math.min(1f, u * 0.92f + 0.08f)), 0.5)
                        * (0.75f + 0.45f * (1 - u) * (1 - u));
                hw = Math.min(hw, half);
                break;
            }
        }
        if (ay < hw) return shade(shape, x, y, ay / Math.max(1e-4f, hw), asp);
        return onStalkOrNull(onStalk, x);
    }

    private static float[] onStalkOrNull(boolean onStalk, float x) {
        return onStalk ? new float[]{0.62f, 0.60f, 0.42f} : null;
    }

    /** Leaf surface: midrib, side veins, mottling, darker rim. edge = 0 centre .. 1 rim. */
    private static float[] shade(int shape, float x, float y, float edge, float asp) {
        float ay = Math.abs(y);
        float l = 0.84f + 0.16f * noise(x * 14f, y * 14f / Math.max(0.2f, asp)) - 0.10f * edge * edge;
        if (ay < 0.006f) l = 1.03f;                                                       // midrib
        else {
            float vein = (x - ay * 1.3f) * 9f;
            float f = vein - (float) Math.floor(vein);
            if (Math.abs(f - 0.5f) < 0.07f && edge < 0.92f) l *= 1.07f;                   // side veins
        }
        if (edge > 0.9f) l *= 0.82f;                                                      // rim
        l *= 0.94f + 0.12f * hash((float) Math.floor(x * 40), (float) Math.floor(y * 40)) * 0.5f;
        return new float[]{l, l, l * 0.97f};
    }
}
