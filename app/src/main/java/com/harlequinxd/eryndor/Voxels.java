package com.harlequinxd.eryndor;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The terrain as a volume instead of a heightmap, so the world can hold tunnels, caves and
 * overhangs. Nothing here is drawn as cubes: the grid is only data, a signed distance to the
 * ground surface every 0.5 m, which the shader blends smoothly and traces per screen pixel.
 *
 * The GPU holds a 256 m window of it around the player: 512 x 128 x 512 (x, y, z), 0.5 m cells,
 * 64 m tall, addressed with wrap-around. It is filled in 32 m chunks generated straight from the
 * terrain and tunnel shapes (no global pass), so the world can go on for kilometres. Each cell is
 * two bytes: R = signed distance (metres, -4..4; negative = inside rock), G = how open the sky is.
 */
final class Voxels {
    private Voxels() { }

    static final int NX = 512, NY = 128, NZ = 512;
    static final float CELL = 0.5f;                 // metres
    static final float RANGE = 4f;                  // distances are clamped to +-4 m
    static final float WINDOW = NX * CELL;          // 256 m
    static final int CHUNK_V = 64;                  // voxels per chunk side (32 m)
    static final int WIN_CHUNKS = NX / CHUNK_V;     // 8 chunks across the window

    /** The window: RG pairs, x fastest, then y, then z (as uploaded). */
    static final byte[] vol = new byte[NX * NY * NZ * 2];

    /** Where the game begins: a cave chamber deep under the meadow south of the village. */
    static final float START_X = 150f, START_Z = 170f;

    // ------------------------------------------------------------------ tunnels
    /** A tunnel: centreline points (x, floorY, z), radius, height, and its bounding box. */
    static final class Tunnel {
        final float[][] pts;
        final float radius, height;
        final float minX, minZ, maxX, maxZ;

        Tunnel(float[][] pts, float radius, float height) {
            this.pts = pts;
            this.radius = radius;
            this.height = height;
            float a = 1e9f, b = 1e9f, c = -1e9f, d = -1e9f;
            for (float[] p : pts) {
                a = Math.min(a, p[0]);
                b = Math.min(b, p[2]);
                c = Math.max(c, p[0]);
                d = Math.max(d, p[2]);
            }
            float m = radius + RANGE + 1f;
            minX = a - m;
            minZ = b - m;
            maxX = c + m;
            maxZ = d + m;
        }

        boolean overlaps(float x0, float z0, float x1, float z1) {
            return maxX >= x0 && minX <= x1 && maxZ >= z0 && minZ <= z1;
        }
    }

    private static volatile Tunnel[] fixedTunnels;
    private static float chamberFloor;
    private static final Tunnel NO_TUNNEL = new Tunnel(new float[][]{{0, 0, 0}}, 0f, 0f);
    private static final ConcurrentHashMap<Long, Tunnel> ridgeTunnels = new ConcurrentHashMap<>();

    /** Hand-made tunnels: Hollow Hill's cave (and the start network, see Underground). */
    static Tunnel[] fixed() {
        Tunnel[] f = fixedTunnels;
        if (f != null) return f;
        synchronized (Voxels.class) {
            if (fixedTunnels != null) return fixedTunnels;
            ArrayList<Tunnel> list = new ArrayList<>();
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
            chamberFloor = hp[hp.length - 1][1] - 0.4f;
            Underground.addTunnels(list);
            fixedTunnels = list.toArray(new Tunnel[0]);
            return fixedTunnels;
        }
    }

    /** The tunnel straight through the ridge of a 256 m region, or null. */
    static Tunnel ridgeTunnel(int rx, int rz) {
        long k = WorldTextures.key(rx, rz);
        Tunnel t = ridgeTunnels.get(k);
        if (t == null) {
            float[] r = WorldTextures.ridgeIn(rx, rz);
            t = r == null ? NO_TUNNEL : throughRidge(r, new Random(rx * 9091L + rz * 77731L));
            ridgeTunnels.put(k, t);
        }
        return t == NO_TUNNEL ? null : t;
    }

