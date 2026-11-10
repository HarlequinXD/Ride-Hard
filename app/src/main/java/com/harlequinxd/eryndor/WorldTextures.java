package com.harlequinxd.eryndor;

import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The world, generated on demand in 32 m chunks (pure Java, no Android dependencies).
 *
 * The land is 32 km across and never repeats: every generator works from absolute world
 * coordinates, seeded by position, so any chunk can be made at any time, in any order, on any
 * thread, and always comes out the same. The hand-made heart of it sits at the centre: the village
 * of Hollowspire at (128, 128) with its cobbled north-south road (which runs on as a track the whole
 * length of the land), an east-west street and a round plaza with a fountain; Hollow Hill and its
 * cave to the east; the standing stones on their mound at (36, 228); a river near z = 30 under a
 * stone bridge. Outside it the wilds go on: rolling hills and high ground, forests (broadleaf and
 * pine), more rivers every ~1.4 km, rocky ridges with tunnels driven through them, boulders,
 * fallen logs, cairns. Mountains wall the land in at the edges.
 *
 * The GPU only ever holds a window around the player (512 m of ground, 256 m of the voxel volume)
 * addressed with wrap-around, so a chunk's data always lives at (world position mod window).
 * {@link ChunkStreamer} keeps the window filled as you walk.
 *
 * Ground texels: 8 px per metre, 15-bit ordered dither; alpha marks water (0) or how lit the land
 * is (0.5..1, baked tree shadows). Clouds: 256 x 256 tileable fbm.
 */
final class WorldTextures {

    // ---- the world: 32 km square, centred on the village
    static final float CENTRE_X = 128f, CENTRE_Z = 128f, WORLD_HALF = 16384f;

    // ---- the window the GPU holds (wrap-around addressing)
    static final int GROUND_SIZE = 4096;
    static final float WORLD_SIZE = 512f;              // metres of ground in the window
    static final float PPM = GROUND_SIZE / WORLD_SIZE; // 8 texels per metre
    static final float CHUNK = 32f;                    // metres per chunk
    static final int CHUNK_PX = 256;                   // ground texels per chunk side
    static final int WIN_CHUNKS = 16;                  // chunks across the 2D window
    static final int CLOUD_SIZE = 256;
    /** Ground texels per noise unit: noise coordinates are metres / 256. */
    private static final float TPU = 2048f;

    static final float DETAIL_MAX = 3f;                // metres represented by 8-bit 255
    static final int[] groundWin = new int[GROUND_SIZE * GROUND_SIZE];
    static final byte[] detailWin = new byte[GROUND_SIZE * GROUND_SIZE];
    static final byte[] matWin = new byte[GROUND_SIZE * GROUND_SIZE];
    private static int[] cloudCache;

    // ---- trees: one optional tree per 16 m cell (the shader draws them per pixel)
    static final int TREE_GRID = 32;                   // cells across the window
    static final float TREE_CELL = 16f;
    static final byte[] treeWin = new byte[TREE_GRID * TREE_GRID * 4];
    static final String[] TREE_NAMES = {"oak", "birch", "scots pine", "spruce", "willow", "dead tree",
            "autumn maple", "blossom", "ancient yew", "poplar"};
    // per species: crown radius, trunk radius, crown centre height, top (match treeParams in scene.frag)
    static final float[] TREE_CROWN = {4.2f, 2.0f, 2.9f, 2.3f, 3.9f, 3.2f, 3.4f, 2.9f, 4.0f, 1.5f};
    static final float[] TREE_TRUNK = {0.38f, 0.16f, 0.26f, 0.24f, 0.38f, 0.26f, 0.30f, 0.22f, 0.60f, 0.20f};
    static final float[] TREE_TOP = {10f, 10.4f, 11.5f, 13.5f, 9.5f, 9f, 9.2f, 7.4f, 6.4f, 14f};

    static final int FERN_GRID = 64;                   // ferns are off; the shader still binds the grid
    static final byte[] fernWin = new byte[FERN_GRID * FERN_GRID * 4];

    /** Small flora painted on the map: off, the ground's look comes from texture sets instead. */
    static final boolean FLORA = false;

    // material ids (shader picks the per-pixel surface texture from these)
    static final int M_GRASS = 0, M_EARTH = 1, M_STONE = 2, M_WOOD = 3, M_WATER = 4, M_PLANT = 5,
            M_ROCK = 6, M_FOREST = 7, M_GRAVEL = 8, M_SAND = 9, M_SNOW = 10,
            M_MOSS = 11, M_NEEDLES = 12, M_ROOTS = 13, M_MUD = 14, M_FOREST_GRASS = 15;
    /** Material ids are stored as id * MAT_STEP in the 8-bit material map (room for 16). */
    static final int MAT_STEP = 16;

    /** World position, species and size of the tree in the cell containing (x, z), or null. */
    static float[] trunkNear(double x, double z) {
        byte[] t = treeWin;
        int cx = Math.floorMod((int) Math.floor(x / TREE_CELL), TREE_GRID), cz = Math.floorMod((int) Math.floor(z / TREE_CELL), TREE_GRID);
        int o = (cz * TREE_GRID + cx) * 4;
        int a = t[o + 3] & 255;
        if (a < 128) return null;
        double baseX = Math.floor(x / TREE_CELL) * TREE_CELL, baseZ = Math.floor(z / TREE_CELL) * TREE_CELL;
        float tx = (float) (baseX + TREE_CELL * 0.5 + ((t[o] & 255) / 255f - 0.5f) * 3f);
        float tz = (float) (baseZ + TREE_CELL * 0.5 + ((t[o + 1] & 255) / 255f - 0.5f) * 3f);
        int v = Math.min(9, (t[o + 2] & 255) / 25);
        float sz = 0.85f + ((a / 255f) - 0.5f) * 0.7f;
        return new float[]{tx, tz, v, sz};
    }

    /** Bilinear detail height in metres (matches GL_LINEAR on the detail texture). */
    static float detailAt(double wx, double wz) {
        byte[] d = detailWin;
        final int S = GROUND_SIZE;
        double fx = wx * PPM - 0.5, fz = wz * PPM - 0.5;
        int x0 = (int) Math.floor(fx), z0 = (int) Math.floor(fz);
        float tx = (float) (fx - x0), tz = (float) (fz - z0);
        int ax = Math.floorMod(x0, S), bx = Math.floorMod(x0 + 1, S), az = Math.floorMod(z0, S), bz = Math.floorMod(z0 + 1, S);
        float a = (d[az * S + ax] & 255), b = (d[az * S + bx] & 255), c = (d[bz * S + ax] & 255), e = (d[bz * S + bx] & 255);
        return lerp(lerp(a, b, tx), lerp(c, e, tx), tz) / 255f * DETAIL_MAX;
    }

    private WorldTextures() {}

    // ------------------------------------------------------------------ noise
    /**
     * Value noise on a lattice of nx x ny cells per noise unit (256 m). The lattice is hashed from
     * the integer cell, so it never repeats however far you go.
     */
    static final class PNoise {
        final int nx, ny, seed;

        PNoise(int n, Random r) { this(n, n, r); }

        PNoise(int nx, int ny, Random r) {
            this.nx = nx;
            this.ny = ny;
            seed = r.nextInt();
        }

        private float val(int x, int y) {
            int h = x * 374761393 + y * 668265263 + seed * 1442695041;
            h = (h ^ (h >>> 13)) * 1274126177;
            h ^= h >>> 16;
            return (h & 0xFFFFFF) / (float) 0x1000000;
        }

        float at(float u, float w) {
            double fx = (double) u * nx, fy = (double) w * ny;
            int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
            float tx = (float) (fx - x0), ty = (float) (fy - y0);
            tx = tx * tx * (3 - 2 * tx);
            ty = ty * ty * (3 - 2 * ty);
            float a = val(x0, y0), b = val(x0 + 1, y0), c = val(x0, y0 + 1), d = val(x0 + 1, y0 + 1);
            float top = a + (b - a) * tx, bot = c + (d - c) * tx;
            return top + (bot - top) * ty;
        }
    }

