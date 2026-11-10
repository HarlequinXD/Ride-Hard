package com.harlequinxd.eryndor;

import java.util.Random;

/**
 * Procedurally painted textures (pure Java, no Android dependencies).
 *
 * Ground: 2048 x 2048, 8 px per metre, tiles every 256 m. Photographic look at a low texel density,
 * baked shadows, 15-bit ordered dither; alpha marks water. Layout (metres): the village of
 * Hollowspire with a cobbled north-south street at x = 128, an east-west street at z = 128 and a
 * round plaza with a fountain at the crossing; a cart track continues out of the village; a river
 * winds east-west near z = 30 under a stone bridge; standing stones on a mound at (36, 228). Wraps seamlessly.
 *
 * Clouds: 256 x 256 tileable fbm, grey.
 */
final class WorldTextures {

    static final int GROUND_SIZE = 2048;
    static final float WORLD_SIZE = 256f;           // metres per texture repeat
    static final float PPM = GROUND_SIZE / WORLD_SIZE;
    static final int CLOUD_SIZE = 256;

    private static int[] groundCache, cloudCache;
    static final float DETAIL_MAX = 3f;             // metres represented by 8-bit 255
    private static byte[] detailCache;
    private static float[] DH;                       // detail heights while painting

    /** Raise the detail height at a texel (keeps the highest). */
    private static void raise(int i, float v) {
        if (DH != null && v > DH[i]) DH[i] = v;
    }

    private static float bl0(PNoise p, float u, float v) {
        return p.at(u, v);
    }

    /** Detail heights (8-bit, 0..DETAIL_MAX metres) matching the ground texture texel for texel. */
    static synchronized byte[] detail() {
        if (detailCache == null) {
            groundCache = null;
            ground();
        }
        return detailCache;
    }

    /** Use previously cached ground + detail data. */
    static synchronized void setCached(int[] g, byte[] d) {
        groundCache = g;
        detailCache = d;
    }

    /** Bilinear detail height in metres (matches GL_LINEAR on the detail texture). */
    static float detailAt(double wx, double wz) {
        byte[] d = detailCache;
        if (d == null) return 0f;
        final int S = GROUND_SIZE;
        double fx = wx / WORLD_SIZE * S - 0.5, fz = wz / WORLD_SIZE * S - 0.5;
        int x0 = (int) Math.floor(fx), z0 = (int) Math.floor(fz);
        float tx = (float) (fx - x0), tz = (float) (fz - z0);
        int ax = Math.floorMod(x0, S), bx = Math.floorMod(x0 + 1, S), az = Math.floorMod(z0, S), bz = Math.floorMod(z0 + 1, S);
        float a = (d[az * S + ax] & 255), b = (d[az * S + bx] & 255), c = (d[bz * S + ax] & 255), e = (d[bz * S + bx] & 255);
        return lerp(lerp(a, b, tx), lerp(c, e, tx), tz) / 255f * DETAIL_MAX;
    }

    private WorldTextures() {}

    // ------------------------------------------------------------------ noise
    /** Periodic value noise on an nx x ny lattice over the unit square (tiles seamlessly). */
    private static final class PNoise {
        final int nx, ny;
        final float[] v;

        PNoise(int n, Random r) { this(n, n, r); }

        PNoise(int nx, int ny, Random r) {
            this.nx = nx;
            this.ny = ny;
            v = new float[nx * ny];
            for (int i = 0; i < v.length; i++) v[i] = r.nextFloat();
        }

        float at(float u, float w) {
            float fx = u * nx, fy = w * ny;
            int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
            float tx = fx - x0, ty = fy - y0;
            tx = tx * tx * (3 - 2 * tx);
            ty = ty * ty * (3 - 2 * ty);
            int ax = Math.floorMod(x0, nx), bx = Math.floorMod(x0 + 1, nx);
            int ay = Math.floorMod(y0, ny), by = Math.floorMod(y0 + 1, ny);
            float a = v[ay * nx + ax], b = v[ay * nx + bx], c = v[by * nx + ax], d = v[by * nx + bx];
            float top = a + (b - a) * tx, bot = c + (d - c) * tx;
            return top + (bot - top) * ty;
        }
    }

    private static float hash(int x, int y) {
        int h = x * 374761393 + y * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / (float) 0x1000000;
    }

