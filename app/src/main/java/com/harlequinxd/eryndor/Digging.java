package com.harlequinxd.eryndor;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Digging with the pickaxe. Every blow bites a rough scoop out of the voxel volume where it lands
 * and knocks loose a handful of dirt clods. The clods fly off the face, tumble, bounce and roll
 * under gravity against the real terrain, then crumble away: nothing is added back, so hitting the
 * same spot again and again just digs the hole deeper. Dug ground shows as bare dirt.
 *
 * Changes are kept as a list of edits per 32 m chunk (the same grid as the streamed world), so
 * they are applied to the window as they happen and again whenever a chunk is regenerated.
 */
final class Digging {
    private Digging() { }

    /** Edits per chunk: x, y, z, radius, amount (+ carves away, - adds ground), seed. */
    private static final ConcurrentHashMap<Long, List<float[]>> edits = new ConcurrentHashMap<>();

    /** Disturbed (bare dirt) mask over the 2D window, 0.5 m cells, like the heights. */
    static final byte[] digWin = new byte[WorldTextures.HEIGHT_SIZE * WorldTextures.HEIGHT_SIZE];

    // ------------------------------------------------------------------ clods
    static final int MAX_CLODS = 48;

    static final class Clod {
        float x, y, z, vx, vy, vz, r, rest, age;
        int seed;
    }

    final static ArrayList<Clod> clods = new ArrayList<>();
    private static final Random rnd = new Random(77);
    private static final boolean DEBUG = System.getenv("ERYNDOR_DIG") != null;

    /** Rough dirty lumps, packed for the shader: xyz (world) and radius. */
    static int packClods(float[] out, double offX, double offZ) {
        int n = Math.min(clods.size(), MAX_CLODS);
        for (int i = 0; i < n; i++) {
            Clod c = clods.get(i);
            out[i * 4] = (float) (c.x - offX);
            out[i * 4 + 1] = c.y;
            out[i * 4 + 2] = (float) (c.z - offZ);
            out[i * 4 + 3] = c.r;
        }
        return n;
    }

    // ------------------------------------------------------------------ a blow
    /** Region of the window changed this frame (cells), for re-uploading: x0, y0, z0, x1, y1, z1. */
    static final int[] dirtyVol = new int[6];
    static final int[] dirtyDig = new int[4];

    static {
        clearDirty();
    }

    static boolean volDirty() {
        return dirtyVol[0] <= dirtyVol[3];
    }

    static boolean digDirty() {
        return dirtyDig[0] <= dirtyDig[2];
    }

    /**
     * The pickaxe head comes down along the view ray. Returns true if it struck the ground (then
     * the hole is cut and the clods are thrown).
     */
    static boolean strike(double ex, double ey, double ez, float dx, float dy, float dz) {
        double t = 0.25;
        boolean hit = false;
        for (int i = 0; i < 90 && t < 3.0; i++) {
            float d = Voxels.distance(ex + dx * t, ey + dy * t, ez + dz * t);
            if (d < 0.02f) { hit = true; break; }
            t += Math.max(0.03, d * 0.8);
        }
        if (!hit) return false;
        float px = (float) (ex + dx * t), py = (float) (ey + dy * t), pz = (float) (ez + dz * t);
        float[] n = normal(px, py, pz);
        if (DEBUG) System.out.printf("dig: hit %.2f %.2f %.2f normal %.2f %.2f %.2f%n", px, py, pz, n[0], n[1], n[2]);
        // the bite: a rough scoop centred just inside the face
        float cx = px - n[0] * 0.18f, cy = py - n[1] * 0.18f, cz = pz - n[2] * 0.18f;
        addEdit(new float[]{cx, cy, cz, 0.70f, 0.17f + rnd.nextFloat() * 0.05f, rnd.nextInt(1 << 20)});
        // the loosened dirt: clods break off the face and fly out, mostly down and away
        int count = 5 + rnd.nextInt(5);
        for (int k = 0; k < count && clods.size() < MAX_CLODS; k++) {
            Clod c = new Clod();
            float jx = (rnd.nextFloat() - 0.5f) * 0.35f, jy = (rnd.nextFloat() - 0.5f) * 0.25f, jz = (rnd.nextFloat() - 0.5f) * 0.35f;
            c.x = px + n[0] * 0.12f + jx;
            c.y = py + n[1] * 0.12f + jy + 0.05f;
            c.z = pz + n[2] * 0.12f + jz;
            // thrown out of the bite: off the face, sideways in any direction, a little back toward you
            float sp = 0.6f + rnd.nextFloat() * 1.6f;
            double a = rnd.nextDouble() * Math.PI * 2;
            float side = 1.4f + rnd.nextFloat() * 2.0f;
            c.vx = n[0] * sp + (float) Math.cos(a) * side - dx * 0.8f;
            c.vy = n[1] * sp * 0.5f + 1.0f + rnd.nextFloat() * 1.6f;
            c.vz = n[2] * sp + (float) Math.sin(a) * side - dz * 0.8f;
            c.r = 0.035f + rnd.nextFloat() * rnd.nextFloat() * 0.075f;
            c.seed = rnd.nextInt(1 << 20);
            clods.add(c);
        }
        return true;
    }