    /** Periodic value noise over the unit square (tiles seamlessly): the clouds. */
    private static final class TileNoise {
        final int n;
        final float[] v;

        TileNoise(int n, Random r) {
            this.n = n;
            v = new float[n * n];
            for (int i = 0; i < v.length; i++) v[i] = r.nextFloat();
        }

        float at(float u, float w) {
            float fx = u * n, fy = w * n;
            int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
            float tx = fx - x0, ty = fy - y0;
            tx = tx * tx * (3 - 2 * tx);
            ty = ty * ty * (3 - 2 * ty);
            int ax = Math.floorMod(x0, n), bx = Math.floorMod(x0 + 1, n);
            int ay = Math.floorMod(y0, n), by = Math.floorMod(y0 + 1, n);
            float a = v[ay * n + ax], b = v[ay * n + bx], c = v[by * n + ax], d = v[by * n + bx];
            float top = a + (b - a) * tx, bot = c + (d - c) * tx;
            return top + (bot - top) * ty;
        }
    }

    // painting noise (fixed seeds, so every chunk agrees with its neighbours)
    private static final PNoise c1, c2, m1, m2, f1, f2, blade, grove, conifer, forestBig, rivN, bigN;
    private static final PNoise[] terrainNoise;

    static {
        Random rnd = new Random(1349);
        c1 = new PNoise(4, rnd);
        c2 = new PNoise(12, rnd);
        m1 = new PNoise(48, rnd);
        m2 = new PNoise(160, rnd);
        f1 = new PNoise(512, rnd);
        f2 = new PNoise(1024, rnd);
        blade = new PNoise(1024, 256, rnd);
        grove = new PNoise(5, rnd);
        conifer = new PNoise(4, rnd);
        forestBig = new PNoise(1, rnd);
        rivN = new PNoise(1, rnd);
        Random r2 = new Random(4711);
        terrainNoise = new PNoise[]{new PNoise(3, r2), new PNoise(7, r2), new PNoise(18, r2), new PNoise(48, r2), new PNoise(128, r2)};
        bigN = new PNoise(1, r2);
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

    /** Signed distance a - b (the world no longer wraps; kept for the hand-made layout code). */
    static float wrapD(float a, float b) {
        return a - b;
    }

    // ------------------------------------------------------------------ cobble cells
    /** Nearest Voronoi cell: out = id, distance to edge (m), offset from its centre (x, z). */
    private static void voronoi(float wx, float wz, float cell, int salt, float[] out) {
        int cx = (int) Math.floor(wx / cell), cz = (int) Math.floor(wz / cell);
        float d1 = 1e9f, d2 = 1e9f;
        for (int j = -1; j <= 1; j++)
            for (int i = -1; i <= 1; i++) {
                int gx = cx + i, gz = cz + j;
                float fx = (gx + 0.2f + 0.6f * hash(gx + salt, gz * 7 + 1)) * cell;
                float fz = (gz + 0.2f + 0.6f * hash(gx * 13 + 5, gz + salt)) * cell;
                float dx = wx - fx, dz = wz - fz, d = dx * dx + dz * dz;
                if (d < d1) {
                    d2 = d1;
                    d1 = d;
                    out[0] = gz * 7919 + gx;
                    out[2] = dx;
                    out[3] = dz;
                } else if (d < d2) d2 = d;
            }
        out[1] = ((float) Math.sqrt(d2) - (float) Math.sqrt(d1)) * 0.5f;
    }

    /** Weathered cobblestone: grey / blue-grey / brown stones, dark joints with moss. */
    private static float[] cobble(float wx, float wz, float cell, float fine, float h, float wear) {
        float[] vo = new float[4];
        voronoi(wx, wz, cell, 3, vo);
        int vId = (int) vo[0];
        float vEdge = vo[1], vDx = vo[2], vDz = vo[3];
        float k = hash(vId, 17), t = hash(vId, 29);
        float r, g, b;
        if (k < 0.45f) { r = 0.54f; g = 0.53f; b = 0.51f; }
        else if (k < 0.75f) { r = 0.51f; g = 0.51f; b = 0.52f; }
        else { r = 0.55f; g = 0.52f; b = 0.47f; }
        float dome = -(vDx + vDz) / cell * 0.9f;                 // rounded tops lit from the north-west
        float l = 0.86f + 0.12f * t + 0.16f * dome + 0.10f * (fine - 0.5f) + 0.04f * (h - 0.5f) + 0.10f * wear;
        r *= l; g *= l; b *= l;
        if (vEdge < cell * 0.13f) {                               // joints
            float moss = hash((int) Math.floor(wx * 8), (int) Math.floor(wz * 8)) * (1f - wear);
            r = lerp(0.30f, 0.30f, moss); g = lerp(0.29f, 0.34f, moss); b = lerp(0.27f, 0.22f, moss);
        }
        return new float[]{r, g, b};
    }

    // ------------------------------------------------------------------ rivers
    private static final double TAU = Math.PI * 2;
    /** Rivers run roughly east-west, one every RIVER_SPACING metres north-south. */
    static final float RIVER_SPACING = 1400f;

    /** Centreline z of river k at x (k = 0 is the village river near z = 30). */
    static float riverCentre(float wx, int k) {
        float z = 30f + k * RIVER_SPACING + 5f * (float) Math.sin(TAU * wx / 256 * 2 + k * 1.7)
                + 2.5f * (float) Math.sin(TAU * wx / 256 * 5 + 1.3 + k * 0.9);
        float wander = (rivN.at(wx / 640f, k * 7.31f + 0.5f) - 0.5f) * 260f;
        if (k == 0) wander *= smooth(300f, 700f, Math.abs(wx - CENTRE_X));   // the village river stays put
        return z + wander;
    }

    static float riverZ(float wx) {
        return riverCentre(wx, 0);
    }

    /** Signed distance (z) from the nearest river's centreline. */
    static float riverOff(float wx, float wz) {
        int k = Math.round((wz - 30f) / RIVER_SPACING);
        // a river wanders at most ~140 m from its line, so away from the midway band only one can be nearest
        if (Math.abs(wz - 30f - k * RIVER_SPACING) < RIVER_SPACING / 2 - 300f) return wz - riverCentre(wx, k);
        float best = 1e9f;
        for (int j = k - 1; j <= k + 1; j++) {
            float d = wz - riverCentre(wx, j);
            if (Math.abs(d) < Math.abs(best)) best = d;
        }
        return best;
    }

    static float riverHalfWidth(float wx) {
        return 3.6f + 0.8f * (float) Math.sin(TAU * wx / 256 * 3 + 0.5);
    }

    // ------------------------------------------------------------------ heights
    static final int HEIGHT_SIZE = 1024;             // 0.5 m per sample over the 512 m window
    static final float HEIGHT_MAX = 64f;             // metres represented by 8-bit 255
    /** The whole land sits this high, leaving deep rock underneath for the tunnel network. */
    static final float BASE = 16f;
    static final float WATER_LEVEL = 4f;
    static final float VILLAGE_H = 10f;
    static final float STONES_X = 36f, STONES_Z = 228f;

    /** Open-air ground height per window sample (quantised to the 8 bits the GPU sees). */
    static final float[] hFloor = new float[HEIGHT_SIZE * HEIGHT_SIZE];

    static float q8(float h) {
        h = Math.max(0f, Math.min(HEIGHT_MAX - 0.2f, h));
        return Math.round(h / HEIGHT_MAX * 255f) * HEIGHT_MAX / 255f;
    }

    /** The open-air ground surface (the voxel volume carves tunnels and caves below it). */
    static float terrainAt(float wx, float wz) {
        return terrain(wx, wz);
    }

    // ---- ridges raised for the tunnels: at most one per 256 m region, chosen from its seed
    static final float RIDGE_H = 11f, RIDGE_HALF_LEN = 30f, RIDGE_HALF_WIDTH = 12f;
    static final float REGION = 256f;
    private static final float[] NONE = new float[0];
    private static final ConcurrentHashMap<Long, float[]> ridgeCache = new ConcurrentHashMap<>();

    static long key(int a, int b) {
        return ((long) a << 32) ^ (b & 0xFFFFFFFFL);
    }

    /** The ridge in region (rx, rz): centre x, z, tunnel direction (radians); or null. */
    static float[] ridgeIn(int rx, int rz) {
        long k = key(rx, rz);
        float[] r = ridgeCache.get(k);
        if (r == null) {
            r = pickRidge(rx, rz);
            ridgeCache.put(k, r);
        }
        return r.length == 0 ? null : r;
    }

    private static float[] pickRidge(int rx, int rz) {
        Random rnd = new Random(rx * 73856093L ^ rz * 19349663L ^ 0x5EEDL);
        if (rnd.nextFloat() > 0.55f) return NONE;
        for (int attempt = 0; attempt < 5; attempt++) {
            float x = rx * REGION + 56 + rnd.nextFloat() * 144, z = rz * REGION + 56 + rnd.nextFloat() * 144;
            float ang = rnd.nextFloat() * (float) Math.PI;
            if (ridgeOk(x, z, ang)) return new float[]{x, z, ang};
        }
        return NONE;
    }

    /** Clear of the village, the north road, rivers, Hollow Hill, the stones and the world's edge. */
    private static boolean ridgeOk(float x, float z, float ang) {
        if (Math.max(Math.abs(x - CENTRE_X), Math.abs(z - CENTRE_Z)) > WORLD_HALF - 1200) return false;
        float rx = -(float) Math.sin(ang), rz = (float) Math.cos(ang);
        for (float sAl = -RIDGE_HALF_LEN; sAl <= RIDGE_HALF_LEN; sAl += 6)
            for (float sAc = -26; sAc <= 26; sAc += 13) {
                float px = x + rx * sAl + (float) Math.cos(ang) * sAc, pz = z + rz * sAl + (float) Math.sin(ang) * sAc;
                if (Math.abs(px - 128f) < 74 && Math.abs(pz - 128f) < 74) return false;          // village
                if (Math.abs(px - 128f) < 14) return false;                                     // north road
                if (Math.abs(riverOff(px, pz)) < riverHalfWidth(px) + 4) return false;            // rivers
                if (Math.hypot(px - Cave.HILL_X, pz - Cave.HILL_Z) < 42) return false;          // Hollow Hill
                if (Math.hypot(px - STONES_X, pz - STONES_Z) < 24) return false;                // standing stones
                if (Math.hypot(px - Voxels.START_X, pz - Voxels.START_Z) < 60) return false;    // over the start caves
            }
        return true;
    }

    /** Open-air terrain height. */
    private static float terrain(float wx, float wz) {
        float h = terrainBase(wx, wz);
        float[] r = ridgeIn((int) Math.floor(wx / REGION), (int) Math.floor(wz / REGION));
        if (r != null) {
            float dx = wx - r[0], dz = wz - r[1];
            float acrossT = dx * (float) Math.cos(r[2]) + dz * (float) Math.sin(r[2]);      // along the tunnel
            float alongR = -dx * (float) Math.sin(r[2]) + dz * (float) Math.cos(r[2]);      // along the ridge
            float prof = (float) Math.exp(-(acrossT * acrossT) / (RIDGE_HALF_WIDTH * RIDGE_HALF_WIDTH));
            float ends = 1f - smooth(RIDGE_HALF_LEN - 14f, RIDGE_HALF_LEN, Math.abs(alongR));
            h += RIDGE_H * prof * ends * (0.85f + 0.15f * (float) Math.cos(alongR * 0.21f));
        }
        return Math.min(h + BASE, HEIGHT_MAX - 2f);
    }

    /** How far into the wilds a point is: 0 in the hand-made centre, 1 well outside it. */
    static float wildness(float wx, float wz) {
        float dx = wx - CENTRE_X, dz = wz - CENTRE_Z;
        return smooth(250f, 700f, (float) Math.sqrt(dx * dx + dz * dz));
    }

    /** Hill amplitude: gentle lowlands to big rolling high ground, varying over kilometres. */
    private static float hilliness(float wx, float wz) {
        float a = 0.45f + 1.1f * bigN.at(wx / 2048f, wz / 2048f);
        return lerp(1f, a, wildness(wx, wz));
    }

    private static float terrainBase(float wx, float wz) {
        PNoise[] n = terrainNoise;
        float u = wx / 256f, v = wz / 256f;
        float A = hilliness(wx, wz);
        float low = 5f + (n[0].at(u, v) * 15f + n[1].at(u, v) * 8f) * A;
        float h = low + (n[2].at(u, v) * 3f + n[3].at(u, v) * 0.9f) * (0.6f + 0.4f * A) + n[4].at(u, v) * 0.25f;

        // standing-stone mound
        float sdx = wx - STONES_X, sdz = wz - STONES_Z;
        h += 6f * (float) Math.exp(-(sdx * sdx + sdz * sdz) / (16f * 16f));

        // village plateau
        float vx = wx - 128f, vz = wz - 128f;
        float dv = (float) Math.pow(Math.pow(Math.abs(vx), 3) + Math.pow(Math.abs(vz), 3), 1.0 / 3.0);   // rounded square
        float wv = 1f - smooth(70f, 100f, dv);
        if (wv > 0f) h = lerp(h, VILLAGE_H + (n[3].at(u, v) - 0.5f) * 0.3f * smooth(30f, 64f, dv), wv);

        // Hollow Hill, east of the village, which holds the cave
        float hx = wx - Cave.HILL_X, hz = wz - Cave.HILL_Z;
        h += 19f * (float) Math.exp(-(hx * hx + hz * hz) / (30f * 30f));

        // mountains wall the land in at its edges
        float edge = Math.max(Math.abs(wx - CENTRE_X), Math.abs(wz - CENTRE_Z));
        h += smooth(WORLD_HALF - 900f, WORLD_HALF - 100f, edge) * 44f;

        // north-south road: flat across its width, following a gentle grade the whole length of the land
        float dx = Math.abs(wx - 128f), roadH = h;
        if (dx < 11f) {
            float roadLow = 5f + (n[0].at(0.5f, v) * 15f + n[1].at(0.5f, v) * 8f) * hilliness(128f, wz);
            float wvRoad = 1f - smooth(70f, 100f, Math.abs(wz - 128f));
            roadH = lerp(roadLow, VILLAGE_H, wvRoad);
            h = lerp(h, roadH, (1f - smooth(3f, 11f, dx)));
        }

        // river valleys and the bridges where the road crosses
        float rd = Math.abs(riverOff(wx, wz)), rhw = riverHalfWidth(wx);
        float bank = WATER_LEVEL + 0.25f + Math.max(rd - rhw, 0f) * 0.42f;
        if (rd < rhw) bank = WATER_LEVEL;
        h = Math.min(h, bank);
        if (dx < 4.2f && rd < rhw + 2.6f) h = Math.max(h, WATER_LEVEL + 1.4f);
        else if (dx < 4.2f) h = Math.max(h, Math.min(roadH, WATER_LEVEL + 1.4f + (rd - rhw - 2.6f) * 0.42f));
        return h;
    }

    /** Height texture data for the window (see heightTexel). */
    static int[] heightTexture() {
        int n = hFloor.length;
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = heightTexel(hFloor[i], Digging.digWin[i]);
        return out;
    }

    /** Height texel: R = B = ground height, G = bare dug / heaped dirt (0..255), A = 255. */
    static int heightTexel(float h, byte dig) {
        int r = Math.round(h / HEIGHT_MAX * 255f);
        return (255 << 24) | (r << 16) | ((dig & 255) << 8) | r;
    }

    /** Bilinear sample of the window heights, matching GL_LINEAR on the height texture. */
    static float sample(float[] layer, double wx, double wz) {
        final int S = HEIGHT_SIZE;
        double fx = wx * 2 - 0.5, fz = wz * 2 - 0.5;
        int x0 = (int) Math.floor(fx), z0 = (int) Math.floor(fz);
        float tx = (float) (fx - x0), tz = (float) (fz - z0);
        int ax = Math.floorMod(x0, S), bx = Math.floorMod(x0 + 1, S), az = Math.floorMod(z0, S), bz = Math.floorMod(z0 + 1, S);
        float a = layer[az * S + ax], b = layer[az * S + bx], c = layer[bz * S + ax], d = layer[bz * S + bx];
        return lerp(lerp(a, b, tx), lerp(c, d, tx), tz);
    }

    static float floorAt(double wx, double wz) {
        return sample(hFloor, wx, wz);
    }

    // ------------------------------------------------------------------ forests
    /** Forest density (above 0.55 = woodland), shared by the trees and the forest floor. */
    static float forestAt(float wx, float wz) {
        float g = grove.at(wx / 256f, wz / 256f);
        return g + (forestBig.at(wx / 1536f, wz / 1536f) - 0.5f) * 0.75f * wildness(wx, wz);
    }

    static boolean coniferAt(float wx, float wz) {
        return conifer.at(wx / 256f, wz / 256f) > 0.5f;
    }

    // ------------------------------------------------------------------ trees
    private static final ConcurrentHashMap<Long, byte[]> treeCache = new ConcurrentHashMap<>();

    /** Tree in a 16 m cell: offset x, offset z, species * 25 + yaw step, presence (a >= 128) + size. */
    static byte[] treeBytes(int cx, int cz) {
        long k = key(cx, cz);
        byte[] t = treeCache.get(k);
        if (t != null) return t;
        Random rnd = new Random(cx * 341873128712L + cz * 132897987541L ^ 0x7EEL);
        float ox = rnd.nextFloat(), oz = rnd.nextFloat(), size = rnd.nextFloat(), pick = rnd.nextFloat();
        float wx = (cx + 0.5f) * TREE_CELL + (ox - 0.5f) * 3f, wz = (cz + 0.5f) * TREE_CELL + (oz - 0.5f) * 3f;
        int px = (int) Math.floor(wx * PPM), pz = (int) Math.floor(wz * PPM);
        boolean clear = true;
        float[] tmp = new float[7];
        for (int a = -20; a <= 20 && clear; a += 4)
            for (int b = -20; b <= 20 && clear; b += 4) {
                paintTexel(px + b, pz + a, tmp);
                if (tmp[6] != 0f) clear = false;
            }
        // never over a tunnel or cave: its roots would hang in the air (the floating tree)
        if (clear && Voxels.carvedUnder(wx, wz, 4.5f)) clear = false;
        if (Math.max(Math.abs(wx - CENTRE_X), Math.abs(wz - CENTRE_Z)) > WORLD_HALF - 300) clear = false;
        float g = forestAt(wx, wz);
        float chance = g > 0.55f ? 0.85f : (g > 0.47f ? 0.45f : 0.22f);
        boolean present = clear && rnd.nextFloat() < chance;
        float riverD = Math.abs(riverOff(wx, wz));
        int v;
        if (riverD < 15f && pick < 0.6f) v = 4;                                   // willows by the water
        else if (g > 0.55f && coniferAt(wx, wz)) v = pick < 0.5f ? 2 : 3;          // pine wood
        else if (g > 0.55f) v = pick < 0.45f ? 0 : (pick < 0.7f ? 1 : (pick < 0.85f ? 6 : 8));   // broadleaf grove
        else v = (int) (pick * 10f) % 10;                                         // anything, scattered
        t = new byte[4];
        t[0] = (byte) Math.round(ox * 255);
        t[1] = (byte) Math.round(oz * 255);
        t[2] = (byte) (v * 25 + rnd.nextInt(25));        // species * 25 + yaw step
        t[3] = (byte) (present ? 128 + Math.round(size * 127) : 0);
        treeCache.put(k, t);
        return t;
    }

    // ------------------------------------------------------------------ chunks
    /** One painted 32 m chunk, ready to copy into the window. */
    static final class Chunk2D {
        final int cx, cz;
        final int[] ground = new int[CHUNK_PX * CHUNK_PX];
        final byte[] detail = new byte[CHUNK_PX * CHUNK_PX], mat = new byte[CHUNK_PX * CHUNK_PX];
        final float[] heights = new float[64 * 64];       // 0.5 m samples
        final byte[] trees = new byte[2 * 2 * 4];         // its 2 x 2 tree cells
        final byte[] dig = new byte[64 * 64];             // bare dirt where it was dug or heaped (0.5 m)

        Chunk2D(int cx, int cz) {
            this.cx = cx;
            this.cz = cz;
        }
    }

    /** Paint surface, used while painting one chunk (texels addressed in global texel coordinates). */
    static final class Canvas {
        final int x0, y0, n;
        final float[] R, G, B, A, DH;
        final byte[] MAT;
        int curMat = M_GRASS;

        Canvas(int x0, int y0, int n) {
            this.x0 = x0;
            this.y0 = y0;
            this.n = n;
            R = new float[n * n];
            G = new float[n * n];
            B = new float[n * n];
            A = new float[n * n];
            DH = new float[n * n];
            MAT = new byte[n * n];
        }

        int idx(int x, int y) {
            x -= x0;
            y -= y0;
            return (x < 0 || y < 0 || x >= n || y >= n) ? -1 : y * n + x;
        }

        /** Whether a stamp centred at (x, y) reaching ext texels can touch this canvas. */
        boolean touches(int x, int y, int ext) {
            return x + ext >= x0 && x - ext < x0 + n && y + ext >= y0 && y - ext < y0 + n;
        }

        /** Raise the detail height at a texel (keeps the highest). */
        void raise(int i, float v) {
            if (v > DH[i]) {
                DH[i] = v;
                MAT[i] = (byte) (curMat * MAT_STEP);
            }
        }
    }

    private static boolean busyAt(int x, int y, float[] tmp) {
        paintTexel(x, y, tmp);
        return tmp[6] != 0f;
    }

    private static long chunkSeed(int cx, int cz, int salt) {
        return cx * 0x9E3779B97F4A7C15L + cz * 0xC2B2AE3D27D4EB4FL + salt * 0x165667B19E3779F9L;
    }

    private static int count(Random r, float mean) {
        int n = (int) mean;
        return n + (r.nextFloat() < mean - n ? 1 : 0);
    }

    /**
     * Paint one 32 m chunk: ground colour, fine relief, materials, the trees in its cells, their
     * shadows (if the species library is ready) and its heights. Thread-safe.
     */
    static Chunk2D paintChunk(int ccx, int ccz, TreeLibrary lib) {
        final int N = CHUNK_PX;
        final int gx0 = ccx * N, gy0 = ccz * N;
        Chunk2D out = new Chunk2D(ccx, ccz);
        Canvas cv = new Canvas(gx0, gy0, N);

        // heights at 0.5 m with a one-sample border (for slopes)
        final int HN = 66;
        float[] hh = new float[HN * HN];
        for (int j = 0; j < HN; j++)
            for (int i = 0; i < HN; i++) {
                float wx = (ccx * 64 + i - 1 + 0.5f) * 0.5f, wz = (ccz * 64 + j - 1 + 0.5f) * 0.5f;
                hh[j * HN + i] = terrain(wx, wz);
            }
        for (int j = 0; j < 64; j++)
            for (int i = 0; i < 64; i++) out.heights[j * 64 + i] = q8(hh[(j + 1) * HN + i + 1]);

        float[] t = new float[7];
        for (int y = 0; y < N; y++)
            for (int x = 0; x < N; x++) {
                int gx = gx0 + x, gy = gy0 + y, i = y * N + x;
                paintTexel(gx, gy, t);
                float r = t[0], g = t[1], b = t[2], dh = t[4];
                int mat = (int) t[5];
                if (t[6] == 0f && (mat == M_GRASS || mat == M_EARTH)) {
                    float wx = (gx + 0.5f) / PPM, wz = (gy + 0.5f) / PPM;
                    int sx = (x >> 2) + 1, sy = (y >> 2) + 1;
                    float gxs = (hh[sy * HN + sx + 1] - hh[sy * HN + sx - 1]), gzs = (hh[(sy + 1) * HN + sx] - hh[(sy - 1) * HN + sx]);
                    float slope = (float) Math.sqrt(gxs * gxs + gzs * gzs);     // rise per metre
                    float h = hash(gx, gy), fine = f1.at(gx / TPU, gy / TPU);
                    float fo = forestAt(wx, wz);
                    if (fo > 0.55f) {                                           // woodland floor
                        boolean pine = coniferAt(wx, wz);
                        float l = 0.80f + 0.30f * fine + 0.14f * (h - 0.5f);
                        if (pine) { r = 0.36f * l; g = 0.27f * l; b = 0.17f * l; mat = M_NEEDLES; }
                        else { r = 0.33f * l; g = 0.28f * l; b = 0.18f * l; mat = M_FOREST; }
                        float moss = m1.at(gx / TPU + 0.37f, gy / TPU + 0.11f);
                        if (moss > 0.66f) { r = 0.25f * l; g = 0.35f * l; b = 0.15f * l; mat = M_MOSS; }
                        else if (moss < 0.22f && !pine) { r = 0.30f * l; g = 0.24f * l; b = 0.16f * l; mat = M_ROOTS; }
                    } else if (fo > 0.48f) {                                    // woodland edge
                        if (h > 0.25f) mat = M_FOREST_GRASS;
                    }
                    if (slope > 0.95f) {                                        // bare rock on steep ground
                        float l = 0.80f + 0.25f * fine + 0.10f * (h - 0.5f);
                        r = 0.47f * l; g = 0.46f * l; b = 0.43f * l;
                        mat = M_ROCK;
                    } else if (slope > 0.70f && mat != M_MOSS) {
                        mat = M_GRAVEL;
                    }
                }
                cv.R[i] = r; cv.G[i] = g; cv.B[i] = b; cv.A[i] = t[3];
                cv.DH[i] = dh;
                cv.MAT[i] = (byte) (mat * MAT_STEP);
            }

        // ---- scatter: items from this chunk and its neighbours (they can reach over the border)
        cv.curMat = M_STONE;
        for (int dz = -1; dz <= 1; dz++)                          // mossy granite
            for (int dx = -1; dx <= 1; dx++) {
                int sx = ccx + dx, sz = ccz + dz;
                Random r = new Random(chunkSeed(sx, sz, 1));
                float wcx = (sx + 0.5f) * CHUNK, wcz = (sz + 0.5f) * CHUNK;
                float mean = 20f * (0.6f + 0.9f * smooth(0.45f, 0.75f, m1.at(wcx / 256f + 0.7f, wcz / 256f + 0.2f)));
                int n = count(r, mean);
                for (int k = 0; k < n; k++) {
                    int cx = sx * N + r.nextInt(N), cy = sz * N + r.nextInt(N);
                    float rad = 1.3f + r.nextFloat() * r.nextFloat() * 7f;
                    long seed = r.nextLong();
                    if (cx < gx0 - 40 || cy < gy0 - 40 || cx > gx0 + N + 40 || cy > gy0 + N + 40) continue;
                    if (busyAt(cx, cy, t)) continue;
                    rock(cv, cx, cy, rad, new Random(seed), f1);
                }
            }
        // standing stones on the rise west of the north road
        if (Math.abs(STONES_X * PPM - (gx0 + N / 2)) < N / 2 + 120 && Math.abs(STONES_Z * PPM - (gy0 + N / 2)) < N / 2 + 120)
            stoneCircle(cv, STONES_X, STONES_Z, 7.5f, 11, f1, new Random(555));
        cv.curMat = M_WOOD;
        for (int dz = -1; dz <= 1; dz++)                          // fallen logs and stumps
            for (int dx = -1; dx <= 1; dx++) {
                int sx = ccx + dx, sz = ccz + dz;
                Random r = new Random(chunkSeed(sx, sz, 2));
                float wcx = (sx + 0.5f) * CHUNK, wcz = (sz + 0.5f) * CHUNK;
                float woods = forestAt(wcx, wcz) > 0.55f ? 2.2f : 1f;
                int n = count(r, 2.5f * woods);
                for (int k = 0; k < n; k++) {
                    int cx = sx * N + r.nextInt(N), cy = sz * N + r.nextInt(N);
                    float len = 10f + r.nextFloat() * 18f, rad = 1.6f + r.nextFloat() * 1.6f, rot = r.nextFloat() * (float) Math.PI;
                    long seed = r.nextLong();
                    if (busyAt(cx, cy, t)) continue;
                    log(cv, cx, cy, len, rad, rot, new Random(seed), f2);
                }
                n = count(r, 3.4f * woods);
                for (int k = 0; k < n; k++) {
                    int cx = sx * N + r.nextInt(N), cy = sz * N + r.nextInt(N);
                    float rad = 2.2f + r.nextFloat() * 2.5f;
                    long seed = r.nextLong();
                    if (busyAt(cx, cy, t)) continue;
                    stump(cv, cx, cy, rad, new Random(seed));
                }
            }
        cv.curMat = M_STONE;
        for (int dz = -1; dz <= 1; dz++)                          // stone cairns
            for (int dx = -1; dx <= 1; dx++) {
                int sx = ccx + dx, sz = ccz + dz;
                Random r = new Random(chunkSeed(sx, sz, 3));
                int n = count(r, 1.4f);
                for (int k = 0; k < n; k++) {
                    int cx = sx * N + r.nextInt(N), cy = sz * N + r.nextInt(N);
                    long seed = r.nextLong();
                    if (busyAt(cx, cy, t)) continue;
                    Random rs = new Random(seed);
                    for (int j = 0; j < 7; j++)
                        rock(cv, cx + rs.nextInt(9) - 4, cy + rs.nextInt(9) - 4, 1.5f + rs.nextFloat() * 2.5f, rs, f1);
                    rock(cv, cx, cy, 2.5f, rs, f1);
                }
            }
        cv.curMat = M_EARTH;
        for (int dz = -1; dz <= 1; dz++)                          // molehills
            for (int dx = -1; dx <= 1; dx++) {
                int sx = ccx + dx, sz = ccz + dz;
                Random r = new Random(chunkSeed(sx, sz, 4));
                int n = count(r, 7.8f);
                for (int k = 0; k < n; k++) {
                    int cx = sx * N + r.nextInt(N), cy = sz * N + r.nextInt(N);
                    float rad = 2f + r.nextFloat() * 2f;
                    long seed = r.nextLong();
                    if (cx < gx0 - 8 || cy < gy0 - 8 || cx > gx0 + N + 8 || cy > gy0 + N + 8) continue;
                    if (busyAt(cx, cy, t)) continue;
                    molehill(cv, cx, cy, rad, new Random(seed));
                }
            }
        cv.curMat = M_STONE;
        if (Math.abs(141 * PPM - (gx0 + N / 2)) < N / 2 + 40 && Math.abs(113 * PPM - (gy0 + N / 2)) < N / 2 + 40)
            well(cv, (int) (141 * PPM), (int) (113 * PPM), 1.25f * PPM, 0.80f * PPM, f1);

        // ---- pack
        for (int y = 0; y < N; y++)
            for (int x = 0; x < N; x++) {
                int i = y * N + x;
                out.ground[i] = pack(cv.R[i], cv.G[i], cv.B[i], gx0 + x, gy0 + y) & 0x00FFFFFF | (Math.round(clamp(cv.A[i]) * 255f) << 24);
                out.detail[i] = (byte) Math.round(clamp(cv.DH[i] / DETAIL_MAX) * 255f);
                out.mat[i] = cv.MAT[i];
            }
        for (int j = 0; j < 2; j++)
            for (int i = 0; i < 2; i++)
                System.arraycopy(treeBytes(ccx * 2 + i, ccz * 2 + j), 0, out.trees, (j * 2 + i) * 4, 4);
        if (lib != null) TreeShadows.applyChunk(out.ground, gx0, gy0, N, lib);
        return out;
    }

    /** Copy a painted chunk into the window arrays (wrap-around addressing). */
    static void store(Chunk2D c) {
        final int N = CHUNK_PX, S = GROUND_SIZE;
        int ox = Math.floorMod(c.cx * N, S), oy = Math.floorMod(c.cz * N, S);
        for (int y = 0; y < N; y++) {
            int dst = (oy + y) * S + ox, src = y * N;
            System.arraycopy(c.ground, src, groundWin, dst, N);
            System.arraycopy(c.detail, src, detailWin, dst, N);
            System.arraycopy(c.mat, src, matWin, dst, N);
        }
        int hx = Math.floorMod(c.cx * 64, HEIGHT_SIZE), hz = Math.floorMod(c.cz * 64, HEIGHT_SIZE);
        for (int y = 0; y < 64; y++) {
            System.arraycopy(c.heights, y * 64, hFloor, (hz + y) * HEIGHT_SIZE + hx, 64);
            System.arraycopy(c.dig, y * 64, Digging.digWin, (hz + y) * HEIGHT_SIZE + hx, 64);
        }
        for (int j = 0; j < 2; j++)
            for (int i = 0; i < 2; i++) {
                int tx = Math.floorMod(c.cx * 2 + i, TREE_GRID), tz = Math.floorMod(c.cz * 2 + j, TREE_GRID);
                System.arraycopy(c.trees, (j * 2 + i) * 4, treeWin, (tz * TREE_GRID + tx) * 4, 4);
            }
    }

    // ------------------------------------------------------------------ one ground texel
    /**
     * The base painting of one ground texel at global texel (x, y): out = r, g, b, alpha, detail
     * height, material id, busy (1 = road / water / plaza: nothing is scattered there).
     * Photographic style at a deliberately low texel density (12.5 cm per texel).
     */
    static void paintTexel(int x, int y, float[] out) {
        float u = (x + 0.5f) / TPU, v = (y + 0.5f) / TPU;
        float wx = (x + 0.5f) / PPM, wz = (y + 0.5f) / PPM;
        boolean busy = false;
        int mat = M_GRASS;
        float big = c1.at(u, v) * 0.6f + c2.at(u, v) * 0.4f;
        float big2 = c2.at(u + 0.5f, v + 0.23f);
        float mid = m1.at(u, v) * 0.55f + m2.at(u, v) * 0.45f;
        float fine = f1.at(u, v) * 0.6f + f2.at(u, v) * 0.4f;
        float h = hash(x, y), h2 = hash(x + 911, y - 317);
        float a = 1f;
        // detail height (m): grass is a fine fuzz of blades
        // lumpy meadow: gentle hummocks plus a dense fuzz of grass blades
        float hummock = smooth(0.35f, 0.9f, m2.at(u + 0.21f, v + 0.67f)) * 0.22f + (f1.at(u + 0.3f, v) - 0.5f) * 0.06f;
        float dh = 0.03f + hummock + (FLORA ? 0.11f * h * h + 0.05f * blade.at(u, v) : 0f);

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
            if (FLORA && h2 > 0.993f && clover > 0.3f) { r = 0.88f; g = 0.88f; b = 0.84f; }
        }
        // bare earth patches
        float bare = smooth(0.70f, 0.78f, m1.at(u + 0.13f, v + 0.51f) * 0.7f + m2.at(u, v) * 0.3f);
        if (bare > 0) {
            float el = 0.88f + 0.2f * fine + 0.1f * (h - 0.5f);
            float er = 0.43f * el, eg = 0.35f * el, eb = 0.25f * el;
            if (h > 0.7f) { er = r; eg = g; eb = b; }             // grass poking through
            r = lerp(r, er, bare); g = lerp(g, eg, bare); b = lerp(b, eb, bare);
            dh = lerp(dh, h > 0.7f ? dh : 0.01f + 0.015f * fine, bare);
            if (bare > 0.5f && h <= 0.7f) mat = M_EARTH;
        }
        // wildflowers in drifts
        float fl = smooth(0.55f, 0.85f, m2.at(u + 0.7f, v + 0.3f)) * (1f - bare);
        if (FLORA && h2 < 0.028f * fl) {
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
            // the stones' own relief comes from the cobblestone texture's height map
            dh = 0.02f;
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
            mat = M_STONE;
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
                if (ta > 0.5f) mat = M_EARTH;
                roadA = Math.max(roadA, ta);
            }
        }