    private static float clamp(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private static float smooth(float e0, float e1, float x) {
        float t = clamp((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** Signed wrapped distance on the 256 m torus. */
    private static float wrapD(float a, float b) {
        float d = a - b;
        d -= WORLD_SIZE * Math.round(d / WORLD_SIZE);
        return d;
    }

    /** Distance to the nearest Voronoi edge (metres) on a wrapping grid: used for mud cracks. */
    private static float crackDist(float wx, float wz, float cell) {
        int n = Math.round(WORLD_SIZE / cell);
        int cx = (int) Math.floor(wx / cell), cz = (int) Math.floor(wz / cell);
        float d1 = 1e9f, d2 = 1e9f;
        for (int j = -1; j <= 1; j++)
            for (int i = -1; i <= 1; i++) {
                int gx = cx + i, gz = cz + j;
                int hx = Math.floorMod(gx, n), hz = Math.floorMod(gz, n);
                float fx = (gx + 0.15f + 0.7f * hash(hx, hz * 7 + 1)) * cell;
                float fz = (gz + 0.15f + 0.7f * hash(hx * 13 + 5, hz)) * cell;
                float dx = wx - fx, dz = wz - fz, d = dx * dx + dz * dz;
                if (d < d1) { d2 = d1; d1 = d; } else if (d < d2) d2 = d;
            }
        return ((float) Math.sqrt(d2) - (float) Math.sqrt(d1)) * 0.5f;
    }

    // ------------------------------------------------------------------ cobble cells
    private static int vId;
    private static float vEdge, vDx, vDz;

    /** Nearest Voronoi cell on a wrapping grid: id, distance to edge (m) and offset from its centre. */
    private static void voronoi(float wx, float wz, float cell, int salt) {
        int n = Math.round(WORLD_SIZE / cell);
        int cx = (int) Math.floor(wx / cell), cz = (int) Math.floor(wz / cell);
        float d1 = 1e9f, d2 = 1e9f;
        for (int j = -1; j <= 1; j++)
            for (int i = -1; i <= 1; i++) {
                int gx = cx + i, gz = cz + j;
                int hx = Math.floorMod(gx, n), hz = Math.floorMod(gz, n);
                float fx = (gx + 0.2f + 0.6f * hash(hx + salt, hz * 7 + 1)) * cell;
                float fz = (gz + 0.2f + 0.6f * hash(hx * 13 + 5, hz + salt)) * cell;
                float dx = wx - fx, dz = wz - fz, d = dx * dx + dz * dz;
                if (d < d1) {
                    d2 = d1;
                    d1 = d;
                    vId = hz * n + hx;
                    vDx = dx;
                    vDz = dz;
                } else if (d < d2) d2 = d;
            }
        vEdge = ((float) Math.sqrt(d2) - (float) Math.sqrt(d1)) * 0.5f;
    }

    /** Weathered cobblestone: grey / blue-grey / brown stones, dark joints with moss. */
    private static float[] cobble(float wx, float wz, float cell, float fine, float h, float wear) {
        voronoi(wx, wz, cell, 3);
        float k = hash(vId, 17), t = hash(vId, 29);
        float r, g, b;
        if (k < 0.45f) { r = 0.54f; g = 0.53f; b = 0.51f; }
        else if (k < 0.75f) { r = 0.51f; g = 0.51f; b = 0.52f; }
        else { r = 0.55f; g = 0.52f; b = 0.47f; }
        float dome = -(vDx + vDz) / cell * 0.9f;                 // rounded tops lit from the north-west
        float l = 0.86f + 0.12f * t + 0.16f * dome + 0.10f * (fine - 0.5f) + 0.04f * (h - 0.5f) + 0.10f * wear;
        r *= l; g *= l; b *= l;
        if (vEdge < cell * 0.13f) {                               // joints
            float moss = hash((int) (wx * 8), (int) (wz * 8)) * (1f - wear);
            r = lerp(0.30f, 0.30f, moss); g = lerp(0.29f, 0.34f, moss); b = lerp(0.27f, 0.22f, moss);
        }
        return new float[]{r, g, b};
    }

    // ------------------------------------------------------------------ river
    private static final double TAU = Math.PI * 2;

    static float riverZ(float wx) {
        return 30f + 5f * (float) Math.sin(TAU * wx / 256 * 2) + 2.5f * (float) Math.sin(TAU * wx / 256 * 5 + 1.3);
    }

    static float riverHalfWidth(float wx) {
        return 3.6f + 0.8f * (float) Math.sin(TAU * wx / 256 * 3 + 0.5);
    }

    // ------------------------------------------------------------------ heightmap
    static final int HEIGHT_SIZE = 512;              // 0.5 m per sample
    static final float HEIGHT_MAX = 48f;             // metres represented by 8-bit 255
    static final float WATER_LEVEL = 4f;
    static final float VILLAGE_H = 10f;
    static final float STONES_X = 36f, STONES_Z = 228f;

    /** Layers, each in metres: floor (ground or cave floor), ceil (underside of rock over a cave),
     *  top (top of that rock), open (0 deep in a cave .. 1 open sky). Outside caves ceil = top = floor. */
    static float[] hFloor, hCeil, hTop, hOpen;

    private static float q8(float h) {
        h = Math.max(0f, Math.min(HEIGHT_MAX - 0.2f, h));
        return Math.round(h / HEIGHT_MAX * 255f) * HEIGHT_MAX / 255f;
    }

    private static PNoise[] terrainNoise;

    /** Open-air terrain height before any cave is cut. */
    private static float terrain(float wx, float wz) {
        PNoise[] n = terrainNoise;
        float u = wx / WORLD_SIZE, v = wz / WORLD_SIZE;
        float low = 5f + n[0].at(u, v) * 15f + n[1].at(u, v) * 8f;
        float h = low + n[2].at(u, v) * 3f + n[3].at(u, v) * 0.9f + n[4].at(u, v) * 0.25f;

        // standing-stone mound
        float sdx = wrapD(wx, STONES_X), sdz = wrapD(wz, STONES_Z);
        h += 6f * (float) Math.exp(-(sdx * sdx + sdz * sdz) / (16f * 16f));

        // village plateau
        float vx = wrapD(wx, 128f), vz = wrapD(wz, 128f);
        float dv = (float) Math.pow(Math.pow(Math.abs(vx), 3) + Math.pow(Math.abs(vz), 3), 1.0 / 3.0);   // rounded square
        float wv = 1f - smooth(70f, 100f, dv);
        h = lerp(h, VILLAGE_H + (n[3].at(u, v) - 0.5f) * 0.3f * smooth(30f, 64f, dv), wv);

        // Hollow Hill, east of the village, which holds the cave
        float hx = wrapD(wx, Cave.HILL_X), hz = wrapD(wz, Cave.HILL_Z);
        h += 19f * (float) Math.exp(-(hx * hx + hz * hz) / (30f * 30f));

        // north-south track: flat across its width, following a gentle grade
        float dx = Math.abs(wrapD(wx, 128f));
        float roadLow = 5f + n[0].at(0.5f, v) * 15f + n[1].at(0.5f, v) * 8f;
        float wvRoad = 1f - smooth(70f, 100f, Math.abs(wrapD(wz, 128f)));
        float roadH = lerp(roadLow, VILLAGE_H, wvRoad);
        h = lerp(h, roadH, (1f - smooth(3f, 11f, dx)));

        // river valley and the bridge
        float rd = Math.abs(wrapD(wz, riverZ(wx))), rhw = riverHalfWidth(wx);
        float bank = WATER_LEVEL + 0.25f + Math.max(rd - rhw, 0f) * 0.42f;
        if (rd < rhw) bank = WATER_LEVEL;
        h = Math.min(h, bank);
        if (dx < 4.2f && rd < rhw + 2.6f) h = Math.max(h, WATER_LEVEL + 1.4f);
        else if (dx < 4.2f) h = Math.max(h, Math.min(roadH, WATER_LEVEL + 1.4f + (rd - rhw - 2.6f) * 0.42f));
        return h;
    }

    /**
     * Layered terrain, quantised to the 8 bits the GPU sees and sampled at texel centres: rolling
     * hills, a flat village plateau, the north road graded down to a bridge, a river valley at
     * water level, a mound for the standing stones, and Hollow Hill with its cave.
     */
    static synchronized void buildHeights() {
        if (hFloor != null) return;
        final int S = HEIGHT_SIZE;
        Random rnd = new Random(4711);
        terrainNoise = new PNoise[]{new PNoise(3, rnd), new PNoise(7, rnd), new PNoise(18, rnd), new PNoise(48, rnd), new PNoise(128, rnd)};
        float[] fl = new float[S * S], ce = new float[S * S], tp = new float[S * S], op = new float[S * S], al = new float[S * S];

        float mouthFloor = terrain(Cave.PATH[0][0], Cave.PATH[0][1]);
        float len = Cave.pathLength();
        float endFloor = mouthFloor - 0.05f * len;
        float sCover = 1e9f;

        for (int y = 0; y < S; y++) {
            float wz = (y + 0.5f) / S * WORLD_SIZE;
            for (int x = 0; x < S; x++) {
                float wx = (x + 0.5f) / S * WORLD_SIZE;
                int i = y * S + x;
                float h = terrain(wx, wz);
                float f = h, c = h, t = h, along = -1f;

                Cave.query(wx, wz);
                float cd = Cave.chamberDist(wx, wz);
                // vaulted cross-sections: the ceiling curves all the way down to meet the floor,
                // so walls and roof are one smooth surface (no corner to stair-step on the grid)
                float gap = -1f, floor = 0f, ceil = 0f;
                // the vault closes at k = 1; a band just beyond keeps the layers closed at the
                // floor level so the ceiling layer meets the floor instead of leaving a sliver of air
                if (Cave.along > 0.01f && Cave.along < len - 0.01f) {
                    float hw = Cave.halfWidth(Cave.along) * 1.3f;
                    float k = Cave.dist / hw;
                    if (k < 1f + 1.2f / hw) {
                        float kc = Math.min(k, 1f);
                        float base = mouthFloor - 0.05f * Cave.along;
                        float fT = base + 0.5f * kc * kc * kc * kc;
                        float cT = k < 1f ? base + (3.7f + 0.25f * (float) Math.sin(Cave.along * 0.9f)) * (float) Math.sqrt(1f - k * k) : fT;
                        cT = Math.max(cT, fT);
                        if (cT - fT > gap) { gap = cT - fT; floor = fT; ceil = cT; along = Cave.along; }
                    }
                }
                if (cd < Cave.CHAMBER_R + 1.2f) {
                    float k = cd / Cave.CHAMBER_R, kc = Math.min(k, 1f);
                    float fC = endFloor - 0.6f * (1f - kc * kc) + 0.6f * kc * kc * kc * kc;   // shallow bowl
                    float cC = k < 1f ? endFloor + 8.0f * (float) Math.sqrt(1f - k * k) : fC; // dome
                    cC = Math.max(cC, fC);
                    if (cC - fC > gap) { gap = cC - fC; floor = fC; ceil = cC; along = len + 3f; }
                }
                if (gap >= 0f) {
                    if (h - ceil >= 1.4f) {          // enough rock overhead: layered cell
                        f = floor;
                        c = ceil;
                        t = h;
                        if (gap > 0.05f) sCover = Math.min(sCover, along);
                    } else if (gap > 0.05f) {        // open cutting leading to the mouth
                        f = Math.min(h, floor);
                        c = t = h;
                        along = -1f;
                    } else {
                        along = -1f;
                    }
                } else {
                    along = -1f;
                }
                fl[i] = q8(f);
                ce[i] = q8(c);
                tp[i] = q8(t);
                al[i] = along;
            }
        }
        // sky openness: fades over the first 14 m under the roof...
        float[] raw = new float[S * S];
        for (int i = 0; i < S * S; i++) {
            boolean covered = tp[i] - ce[i] > 0.5f;
            raw[i] = covered ? 1f - smooth(sCover - 2f, sCover + 14f, al[i]) : 1f;
        }
        // ...and bleeds a little into the surrounding rock, so the cave's walls share its darkness
        final int R = 3;
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                float m = raw[y * S + x];
                for (int dy = -R; dy <= R; dy++)
                    for (int dx = -R; dx <= R; dx++) {
                        float o = raw[Math.floorMod(y + dy, S) * S + Math.floorMod(x + dx, S)];
                        if (o < m) m = Math.min(m, o + 0.13f * (float) Math.sqrt(dx * dx + dy * dy));
                    }
                op[y * S + x] = Math.min(1f, m);
            }
        hFloor = fl;
        hCeil = ce;
        hTop = tp;
        hOpen = op;
    }

    /** Height texture: R floor, G ceiling, B top, A openness (8 bits each). */
    static int[] heightTexture() {
        buildHeights();
        int n = hFloor.length;
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            int r = Math.round(hFloor[i] / HEIGHT_MAX * 255f), g = Math.round(hCeil[i] / HEIGHT_MAX * 255f);
            int b = Math.round(hTop[i] / HEIGHT_MAX * 255f), a = Math.round(clamp(hOpen[i]) * 255f);
            out[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        return out;
    }

    /** Bilinear sample of a layer, matching GL_LINEAR on the height texture. */
    static float sample(float[] layer, double wx, double wz) {
        buildHeights();
        final int S = HEIGHT_SIZE;
        double fx = wx / WORLD_SIZE * S - 0.5, fz = wz / WORLD_SIZE * S - 0.5;
        int x0 = (int) Math.floor(fx), z0 = (int) Math.floor(fz);
        float tx = (float) (fx - x0), tz = (float) (fz - z0);
        int ax = Math.floorMod(x0, S), bx = Math.floorMod(x0 + 1, S), az = Math.floorMod(z0, S), bz = Math.floorMod(z0 + 1, S);
        float a = layer[az * S + ax], b = layer[az * S + bx], c = layer[bz * S + ax], d = layer[bz * S + bx];
        return lerp(lerp(a, b, tx), lerp(c, d, tx), tz);
    }

    static float floorAt(double wx, double wz) {
        buildHeights();
        return sample(hFloor, wx, wz);
    }

    /** Underside of the rock overhead, or +infinity in the open. */
    static float ceilingAt(double wx, double wz) {
        buildHeights();
        float c = sample(hCeil, wx, wz), t = sample(hTop, wx, wz);
        return t - c > 0.5f ? c : Float.POSITIVE_INFINITY;
    }

    // ------------------------------------------------------------------ ground
    /**
     * Photographic-style ground at a deliberately low texel density (12.5 cm per texel), like a
     * downsampled photo. Alpha marks water (0 = open water, the shader adds sky reflections).
     */
    static synchronized int[] ground() {
        if (groundCache != null) return groundCache;
        final int S = GROUND_SIZE;
        final float[] R = new float[S * S], G = new float[S * S], B = new float[S * S], A = new float[S * S];
        DH = new float[S * S];
        final byte[] busy = new byte[S * S];            // 1 = road / water / plaza: no scatter
        Random rnd = new Random(1349);
        final float caveLen = Cave.pathLength();
        PNoise c1 = new PNoise(4, rnd), c2 = new PNoise(12, rnd), m1 = new PNoise(48, rnd),
                m2 = new PNoise(160, rnd), f1 = new PNoise(512, rnd), f2 = new PNoise(1024, rnd),
                blade = new PNoise(1024, 256, rnd);

        for (int y = 0; y < S; y++) {
            float v = (y + 0.5f) / S, wz = v * WORLD_SIZE;
            for (int x = 0; x < S; x++) {
                float u = (x + 0.5f) / S, wx = u * WORLD_SIZE;
                int i = y * S + x;
                float big = c1.at(u, v) * 0.6f + c2.at(u, v) * 0.4f;
                float big2 = c2.at(u + 0.5f, v + 0.23f);
                float mid = m1.at(u, v) * 0.55f + m2.at(u, v) * 0.45f;
                float fine = f1.at(u, v) * 0.6f + f2.at(u, v) * 0.4f;
                float h = hash(x, y), h2 = hash(x + 911, y - 317);
                float a = 1f;
                // detail height (m): grass is a fine fuzz of blades
                // lumpy meadow: gentle hummocks plus a dense fuzz of grass blades
                float hummock = smooth(0.35f, 0.9f, m2.at(u + 0.21f, v + 0.67f)) * 0.22f + (f1.at(u + 0.3f, v) - 0.5f) * 0.06f;
                float dh = 0.03f + hummock + 0.11f * h * h + 0.05f * bl0(blade, u, v);

                // ---- meadow: lush to sun-dried grass, strong per-texel blade variation
                float t = smooth(0.30f, 0.75f, big);
                float r = lerp(0.29f, 0.47f, t), g = lerp(0.41f, 0.49f, t), b = lerp(0.16f, 0.24f, t);
                float dry = smooth(0.62f, 0.85f, big2) * 0.8f;
                r = lerp(r, 0.60f, dry); g = lerp(g, 0.55f, dry); b = lerp(b, 0.32f, dry);
                float bl = blade.at(u, v);
                float lum = 0.86f + 0.20f * (mid - 0.5f) + 0.12f * (fine - 0.5f) + 0.26f * (h - 0.5f) + 0.10f * (bl - 0.5f);
                float tint = h2 - 0.5f;                                   // some blades yellower, some bluer
                r *= lum * (1f + 0.10f * tint); g *= lum; b *= lum * (1f - 0.12f * tint);
                // clover patches
                float clover = smooth(0.60f, 0.78f, m1.at(u + 0.4f, v + 0.8f) * 0.7f + fine * 0.3f) * 0.5f;
                if (clover > 0) {
                    float cl = 0.85f + 0.3f * fine;
                    r = lerp(r, 0.25f * cl, clover); g = lerp(g, 0.40f * cl, clover); b = lerp(b, 0.16f * cl, clover);
                    if (h2 > 0.993f && clover > 0.3f) { r = 0.88f; g = 0.88f; b = 0.84f; }
                }
                // bare earth patches
                float bare = smooth(0.70f, 0.78f, m1.at(u + 0.13f, v + 0.51f) * 0.7f + m2.at(u, v) * 0.3f);
                if (bare > 0) {
                    float el = 0.88f + 0.2f * fine + 0.1f * (h - 0.5f);
                    float er = 0.43f * el, eg = 0.35f * el, eb = 0.25f * el;
                    if (h > 0.7f) { er = r; eg = g; eb = b; }             // grass poking through
                    r = lerp(r, er, bare); g = lerp(g, eg, bare); b = lerp(b, eb, bare);
                    dh = lerp(dh, h > 0.7f ? dh : 0.01f + 0.015f * fine, bare);
                }
                // wildflowers in drifts
                float fl = smooth(0.55f, 0.85f, m2.at(u + 0.7f, v + 0.3f)) * (1f - bare);
                if (h2 < 0.028f * fl) {
                    float k = hash(x * 3, y * 5);
                    if (k < 0.35f) { r = 0.95f; g = 0.93f; b = 0.86f; }       // daisies
                    else if (k < 0.65f) { r = 0.95f; g = 0.80f; b = 0.20f; }  // buttercups
                    else if (k < 0.85f) { r = 0.55f; g = 0.40f; b = 0.75f; }  // violets
                    else { r = 0.80f; g = 0.18f; b = 0.14f; }                 // poppies
                    dh = 0.13f + 0.06f * hash(x, y * 3);
                }

                // ---- roads
                float dx = wrapD(wx, 128f), dz = wrapD(wz, 128f);
                float sd = Math.abs(dx), sdz = Math.abs(dz);
                float edgeN = (m2.at(u * 2f, v * 2f) - 0.5f) * 0.8f;
                float townN = smooth(60f, 68f, wz) * (1f - smooth(188f, 196f, wz));   // N-S street paved
                float townE = smooth(60f, 68f, wx) * (1f - smooth(188f, 196f, wx));   // E-W street paved
                float plaza = (float) Math.sqrt(dx * dx + dz * dz);
                boolean paved = false;
                float roadA = 0f;

                // paved streets with curb stones, and the round plaza
                float halfN = 4.6f, halfE = 4.2f;
                boolean onN = sd < halfN + 0.35f && townN > 0.5f;
                boolean onE = sdz < halfE + 0.35f && townE > 0.5f;
                boolean onP = plaza < 15.5f;
                if (onN || onE || onP) {
                    float wear = smooth(0.6f, 0.0f, Math.min(Math.abs(sd - 1.6f), Math.abs(sd + 0f - 1.6f))) * (onN ? 1f : 0f);
                    float[] c = cobble(wx, wz, 0.36f, fine, h, wear * 0.6f);
                    dh = vEdge < 0.36f * 0.13f ? 0.0f : 0.02f + 0.035f * smooth(0f, 0.12f, vEdge);
                    boolean curb = (onN && !onP && !(sdz < halfE + 0.35f) && sd > halfN)
                            || (onE && !onP && !(sd < halfN + 0.35f) && sdz > halfE)
                            || (onP && plaza > 15.0f && !(sd < halfN + 0.35f) && !(sdz < halfE + 0.35f));
                    if (curb) {
                        int seg = (int) Math.floor((onN ? wz : (onE ? wx : (float) Math.atan2(dz, dx) * 15f)) / 0.9f);
                        float cl = 0.78f + 0.15f * hash(seg, 3) + 0.06f * (h - 0.5f);
                        c = new float[]{0.60f * cl, 0.59f * cl, 0.56f * cl};
                        dh = 0.16f;
                    }
                    r = c[0]; g = c[1]; b = c[2];
                    paved = true;
                    roadA = 1f;
                } else {
                    // soft shadow of the curb onto the verge
                    float near = Math.min(townN > 0.5f ? sd - (halfN + 0.35f) : 9f, townE > 0.5f ? sdz - (halfE + 0.35f) : 9f);
                    near = Math.min(near, plaza - 15.5f);
                    if (near < 0.4f) { float s = 0.75f + 0.25f * (near / 0.4f); r *= s; g *= s; b *= s; }
                }
                // dirt cart track outside town (two ruts with a grassy crown)
                if (!paved) {
                    float trackN = 1f - townN, trackE = 0f;
                    float acr = dx, along = wz;
                    float hw = 2.0f + edgeN;
                    if (trackN > 0.01f && sd < hw + 0.8f) {
                        float wob = 0.15f * (float) Math.sin(along * 0.13f);
                        float[] tr = track(acr - wob, along, fine, h, mid);
                        float ta = (1f - smooth(hw - 0.5f, hw + 0.8f, sd)) * trackN * tr[3];
                        r = lerp(r, tr[0], ta); g = lerp(g, tr[1], ta); b = lerp(b, tr[2], ta);
                        dh = lerp(dh, tr[4], ta);
                        roadA = Math.max(roadA, ta);
                    }
                }

                // ---- river with muddy banks, reeds and a stone bridge on the main road
                float rcz = riverZ(wx), rhw = riverHalfWidth(wx) + edgeN * 0.8f;
                float rd = Math.abs(wrapD(wz, rcz));
                boolean bridge = sd < 4.2f && rd < rhw + 2.6f;
                if (bridge) {
                    float alongB = wrapD(wz, rcz);
                    int fx = (int) Math.floor((dx + 8f) / 0.62f), fz = (int) Math.floor((alongB + 20f) / 0.42f + (fx & 1) * 0.5f);
                    float fu = (dx + 8f) / 0.62f - fx, fv = (alongB + 20f) / 0.42f + (fx & 1) * 0.5f - fz;
                    float bl2 = 0.74f + 0.22f * hash(fx * 31 + fz, 7) + 0.06f * (h - 0.5f) + 0.08f * (fine - 0.5f);
                    r = 0.58f * bl2; g = 0.55f * bl2; b = 0.50f * bl2;
                    if (fu < 0.10f || fv < 0.12f) { r *= 0.55f; g *= 0.55f; b *= 0.55f; }
                    dh = (fu < 0.10f || fv < 0.12f) ? 0.0f : 0.025f;
                    if (sd > 3.5f) {                                       // parapet walls
                        dh = 0.95f + 0.05f * hash(fz, 3);
                        float pl = 0.95f - 0.35f * (dx > 0 ? 1f : 0f) * smooth(3.5f, 4.2f, sd) + 0.1f * (h - 0.5f);
                        r = 0.62f * pl; g = 0.60f * pl; b = 0.55f * pl;
                    }
                    busy[i] = 1;
                    paved = true;
                } else if (rd < rhw + 1.8f) {
                    busy[i] = 1;
                    float bank = smooth(rhw + 1.8f, rhw, rd);              // 0 at grass edge .. 1 at waterline
                    float ml = 0.85f + 0.25f * fine + 0.12f * (h - 0.5f);
                    float mr = 0.42f * ml, mg = 0.36f * ml, mb = 0.27f * ml;
                    if (hash(x >> 1, y >> 1) < 0.12f) { mr = 0.55f * ml; mg = 0.53f * ml; mb = 0.50f * ml; }  // pebbles
                    r = lerp(r, mr, bank); g = lerp(g, mg, bank); b = lerp(b, mb, bank);
                    dh = lerp(dh, hash(x >> 1, y >> 1) < 0.12f ? 0.05f : 0.01f, bank);
                    // reeds along the outer bank
                    if (rd > rhw + 0.3f && rd < rhw + 1.3f && m2.at(u * 4f, v * 4f) > 0.55f && h > 0.45f) {
                        float rl = 0.7f + 0.5f * h2;
                        r = 0.30f * rl; g = 0.40f * rl; b = 0.18f * rl;
                        dh = 0.35f + 0.45f * h2;
                    }
                    if (rd < rhw) {
                        float depth = smooth(0f, 1.6f, rhw - rd);
                        a = 1f - (0.55f + 0.45f * depth);                  // shallows reflect less
                        float wl = 0.85f + 0.2f * fine;
                        r = lerp(0.30f, 0.10f, depth) * wl; g = lerp(0.29f, 0.15f, depth) * wl; b = lerp(0.22f, 0.13f, depth) * wl;
                        dh = 0f;
                    }
                }
                // shadow of the bridge on the water either side
                if (!bridge && sd < 5.2f && sd >= 4.2f && rd < rhw && dx > 0) { r *= 0.6f; g *= 0.6f; b *= 0.6f; a = Math.min(1f, a + 0.3f); }

                // ---- plaza fountain
                if (plaza < 4.0f) {
                    busy[i] = 1;
                    if (plaza > 3.2f) {                                    // basin rim
                        int st = (int) Math.floor((Math.atan2(dz, dx) + Math.PI) / TAU * 26);
                        float sf = (float) ((Math.atan2(dz, dx) + Math.PI) / TAU * 26 - st);
                        float rl = 0.80f + 0.18f * hash(st, 9) - 0.25f * (dx + dz) / 4f + 0.05f * (h - 0.5f);
                        if (sf < 0.08f) rl *= 0.6f;
                        r = 0.66f * rl; g = 0.63f * rl; b = 0.58f * rl;
                        a = 1f;
                        dh = 0.55f;
                    } else if (plaza < 0.55f) {                            // central column
                        float rl = 0.75f - 0.4f * (dx + dz) / 0.55f;
                        r = 0.62f * rl; g = 0.60f * rl; b = 0.56f * rl;
                        a = 1f;
                        dh = 1.6f;
                    } else {
                        float ripple = 0.5f + 0.5f * (float) Math.sin(plaza * 9f);
                        r = 0.12f + 0.03f * ripple; g = 0.17f + 0.03f * ripple; b = 0.17f + 0.03f * ripple;
                        a = 0.15f;
                        dh = 0.25f;                                        // water surface inside the basin
                    }
                }

                // ---- cave floor in Hollow Hill: damp earth, grit and fallen stone
                if (wx > 195f && wx < 266f && wz > 160f && wz < 216f) {
                    Cave.query(wx, wz);
                    float hwc = Cave.halfWidth(Cave.along) * 1.3f + 0.4f, cdc = Cave.chamberDist(wx, wz) - 0.4f;
                    float e1 = Cave.along > 0.01f && Cave.along < caveLen ? Cave.dist - hwc : 9f;
                    float e = Math.min(e1, cdc - Cave.CHAMBER_R);
                    if (e < 0.8f) {
                        float k = smooth(0.8f, -0.4f, e);
                        float cl = 0.80f + 0.30f * fine + 0.14f * (h - 0.5f);
                        float cr = 0.33f * cl, cg = 0.30f * cl, cb = 0.26f * cl;
                        if (hash(x >> 1, y >> 1) < 0.10f) { cr = 0.47f * cl; cg = 0.45f * cl; cb = 0.42f * cl; }
                        r = lerp(r, cr, k); g = lerp(g, cg, k); b = lerp(b, cb, k);
                        dh = lerp(dh, hash(x >> 1, y >> 1) < 0.10f ? 0.06f : 0.01f, k);
                        if (k > 0.3f) busy[i] = 1;
                    }
                }

                if (paved || roadA > 0.5f || a < 1f) busy[i] = 1;
                R[i] = r; G[i] = g; B[i] = b; A[i] = a;
                DH[i] = dh;
            }
        }

        // ---- scatter
        for (int k = 0; k < 9000; k++) {                         // tall grass clumps
            int cx = rnd.nextInt(S), cy = rnd.nextInt(S);
            if (busy[cy * S + cx] != 0) continue;
            tuft(R, G, B, cx, cy, rnd);
        }
        for (int k = 0; k < 1300; k++) {                         // mossy granite
            int cx = rnd.nextInt(S), cy = rnd.nextInt(S);
            if (busy[cy * S + cx] != 0) continue;
            rock(R, G, B, cx, cy, 1.3f + rnd.nextFloat() * rnd.nextFloat() * 7f, rnd, f1);
        }
        for (int k = 0; k < 2100; k++) {                         // shrubs, gorse and bramble
            int cx = rnd.nextInt(S), cy = rnd.nextInt(S);
            if (busy[cy * S + cx] != 0) continue;
            bush(R, G, B, cx, cy, 3.5f + rnd.nextFloat() * 7f, rnd.nextFloat(), rnd.nextInt(1 << 20), f2);
        }
        // standing stones on the rise west of the north road
        stoneCircle(R, G, B, STONES_X, STONES_Z, 7.5f, 11, f1, rnd);
        // fairy rings
        for (int k = 0; k < 1400; k++) {                         // ferns
            int cx = rnd.nextInt(S), cy = rnd.nextInt(S);
            if (busy[cy * S + cx] != 0) continue;
            fern(R, G, B, cx, cy, 3f + rnd.nextFloat() * 4f, rnd);
        }
        for (int k = 0; k < 160; k++) {                          // fallen logs
            int cx = rnd.nextInt(S), cy = rnd.nextInt(S);
            if (busy[cy * S + cx] != 0) continue;
            log(R, G, B, cx, cy, 10f + rnd.nextFloat() * 18f, 1.6f + rnd.nextFloat() * 1.6f, rnd.nextFloat() * (float) Math.PI, rnd, f2);
        }
        for (int k = 0; k < 220; k++) {                          // tree stumps
            int cx = rnd.nextInt(S), cy = rnd.nextInt(S);
            if (busy[cy * S + cx] != 0) continue;
            stump(R, G, B, cx, cy, 2.2f + rnd.nextFloat() * 2.5f, rnd);
        }
        for (int k = 0; k < 90; k++) {                           // stone cairns
            int cx = rnd.nextInt(S), cy = rnd.nextInt(S);
            if (busy[cy * S + cx] != 0) continue;
            for (int j = 0; j < 7; j++)
                rock(R, G, B, cx + rnd.nextInt(9) - 4, cy + rnd.nextInt(9) - 4, 1.5f + rnd.nextFloat() * 2.5f, rnd, f1);
            rock(R, G, B, cx, cy, 2.5f, rnd, f1);
        }
        for (int k = 0; k < 500; k++) {                          // molehills
            int cx = rnd.nextInt(S), cy = rnd.nextInt(S);
            if (busy[cy * S + cx] != 0) continue;
            molehill(R, G, B, cx, cy, 2f + rnd.nextFloat() * 2f, rnd);
        }
        fairyRing(R, G, B, 196f, 70f, 2.4f, rnd);
        fairyRing(R, G, B, 40f, 110f, 1.8f, rnd);
        fairyRing(R, G, B, 210f, 222f, 2.9f, rnd);
        well(R, G, B, (int) (141 * PPM), (int) (113 * PPM), 1.25f * PPM, 0.80f * PPM, f1);

        int[] out = new int[S * S];
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                int i = y * S + x;
                out[i] = pack(R[i], G[i], B[i], x, y) & 0x00FFFFFF | (Math.round(clamp(A[i]) * 255f) << 24);
            }
        groundCache = out;
        byte[] det = new byte[S * S];
        for (int i = 0; i < S * S; i++) det[i] = (byte) Math.round(clamp(DH[i] / DETAIL_MAX) * 255f);
        detailCache = det;
        DH = null;
        return out;
    }

    /** Cart track: two bare ruts with a grassy crown. Returns rgb + coverage. */
    private static float[] track(float across, float along, float fine, float h, float mid) {
        float l = 0.86f + 0.16f * (fine - 0.5f) + 0.08f * (h - 0.5f);
        float r = 0.46f * l, g = 0.38f * l, b = 0.27f * l;
        float rut = Math.min(Math.abs(across - 0.78f), Math.abs(across + 0.78f));
        float cov, th = 0.03f + 0.02f * h;
        if (rut < 0.28f) { float d = 0.88f + 0.12f * (rut / 0.28f); r *= d; g *= d; b *= d; cov = 1f; th = 0.0f; }
        else if (Math.abs(across) < 0.5f) cov = h > 0.35f ? 0.15f : 0.8f;     // grass crown
        else cov = smooth(1.6f, 0.9f, Math.abs(across)) * (h > 0.3f ? 1f : 0.4f);
        return new float[]{r, g, b, cov, th};
    }

    private static void tuft(float[] R, float[] G, float[] B, int cx, int cy, Random rnd) {
        final int S = GROUND_SIZE;
        int blades = 5 + rnd.nextInt(8);
        float t = 0.85f + rnd.nextFloat() * 0.3f;
        for (int dy = 0; dy <= 1; dy++)
            for (int dx = 0; dx <= 1; dx++) {
                int i = Math.floorMod(cy + dy, S) * S + Math.floorMod(cx + dx, S);
                R[i] *= 0.62f; G[i] *= 0.62f; B[i] *= 0.62f;
                raise(i, 0.10f);
            }
        for (int k = 0; k < blades; k++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            int len = 1 + rnd.nextInt(3);
            float bl = t * (0.85f + 0.3f * rnd.nextFloat());
            for (int s = 1; s <= len; s++) {
                int x = Math.floorMod(cx + (int) Math.round(Math.cos(ang) * s), S);
                int y = Math.floorMod(cy + (int) Math.round(Math.sin(ang) * s), S);
                int i = y * S + x;
                R[i] = lerp(R[i], 0.48f * bl, 0.7f); G[i] = lerp(G[i], 0.56f * bl, 0.7f); B[i] = lerp(B[i], 0.26f * bl, 0.7f);
                raise(i, (0.14f + 0.22f * bl) * (1f - 0.25f * (s - 1)));
            }
        }
    }

    private static float lobe(int seed, double ang, int lobes) {
        double p = (ang + Math.PI) / (Math.PI * 2) * lobes;
        int k = (int) Math.floor(p);
        float f = (float) (p - k);
        f = f * f * (3 - 2 * f);
        return lerp(hash(seed, Math.floorMod(k, lobes)), hash(seed, Math.floorMod(k + 1, lobes)), f);
    }

    /** Shrub seen from above: leafy clumps with dark gaps and a soft shadow. kind picks the species. */
    private static void bush(float[] R, float[] G, float[] B, int cx, int cy, float rad, float kind, int seed, PNoise fn) {
        final int S = GROUND_SIZE;
        float cr, cg, cb;
        boolean blossom = false;
        if (kind < 0.45f) { cr = 0.22f; cg = 0.36f; cb = 0.14f; }               // dark green shrub
        else if (kind < 0.75f) { cr = 0.33f; cg = 0.42f; cb = 0.15f; blossom = true; }  // gorse
        else { cr = 0.27f; cg = 0.33f; cb = 0.17f; }                           // bramble
        int ext = (int) Math.ceil(rad * 1.9f) + 2;
        float so = rad * 0.55f;
        for (int dy = -ext; dy <= ext; dy++)
            for (int dx = -ext; dx <= ext; dx++) {
                float sx = dx - so, sy = dy - so;
                float er = rad * (0.85f + 0.35f * lobe(seed + 1, Math.atan2(sy, sx), 9));
                float d = (float) Math.sqrt(sx * sx + sy * sy * 1.2f);
                if (d < er * 1.15f) {
                    int i = Math.floorMod(cy + dy, S) * S + Math.floorMod(cx + dx, S);
                    float s = 0.48f + 0.52f * smooth(er * 0.55f, er * 1.15f, d);
                    R[i] *= s; G[i] *= s; B[i] *= s;
                }
            }
        for (int dy = -ext; dy <= ext; dy++)
            for (int dx = -ext; dx <= ext; dx++) {
                float er = rad * (0.75f + 0.45f * lobe(seed, Math.atan2(dy, dx), 11));
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d >= er) continue;
                int x = Math.floorMod(cx + dx, S), y = Math.floorMod(cy + dy, S), i = y * S + x;
                float n = fn.at((x + 0.5f) / S, (y + 0.5f) / S);
                float hh = hash(x * 3 + seed, y);
                float density = n * 0.6f + hh * 0.4f + 0.38f * (1f - d / er);
                if (density > 0.52f) {
                    float light = 0.80f + 0.45f * (-(dx + dy) / (er * 1.6f)) + 0.28f * (hh - 0.5f) + 0.15f * (1f - d / er);
                    R[i] = cr * light; G[i] = cg * light; B[i] = cb * light;
                    float prof = (float) Math.sqrt(Math.max(0f, 1f - (d / er) * (d / er)));
                    raise(i, (0.35f + rad / PPM * 0.55f) * prof * (0.80f + 0.25f * hh));
                    if (blossom && hash(x, y * 7 + seed) > 0.86f) { R[i] = 0.92f * light; G[i] = 0.78f * light; B[i] = 0.18f * light; }
                } else {
                    float s = 0.40f + 0.2f * hh;
                    R[i] *= s; G[i] *= s; B[i] *= s;
                    float prof = (float) Math.sqrt(Math.max(0f, 1f - (d / er) * (d / er)));
                    raise(i, (0.35f + rad / PPM * 0.55f) * prof * 0.55f);
                }
            }
    }

    /** Fern seen from above: arching fronds radiating from a crown. */
    private static void fern(float[] R, float[] G, float[] B, int cx, int cy, float len, Random rnd) {
        final int S = GROUND_SIZE;
        int fronds = 6 + rnd.nextInt(5);
        float tint = 0.85f + rnd.nextFloat() * 0.3f;
        for (int f = 0; f < fronds; f++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            float l = len * (0.7f + 0.3f * rnd.nextFloat());
            for (float s = 0; s <= l; s += 0.5f) {
                float k = s / l;
                float w = 1.6f * (float) Math.sin(Math.PI * Math.min(1f, k * 1.1f)) + 0.3f;
                float bx = cx + (float) Math.cos(ang) * s, by = cy + (float) Math.sin(ang) * s;
                for (float o = -w; o <= w; o += 0.5f) {
                    int x = Math.floorMod((int) (bx - Math.sin(ang) * o), S), y = Math.floorMod((int) (by + Math.cos(ang) * o), S);
                    int i = y * S + x;
                    float light = (0.75f + 0.35f * (1f - Math.abs(o) / (w + 0.01f))) * tint * (0.9f + 0.2f * hash(x, y));
                    // shadow first, one texel south-east
                    int sh = Math.floorMod(y + 1, S) * S + Math.floorMod(x + 1, S);
                    R[sh] *= 0.85f; G[sh] *= 0.85f; B[sh] *= 0.85f;
                    R[i] = 0.26f * light; G[i] = 0.44f * light; B[i] = 0.16f * light;
                    raise(i, 0.55f * (float) Math.sin(Math.PI * (0.15f + 0.7f * k)) * (1f - 0.4f * Math.abs(o) / (w + 0.01f)));
                }
            }
        }
    }

    /** Fallen log: a rotated capsule of bark with a pale cut end and a soft shadow. */
    private static void log(float[] R, float[] G, float[] B, int cx, int cy, float len, float rad, float rot, Random rnd, PNoise fn) {
        final int S = GROUND_SIZE;
        float c = (float) Math.cos(rot), s = (float) Math.sin(rot);
        int ext = (int) (len + rad + 4);
        for (int pass = 0; pass < 2; pass++)
            for (int dy = -ext; dy <= ext; dy++)
                for (int dx = -ext; dx <= ext; dx++) {
                    float qx = dx - (pass == 0 ? rad * 0.8f : 0), qy = dy - (pass == 0 ? rad * 0.8f : 0);
                    float a = qx * c + qy * s, b = -qx * s + qy * c;            // along, across
                    float ac = Math.max(-len / 2, Math.min(len / 2, a));
                    float d = (float) Math.sqrt((a - ac) * (a - ac) + b * b);
                    if (d >= rad) continue;
                    int x = Math.floorMod(cx + dx, S), y = Math.floorMod(cy + dy, S), i = y * S + x;
                    if (pass == 0) { R[i] *= 0.6f; G[i] *= 0.6f; B[i] *= 0.6f; continue; }
                    float prof = (float) Math.sqrt(1f - (b / rad) * (b / rad));
                    float bark = 0.75f + 0.25f * hash((int) (a * 1.5f) + x, (int) (b * 3)) + 0.2f * (fn.at((x + .5f) / S, (y + .5f) / S) - 0.5f);
                    float light = (0.55f + 0.5f * prof - 0.25f * (b / rad) * (c - s)) * bark;
                    float r = 0.38f * light, g = 0.29f * light, bb = 0.20f * light;
                    if (Math.abs(a) > len / 2 - 0.8f) { r = 0.62f * light; g = 0.50f * light; bb = 0.34f * light; }   // cut end
                    if (hash(x * 3, y) > 0.9f) { r = lerp(r, 0.30f, 0.6f); g = lerp(g, 0.42f, 0.6f); bb = lerp(bb, 0.18f, 0.6f); } // moss
                    R[i] = r; G[i] = g; B[i] = bb;
                    raise(i, rad / PPM * 2f * (0.5f + 0.5f * prof));
                }
    }

    /** Sawn tree stump with growth rings. */
    private static void stump(float[] R, float[] G, float[] B, int cx, int cy, float rad, Random rnd) {
        final int S = GROUND_SIZE;
        int ext = (int) (rad * 2.2f) + 2;
        float hgt = 0.35f + rnd.nextFloat() * 0.35f;
        for (int dy = -ext; dy <= ext; dy++)
            for (int dx = -ext; dx <= ext; dx++) {
                float ds = (float) Math.sqrt((dx - rad) * (dx - rad) + (dy - rad * 0.7f) * (dy - rad * 0.7f));
                if (ds < rad * 1.1f) {
                    int i = Math.floorMod(cy + dy, S) * S + Math.floorMod(cx + dx, S);
                    R[i] *= 0.6f; G[i] *= 0.6f; B[i] *= 0.6f;
                }
            }
        for (int dy = -ext; dy <= ext; dy++)
            for (int dx = -ext; dx <= ext; dx++) {
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d >= rad) continue;
                int x = Math.floorMod(cx + dx, S), y = Math.floorMod(cy + dy, S), i = y * S + x;
                float r, g, b;
                if (d > rad - 1.0f) { r = 0.30f; g = 0.23f; b = 0.16f; }               // bark rim
                else {
                    float ring = 0.85f + 0.15f * (float) Math.sin(d * 2.6f);
                    r = 0.66f * ring; g = 0.54f * ring; b = 0.38f * ring;
                }
                R[i] = r; G[i] = g; B[i] = b;
                raise(i, hgt);
            }
    }

    /** Molehill: a small dome of fresh dark soil. */
    private static void molehill(float[] R, float[] G, float[] B, int cx, int cy, float rad, Random rnd) {
        final int S = GROUND_SIZE;
        int ext = (int) rad + 2;
        for (int dy = -ext; dy <= ext; dy++)
            for (int dx = -ext; dx <= ext; dx++) {
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d >= rad) continue;
                int x = Math.floorMod(cx + dx, S), y = Math.floorMod(cy + dy, S), i = y * S + x;
                float dome = (float) Math.sqrt(1f - (d / rad) * (d / rad));
                float l = (0.7f + 0.4f * dome - 0.2f * (dx + dy) / rad) * (0.85f + 0.3f * hash(x, y));
                R[i] = 0.30f * l; G[i] = 0.22f * l; B[i] = 0.15f * l;
                raise(i, 0.18f * dome + 0.02f);
            }
    }