    /** Portals on open ground either side, floor graded between them, a gentle curve. */
    private static Tunnel throughRidge(float[] r, Random rnd) {
        float ca = (float) Math.cos(r[2]), sa = (float) Math.sin(r[2]);
        float half = WorldTextures.RIDGE_HALF_WIDTH * 2.3f;
        float ax = r[0] - ca * half, az = r[1] - sa * half, cx = r[0] + ca * half, cz = r[1] + sa * half;
        float fa = WorldTextures.terrainAt(ax, az), fc = WorldTextures.terrainAt(cx, cz);   // floors meet the ground
        int seg = 8;
        float[][] p = new float[seg + 1][];
        float side = (rnd.nextFloat() - 0.5f) * 5f;
        for (int i = 0; i <= seg; i++) {
            float t = i / (float) seg;
            float bend = (float) Math.sin(t * Math.PI) * side;
            p[i] = new float[]{ax + (cx - ax) * t - sa * bend, fa + (fc - fa) * t, az + (cz - az) * t + ca * bend};
        }
        return new Tunnel(withRamps(p, true, true), 2.5f + rnd.nextFloat() * 0.7f, 3.6f + rnd.nextFloat() * 0.8f);
    }

    /** Every tunnel whose reach overlaps the box. */
    static List<Tunnel> tunnelsNear(float x0, float z0, float x1, float z1) {
        ArrayList<Tunnel> out = new ArrayList<>();
        for (Tunnel t : fixed()) if (t.overlaps(x0, z0, x1, z1)) out.add(t);
        int rx0 = (int) Math.floor(x0 / WorldTextures.REGION), rx1 = (int) Math.floor(x1 / WorldTextures.REGION);
        int rz0 = (int) Math.floor(z0 / WorldTextures.REGION), rz1 = (int) Math.floor(z1 / WorldTextures.REGION);
        for (int rz = rz0; rz <= rz1; rz++)
            for (int rx = rx0; rx <= rx1; rx++) {
                Tunnel t = ridgeTunnel(rx, rz);
                if (t != null && t.overlaps(x0, z0, x1, z1)) out.add(t);
            }
        return out;
    }

    /**
     * Run a tunnel a few metres on past its portals as a ramp that follows the ground, so the floor
     * meets the hillside smoothly instead of ending in a lip at the bore's rounded end.
     */
    static float[][] withRamps(float[][] p, boolean start, boolean end) {
        ArrayList<float[]> out = new ArrayList<>();
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
    static float tunnelAir(Tunnel tn, float x, float y, float z) {
        float best = 1e9f, floor = 0f;
        for (int i = 0; i < tn.pts.length - 1; i++) {
            float[] a = tn.pts[i], b = tn.pts[i + 1];
            float dx = b[0] - a[0], dz = b[2] - a[2];
            float px = x - a[0], pz = z - a[2];
            float len2 = dx * dx + dz * dz;
            float t = len2 < 1e-6f ? 0f : Math.max(0f, Math.min(1f, (px * dx + pz * dz) / len2));
            float fy = a[1] + (b[1] - a[1]) * t;
            float cy = fy + tn.height * 0.45f;
            float ex = px - dx * t, ez = pz - dz * t, ey = (y - cy) * 1.25f;     // a little taller than wide
            float d = (float) Math.sqrt(ex * ex + ez * ez + ey * ey) - tn.radius;
            if (d < best) { best = d; floor = fy; }
        }
        return Math.max(best, floor - y);                // bore, cut flat at the floor
    }

    /** Signed distance to the air of Hollow Hill's domed chamber. */
    private static float chamberAir(float x, float y, float z) {
        float dx = x - Cave.CHAMBER_X, dz = z - Cave.CHAMBER_Z;
        float dy = (y - chamberFloor) * 1.15f;
        float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz) - Cave.CHAMBER_R;
        return Math.max(d, chamberFloor - y);
    }

    private static boolean chamberNear(float x0, float z0, float x1, float z1) {
        float m = Cave.CHAMBER_R + RANGE + 1f;
        return Cave.CHAMBER_X + m >= x0 && Cave.CHAMBER_X - m <= x1 && Cave.CHAMBER_Z + m >= z0 && Cave.CHAMBER_Z - m <= z1;
    }

    /** Air of every carved space at a point (min over tunnels, chamber and the start caves). */
    private static float carvedAir(List<Tunnel> tl, boolean chamber, float x, float y, float z) {
        float air = 1e9f;
        for (Tunnel tn : tl) air = Math.min(air, tunnelAir(tn, x, y, z));
        if (chamber) air = Math.min(air, chamberAir(x, y, z));
        return Math.min(air, Underground.roomAir(x, y, z));
    }