        // ---- river with muddy banks, reeds and a stone bridge on the main road
        float rOff = riverOff(wx, wz), rhw = riverHalfWidth(wx) + edgeN * 0.8f;
        float rd = Math.abs(rOff);
        boolean bridge = sd < 4.2f && rd < rhw + 2.6f;
        if (bridge) {
            float alongB = rOff;
            int fx = (int) Math.floor((dx + 8f) / 0.62f), fz = (int) Math.floor((alongB + 20f) / 0.42f + (fx & 1) * 0.5f);
            float fu = (dx + 8f) / 0.62f - fx, fv = (alongB + 20f) / 0.42f + (fx & 1) * 0.5f - fz;
            float bl2 = 0.74f + 0.22f * hash(fx * 31 + fz, 7) + 0.06f * (h - 0.5f) + 0.08f * (fine - 0.5f);
            r = 0.58f * bl2; g = 0.55f * bl2; b = 0.50f * bl2;
            if (fu < 0.10f || fv < 0.12f) { r *= 0.55f; g *= 0.55f; b *= 0.55f; }
            dh = 0.02f;                                            // relief from the cobblestone texture
            if (sd > 3.5f) {                                       // parapet walls
                dh = 0.95f + 0.05f * hash(fz, 3);
                float pl = 0.95f - 0.35f * (dx > 0 ? 1f : 0f) * smooth(3.5f, 4.2f, sd) + 0.1f * (h - 0.5f);
                r = 0.62f * pl; g = 0.60f * pl; b = 0.55f * pl;
            }
            busy = true;
            mat = M_STONE;
            paved = true;
        } else if (rd < rhw + 1.8f) {
            busy = true;
            float bank = smooth(rhw + 1.8f, rhw, rd);              // 0 at grass edge .. 1 at waterline
            float ml = 0.85f + 0.25f * fine + 0.12f * (h - 0.5f);
            float mr = 0.42f * ml, mg = 0.36f * ml, mb = 0.27f * ml;
            if (hash(x >> 1, y >> 1) < 0.12f) { mr = 0.55f * ml; mg = 0.53f * ml; mb = 0.50f * ml; }  // pebbles
            r = lerp(r, mr, bank); g = lerp(g, mg, bank); b = lerp(b, mb, bank);
            dh = lerp(dh, hash(x >> 1, y >> 1) < 0.12f ? 0.05f : 0.01f, bank);
            if (bank > 0.5f) mat = M_EARTH;
            // reeds along the outer bank
            if (FLORA && rd > rhw + 0.3f && rd < rhw + 1.3f && m2.at(u * 4f, v * 4f) > 0.55f && h > 0.45f) {
                float rl = 0.7f + 0.5f * h2;
                r = 0.30f * rl; g = 0.40f * rl; b = 0.18f * rl;
                dh = 0.35f + 0.45f * h2;
                mat = M_PLANT;
            }
            if (rd < rhw) {
                float depth = smooth(0f, 1.6f, rhw - rd);
                a = 1f - (0.55f + 0.45f * depth);                  // shallows reflect less
                float wl = 0.85f + 0.2f * fine;
                r = lerp(0.30f, 0.10f, depth) * wl; g = lerp(0.29f, 0.15f, depth) * wl; b = lerp(0.22f, 0.13f, depth) * wl;
                dh = 0f;
                mat = M_WATER;
            }
        }
        // shadow of the bridge on the water either side
        if (!bridge && sd < 5.2f && sd >= 4.2f && rd < rhw && dx > 0) { r *= 0.6f; g *= 0.6f; b *= 0.6f; a = Math.min(1f, a + 0.3f); }