    /** Granite boulder with lichen and moss, lit from the north-west. */
    private static void rock(float[] R, float[] G, float[] B, int cx, int cy, float rad, Random rnd, PNoise fn) {
        final int S = GROUND_SIZE;
        int seed = rnd.nextInt(1 << 20);
        float k = rnd.nextFloat();
        float cr = 0.56f, cg = 0.56f, cb = 0.54f;
        if (k > 0.7f) { cr = 0.50f; cg = 0.51f; cb = 0.53f; }
        int ext = (int) Math.ceil(rad * 1.8f) + 2;
        float so = rad * 0.5f;
        for (int dy = -ext; dy <= ext; dy++)
            for (int dx = -ext; dx <= ext; dx++) {
                float sx = dx - so, sy = dy - so;
                float d = (float) Math.sqrt(sx * sx + sy * sy);
                float er = rad * (0.8f + 0.3f * lobe(seed + 3, Math.atan2(sy, sx), 6));
                if (d < er * 1.1f) {
                    int i = Math.floorMod(cy + dy, S) * S + Math.floorMod(cx + dx, S);
                    float s = 0.50f + 0.50f * smooth(er * 0.6f, er * 1.1f, d);
                    R[i] *= s; G[i] *= s; B[i] *= s;
                }
            }
        for (int dy = -ext; dy <= ext; dy++)
            for (int dx = -ext; dx <= ext; dx++) {
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                float er = rad * (0.75f + 0.4f * lobe(seed, Math.atan2(dy, dx), 7));
                if (d >= er) continue;
                int x = Math.floorMod(cx + dx, S), y = Math.floorMod(cy + dy, S), i = y * S + x;
                float n = fn.at((x + 0.5f) / S, (y + 0.5f) / S);
                float cell = Math.max(1.5f, rad * 0.5f);
                float facet = hash((int) Math.floor((dx + 0.5f * dy) / cell) + seed, (int) Math.floor((dy - 0.3f * dx) / cell));
                float dome = (float) Math.sqrt(Math.max(0, 1 - (d / er) * (d / er)));
                float light = 0.60f + 0.45f * (-(dx + dy) / (er * 1.5f)) + 0.20f * dome + 0.18f * (facet - 0.5f) + 0.12f * (n - 0.5f);
                if (d > er - 1.0f) light *= 0.8f;
                float r = cr * light, g = cg * light, b = cb * light;
                float moss = smooth(0.55f, 0.75f, n) * (dy > 0 ? 1f : 0.4f);    // moss on the shaded side
                r = lerp(r, 0.30f * light, moss); g = lerp(g, 0.40f * light, moss); b = lerp(b, 0.16f * light, moss);
                if (hash(x * 5, y + seed) > 0.95f) { r = 0.75f; g = 0.74f; b = 0.55f; }   // lichen flecks
                R[i] = r; G[i] = g; B[i] = b;
                raise(i, rad / PPM * 0.75f * (float) Math.pow(dome, 0.7) * (0.88f + 0.12f * facet));
            }
    }