    private static float[] normal(float x, float y, float z) {
        float e = 0.12f;
        float gx = Voxels.distance(x + e, y, z) - Voxels.distance(x - e, y, z);
        float gy = Voxels.distance(x, y + e, z) - Voxels.distance(x, y - e, z);
        float gz = Voxels.distance(x, y, z + e) - Voxels.distance(x, y, z - e);
        float l = (float) Math.sqrt(gx * gx + gy * gy + gz * gz);
        if (l < 1e-5f) return new float[]{0, 1, 0};
        return new float[]{gx / l, gy / l, gz / l};
    }

    /** Clods fly, bounce, roll and settle; settled ones become ground. Call once per frame. */
    static void update(float dt) {
        if (clods.isEmpty()) return;
        int steps = 2;
        float h = dt / steps;
        for (int i = clods.size() - 1; i >= 0; i--) {
            Clod c = clods.get(i);
            c.age += dt;
            for (int s = 0; s < steps; s++) {
                c.vy -= 9.81f * h;
                c.x += c.vx * h;
                c.y += c.vy * h;
                c.z += c.vz * h;
                float d = Voxels.distance(c.x, c.y, c.z);
                if (d < c.r) {
                    float[] n = normal(c.x, c.y, c.z);
                    float push = c.r - d;
                    c.x += n[0] * push;
                    c.y += n[1] * push;
                    c.z += n[2] * push;
                    float vn = c.vx * n[0] + c.vy * n[1] + c.vz * n[2];
                    if (vn < 0) {                             // bounce a little, lose most of it
                        float e = 0.22f + 0.12f * ((c.seed & 7) / 7f);
                        c.vx -= (1 + e) * vn * n[0];
                        c.vy -= (1 + e) * vn * n[1];
                        c.vz -= (1 + e) * vn * n[2];
                    }
                    float fr = (float) Math.pow(0.04, h);     // rolling friction on dirt
                    c.vx *= fr;
                    c.vz *= fr;
                    float speed = (float) Math.sqrt(c.vx * c.vx + c.vy * c.vy + c.vz * c.vz);
                    if (speed < 0.35f) c.rest += h; else c.rest = 0f;
                }
            }
            if (c.rest > 0.2f || c.age > 6f || c.y < -5f) {
                clods.remove(i);
                if (c.y > 0f) settle(c);
            }
        }
    }

    /** A clod comes to rest and crumbles away: digging only takes ground away, it never adds any. */
    private static void settle(Clod c) {
    }