        // ---- plaza fountain
        if (plaza < 4.0f) {
            busy = true;
            if (plaza > 3.2f) {                                    // basin rim
                int st = (int) Math.floor((Math.atan2(dz, dx) + Math.PI) / TAU * 26);
                float sf = (float) ((Math.atan2(dz, dx) + Math.PI) / TAU * 26 - st);
                float rl = 0.80f + 0.18f * hash(st, 9) - 0.25f * (dx + dz) / 4f + 0.05f * (h - 0.5f);
                if (sf < 0.08f) rl *= 0.6f;
                r = 0.66f * rl; g = 0.63f * rl; b = 0.58f * rl;
                a = 1f;
                dh = 0.55f;
                mat = M_STONE;
            } else if (plaza < 0.55f) {                            // central column
                float rl = 0.75f - 0.4f * (dx + dz) / 0.55f;
                r = 0.62f * rl; g = 0.60f * rl; b = 0.56f * rl;
                a = 1f;
                dh = 1.6f;
                mat = M_STONE;
            } else {
                float ripple = 0.5f + 0.5f * (float) Math.sin(plaza * 9f);
                r = 0.12f + 0.03f * ripple; g = 0.17f + 0.03f * ripple; b = 0.17f + 0.03f * ripple;
                a = 0.15f;
                dh = 0.25f;                                        // water surface inside the basin
                mat = M_WATER;
            }
        }