    /** Ring of standing stones with long baked shadows and a flat altar stone in the middle. */
    private static void stoneCircle(float[] R, float[] G, float[] B, float wx, float wz, float radius, int count, PNoise fn, Random rnd) {
        final int S = GROUND_SIZE;
        // worn grass inside the ring
        int ext = (int) ((radius + 3) * PPM);
        int cx = (int) (wx * PPM), cy = (int) (wz * PPM);
        for (int dy = -ext; dy <= ext; dy++)
            for (int dx = -ext; dx <= ext; dx++) {
                float d = (float) Math.sqrt(dx * dx + dy * dy) / PPM;
                if (d < radius + 1.5f) {
                    int i = Math.floorMod(cy + dy, S) * S + Math.floorMod(cx + dx, S);
                    float k = smooth(radius + 1.5f, radius - 1f, d) * 0.35f;
                    R[i] = lerp(R[i], R[i] * 1.15f + 0.04f, k); G[i] = lerp(G[i], G[i] * 0.95f, k); B[i] = lerp(B[i], B[i] * 0.9f, k);
                }
            }
        for (int k = 0; k < count; k++) {
            double ang = TAU * k / count + rnd.nextFloat() * 0.12f;
            float sx = wx + (float) Math.cos(ang) * radius, sz = wz + (float) Math.sin(ang) * radius;
            float len = 0.55f + rnd.nextFloat() * 0.25f, wid = 0.32f + rnd.nextFloat() * 0.12f;
            menhir(R, G, B, sx, sz, len, wid, (float) ang + (float) Math.PI / 2, 2.6f + rnd.nextFloat() * 1.4f, fn, rnd.nextInt(9999),
                    2.1f + rnd.nextFloat() * 0.8f);
        }
        menhir(R, G, B, wx, wz, 1.2f, 0.7f, 0.3f, 0.6f, fn, 4242, 0.55f);
    }