    // ------------------------------------------------------------------ edits
    private static void addEdit(float[] e) {
        float rad = e[3] + 0.6f;
        int c0x = (int) Math.floor((e[0] - rad) / WorldTextures.CHUNK), c1x = (int) Math.floor((e[0] + rad) / WorldTextures.CHUNK);
        int c0z = (int) Math.floor((e[2] - rad) / WorldTextures.CHUNK), c1z = (int) Math.floor((e[2] + rad) / WorldTextures.CHUNK);
        for (int cz = c0z; cz <= c1z; cz++)
            for (int cx = c0x; cx <= c1x; cx++)
            {
                List<float[]> l = edits.computeIfAbsent(WorldTextures.key(cx, cz), k -> new ArrayList<>());
                synchronized (l) {
                    l.add(e);
                }
            }
        applyToWindow(e);
    }

    private static List<float[]> editsFor(int cx, int cz) {
        List<float[]> l = edits.get(WorldTextures.key(cx, cz));
        if (l == null) return null;
        synchronized (l) {
            return new ArrayList<>(l);
        }
    }

    private static float hash(int x, int y, int z, int s) {
        int h = x * 374761393 + y * 668265263 + z * 1274126177 + s * 1442695041;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / (float) 0x1000000;
    }

    /** Change one cell's distance by an edit (rough: each cell gets its own share). */
    private static float editAt(float[] e, float wx, float wy, float wz, int gx, int gy, int gz) {
        float dx = wx - e[0], dy = wy - e[1], dz = wz - e[2];
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist >= e[3]) return 0f;
        float f = 1f - dist / e[3];
        f = f * f * (3f - 2f * f);
        return e[4] * f * (0.6f + 0.8f * hash(gx, gy, gz, (int) e[5]));
    }

    /** Apply an edit to the volume and dirt mask in the window, and mark what must be uploaded. */
    private static void applyToWindow(float[] e) {
        final int NX = Voxels.NX, NY = Voxels.NY, NZ = Voxels.NZ;
        final float C = Voxels.CELL, R = Voxels.RANGE;
        int x0 = (int) Math.floor((e[0] - e[3]) / C), x1 = (int) Math.floor((e[0] + e[3]) / C);
        int y0 = Math.max(0, (int) Math.floor((e[1] - e[3]) / C)), y1 = Math.min(NY - 1, (int) Math.floor((e[1] + e[3]) / C));
        int z0 = (int) Math.floor((e[2] - e[3]) / C), z1 = (int) Math.floor((e[2] + e[3]) / C);
        for (int gz = z0; gz <= z1; gz++)
            for (int gy = y0; gy <= y1; gy++)
                for (int gx = x0; gx <= x1; gx++) {
                    float ch = editAt(e, (gx + 0.5f) * C, (gy + 0.5f) * C, (gz + 0.5f) * C, gx, gy, gz);
                    if (ch == 0f) continue;
                    int i = ((Math.floorMod(gz, NZ) * NY + gy) * NX + Math.floorMod(gx, NX)) * 2;
                    float d = (Voxels.vol[i] & 255) / 255f * 2 * R - R;
                    d = Math.max(-R, Math.min(R, d + ch));
                    Voxels.vol[i] = (byte) Math.round((d + R) / (2 * R) * 255f);
                    if (ch > 0f) {                                    // a dug hole is open to the sky above it
                        int o = Voxels.vol[i + 1] & 255;
                        Voxels.vol[i + 1] = (byte) Math.max(o, Math.round(255f * Math.min(1f, ch * 6f)) & 255);
                    }
                }
        dirtyVol[0] = Math.min(dirtyVol[0], x0); dirtyVol[3] = Math.max(dirtyVol[3], x1);
        dirtyVol[1] = Math.min(dirtyVol[1], y0); dirtyVol[4] = Math.max(dirtyVol[4], y1);
        dirtyVol[2] = Math.min(dirtyVol[2], z0); dirtyVol[5] = Math.max(dirtyVol[5], z1);
        // bare dirt where it was dug or heaped
        final int S = WorldTextures.HEIGHT_SIZE;
        float mr = e[3] + 0.35f;
        int a0 = (int) Math.floor((e[0] - mr) * 2), a1 = (int) Math.floor((e[0] + mr) * 2);
        int b0 = (int) Math.floor((e[2] - mr) * 2), b1 = (int) Math.floor((e[2] + mr) * 2);
        for (int b = b0; b <= b1; b++)
            for (int a = a0; a <= a1; a++) {
                float v = mask(e, a, b);
                if (v <= 0f) continue;
                int i = Math.floorMod(b, S) * S + Math.floorMod(a, S);
                digWin[i] = (byte) Math.max(digWin[i] & 255, Math.round(v * 255f));
            }
        dirtyDig[0] = Math.min(dirtyDig[0], a0); dirtyDig[2] = Math.max(dirtyDig[2], a1);
        dirtyDig[1] = Math.min(dirtyDig[1], b0); dirtyDig[3] = Math.max(dirtyDig[3], b1);
    }

    private static float mask(float[] e, int a, int b) {
        float dx = (a + 0.5f) * 0.5f - e[0], dz = (b + 0.5f) * 0.5f - e[2];
        float d = (float) Math.sqrt(dx * dx + dz * dz), mr = e[3] + 0.35f;
        return d >= mr ? 0f : Math.min(1f, (mr - d) / 0.35f);
    }

    /** Reset the dirty regions after uploading them. */
    static void clearDirty() {
        dirtyVol[0] = dirtyVol[1] = dirtyVol[2] = Integer.MAX_VALUE;
        dirtyVol[3] = dirtyVol[4] = dirtyVol[5] = Integer.MIN_VALUE;
        dirtyDig[0] = dirtyDig[1] = Integer.MAX_VALUE;
        dirtyDig[2] = dirtyDig[3] = Integer.MIN_VALUE;
    }

    // ------------------------------------------------------------------ regenerated chunks
    /** Re-apply a chunk's edits to its freshly generated volume. */
    static void applyTo(Voxels.VolChunk c) {
        List<float[]> l = editsFor(c.cx, c.cz);
        if (l == null) return;
        final int CV = Voxels.CHUNK_V, NY = Voxels.NY;
        final float C = Voxels.CELL, R = Voxels.RANGE;
        int bx = c.cx * CV, bz = c.cz * CV;
        for (float[] e : l)
            for (int z = 0; z < CV; z++)
                for (int x = 0; x < CV; x++) {
                    int gx = bx + x, gz = bz + z;
                    float wx = (gx + 0.5f) * C, wz = (gz + 0.5f) * C;
                    if (Math.abs(wx - e[0]) > e[3] || Math.abs(wz - e[2]) > e[3]) continue;
                    for (int y = Math.max(0, (int) ((e[1] - e[3]) / C)); y <= Math.min(NY - 1, (int) ((e[1] + e[3]) / C)); y++) {
                        float ch = editAt(e, wx, (y + 0.5f) * C, wz, gx, y, gz);
                        if (ch == 0f) continue;
                        int i = ((z * NY + y) * CV + x) * 2;
                        float d = (c.rg[i] & 255) / 255f * 2 * R - R;
                        d = Math.max(-R, Math.min(R, d + ch));
                        c.rg[i] = (byte) Math.round((d + R) / (2 * R) * 255f);
                        if (ch > 0f) c.rg[i + 1] = (byte) Math.max(c.rg[i + 1] & 255, Math.round(255f * Math.min(1f, ch * 6f)) & 255);
                    }
                }
    }

    /** The bare-dirt mask for a freshly painted 2D chunk (64 x 64 cells of 0.5 m). */
    static void maskFor(WorldTextures.Chunk2D c) {
        List<float[]> l = editsFor(c.cx, c.cz);
        if (l == null) return;
        int ba = c.cx * 64, bb = c.cz * 64;
        for (float[] e : l)
            for (int j = 0; j < 64; j++)
                for (int i = 0; i < 64; i++) {
                    float v = mask(e, ba + i, bb + j);
                    if (v > 0f) c.dig[j * 64 + i] = (byte) Math.max(c.dig[j * 64 + i] & 255, Math.round(v * 255f));
                }
    }
}
