package com.harlequinxd.eryndor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The terrain as a volume instead of a heightmap, so the world can hold tunnels, caves and
 * overhangs. Nothing here is drawn as cubes: the grid is only data, a signed distance to the
 * ground surface every 0.5 m, which the shader blends smoothly and traces per screen pixel.
 *
 * Grid: 512 x 128 x 512 (x, y, z) over the 256 m tile and 64 m of height. Each cell is two bytes:
 * R = signed distance (metres, -4..4; negative = inside rock), G = how open the sky is there.
 */
final class Voxels {
    private Voxels() { }

    static final int NX = 512, NY = 128, NZ = 512;
    static final float CELL = 0.5f;                 // metres
    static final float RANGE = 4f;                  // distances are clamped to +-4 m
    static final int CACHE_VERSION = 4;

    /** Signed distances (as uploaded), x fastest, then y, then z. */
    private static byte[] sdf;
    /** Sky openness on a coarse grid (2 m), x fastest, then y, then z. */
    private static byte[] open;
    static final int OX = 128, OY = 32, OZ = 128;

    // ------------------------------------------------------------------ tunnels
    /** A tunnel: centreline points (x, z), floor height at each point, radius. */
    static final class Tunnel {
        final float[][] pts;     // x, floorY, z
        final float radius, height;

        Tunnel(float[][] pts, float radius, float height) {
            this.pts = pts;
            this.radius = radius;
            this.height = height;
        }
    }

    static Tunnel[] tunnels;

    /** Build the tunnel list: Hollow Hill's cave plus tunnels driven through the highest ridges. */
    private static Tunnel[] makeTunnels() {
        java.util.ArrayList<Tunnel> list = new java.util.ArrayList<>();
        // Hollow Hill: from the mouth, winding down into the hill
        float mouth = WorldTextures.terrainAt(Cave.PATH[0][0], Cave.PATH[0][1]);    // floor meets the ground
        float[][] hp = new float[Cave.PATH.length][];
        float s = 0f;
        for (int i = 0; i < Cave.PATH.length; i++) {
            if (i > 0) {
                float dx = Cave.PATH[i][0] - Cave.PATH[i - 1][0], dz = Cave.PATH[i][1] - Cave.PATH[i - 1][1];
                s += (float) Math.sqrt(dx * dx + dz * dz);
            }
            hp[i] = new float[]{Cave.PATH[i][0], mouth - 0.05f * s, Cave.PATH[i][1]};
        }
        list.add(new Tunnel(withRamps(hp, true, false), 2.6f, 3.6f));

        // a tunnel straight through each ridge, portals on open ground either side, floor graded
        // between them (the ridges are raised for this in WorldTextures.terrain)
        java.util.Random rnd = new java.util.Random(9091);
        for (float[] r : WorldTextures.ridges) {
            float ca = (float) Math.cos(r[2]), sa = (float) Math.sin(r[2]);
            float half = WorldTextures.RIDGE_HALF_WIDTH * 2.3f;
            float ax = r[0] - ca * half, az = r[1] - sa * half, cx = r[0] + ca * half, cz = r[1] + sa * half;
            float fa = WorldTextures.terrainAt(ax, az), fc = WorldTextures.terrainAt(cx, cz);   // floors meet the ground
            int seg = 8;
            float[][] p = new float[seg + 1][];
            float side = (rnd.nextFloat() - 0.5f) * 5f;
            for (int i = 0; i <= seg; i++) {
                float t = i / (float) seg;
                float bend = (float) Math.sin(t * Math.PI) * side;     // a gentle curve
                p[i] = new float[]{ax + (cx - ax) * t - sa * bend, fa + (fc - fa) * t, az + (cz - az) * t + ca * bend};
            }
            list.add(new Tunnel(withRamps(p, true, true), 2.5f + rnd.nextFloat() * 0.7f, 3.6f + rnd.nextFloat() * 0.8f));
        }
        return list.toArray(new Tunnel[0]);
    }

    /**
     * Run a tunnel a few metres on past its portals as a ramp that follows the ground, so the floor
     * meets the hillside smoothly instead of ending in a lip at the bore's rounded end.
     */
    private static float[][] withRamps(float[][] p, boolean start, boolean end) {
        java.util.ArrayList<float[]> out = new java.util.ArrayList<>();
        if (start) out.add(ramp(p[0], p[1]));
        java.util.Collections.addAll(out, p);
        if (end) out.add(ramp(p[p.length - 1], p[p.length - 2]));
        return out.toArray(new float[0][]);
    }