    /** A standing stone seen from above: rotated rounded slab, lit top, long shadow to the south-east. */
    private static void menhir(float[] R, float[] G, float[] B, float wx, float wz, float halfLen, float halfWid, float rot,
                               float shadowLen, PNoise fn, int seed, float height) {
        final int S = GROUND_SIZE;
        float c = (float) Math.cos(rot), s = (float) Math.sin(rot);
        int ext = (int) ((halfLen + shadowLen + 1) * PPM);
        int cx = (int) (wx * PPM), cy = (int) (wz * PPM);
        for (int pass = 0; pass < 2; pass++)
            for (int dy = -ext; dy <= ext; dy++)
                for (int dx = -ext; dx <= ext; dx++) {
                    float mx = dx / PPM, mz = dy / PPM;
                    if (pass == 0) {                               // shadow: swept along (+1,+0.7)
                        boolean hit = false;
                        for (float t = 0; t <= shadowLen; t += 0.1f) {
                            float qx = mx - t, qz = mz - t * 0.7f;
                            float lx = qx * c + qz * s, lz = -qx * s + qz * c;
                            if ((lx / halfLen) * (lx / halfLen) + (lz / halfWid) * (lz / halfWid) < 1f) { hit = true; break; }
                        }
                        if (hit) {
                            int i = Math.floorMod(cy + dy, S) * S + Math.floorMod(cx + dx, S);
                            R[i] *= 0.55f; G[i] *= 0.55f; B[i] *= 0.58f;
                        }
                    } else {
                        float lx = mx * c + mz * s, lz = -mx * s + mz * c;
                        float e = (lx / halfLen) * (lx / halfLen) + (lz / halfWid) * (lz / halfWid);
                        if (e >= 1f) continue;
                        int x = Math.floorMod(cx + dx, S), y = Math.floorMod(cy + dy, S), i = y * S + x;
                        float n = fn.at((x + 0.5f) / S, (y + 0.5f) / S);
                        float light = 0.95f - 0.35f * (mx + mz) / (halfLen + halfWid) + 0.15f * (n - 0.5f) - 0.2f * e + 0.08f * (hash(x, y + seed) - 0.5f);
                        float r = 0.55f * light, g = 0.55f * light, b = 0.54f * light;
                        float moss = smooth(0.6f, 0.8f, n);
                        r = lerp(r, 0.32f * light, moss); g = lerp(g, 0.42f * light, moss); b = lerp(b, 0.20f * light, moss);
                        R[i] = r; G[i] = g; B[i] = b;
                        raise(i, height * (1f - 0.35f * e * e));
                    }
                }
    }