    /** True if a tunnel or cave runs within `depth` metres under the ground near (x, z). */
    static boolean carvedUnder(float x, float z, float depth) {
        fixed();
        List<Tunnel> tl = tunnelsNear(x - 4, z - 4, x + 4, z + 4);
        boolean ch = chamberNear(x - 4, z - 4, x + 4, z + 4);
        float[][] off = {{0, 0}, {2.5f, 0}, {-2.5f, 0}, {0, 2.5f}, {0, -2.5f}};
        for (float[] o : off) {
            float px = x + o[0], pz = z + o[1];
            float top = WorldTextures.terrainAt(px, pz);
            if (carvedAir(tl, ch, px, top - 0.5f, pz) < depth) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ chunks
    /** One 32 m column of the volume (64 x 128 x 64 cells, RG), ready to copy into the window. */
    static final class VolChunk {
        final int cx, cz;
        final byte[] rg = new byte[CHUNK_V * NY * CHUNK_V * 2];

        VolChunk(int cx, int cz) {
            this.cx = cx;
            this.cz = cz;
        }
    }

    /** Build one chunk straight from the terrain and the carved shapes. Thread-safe. */
    static VolChunk fillChunk(int vcx, int vcz) {
        fixed();
        final int C = CHUNK_V;
        final float x0 = vcx * C * CELL, z0 = vcz * C * CELL, x1 = x0 + C * CELL, z1 = z0 + C * CELL;
        VolChunk out = new VolChunk(vcx, vcz);
        List<Tunnel> tl = tunnelsNear(x0 - 6, z0 - 6, x1 + 6, z1 + 6);
        boolean ch = chamberNear(x0, z0, x1, z1);
        boolean room = Underground.near(x0 - 6, z0 - 6, x1 + 6, z1 + 6);
        boolean carved = !tl.isEmpty() || ch || room;

        // ground surface per column with a border: height and slope factor (vertical -> true distance)
        final int HN = C + 2;
        float[] top = new float[HN * HN];
        for (int j = 0; j < HN; j++)
            for (int i = 0; i < HN; i++)
                top[j * HN + i] = WorldTextures.terrainAt(x0 + (i - 1 + 0.5f) * CELL, z0 + (j - 1 + 0.5f) * CELL);
        float[] d = new float[NY];
        for (int z = 0; z < C; z++) {
            float wz = z0 + (z + 0.5f) * CELL;
            for (int x = 0; x < C; x++) {
                float wx = x0 + (x + 0.5f) * CELL;
                int c = (z + 1) * HN + x + 1;
                float gx = (top[c + 1] - top[c - 1]) / (2 * CELL), gz = (top[c + HN] - top[c - HN]) / (2 * CELL);
                float slope = 1f / (float) Math.sqrt(1 + gx * gx + gz * gz);
                for (int y = 0; y < NY; y++) d[y] = ((y + 0.5f) * CELL - top[c]) * slope;
                if (carved) {
                    for (int y = 0; y < NY; y++) {
                        float air = carvedAir(tl, ch, wx, (y + 0.5f) * CELL, wz);
                        if (air < RANGE) d[y] = Math.max(d[y], -air);
                    }
                }
                for (int y = 0; y < NY; y++) {
                    float v = Math.max(-RANGE, Math.min(RANGE, d[y]));
                    out.rg[((z * NY + y) * C + x) * 2] = (byte) Math.round((v + RANGE) / (2 * RANGE) * 255f);
                }
            }
        }

        if (!carved) {
            // nothing carved here: the sky is open down to ~2 m under the ground, then shut
            for (int z = 0; z < C; z++)
                for (int x = 0; x < C; x++) {
                    float t = top[(z + 1) * HN + x + 1];
                    for (int y = 0; y < NY; y++) {
                        float o = Math.max(0f, Math.min(1f, ((y + 0.5f) * CELL - t + 2f) * 0.5f + 0.5f));
                        out.rg[((z * NY + y) * C + x) * 2 + 1] = (byte) Math.round(o * 255f);
                    }
                }
            return out;
        }
        // sky openness on a 2 m grid (with a one-cell border), then spread to the cells
        final int OC = C / 4 + 2, OY = NY / 4;
        float[] op = new float[OC * OY * OC];
        int k0x = vcx * (C / 4) - 1, k0z = vcz * (C / 4) - 1;
        for (int k = 0; k < OC; k++)
            for (int i = 0; i < OC; i++) {
                float wx = (k0x + i + 0.5f) * 2f, wz = (k0z + k + 0.5f) * 2f;
                float t = WorldTextures.terrainAt(wx, wz);
                for (int j = 0; j < OY; j++)
                    op[(k * OY + j) * OC + i] = openness(wx, (j + 0.5f) * 2f, wz, t, carved ? tl : null, ch);
            }
        for (int z = 0; z < C; z++)
            for (int y = 0; y < NY; y++)
                for (int x = 0; x < C; x++) {
                    // cell centre in openness-grid units (the grid starts one 2 m cell early)
                    float fx = (x + 0.5f) / 4f + 1f - 0.5f, fy = Math.max(0f, Math.min(OY - 1.001f, (y + 0.5f) / 4f - 0.5f)), fz = (z + 0.5f) / 4f + 1f - 0.5f;
                    int ix = (int) fx, iy = (int) fy, iz = (int) fz;
                    float tx = fx - ix, ty = fy - iy, tz = fz - iz;
                    float r = 0f;
                    for (int q = 0; q < 8; q++) {
                        int dx = q & 1, dy = (q >> 1) & 1, dz = q >> 2;
                        float w = (dx == 1 ? tx : 1 - tx) * (dy == 1 ? ty : 1 - ty) * (dz == 1 ? tz : 1 - tz);
                        r += w * op[((iz + dz) * OY + Math.min(OY - 1, iy + dy)) * OC + Math.min(OC - 1, ix + dx)];
                    }
                    out.rg[((z * NY + y) * C + x) * 2 + 1] = (byte) Math.round(Math.max(0f, Math.min(1f, r)) * 255f);
                }
        return out;
    }

    /** Analytic signed distance (no window needed): the ground, carved by the tunnels and caves. */
    private static float sdfAt(float x, float y, float z, List<Tunnel> tl, boolean ch) {
        float d = (y - WorldTextures.terrainAt(x, z)) * 0.75f;
        if (tl != null) d = Math.max(d, -carvedAir(tl, ch, x, y, z));
        return d;
    }

    /**
     * How much sky a point sees: 1 in the open, 0 deep in rock; near tunnels and caves, rays
     * straight up and at 45 degrees around are marched through the carved shapes.
     */
    private static float openness(float wx, float wy, float wz, float top, List<Tunnel> tl, boolean ch) {
        float hereGround = (wy - top) * 0.75f;
        if (tl == null) return hereGround < -1.5f ? 0f : 1f;
        float air = carvedAir(tl, ch, wx, wy, wz);
        float here = Math.max(hereGround, -air);
        if (here < -1.5f) return 0f;
        if (air > 3f) return hereGround < -1.5f ? 0f : 1f;           // nowhere near a carved space
        float sy = wy + Math.max(0, -here) + 0.6f;
        int esc = 0;
        for (int k = 0; k < 9; k++) {
            float dx = 0, dy = 1, dz = 0;
            if (k > 0) {
                double a = (k - 1) * Math.PI / 4;
                dx = (float) Math.cos(a) * 0.7071f;
                dy = 0.7071f;
                dz = (float) Math.sin(a) * 0.7071f;
            }
            float px = wx, py = sy, pz = wz, t = 0;
            boolean blocked = false;
            while (t < 30 && py < NY * CELL) {
                float d = sdfAt(px, py, pz, tl, ch);
                if (d < 0.05f) { blocked = true; break; }
                float st = Math.max(0.3f, d * 0.9f);
                px += dx * st; py += dy * st; pz += dz * st; t += st;
            }
            if (!blocked) esc++;
        }
        return (float) Math.sqrt(esc / 9f);                           // a little light carries far
    }

    /** Copy a chunk into the window (wrap-around addressing). */
    static void store(VolChunk c) {
        final int C = CHUNK_V;
        int ox = Math.floorMod(c.cx * C, NX), oz = Math.floorMod(c.cz * C, NZ);
        for (int z = 0; z < C; z++)
            for (int y = 0; y < NY; y++)
                System.arraycopy(c.rg, (z * NY + y) * C * 2, vol, (((oz + z) * NY + y) * NX + ox) * 2, C * 2);
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
        return ((vol[((z * NY + y) * NX + x) * 2] & 255) / 255f) * 2 * RANGE - RANGE;
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
}