    private static float[] ramp(float[] portal, float[] inner) {
        float dx = portal[0] - inner[0], dz = portal[2] - inner[2], l = (float) Math.hypot(dx, dz);
        float x = portal[0] + dx / l * 4.5f, z = portal[2] + dz / l * 4.5f;
        return new float[]{x, WorldTextures.terrainAt(x, z), z};
    }

    /** Signed distance to the air of one tunnel (negative inside): a rounded bore with a flat floor. */
    private static float tunnelAir(Tunnel tn, float x, float y, float z) {
        float best = 1e9f, floor = 0f;
        for (int i = 0; i < tn.pts.length - 1; i++) {
            float[] a = tn.pts[i], b = tn.pts[i + 1];
            float ax = a[0], az = a[2], bx = b[0], bz = b[2];
            float dx = WorldTextures.wrapD(bx, ax), dz = WorldTextures.wrapD(bz, az);
            float px = WorldTextures.wrapD(x, ax), pz = WorldTextures.wrapD(z, az);
            float len2 = dx * dx + dz * dz;
            float t = Math.max(0f, Math.min(1f, (px * dx + pz * dz) / len2));
            float fy = a[1] + (b[1] - a[1]) * t;
            float cy = fy + tn.height * 0.45f;
            float ex = px - dx * t, ez = pz - dz * t, ey = (y - cy) * 1.25f;     // a little taller than wide
            float d = (float) Math.sqrt(ex * ex + ez * ez + ey * ey) - tn.radius;
            if (d < best) { best = d; floor = fy; }
        }
        return Math.max(best, floor - y);                // bore, cut flat at the floor
    }