    /** Ring of small pale toadstools. */
    private static void fairyRing(float[] R, float[] G, float[] B, float wx, float wz, float radius, Random rnd) {
        final int S = GROUND_SIZE;
        int n = (int) (radius * 14);
        for (int k = 0; k < n; k++) {
            double ang = TAU * k / n + rnd.nextFloat() * 0.05;
            float rr = radius + (rnd.nextFloat() - 0.5f) * 0.25f;
            int x = Math.floorMod((int) ((wx + Math.cos(ang) * rr) * PPM), S), y = Math.floorMod((int) ((wz + Math.sin(ang) * rr) * PPM), S);
            int sh = Math.floorMod(y + 1, S) * S + Math.floorMod(x + 1, S);
            R[sh] *= 0.6f; G[sh] *= 0.6f; B[sh] *= 0.6f;
            int i = y * S + x;
            boolean red = rnd.nextFloat() < 0.25f;
            R[i] = red ? 0.78f : 0.90f; G[i] = red ? 0.25f : 0.86f; B[i] = red ? 0.18f : 0.76f;
            raise(i, 0.07f + 0.04f * rnd.nextFloat());
        }
        // darker, lusher grass band of the ring
        int ext = (int) ((radius + 1) * PPM), cx = (int) (wx * PPM), cy = (int) (wz * PPM);
        for (int dy = -ext; dy <= ext; dy++)
            for (int dx = -ext; dx <= ext; dx++) {
                float d = Math.abs((float) Math.sqrt(dx * dx + dy * dy) / PPM - radius);
                if (d < 0.45f) {
                    int i = Math.floorMod(cy + dy, S) * S + Math.floorMod(cx + dx, S);
                    float k = 1f - d / 0.45f;
                    G[i] *= 1f + 0.12f * k; R[i] *= 1f - 0.12f * k;
                }
            }
    }

