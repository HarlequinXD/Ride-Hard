package com.harlequinxd.ridehard;

import java.util.Random;

/**
 * Procedurally painted textures (pure Java, no Android dependencies).
 *
 * Ground: 2048 x 2048, 8 px per metre, tiles every 256 m. Photographic look at a low texel density, with baked
 * shadows and ordered dithering to 15-bit colour. Layout (metres): main street runs north-south at
 * x = 128, a cross street east-west at z = 128, boardwalks along the main street in town, and a
 * railroad east-west at z = 30. Everything wraps seamlessly.
 *
 * Clouds: 256 x 256 tileable fbm, grey.
 */
final class WorldTextures {

    static final int GROUND_SIZE = 2048;
    static final float WORLD_SIZE = 256f;           // metres per texture repeat
    static final float PPM = GROUND_SIZE / WORLD_SIZE;
    static final int CLOUD_SIZE = 256;

    private static int[] groundCache, cloudCache;

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

    // ------------------------------------------------------------------ ground
    /**
     * Photographic-style ground at a deliberately low texel density (12.5 cm per texel), like a
     * downsampled photo: layered soil colour, grains and pebbles, cracked hardpan, soft wagon ruts,
     * weathered boardwalk planks and a ballasted railroad, with baked soft shadows.
     */
    static synchronized int[] ground() {
        if (groundCache != null) return groundCache;
        final int S = GROUND_SIZE;
        final float[] R = new float[S * S], G = new float[S * S], B = new float[S * S];
        final byte[] busy = new byte[S * S];            // 1 = road / rail / boardwalk: no scatter
        Random rnd = new Random(1873);
        PNoise c1 = new PNoise(4, rnd), c2 = new PNoise(12, rnd), m1 = new PNoise(48, rnd),
                m2 = new PNoise(160, rnd), f1 = new PNoise(512, rnd), f2 = new PNoise(1024, rnd),
                streakNS = new PNoise(1024, 40, rnd), streakEW = new PNoise(40, 1024, rnd),
                grainNS = new PNoise(256, 16, rnd);

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

                // ---- natural soil
                float t = smooth(0.30f, 0.72f, big);
                float r = lerp(0.80f, 0.69f, t), g = lerp(0.69f, 0.54f, t), b = lerp(0.54f, 0.40f, t);
                float red = smooth(0.55f, 0.80f, big2) * 0.7f;
                r += 0.015f * red; g -= 0.06f * red; b -= 0.075f * red;
                float hard = smooth(0.60f, 0.67f, m1.at(u + 0.31f, v + 0.17f) * 0.6f + c2.at(u + 0.1f, v + 0.6f) * 0.4f);
                float lum = 0.90f + 0.16f * (mid - 0.5f) + 0.11f * (fine - 0.5f) + 0.07f * (h - 0.5f);
                lum *= 1f + 0.03f * (float) Math.sin((wx * 0.55f + wz * 1.9f) * 2.2f + mid * 10f) * (1f - hard); // wind ripples
                if (h2 > 0.975f) lum *= 0.82f;                  // dark grains
                else if (h2 < 0.015f) lum *= 1.10f;             // quartz
                r *= lum; g *= lum; b *= lum;

                // ---- cracked hardpan
                if (hard > 0.001f) {
                    float ml = 0.94f + 0.08f * (fine - 0.5f) + 0.04f * (h - 0.5f);
                    float mr = 0.80f * ml, mg = 0.67f * ml, mb = 0.53f * ml;
                    float cd = crackDist(wx + (fine - 0.5f) * 0.25f, wz + (mid - 0.5f) * 0.25f, 0.85f);
                    float ck = 0.5f + 0.5f * hash((int) (wx * 3f), (int) (wz * 3f));
                    if (cd < 0.035f) { float c = 1f - 0.28f * ck; mr *= c; mg *= c; mb *= c; }
                    else if (cd < 0.09f) { mr *= 1.03f; mg *= 1.03f; mb *= 1.03f; }   // curled lip catches light
                    r = lerp(r, mr, hard * 0.8f); g = lerp(g, mg, hard * 0.8f); b = lerp(b, mb, hard * 0.8f);
                }

                // ---- gravel (2x2 texel pebbles), denser in patches
                float gravel = 0.015f + 0.09f * smooth(0.55f, 0.80f, m1.at(u + 0.7f, v + 0.1f));
                float roadMask = 0f;

                // ---- main street (north-south) and trail out of town
                float dx = wrapD(wx, 128f);
                float edgeN = (m2.at(u * 2f, v * 2f) - 0.5f) * 1.2f;
                float townFade = smooth(60f, 70f, wz) * (1f - smooth(186f, 196f, wz));
                float hw = 2.3f + 5.3f * townFade + edgeN;
                float sd = Math.abs(dx);
                if (sd < hw + 0.9f) {
                    float a = 1f - smooth(hw - 0.9f, hw + 0.9f, sd + (fine - 0.5f) * 0.8f);
                    float[] c = road(dx, wz, streakNS.at(u, v), grainNS.at(u, v), fine, h, townFade);
                    r = lerp(r, c[0], a); g = lerp(g, c[1], a); b = lerp(b, c[2], a);
                    roadMask = Math.max(roadMask, a);
                }
                // ---- cross street (east-west)
                float dz = wrapD(wz, 128f);
                float xFade = smooth(58f, 70f, wx) * (1f - smooth(186f, 198f, wx));
                float hw2 = 6.0f * xFade + edgeN;
                if (xFade > 0.01f && Math.abs(dz) < hw2 + 0.9f) {
                    float a = 1f - smooth(hw2 - 0.9f, hw2 + 0.9f, Math.abs(dz) + (fine - 0.5f) * 0.8f);
                    float[] c = road(dz, wx, streakEW.at(u, v), grainNS.at(v, u), fine, h, xFade);
                    r = lerp(r, c[0], a); g = lerp(g, c[1], a); b = lerp(b, c[2], a);
                    roadMask = Math.max(roadMask, a);
                }
                gravel *= 1f - 0.8f * roadMask;
                if (roadMask > 0.5f) busy[i] = 1;

                // pebbles
                int gx = x >> 1, gy = y >> 1;
                float ph = hash(gx * 3 + 1, gy * 5 + 2);
                if (ph < gravel) {
                    float k = hash(gx + 77, gy + 13);
                    float pr, pg, pb;
                    if (k < 0.35f) { pr = 0.58f; pg = 0.55f; pb = 0.51f; }
                    else if (k < 0.65f) { pr = 0.60f; pg = 0.42f; pb = 0.31f; }
                    else if (k < 0.85f) { pr = 0.84f; pg = 0.79f; pb = 0.70f; }
                    else { pr = 0.36f; pg = 0.34f; pb = 0.31f; }
                    float sh = ((x & 1) + (y & 1)) == 0 ? 1.15f : (((x & 1) & (y & 1)) == 1 ? 0.70f : 0.95f);
                    float pv = 0.85f + 0.3f * hash(gx, gy + 999);
                    float pa = 0.35f + 0.3f * hash(gx * 5, gy * 7);
                    r = lerp(r, pr * sh * pv, pa); g = lerp(g, pg * sh * pv, pa); b = lerp(b, pb * sh * pv, pa);
                }

                // ---- boardwalks along the main street, broken at the crossing
                boolean walkZ = wz > 72f && wz < 184f && Math.abs(dz) > 8.6f;
                if (walkZ && sd > 8.1f && sd < 11.4f) {
                    busy[i] = 1;
                    int plank = (int) Math.floor(wz / 0.30f);
                    float seam = wz / 0.30f - plank;
                    int side = dx > 0 ? 1 : 0;
                    float pt = hash(plank, side + 3);
                    // weathered: grey-brown, some boards greyer, some browner
                    float wr = lerp(0.60f, 0.54f, pt), wg = lerp(0.50f, 0.49f, pt), wb = lerp(0.39f, 0.44f, pt);
                    float grain = grainNS.at(v * 4f, u) * 0.6f + f2.at(u, v) * 0.4f;   // grain runs across the walk
                    float wl = 0.80f + 0.30f * grain + 0.06f * (h - 0.5f) + 0.12f * (hash(plank, side + 9) - 0.5f);
                    wr *= wl; wg *= wl; wb *= wl;
                    if (seam < 0.15f) { wr *= 0.45f; wg *= 0.45f; wb *= 0.45f; }         // gap
                    else if (seam > 0.88f) { wr *= 0.85f; wg *= 0.85f; wb *= 0.85f; }
                    float joist = ((sd - 8.1f) % 1.1f);
                    if (joist > 0.5f && joist < 0.6f && seam > 0.3f && seam < 0.75f && hash(plank, (int) (sd * 3)) > 0.4f) {
                        wr = 0.22f; wg = 0.20f; wb = 0.18f;                                  // nail heads
                    }
                    if (hash(plank, side + 21) > 0.975f) { wr *= 0.45f; wg *= 0.45f; wb *= 0.45f; } // broken board
                    float dust = smooth(0.55f, 0.85f, fine + (1f - smooth(8.1f, 8.8f, sd)) * 0.4f + (smooth(10.7f, 11.4f, sd)) * 0.4f);
                    r = lerp(wr, r, dust * 0.6f); g = lerp(wg, g, dust * 0.6f); b = lerp(wb, b, dust * 0.6f);
                    if (sd < 8.3f || sd > 11.2f) { r *= 0.75f; g *= 0.75f; b *= 0.75f; }
                } else if (walkZ && sd > 7.3f && sd <= 8.1f) {
                    float s = 0.60f + 0.40f * smooth(7.3f, 8.1f, 15.4f - sd);           // soft shadow under the walk edge
                    r *= s; g *= s; b *= s;
                }

                // ---- railroad east-west at z = 30
                float rz = wrapD(wz, 30f), ar = Math.abs(rz);
                if (ar < 2.3f) {
                    busy[i] = 1;
                    float ba = 1f - smooth(1.6f, 2.3f, ar + (fine - 0.5f) * 0.6f);
                    // crushed stone: per-stone (2 texel) greys, some rust-stained
                    int sx = x >> 1, sy = (y + (x >> 1 & 1)) >> 1;
                    float sk = hash(sx * 7, sy * 3);
                    float gr = 0.40f + 0.28f * sk + 0.06f * (h - 0.5f);
                    float shs = ((x & 1) == 0 && (y & 1) == 0) ? 1.12f : ((x & 1) == 1 && (y & 1) == 1 ? 0.78f : 1f);
                    float rust = smooth(0.4f, 1.1f, 1.3f - Math.min(Math.abs(rz - 0.72f), Math.abs(rz + 0.72f)) * 2f) * 0.25f;
                    float br = gr * shs * (1.02f + rust * 0.5f), bg = gr * shs * (0.98f - rust * 0.2f), bb = gr * shs * (0.94f - rust * 0.5f);
                    r = lerp(r, br, ba); g = lerp(g, bg, ba); b = lerp(b, bb, ba);
                    int tie = (int) Math.floor(wx / 0.60f);
                    float tf = wx / 0.60f - tie;
                    float tieLen = 1.25f + 0.08f * (hash(tie, 31) - 0.5f);
                    float tieW = 0.36f + 0.06f * hash(tie, 41);
                    if (ar < tieLen && tf < tieW) {
                        float tg = grainNS.at(u, v) * 0.5f + f1.at(u, v) * 0.5f;
                        float tt = 0.75f + 0.35f * tg + 0.12f * (hash(tie, 11) - 0.5f);
                        r = 0.40f * tt; g = 0.34f * tt; b = 0.28f * tt;                       // weathered grey sleepers
                        if (tf < 0.05f || tf > tieW - 0.05f) { r *= 0.72f; g *= 0.72f; b *= 0.72f; }
                        if (hash(tie * 3 + (int) (ar * 6), 17) > 0.93f) { r *= 0.6f; g *= 0.6f; b *= 0.6f; } // splits
                    } else if (ar < tieLen && tf < tieW + 0.07f) {
                        r *= 0.72f; g *= 0.72f; b *= 0.72f;                                   // sleeper shadow
                    }
                    float rail = Math.min(Math.abs(rz - 0.72f), Math.abs(rz + 0.72f));
                    if (rail < 0.06f) {
                        if (rail < 0.025f) { r = 0.70f; g = 0.69f; b = 0.68f; }              // polished head
                        else { r = 0.36f; g = 0.25f; b = 0.18f; }                            // rusty web
                        float n = 0.9f + 0.2f * f2.at(u, v);
                        r *= n; g *= n; b *= n;
                    } else if (rail < 0.14f && ((rz > 0.72f) || (rz > -0.72f && rz < 0f))) {
                        r *= 0.62f; g *= 0.62f; b *= 0.62f;
                    }
                }

                R[i] = r; G[i] = g; B[i] = b;
            }
        }

        // ---- scatter
        for (int k = 0; k < 3500; k++) {                         // dry grass clumps
            int cx = rnd.nextInt(S), cy = rnd.nextInt(S);
            if (busy[cy * S + cx] != 0) continue;
            tuft(R, G, B, cx, cy, rnd);
        }
        for (int k = 0; k < 650; k++) {                          // sandstone rocks
            int cx = rnd.nextInt(S), cy = rnd.nextInt(S);
            if (busy[cy * S + cx] != 0) continue;
            float rad = 1.3f + rnd.nextFloat() * rnd.nextFloat() * 7f;
            rock(R, G, B, cx, cy, rad, rnd, f1);
        }
        for (int k = 0; k < 950; k++) {                          // sagebrush and dead brush
            int cx = rnd.nextInt(S), cy = rnd.nextInt(S);
            if (busy[cy * S + cx] != 0) continue;
            float rad = 3.5f + rnd.nextFloat() * 6.5f;
            bush(R, G, B, cx, cy, rad, rnd.nextFloat() < 0.3f, rnd.nextInt(1 << 20), f2);
        }
        well(R, G, B, (int) (147 * PPM), (int) (143 * PPM), 1.35f * PPM, 0.85f * PPM, f1);

        int[] out = new int[S * S];
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                int i = y * S + x;
                out[i] = pack(R[i], G[i], B[i], x, y);
            }
        groundCache = out;
        return out;
    }

    /** Packed dirt road cross-section: soft meandering wagon ruts, compacted lanes, loose dust. */
    private static float[] road(float across, float along, float streak, float grain, float fine, float h, float town) {
        float l = 0.88f + 0.16f * (streak - 0.5f) + 0.08f * (grain - 0.5f) + 0.07f * (fine - 0.5f) + 0.05f * (h - 0.5f);
        float r = 0.70f * l, g = 0.58f * l, b = 0.46f * l;
        float[] ruts = town > 0.5f ? new float[]{-3.4f, -1.9f, 1.9f, 3.4f} : new float[]{-0.76f, 0.76f};
        float wob = 0.12f * (float) Math.sin(along * 0.11f + across * 0.3f) + 0.06f * (float) Math.sin(along * 0.37f);
        float m = 1f;
        for (float o : ruts) {
            float d = Math.abs(across - o - wob);
            m -= 0.20f * (float) Math.exp(-(d / 0.15f) * (d / 0.15f));                    // rut
            m += 0.07f * (float) Math.exp(-((d - 0.33f) / 0.12f) * ((d - 0.33f) / 0.12f)); // berm
        }
        // loose lighter dust in the crown between the ruts
        m += 0.05f * smooth(0.55f, 0.8f, streak);
        // hoof prints: small soft depressions
        int hx = (int) Math.floor(along / 0.5f), hy = (int) Math.floor(across / 0.45f);
        if (hash(hx * 3 + 1, hy + 50) > 0.84f) {
            float fx = along / 0.5f - hx - 0.5f, fy = across / 0.45f - hy - 0.5f;
            float d2 = fx * fx * 9f + fy * fy * 12f;
            if (d2 < 1f) m *= 0.84f + 0.16f * d2;
        }
        return new float[]{r * m, g * m, b * m};
    }

    private static void tuft(float[] R, float[] G, float[] B, int cx, int cy, Random rnd) {
        final int S = GROUND_SIZE;
        int blades = 4 + rnd.nextInt(7);
        float t = 0.85f + rnd.nextFloat() * 0.25f;
        // dark base
        for (int dy = 0; dy <= 1; dy++)
            for (int dx = 0; dx <= 1; dx++) {
                int i = Math.floorMod(cy + dy, S) * S + Math.floorMod(cx + dx, S);
                R[i] *= 0.78f; G[i] *= 0.78f; B[i] *= 0.78f;
            }
        for (int k = 0; k < blades; k++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            int len = 1 + rnd.nextInt(3);
            float bl = t * (0.85f + 0.3f * rnd.nextFloat());
            for (int s = 1; s <= len; s++) {
                int x = Math.floorMod(cx + (int) Math.round(Math.cos(ang) * s), S);
                int y = Math.floorMod(cy + (int) Math.round(Math.sin(ang) * s), S);
                int i = y * S + x;
                R[i] = lerp(R[i], 0.80f * bl, 0.6f); G[i] = lerp(G[i], 0.72f * bl, 0.6f); B[i] = lerp(B[i], 0.52f * bl, 0.6f);
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

    /** Sagebrush seen from above: grey-green foliage clumps with dark gaps and a soft shadow. */
    private static void bush(float[] R, float[] G, float[] B, int cx, int cy, float rad, boolean dead, int seed, PNoise fn) {
        final int S = GROUND_SIZE;
        int ext = (int) Math.ceil(rad * 1.9f) + 2;
        float so = rad * 0.55f;
        for (int dy = -ext; dy <= ext; dy++)
            for (int dx = -ext; dx <= ext; dx++) {
                float sx = dx - so, sy = dy - so;
                double ang = Math.atan2(sy, sx);
                float er = rad * (0.85f + 0.35f * lobe(seed + 1, ang, 9));
                float d = (float) Math.sqrt(sx * sx + sy * sy * 1.2f);
                if (d < er * 1.15f) {
                    int i = Math.floorMod(cy + dy, S) * S + Math.floorMod(cx + dx, S);
                    float s = 0.52f + 0.48f * smooth(er * 0.55f, er * 1.15f, d);
                    R[i] *= s; G[i] *= s; B[i] *= s;
                }
            }
        for (int dy = -ext; dy <= ext; dy++)
            for (int dx = -ext; dx <= ext; dx++) {
                double ang = Math.atan2(dy, dx);
                float er = rad * (0.75f + 0.45f * lobe(seed, ang, 11));
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d >= er) continue;
                int x = Math.floorMod(cx + dx, S), y = Math.floorMod(cy + dy, S), i = y * S + x;
                float n = fn.at((x + 0.5f) / S, (y + 0.5f) / S);
                float hh = hash(x * 3 + seed, y);
                float density = n * 0.6f + hh * 0.4f + 0.35f * (1f - d / er) - (dead ? 0.25f : 0f);
                if (density > 0.55f) {
                    float light = 0.78f + 0.40f * (-(dx + dy) / (er * 1.6f)) + 0.25f * (hh - 0.5f) + 0.15f * (1f - d / er);
                    if (dead) { R[i] = 0.56f * light; G[i] = 0.46f * light; B[i] = 0.34f * light; }
                    else {
                        float sg = hash(x, y * 5 + seed);       // mix of sage greys and olive
                        R[i] = lerp(0.46f, 0.52f, sg) * light; G[i] = lerp(0.50f, 0.52f, sg) * light; B[i] = lerp(0.38f, 0.45f, sg) * light;
                    }
                } else {
                    float s = 0.42f + 0.2f * hh;                // shadowed ground through the gaps
                    R[i] *= s; G[i] *= s; B[i] *= s;
                }
            }
    }

    /** Irregular sandstone rock with noise-based facets, lit from the north-west. */
    private static void rock(float[] R, float[] G, float[] B, int cx, int cy, float rad, Random rnd, PNoise fn) {
        final int S = GROUND_SIZE;
        int seed = rnd.nextInt(1 << 20);
        float k = rnd.nextFloat();
        float cr, cg, cb;
        if (k < 0.45f) { cr = 0.70f; cg = 0.52f; cb = 0.40f; }        // red sandstone
        else if (k < 0.80f) { cr = 0.72f; cg = 0.64f; cb = 0.54f; }   // tan
        else { cr = 0.55f; cg = 0.53f; cb = 0.50f; }                  // grey
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
                float facet = hash((int) Math.floor((dx + 0.5f * dy) / Math.max(1.5f, rad * 0.5f)) + seed, (int) Math.floor((dy - 0.3f * dx) / Math.max(1.5f, rad * 0.5f)));
                float dome = (float) Math.sqrt(Math.max(0, 1 - (d / er) * (d / er)));
                float light = 0.60f + 0.45f * (-(dx + dy) / (er * 1.5f)) + 0.20f * dome + 0.18f * (facet - 0.5f) + 0.12f * (n - 0.5f);
                if (d > er - 1.0f) light *= 0.8f;
                R[i] = cr * light; G[i] = cg * light; B[i] = cb * light;
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
                    float w = 0.06f + 0.10f * smooth(ri * 0.2f, ri, d + dx * 0.5f);   // dark shaft
                    R[i] = w; G[i] = w * 1.05f; B[i] = w * 1.15f;
                } else if (d < ro) {
                    float ang = (float) Math.atan2(dy, dx);
                    boolean outer = d > (ri + ro) / 2;
                    int stone = (int) Math.floor(ang * (outer ? 3.2f : 2.4f) + (outer ? 0.5f : 0f)) + (outer ? 50 : 0);
                    float sf = ang * (outer ? 3.2f : 2.4f) + (outer ? 0.5f : 0f);
                    sf -= Math.floor(sf);
                    float t = 0.72f + 0.3f * hash(stone, 5) - 0.22f * (dx + dy) / ro + 0.15f * (fn.at((x + 0.5f) / S, (y + 0.5f) / S) - 0.5f);
                    if (sf < 0.12f || Math.abs(d - (ri + ro) / 2) < 0.5f) t *= 0.55f;   // mortar joints
                    R[i] = 0.63f * t; G[i] = 0.58f * t; B[i] = 0.51f * t;
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