    /** Signed distance to the air of Hollow Hill's domed chamber. */
    private static float chamberAir(float x, float y, float z, float floorY) {
        float dx = WorldTextures.wrapD(x, Cave.CHAMBER_X), dz = WorldTextures.wrapD(z, Cave.CHAMBER_Z);
        float dy = (y - floorY) * 1.15f;
        float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz) - Cave.CHAMBER_R;
        return Math.max(d, floorY - y);
    }

    // ------------------------------------------------------------------ build
    static synchronized void build(File cacheDir) {
        if (sdf != null) return;
        File f = new File(cacheDir, "voxels_v" + CACHE_VERSION + ".bin");
        int n = NX * NY * NZ, no = OX * OY * OZ;
        tunnels = makeTunnels();
        if (f.length() == n + no) {
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] a = new byte[n], b = new byte[no];
                if (read(in, a) && read(in, b)) {
                    sdf = a;
                    open = b;
                    Loading.set("Reading the hollow earth", 0.45f);
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        Loading.set("Hollowing the earth", 0.40f);
        byte[] out = new byte[n];
        // ground surface per column: height and slope factor (vertical distance -> true distance)
        float[] top = new float[NX * NZ], slope = new float[NX * NZ];
        for (int z = 0; z < NZ; z++)
            for (int x = 0; x < NX; x++)
                top[z * NX + x] = WorldTextures.terrainAt((x + 0.5f) * CELL, (z + 0.5f) * CELL);
        for (int z = 0; z < NZ; z++)
            for (int x = 0; x < NX; x++) {
                float gx = (top[z * NX + (x + 1) % NX] - top[z * NX + (x + NX - 1) % NX]) / (2 * CELL);
                float gz = (top[((z + 1) % NZ) * NX + x] - top[((z + NZ - 1) % NZ) * NX + x]) / (2 * CELL);
                slope[z * NX + x] = 1f / (float) Math.sqrt(1 + gx * gx + gz * gz);
            }
        float[] d = new float[NY];
        float chamberFloor = tunnels[0].pts[tunnels[0].pts.length - 1][1] - 0.4f;
        for (int z = 0; z < NZ; z++) {
            if ((z & 31) == 0) Loading.within(0.40f, 0.48f, z / (float) NZ);
            float wz = (z + 0.5f) * CELL;
            for (int x = 0; x < NX; x++) {
                float wx = (x + 0.5f) * CELL;
                int c = z * NX + x;
                for (int y = 0; y < NY; y++) d[y] = ((y + 0.5f) * CELL - top[c]) * slope[c];
                // carve the tunnels (only where one passes nearby)
                for (Tunnel tn : tunnels) {
                    if (!nearTunnel(tn, wx, wz)) continue;
                    for (int y = 0; y < NY; y++) {
                        float air = tunnelAir(tn, wx, (y + 0.5f) * CELL, wz);
                        if (air < RANGE) d[y] = Math.max(d[y], -air);
                    }
                }
                if (Math.hypot(WorldTextures.wrapD(wx, Cave.CHAMBER_X), WorldTextures.wrapD(wz, Cave.CHAMBER_Z)) < Cave.CHAMBER_R + RANGE) {
                    for (int y = 0; y < NY; y++) {
                        float air = chamberAir(wx, (y + 0.5f) * CELL, wz, chamberFloor);
                        d[y] = Math.max(d[y], -air);
                    }
                }
                for (int y = 0; y < NY; y++) {
                    float v = Math.max(-RANGE, Math.min(RANGE, d[y]));
                    out[(z * NY + y) * NX + x] = (byte) Math.round((v + RANGE) / (2 * RANGE) * 255f);
                }
            }
        }
        sdf = out;
        Loading.set("Letting in the light", 0.48f);
        open = computeOpenness();
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(sdf);
            o.write(open);
        } catch (Exception ignored) {
        }
    }

    private static boolean read(FileInputStream in, byte[] a) throws java.io.IOException {
        int off = 0, r;
        while (off < a.length && (r = in.read(a, off, a.length - off)) > 0) off += r;
        return off == a.length;
    }

    private static boolean nearTunnel(Tunnel tn, float x, float z) {
        float r = tn.radius + RANGE + 1f;
        for (int i = 0; i < tn.pts.length - 1; i++) {
            float[] a = tn.pts[i], b = tn.pts[i + 1];
            float dx = WorldTextures.wrapD(b[0], a[0]), dz = WorldTextures.wrapD(b[2], a[2]);
            float px = WorldTextures.wrapD(x, a[0]), pz = WorldTextures.wrapD(z, a[2]);
            float t = Math.max(0f, Math.min(1f, (px * dx + pz * dz) / (dx * dx + dz * dz)));
            float ex = px - dx * t, ez = pz - dz * t;
            if (ex * ex + ez * ez < r * r) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ sampling (CPU side)
    /** Signed distance at a world point (trilinear, metres; > 0 air, < 0 rock). */
    static float distance(double wx, double wy, double wz) {
        double fx = wx / CELL - 0.5, fy = wy / CELL - 0.5, fz = wz / CELL - 0.5;
        if (fy < 0) return (float) (wy - CELL * 0.5) - 0.1f;            // under the volume: rock
        if (fy > NY - 1) return (float) (wy - (NY - 1) * CELL) + 1f;     // above it: air
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy), z0 = (int) Math.floor(fz);
        float tx = (float) (fx - x0), ty = (float) (fy - y0), tz = (float) (fz - z0);
        float c000 = at(x0, y0, z0), c100 = at(x0 + 1, y0, z0), c010 = at(x0, y0 + 1, z0), c110 = at(x0 + 1, y0 + 1, z0);
        float c001 = at(x0, y0, z0 + 1), c101 = at(x0 + 1, y0, z0 + 1), c011 = at(x0, y0 + 1, z0 + 1), c111 = at(x0 + 1, y0 + 1, z0 + 1);
        float a = c000 + (c100 - c000) * tx, b = c010 + (c110 - c010) * tx;
        float c = c001 + (c101 - c001) * tx, e = c011 + (c111 - c011) * tx;
        float f = a + (b - a) * ty, g = c + (e - c) * ty;
        return f + (g - f) * tz;
    }

    private static float at(int x, int y, int z) {
        x = Math.floorMod(x, NX);
        z = Math.floorMod(z, NZ);
        y = Math.max(0, Math.min(NY - 1, y));
        return ((sdf[(z * NY + y) * NX + x] & 255) / 255f) * 2 * RANGE - RANGE;
    }

    /** Height of the floor under a point: the first rock surface at or below fromY. */
    static float floorBelow(double wx, double fromY, double wz) {
        double y = fromY;
        for (int i = 0; i < 200; i++) {
            float d = distance(wx, y, wz);
            if (d <= 0.02f) {
                // refine upward to the surface
                double lo = y, hi = y + 0.25;
                for (int k = 0; k < 8; k++) {
                    double m = (lo + hi) / 2;
                    if (distance(wx, m, wz) <= 0f) lo = m; else hi = m;
                }
                return (float) hi;
            }
            y -= Math.max(0.05, Math.min(d * 0.8, 1.0));
            if (y < 0) return 0f;
        }
        return (float) y;
    }

    /** Height of the rock overhead (+infinity in the open). */
    static float ceilingAbove(double wx, double fromY, double wz) {
        double y = fromY;
        for (int i = 0; i < 120; i++) {
            float d = distance(wx, y, wz);
            if (d <= 0.02f) return (float) y;
            y += Math.max(0.05, Math.min(d * 0.8, 1.0));
            if (y > NY * CELL) return Float.POSITIVE_INFINITY;
        }
        return Float.POSITIVE_INFINITY;
    }

    // ------------------------------------------------------------------ sky openness
    /**
     * How much sky each 2 m cell sees: rays straight up and at 45 degrees around, marched through
     * the volume; 1 in the open, falling away inside tunnels and deep under overhangs.
     */
    private static byte[] computeOpenness() {
        byte[] o = new byte[OX * OY * OZ];
        double[][] dirs = new double[9][];
        dirs[0] = new double[]{0, 1, 0};
        for (int k = 0; k < 8; k++) {
            double a = k * Math.PI / 4;
            dirs[k + 1] = new double[]{Math.cos(a) * 0.7071, 0.7071, Math.sin(a) * 0.7071};
        }
        for (int z = 0; z < OZ; z++) {
            if ((z & 15) == 0) Loading.within(0.48f, 0.52f, z / (float) OZ);
            for (int y = 0; y < OY; y++)
                for (int x = 0; x < OX; x++) {
                    double wx = (x + 0.5) * 2, wy = (y + 0.5) * 2, wz = (z + 0.5) * 2;
                    float here = distance(wx, wy, wz);
                    float val;
                    if (here < -1.5f) {
                        val = 0f;                                  // deep in rock
                    } else {
                        // start from just above the nearby surface, so rock cells at a surface
                        // share the light of the air beside them
                        double sy = wy + Math.max(0, -here) + 0.6;
                        int esc = 0;
                        for (double[] dv : dirs) {
                            double px = wx, py = sy, pz = wz, t = 0;
                            boolean blocked = false;
                            while (t < 30 && py < NY * CELL) {
                                float d = distance(px, py, pz);
                                if (d < 0.05f) { blocked = true; break; }
                                double st = Math.max(0.3, d * 0.9);
                                px += dv[0] * st; py += dv[1] * st; pz += dv[2] * st; t += st;
                            }
                            if (!blocked) esc++;
                        }
                        val = esc / 9f;
                        val = (float) Math.sqrt(val);              // a little light carries far
                    }
                    o[(z * OY + y) * OX + x] = (byte) Math.round(val * 255f);
                }
        }
        return o;
    }

    /** Upload data: RG8, R = distance, G = openness (trilinear from the coarse grid). */
    static ByteBuffer volumeRG() {
        ByteBuffer bb = ByteBuffer.allocateDirect(NX * NY * NZ * 2).order(ByteOrder.nativeOrder());
        for (int z = 0; z < NZ; z++) {
            for (int y = 0; y < NY; y++) {
                for (int x = 0; x < NX; x++) {
                    bb.put(sdf[(z * NY + y) * NX + x]);
                    bb.put((byte) Math.round(openAt((x + 0.5) * CELL, (y + 0.5) * CELL, (z + 0.5) * CELL) * 255f));
                }
            }
        }
        bb.position(0);
        return bb;
    }

    /** Sky openness at a world point (trilinear on the coarse grid). */
    static float openAt(double wx, double wy, double wz) {
        double fx = wx / 2 - 0.5, fy = Math.max(0, Math.min(OY - 1.001, wy / 2 - 0.5)), fz = wz / 2 - 0.5;
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy), z0 = (int) Math.floor(fz);
        float tx = (float) (fx - x0), ty = (float) (fy - y0), tz = (float) (fz - z0);
        float r = 0f;
        for (int k = 0; k < 8; k++) {
            int dx = k & 1, dy = (k >> 1) & 1, dz = k >> 2;
            int xi = Math.floorMod(x0 + dx, OX), yi = Math.min(OY - 1, y0 + dy), zi = Math.floorMod(z0 + dz, OZ);
            float w = (dx == 1 ? tx : 1 - tx) * (dy == 1 ? ty : 1 - ty) * (dz == 1 ? tz : 1 - tz);
            r += w * ((open[(zi * OY + yi) * OX + xi] & 255) / 255f);
        }
        return r;
    }
}