    private static void well(float[] R, float[] G, float[] B, int cx, int cy, float ro, float ri, PNoise fn) {
        final int S = GROUND_SIZE;
        int ext = (int) ro + 6;
        for (int dy = -ext; dy <= ext; dy++)
            for (int dx = -ext; dx <= ext; dx++) {
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                int x = Math.floorMod(cx + dx, S), y = Math.floorMod(cy + dy, S), i = y * S + x;
                float sd = (float) Math.sqrt((dx - 4) * (dx - 4) + (dy - 4) * (dy - 4));
                if (d >= ro && sd < ro + 1) { R[i] *= 0.55f; G[i] *= 0.55f; B[i] *= 0.55f; }
                if (d < ri) {
                    float w = 0.06f + 0.10f * smooth(ri * 0.2f, ri, d + dx * 0.5f);
                    R[i] = w; G[i] = w * 1.05f; B[i] = w * 1.15f;
                    if (DH != null) DH[i] = 0f;
                } else if (d < ro) {
                    float ang = (float) Math.atan2(dy, dx);
                    boolean outer = d > (ri + ro) / 2;
                    float sf = ang * (outer ? 3.2f : 2.4f) + (outer ? 0.5f : 0f);
                    int stone = (int) Math.floor(sf) + (outer ? 50 : 0);
                    sf -= (float) Math.floor(sf);
                    float t = 0.72f + 0.3f * hash(stone, 5) - 0.22f * (dx + dy) / ro + 0.15f * (fn.at((x + 0.5f) / S, (y + 0.5f) / S) - 0.5f);
                    if (sf < 0.12f || Math.abs(d - (ri + ro) / 2) < 0.5f) t *= 0.55f;
                    R[i] = 0.60f * t; G[i] = 0.58f * t; B[i] = 0.53f * t;
                    raise(i, 0.75f);
                }
            }
    }

