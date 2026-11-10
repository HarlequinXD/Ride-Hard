package com.harlequinxd.eryndor;

/**
 * The alien town at the centre of the map: a 15 x 15 grid of 14 m lots around a central plaza,
 * cut by two broad boulevards (the north road runs through on one). Every other lot holds an alien
 * building of one of four kinds, drawn per pixel in scene.frag from distance-field shapes:
 *   1 dome   - a ribbed pearly dome with a ring of round windows and a little spire
 *   2 tower  - a tall swelling tower crowned with a bulb, glowing window bands
 *   3 pods   - three fused bubble pods
 *   4 hall   - a long ribbed shell like a seed husk
 * Each is hollow: you walk in through a porch whose door (two panels with glyphs and a glowing
 * seam, in a lit frame) slides open into the walls as you come near. Inside is one room on the
 * ground, lit by glowing strips.
 *
 * The same shapes are evaluated here (for walking into walls and keeping the camera out of them)
 * and in the shader (for drawing), so they must stay in step: see townDist in scene.frag.
 */
final class Town {
    private Town() { }

    static final int N = 15;                       // lots across
    static final float LOT = 14f;                  // metres per lot
    static final float CX = 128f, CZ = 128f;       // the plaza
    static final float X0 = CX - N * LOT / 2, Z0 = CZ - N * LOT / 2;
    static final float SPAN = N * LOT;
    static final float STREET = 1.6f;              // margin each side of a lot that stays street
    static final float GROUND = WorldTextures.q8(WorldTextures.VILLAGE_H + WorldTextures.BASE);

    /** Per lot: type (0 none, 1..4 buildings, 5 the plaza monument), rotation (0..3), size, height, seed. */
    static final int[] type = new int[N * N], rot = new int[N * N];
    static final float[] size = new float[N * N], tall = new float[N * N], seed = new float[N * N];

    static {
        java.util.Random r = new java.util.Random(4242);
        int c = N / 2;
        for (int j = 0; j < N; j++)
            for (int i = 0; i < N; i++) {
                int k = j * N + i;
                float a = r.nextFloat(), b = r.nextFloat(), s = r.nextFloat(), h = r.nextFloat(), e = r.nextFloat();
                if (i == c && j == c) { type[k] = 5; continue; }              // the plaza
                if (i == c || j == c) continue;                              // the boulevards
                if (Math.abs(i - c) <= 1 && Math.abs(j - c) <= 1) continue;  // open ground round the plaza
                if (a < 0.12f) continue;                                     // a few empty lots: little squares
                type[k] = 1 + (int) (b * 4f) % 4;
                // the door faces the nearer boulevard
                int di = c - i, dj = c - j;
                if (Math.abs(di) < Math.abs(dj)) rot[k] = di > 0 ? 1 : 3; else rot[k] = dj > 0 ? 0 : 2;
                if (Math.abs(di) == Math.abs(dj)) rot[k] = dj > 0 ? 0 : 2;
                size[k] = 0.86f + 0.22f * s;
                tall[k] = 0.85f + 0.45f * h;
                seed[k] = e;
            }
    }

    /** Lot data for the shader: RGBA per lot = type * 40, rotation * 64, size, height (0..255). */
    static byte[] texture() {
        byte[] t = new byte[N * N * 4];
        for (int k = 0; k < N * N; k++) {
            t[k * 4] = (byte) (type[k] * 40);
            t[k * 4 + 1] = (byte) (rot[k] * 64 + Math.round(seed[k] * 63));
            t[k * 4 + 2] = (byte) Math.round((size[k] - 0.8f) / 0.4f * 255f);
            t[k * 4 + 3] = (byte) Math.round((tall[k] - 0.8f) / 0.6f * 255f);
        }
        return t;
    }

    static boolean inTown(double x, double z) {
        return x > X0 - 2 && x < X0 + SPAN + 2 && z > Z0 - 2 && z < Z0 + SPAN + 2;
    }

    /** Lot index at a point, or -1. */
    static int lotAt(double x, double z) {
        int i = (int) Math.floor((x - X0) / LOT), j = (int) Math.floor((z - Z0) / LOT);
        return (i < 0 || j < 0 || i >= N || j >= N) ? -1 : j * N + i;
    }