        if (paved || roadA > 0.5f || a < 1f) busy = true;
        out[0] = r; out[1] = g; out[2] = b; out[3] = a;
        out[4] = dh;
        out[5] = mat;
        out[6] = busy ? 1f : 0f;
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



    private static float lobe(int seed, double ang, int lobes) {
        double p = (ang + Math.PI) / (Math.PI * 2) * lobes;
        int k = (int) Math.floor(p);
        float f = (float) (p - k);
        f = f * f * (3 - 2 * f);
        return lerp(hash(seed, Math.floorMod(k, lobes)), hash(seed, Math.floorMod(k + 1, lobes)), f);
    }


    /** Fallen log: a rotated capsule of bark with a pale cut end and a soft shadow. */
    private static void log(Canvas cv, int cx, int cy, float len, float rad, float rot, Random rnd, PNoise fn) {
        final float[] R = cv.R, G = cv.G, B = cv.B;
        float c = (float) Math.cos(rot), s = (float) Math.sin(rot);
        int ext = (int) (len + rad + 4);
        if (!cv.touches(cx, cy, ext)) return;
        for (int pass = 0; pass < 2; pass++)
            for (int dy = -ext; dy <= ext; dy++)
                for (int dx = -ext; dx <= ext; dx++) {
                    float qx = dx - (pass == 0 ? rad * 0.8f : 0), qy = dy - (pass == 0 ? rad * 0.8f : 0);
                    float a = qx * c + qy * s, b = -qx * s + qy * c;            // along, across
                    float ac = Math.max(-len / 2, Math.min(len / 2, a));
                    float d = (float) Math.sqrt((a - ac) * (a - ac) + b * b);
                    if (d >= rad) continue;
                    int x = cx + dx, y = cy + dy, i = cv.idx(x, y);
                    if (i < 0) continue;
                    if (pass == 0) { R[i] *= 0.6f; G[i] *= 0.6f; B[i] *= 0.6f; continue; }
                    float prof = (float) Math.sqrt(1f - (b / rad) * (b / rad));
                    float bark = 0.75f + 0.25f * hash((int) (a * 1.5f) + x, (int) (b * 3)) + 0.2f * (fn.at((x + .5f) / TPU, (y + .5f) / TPU) - 0.5f);
                    float light = (0.55f + 0.5f * prof - 0.25f * (b / rad) * (c - s)) * bark;
                    float r = 0.38f * light, g = 0.29f * light, bb = 0.20f * light;
                    if (Math.abs(a) > len / 2 - 0.8f) { r = 0.62f * light; g = 0.50f * light; bb = 0.34f * light; }   // cut end
                    if (hash(x * 3, y) > 0.9f) { r = lerp(r, 0.30f, 0.6f); g = lerp(g, 0.42f, 0.6f); bb = lerp(bb, 0.18f, 0.6f); } // moss
                    R[i] = r; G[i] = g; B[i] = bb;
                    cv.raise(i, rad / PPM * 2f * (0.5f + 0.5f * prof));
                }
    }