    private static final float[] BAYER = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};

    /** Quantise to 5 bits per channel with a 4x4 ordered dither. */
    private static int pack(float r, float g, float b, int x, int y) {
        float th = BAYER[(y & 3) * 4 + (x & 3)] / 16f;
        int ri = q5(r, th), gi = q5(g, th), bi = q5(b, th);
        return 0xFF000000 | (ri << 16) | (gi << 8) | bi;
    }

    private static int q5(float v, float th) {
        int q = (int) Math.floor(clamp(v) * 31f + th);
        if (q > 31) q = 31;
        return (q * 255 + 15) / 31;
    }

    // ------------------------------------------------------------------ clouds
    static synchronized int[] clouds() {
        if (cloudCache != null) return cloudCache;
        final int S = CLOUD_SIZE;
        Random rnd = new Random(77);
        PNoise[] oct = {new PNoise(4, rnd), new PNoise(8, rnd), new PNoise(16, rnd), new PNoise(32, rnd), new PNoise(64, rnd)};
        float[] amp = {0.42f, 0.26f, 0.16f, 0.10f, 0.06f};
        float[] v = new float[S * S];
        float mn = 1e9f, mx = -1e9f;
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                float u = (x + 0.5f) / S, w = (y + 0.5f) / S, s = 0;
                for (int o = 0; o < oct.length; o++) s += oct[o].at(u, w) * amp[o];
                v[y * S + x] = s;
                mn = Math.min(mn, s);
                mx = Math.max(mx, s);
            }
        int[] out = new int[S * S];
        for (int i = 0; i < v.length; i++) {
            int c = Math.round((v[i] - mn) / (mx - mn) * 255f);
            out[i] = 0xFF000000 | (c << 16) | (c << 8) | c;
        }
        cloudCache = out;
        return out;
    }
}