    // ------------------------------------------------------------------ shapes (mirror scene.frag)
    private static float len(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private static float ell(float x, float y, float z, float rx, float ry, float rz) {
        float k0 = len(x / rx, y / ry, z / rz), k1 = len(x / (rx * rx), y / (ry * ry), z / (rz * rz));
        return k0 * (k0 - 1f) / Math.max(k1, 1e-6f);
    }

    private static float smin(float a, float b, float k) {
        float h = Math.max(0f, Math.min(1f, 0.5f + 0.5f * (b - a) / k));
        return b + (a - b) * h - k * h * (1 - h);
    }

    private static float box(float x, float y, float z, float hx, float hy, float hz, float r) {
        float qx = Math.abs(x) - hx + r, qy = Math.abs(y) - hy + r, qz = Math.abs(z) - hz + r;
        float o = len(Math.max(qx, 0f), Math.max(qy, 0f), Math.max(qz, 0f));
        return o + Math.min(Math.max(qx, Math.max(qy, qz)), 0f) - r;
    }

    /** The front of each kind (where its porch meets the wall), local z. */
    static float front(int t, float s) {
        switch (t) {
            case 1: return 4.1f * s;
            case 2: return 2.4f * s + 0.35f;
            case 3: return 4.0f * s;
            default: return 3.1f * s;
        }
    }

    /** Outer body of a building (local coordinates, y up from the floor). */
    static float body(int t, float s, float h, float x, float y, float z) {
        switch (t) {
            case 1:
                return ell(x, y + 0.8f, z, 4.3f * s, 4.6f * s * h, 4.3f * s);
            case 2: {
                float H = 11f * h * s;
                float r = s * (2.4f + 0.45f * (float) Math.sin(y * 0.45f)) * (1f - 0.30f * smoothstep(3f, H, y));
                float o = Math.max((float) Math.hypot(x, z) - r, Math.max(y - H, -y - 1f));
                return smin(o, len(x, y - H - 1.0f, z) - 1.5f * s, 0.6f);
            }
            case 3: {
                float a = ell(x, y + 0.5f, z - 1.2f * s, 3.0f * s, 3.4f * s * h, 3.0f * s);
                float b = ell(x + 2.4f * s, y + 0.5f, z + 1.6f * s, 2.6f * s, 3.0f * s * h, 2.6f * s);
                float c = ell(x - 2.5f * s, y + 0.5f, z + 1.4f * s, 2.3f * s, 2.7f * s * h, 2.3f * s);
                return smin(smin(a, b, 0.8f), c, 0.8f);
            }
            default:
                return ell(x, y + 0.6f, z, 4.8f * s, 3.6f * s * h, 3.2f * s);
        }
    }

    /** The hollow inside (negative in the room). */
    static float cavity(int t, float s, float h, float x, float y, float z) {
        final float w = 0.35f;
        switch (t) {
            case 1: return ell(x, y + 0.8f, z, 4.3f * s - w, 4.6f * s * h - w, 4.3f * s - w);
            case 2: {
                float r = s * (2.4f + 0.45f * (float) Math.sin(y * 0.45f)) - w;
                return Math.max((float) Math.hypot(x, z) - r, Math.max(y - 3.4f, -y - 1f));
            }
            case 3: {
                float a = ell(x, y + 0.5f, z - 1.2f * s, 3.0f * s - w, 3.4f * s * h - w, 3.0f * s - w);
                float b = ell(x + 2.4f * s, y + 0.5f, z + 1.6f * s, 2.6f * s - w, 3.0f * s * h - w, 2.6f * s - w);
                float c = ell(x - 2.5f * s, y + 0.5f, z + 1.4f * s, 2.3f * s - w, 2.7f * s * h - w, 2.3f * s - w);
                return smin(smin(a, b, 0.8f), c, 0.8f);
            }
            default: return ell(x, y + 0.6f, z, 4.8f * s - w, 3.6f * s * h - w, 3.2f * s - w);
        }
    }

    /** The building's walls (with porch and doorway; the door itself is not included). */
    static float walls(int t, float s, float h, float x, float y, float z) {
        float f = front(t, s);
        float o = body(t, s, h, x, y, z);
        float porch = box(x, y - 1.45f, z - (f - 0.3f), 1.15f, 1.45f, 0.9f, 0.45f);
        o = smin(o, porch, 0.3f);
        float cav = cavity(t, s, h, x, y, z);
        cav = Math.min(cav, box(x, y - 1.15f, z - (f - 0.5f), 0.70f, 1.15f, 1.2f, 0.25f));   // through the porch
        return Math.max(o, -cav);
    }

    private static float smoothstep(float a, float b, float x) {
        float t = Math.max(0f, Math.min(1f, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    /** Distance to the town's walls at a world point (large where there are none). */
    static float dist(double wx, double wy, double wz) {
        int k = lotAt(wx, wz);
        if (k < 0 || type[k] == 0) return 99f;
        int i = k % N, j = k / N;
        float lx = (float) (wx - (X0 + (i + 0.5f) * LOT)), lz = (float) (wz - (Z0 + (j + 0.5f) * LOT));
        float ly = (float) (wy - GROUND);
        if (type[k] == 5) return Math.max((float) Math.hypot(lx, lz) - 1.6f, ly - 9f);   // the monument's foot
        // rotate into the lot's frame (door toward +z)
        float x, z;
        switch (rot[k]) {
            case 1: x = -lz; z = lx; break;
            case 2: x = -lx; z = -lz; break;
            case 3: x = lz; z = -lx; break;
            default: x = lx; z = lz;
        }
        return walls(type[k], size[k], tall[k], x, ly, z);
    }

    /** Whether a point is inside a building's room (for the light). */
    static boolean inside(double wx, double wy, double wz) {
        int k = lotAt(wx, wz);
        if (k < 0 || type[k] == 0 || type[k] == 5) return false;
        int i = k % N, j = k / N;
        float lx = (float) (wx - (X0 + (i + 0.5f) * LOT)), lz = (float) (wz - (Z0 + (j + 0.5f) * LOT));
        float x, z;
        switch (rot[k]) {
            case 1: x = -lz; z = lx; break;
            case 2: x = -lx; z = -lz; break;
            case 3: x = lz; z = -lx; break;
            default: x = lx; z = lz;
        }
        return cavity(type[k], size[k], tall[k], x, (float) (wy - GROUND), z) < 0f;
    }
}