    /** Sawn tree stump with growth rings. */
    private static void stump(Canvas cv, int cx, int cy, float rad, Random rnd) {
        final float[] R = cv.R, G = cv.G, B = cv.B;
        int ext = (int) (rad * 2.2f) + 2;
        float hgt = 0.35f + rnd.nextFloat() * 0.35f;
        if (!cv.touches(cx, cy, ext)) return;
        for (int dy = -ext; dy <= ext; dy++)
            for (int dx = -ext; dx <= ext; dx++) {
                float ds = (float) Math.sqrt((dx - rad) * (dx - rad) + (dy - rad * 0.7f) * (dy - rad * 0.7f));
                if (ds < rad * 1.1f) {
                    int i = cv.idx(cx + dx, cy + dy);
                    if (i < 0) continue;
                    R[i] *= 0.6f; G[i] *= 0.6f; B[i] *= 0.6f;
                }
            }
        for (int dy = -ext; dy <= ext; dy++)
            for (int dx = -ext; dx <= ext; dx++) {
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d >= rad) continue;
                int x = cx + dx, y = cy + dy, i = cv.idx(x, y);
                    if (i < 0) continue;
                float r, g, b;
                if (d > rad - 1.0f) { r = 0.30f; g = 0.23f; b = 0.16f; }               // bark rim
                else {
                    float ring = 0.85f + 0.15f * (float) Math.sin(d * 2.6f);
                    r = 0.66f * ring; g = 0.54f * ring; b = 0.38f * ring;
                }
                R[i] = r; G[i] = g; B[i] = b;
                cv.raise(i, hgt);
            }
    }

    /** Molehill: a small dome of fresh dark soil. */
    private static void molehill(Canvas cv, int cx, int cy, float rad, Random rnd) {
        final float[] R = cv.R, G = cv.G, B = cv.B;
        int ext = (int) rad + 2;
        if (!cv.touches(cx, cy, ext)) return;
        for (int dy = -ext; dy <= ext; dy++)
            for (int dx = -ext; dx <= ext; dx++) {
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d >= rad) continue;
                int x = cx + dx, y = cy + dy, i = cv.idx(x, y);
                    if (i < 0) continue;
                float dome = (float) Math.sqrt(1f - (d / rad) * (d / rad));
                float l = (0.7f + 0.4f * dome - 0.2f * (dx + dy) / rad) * (0.85f + 0.3f * hash(x, y));
                R[i] = 0.30f * l; G[i] = 0.22f * l; B[i] = 0.15f * l;
                cv.raise(i, 0.18f * dome + 0.02f);
            }
    }

    /** Granite boulder with lichen and moss, lit from the north-west. */
    private static void rock(Canvas cv, int cx, int cy, float rad, Random rnd, PNoise fn) {
        final float[] R = cv.R, G = cv.G, B = cv.B;
        int seed = rnd.nextInt(1 << 20);
        float k = rnd.nextFloat();
        float cr = 0.56f, cg = 0.56f, cb = 0.54f;
        if (k > 0.7f) { cr = 0.50f; cg = 0.51f; cb = 0.53f; }
        int ext = (int) Math.ceil(rad * 1.8f) + 2;
        float so = rad * 0.5f;
        if (!cv.touches(cx, cy, ext)) return;
        for (int dy = -ext; dy <= ext; dy++)
            for (int dx = -ext; dx <= ext; dx++) {
                float sx = dx - so, sy = dy - so;
                float d = (float) Math.sqrt(sx * sx + sy * sy);
                float er = rad * (0.8f + 0.3f * lobe(seed + 3, Math.atan2(sy, sx), 6));
                if (d < er * 1.1f) {
                    int i = cv.idx(cx + dx, cy + dy);
                    if (i < 0) continue;
                    float s = 0.50f + 0.50f * smooth(er * 0.6f, er * 1.1f, d);
                    R[i] *= s; G[i] *= s; B[i] *= s;
                }
            }
        for (int dy = -ext; dy <= ext; dy++)
            for (int dx = -ext; dx <= ext; dx++) {
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                float er = rad * (0.75f + 0.4f * lobe(seed, Math.atan2(dy, dx), 7));
                if (d >= er) continue;
                int x = cx + dx, y = cy + dy, i = cv.idx(x, y);
                    if (i < 0) continue;
                float n = fn.at((x + 0.5f) / TPU, (y + 0.5f) / TPU);
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
                cv.raise(i, rad / PPM * 0.75f * (float) Math.pow(dome, 0.7) * (0.88f + 0.12f * facet));
            }
    }

    /** Ring of standing stones with long baked shadows and a flat altar stone in the middle. */
    private static void stoneCircle(Canvas cv, float wx, float wz, float radius, int count, PNoise fn, Random rnd) {
        final float[] R = cv.R, G = cv.G, B = cv.B;
        // worn grass inside the ring
        int ext = (int) ((radius + 3) * PPM);
        int cx = (int) Math.floor(wx * PPM), cy = (int) Math.floor(wz * PPM);
        for (int dy = -ext; dy <= ext; dy++)
            for (int dx = -ext; dx <= ext; dx++) {
                float d = (float) Math.sqrt(dx * dx + dy * dy) / PPM;
                if (d < radius + 1.5f) {
                    int i = cv.idx(cx + dx, cy + dy);
                    if (i < 0) continue;
                    float k = smooth(radius + 1.5f, radius - 1f, d) * 0.35f;
                    R[i] = lerp(R[i], R[i] * 1.15f + 0.04f, k); G[i] = lerp(G[i], G[i] * 0.95f, k); B[i] = lerp(B[i], B[i] * 0.9f, k);
                }
            }
        for (int k = 0; k < count; k++) {
            double ang = TAU * k / count + rnd.nextFloat() * 0.12f;
            float sx = wx + (float) Math.cos(ang) * radius, sz = wz + (float) Math.sin(ang) * radius;
            float len = 0.55f + rnd.nextFloat() * 0.25f, wid = 0.32f + rnd.nextFloat() * 0.12f;
            menhir(cv, sx, sz, len, wid, (float) ang + (float) Math.PI / 2, 2.6f + rnd.nextFloat() * 1.4f, fn, rnd.nextInt(9999),
                    2.1f + rnd.nextFloat() * 0.8f);
        }
        menhir(cv, wx, wz, 1.2f, 0.7f, 0.3f, 0.6f, fn, 4242, 0.55f);
    }

    /** A standing stone seen from above: rotated rounded slab, lit top, long shadow to the south-east. */
    private static void menhir(Canvas cv, float wx, float wz, float halfLen, float halfWid, float rot,
                               float shadowLen, PNoise fn, int seed, float height) {
        final float[] R = cv.R, G = cv.G, B = cv.B;
        float c = (float) Math.cos(rot), s = (float) Math.sin(rot);
        int ext = (int) ((halfLen + shadowLen + 1) * PPM);
        int cx = (int) Math.floor(wx * PPM), cy = (int) Math.floor(wz * PPM);
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
                            int i = cv.idx(cx + dx, cy + dy);
                    if (i < 0) continue;
                            R[i] *= 0.55f; G[i] *= 0.55f; B[i] *= 0.58f;
                        }
                    } else {
                        float lx = mx * c + mz * s, lz = -mx * s + mz * c;
                        float e = (lx / halfLen) * (lx / halfLen) + (lz / halfWid) * (lz / halfWid);
                        if (e >= 1f) continue;
                        int x = cx + dx, y = cy + dy, i = cv.idx(x, y);
                    if (i < 0) continue;
                        float n = fn.at((x + 0.5f) / TPU, (y + 0.5f) / TPU);
                        float light = 0.95f - 0.35f * (mx + mz) / (halfLen + halfWid) + 0.15f * (n - 0.5f) - 0.2f * e + 0.08f * (hash(x, y + seed) - 0.5f);
                        float r = 0.55f * light, g = 0.55f * light, b = 0.54f * light;
                        float moss = smooth(0.6f, 0.8f, n);
                        r = lerp(r, 0.32f * light, moss); g = lerp(g, 0.42f * light, moss); b = lerp(b, 0.20f * light, moss);
                        R[i] = r; G[i] = g; B[i] = b;
                        cv.raise(i, height * (1f - 0.35f * e * e));
                    }
                }
    }



    private static void well(Canvas cv, int cx, int cy, float ro, float ri, PNoise fn) {
        final float[] R = cv.R, G = cv.G, B = cv.B;
        int ext = (int) ro + 6;
        for (int dy = -ext; dy <= ext; dy++)
            for (int dx = -ext; dx <= ext; dx++) {
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                int x = cx + dx, y = cy + dy, i = cv.idx(x, y);
                    if (i < 0) continue;
                float sd = (float) Math.sqrt((dx - 4) * (dx - 4) + (dy - 4) * (dy - 4));
                if (d >= ro && sd < ro + 1) { R[i] *= 0.55f; G[i] *= 0.55f; B[i] *= 0.55f; }
                if (d < ri) {
                    float w = 0.06f + 0.10f * smooth(ri * 0.2f, ri, d + dx * 0.5f);
                    R[i] = w; G[i] = w * 1.05f; B[i] = w * 1.15f;
                    cv.DH[i] = 0f;
                } else if (d < ro) {
                    float ang = (float) Math.atan2(dy, dx);
                    boolean outer = d > (ri + ro) / 2;
                    float sf = ang * (outer ? 3.2f : 2.4f) + (outer ? 0.5f : 0f);
                    int stone = (int) Math.floor(sf) + (outer ? 50 : 0);
                    sf -= (float) Math.floor(sf);
                    float t = 0.72f + 0.3f * hash(stone, 5) - 0.22f * (dx + dy) / ro + 0.15f * (fn.at((x + 0.5f) / TPU, (y + 0.5f) / TPU) - 0.5f);
                    if (sf < 0.12f || Math.abs(d - (ri + ro) / 2) < 0.5f) t *= 0.55f;
                    R[i] = 0.60f * t; G[i] = 0.58f * t; B[i] = 0.53f * t;
                    cv.raise(i, 0.75f);
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
        TileNoise[] oct = {new TileNoise(4, rnd), new TileNoise(8, rnd), new TileNoise(16, rnd), new TileNoise(32, rnd), new TileNoise(64, rnd)};
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
