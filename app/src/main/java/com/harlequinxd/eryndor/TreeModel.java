package com.harlequinxd.eryndor;

import java.util.ArrayList;
import java.util.Random;

/**
 * Grows a tree from a {@link TreeSpec}: a spline trunk, branches, sub-branches and twigs (each a
 * chain of tapered round segments), and textured leaves on the twigs. The result is a flat list of
 * primitives plus a 3D grid of zones listing which primitives pass through each zone, so the shader
 * only tests what is near each ray. Pure Java.
 *
 * Primitive record = 12 floats (3 RGBA32F texels):
 *   segment: a.xyz, radius a | b.xyz, radius b | 0, level, bark v at a, bark v at b
 *   leaf:    centre.xyz, size | direction.xyz, shape | 1 (normal.y >= 0) or 2 (< 0), normal.x, normal.z, tint
 */
final class TreeModel {

    static final int MAX_PRIMS = 7000;
    /** Leaf cards: each is a sprig of several leaves, CARD times the single-leaf size. */
    static final float[] LEAF_ASPECT = {0.85f, 0.85f, 0.85f, 0.85f, 0.55f, 0.6f, 0.85f, 0.85f};
    static final float CARD = 6.0f;

    float[] prims = new float[0];
    int primCount;
    float minX, minY, minZ, maxX, maxY, maxZ;
    int gx, gy, gz;
    int[] cellStart, cellCount, index;
    float trunkRadius;

    private final ArrayList<float[]> out = new ArrayList<>();
    private Random rnd;
    private TreeSpec s;

    /** One grown branch: polyline points (x, y, z, radius) and its cumulative length. */
    private static final class Limb {
        final ArrayList<float[]> pts = new ArrayList<>();
        float length;
        int level;
    }

    static TreeModel grow(TreeSpec spec) {
        TreeModel m = new TreeModel();
        m.build(spec);
        return m;
    }

    private void build(TreeSpec spec) {
        s = spec;
        rnd = new Random(spec.whole(TreeSpec.SEED) * 7919L + 17);
        float H = s.get(TreeSpec.HEIGHT), R0 = s.get(TreeSpec.TRUNK_R);
        trunkRadius = R0;

        // ---- trunk: a gently bent spline with wiggle and root flare
        Limb trunk = new Limb();
        trunk.level = 0;
        float bend = s.get(TreeSpec.BEND), wig = s.get(TreeSpec.WIGGLE), flare = s.get(TreeSpec.FLARE);
        double bendDir = rnd.nextDouble() * Math.PI * 2, phase = rnd.nextDouble() * 6;
        int segs = 12;
        for (int i = 0; i <= segs; i++) {
            float t = i / (float) segs;
            float off = bend * H * 0.12f * t * t;
            float wx = wig * 0.25f * (float) Math.sin(t * 9 + phase) * t, wz = wig * 0.25f * (float) Math.cos(t * 7 + phase * 1.3) * t;
            float x = (float) Math.cos(bendDir) * off + wx, z = (float) Math.sin(bendDir) * off + wz;
            float r = R0 * (1f - (1f - s.get(TreeSpec.TAPER)) * t);
            r *= 1f + flare * (float) Math.exp(-t * H * 2.2f);
            trunk.pts.add(new float[]{x, t * H, z, r});
        }
        emit(trunk);

        // ---- branch levels
        ArrayList<Limb> level1 = new ArrayList<>(), level2 = new ArrayList<>(), twigs = new ArrayList<>();
        int n1 = s.whole(TreeSpec.B1_COUNT);
        float start = s.get(TreeSpec.B1_START), spiral = (float) Math.toRadians(s.get(TreeSpec.SPIRAL));
        int crown = s.whole(TreeSpec.CROWN);
        for (int i = 0; i < n1; i++) {
            float u = start + (1f - start) * ((i + 0.5f) / n1) * 0.96f + (rnd.nextFloat() - 0.5f) * 0.04f;
            u = Math.max(0.02f, Math.min(0.97f, u));
            float rel = (u - start) / Math.max(0.01f, 1f - start);
            float len = H * s.get(TreeSpec.B1_LENGTH) * profile(crown, rel) * (0.75f + 0.5f * rnd.nextFloat());
            float az = i * spiral + (rnd.nextFloat() - 0.5f) * 0.6f;
            float elev = (float) Math.toRadians(s.get(TreeSpec.B1_ANGLE) + (rnd.nextFloat() - 0.5f) * 18f);
            float droop = s.get(TreeSpec.B1_DROOP) + (crown == 3 ? 0.6f : 0f);
            Limb b = child(trunk, u, az, elev, len, s.get(TreeSpec.B1_RADIUS), droop, s.get(TreeSpec.B1_CURL), 6, 1, true);
            if (b != null) level1.add(b);
        }
        int n2 = s.whole(TreeSpec.B2_COUNT);
        for (Limb p : level1)
            for (int i = 0; i < n2; i++) {
                float u = 0.25f + 0.7f * (i + 0.5f) / n2 + (rnd.nextFloat() - 0.5f) * 0.08f;
                float len = p.length * s.get(TreeSpec.B2_LENGTH) * (1f - 0.45f * u) * (0.75f + 0.5f * rnd.nextFloat());
                float az = (float) (i * 2.4 + rnd.nextFloat());
                float elev = (float) Math.toRadians(s.get(TreeSpec.B2_ANGLE) + (rnd.nextFloat() - 0.5f) * 20f);
                Limb b = child(p, u, az, elev, len, 0.6f, s.get(TreeSpec.B2_DROOP) + (crown == 3 ? 0.5f : 0f), 0.3f, 4, 2, false);
                if (b != null) level2.add(b);
            }
        int n3 = s.whole(TreeSpec.TW_COUNT);
        ArrayList<Limb> twigParents = level2.isEmpty() ? level1 : level2;
        for (Limb p : twigParents)
            for (int i = 0; i < n3; i++) {
                float u = 0.3f + 0.68f * (i + 0.5f) / n3;
                float len = Math.max(0.15f, p.length * s.get(TreeSpec.TW_LENGTH) * (0.7f + 0.6f * rnd.nextFloat()));
                float az = (float) (i * 2.4 + rnd.nextFloat());
                float elev = (float) Math.toRadians(s.get(TreeSpec.TW_ANGLE) + (rnd.nextFloat() - 0.5f) * 20f);
                Limb b = child(p, u, az, elev, len, 0.55f, crown == 3 ? 0.9f : 0.2f, 0.3f, 2, 3, false);
                if (b != null) twigs.add(b);
            }

        // ---- leaves on the outermost wood
        ArrayList<Limb> leafy = !twigs.isEmpty() ? twigs : (!level2.isEmpty() ? level2 : level1);
        int per = s.whole(TreeSpec.LEAF_COUNT);
        int budget = MAX_PRIMS - out.size();
        if (per > 0 && !leafy.isEmpty()) {
            int total = per * leafy.size();
            float keep = total > budget ? budget / (float) total : 1f;
            for (Limb l : leafy) leaves(l, per, keep);
        }

        pack();
    }

    /** Branch length multiplier by height within the crown, for each crown shape. */
    private static float profile(int crown, float rel) {
        switch (crown) {
            case 1: return Math.max(0.12f, 1f - 0.88f * rel);                                 // cone
            case 2: return 0.55f + 0.25f * (float) Math.sin(Math.PI * rel);                   // column
            case 4: return Math.max(0.2f, 1f - 0.6f * (float) Math.pow(rel, 1.5));            // spreading
            default: return 0.45f + 0.55f * (float) Math.sin(Math.PI * (0.12f + 0.88f * rel)); // round, weeping
        }
    }

    /** Grow a child limb from a parent at fraction u of its length. */
    private Limb child(Limb parent, float u, float az, float elev, float len, float radiusRatio, float droop, float curl,
                       int segs, int level, boolean aroundVertical) {
        if (len < 0.05f) return null;
        float[] pos = new float[4], dir = new float[3];
        sample(parent, u, pos, dir);
        float[] axis = aroundVertical ? new float[]{0, 1, 0} : dir;
        float[] a = perpendicular(axis), b = cross(axis, a);
        float ce = (float) Math.cos(elev), se = (float) Math.sin(elev), ca = (float) Math.cos(az), sa = (float) Math.sin(az);
        float[] d = {axis[0] * ce + (a[0] * ca + b[0] * sa) * se, axis[1] * ce + (a[1] * ca + b[1] * sa) * se, axis[2] * ce + (a[2] * ca + b[2] * sa) * se};
        normalize(d);
        Limb l = new Limb();
        l.level = level;
        float r0 = Math.max(0.006f, pos[3] * radiusRatio), r1 = Math.max(0.004f, r0 * 0.3f);
        float[] side = perpendicular(d);
        float[] p = {pos[0], pos[1], pos[2]};
        l.pts.add(new float[]{p[0], p[1], p[2], r0});
        float step = len / segs;
        for (int i = 1; i <= segs; i++) {
            float t = i / (float) segs;
            d[1] -= droop * 0.32f * (0.4f + t);                 // gravity (negative droop lifts)
            float c = curl * 0.35f * (float) Math.sin(t * 3 + az);
            d[0] += side[0] * c + (rnd.nextFloat() - 0.5f) * 0.12f;
            d[1] += side[1] * c + (rnd.nextFloat() - 0.5f) * 0.12f;
            d[2] += side[2] * c + (rnd.nextFloat() - 0.5f) * 0.12f;
            normalize(d);
            p[0] += d[0] * step; p[1] += d[1] * step; p[2] += d[2] * step;
            l.pts.add(new float[]{p[0], p[1], p[2], r0 + (r1 - r0) * t});
        }
        l.length = len;
        emit(l);
        return l;
    }

    /** Position (+radius) and direction at fraction u along a limb. */
    private static void sample(Limb l, float u, float[] pos, float[] dir) {
        int n = l.pts.size() - 1;
        float f = Math.max(0f, Math.min(0.9999f, u)) * n;
        int i = (int) f;
        float t = f - i;
        float[] a = l.pts.get(i), b = l.pts.get(i + 1);
        for (int k = 0; k < 4; k++) pos[k] = a[k] + (b[k] - a[k]) * t;
        dir[0] = b[0] - a[0]; dir[1] = b[1] - a[1]; dir[2] = b[2] - a[2];
        normalize(dir);
    }

    private void emit(Limb l) {
        float v = 0f;
        for (int i = 0; i + 1 < l.pts.size(); i++) {
            float[] a = l.pts.get(i), b = l.pts.get(i + 1);
            float seg = (float) Math.sqrt(sq(b[0] - a[0]) + sq(b[1] - a[1]) + sq(b[2] - a[2]));
            if (out.size() >= MAX_PRIMS) return;
            out.add(new float[]{a[0], a[1], a[2], a[3], b[0], b[1], b[2], b[3], 0, l.level, v, v + seg});
            v += seg;
        }
        if (l.length == 0f) l.length = v;
    }

    private void leaves(Limb twig, int count, float keep) {
        int shape = s.whole(TreeSpec.LEAF_SHAPE);
        float size = s.get(TreeSpec.LEAF_SIZE), spread = Math.max(0.05f, s.get(TreeSpec.LEAF_SPREAD)), hang = s.get(TreeSpec.LEAF_HANG);
        float mix = s.get(TreeSpec.LEAF_MIX);
        float[] pos = new float[4], dir = new float[3];
        for (int i = 0; i < count; i++) {
            if (rnd.nextFloat() > keep) continue;
            if (out.size() >= MAX_PRIMS) return;
            float u = 1f - spread + spread * (i + 0.5f) / count;
            sample(twig, u, pos, dir);
            float[] a = perpendicular(dir), b = cross(dir, a);
            float ang = i * 2.39996f + rnd.nextFloat() * 0.5f;
            float[] side = {a[0] * (float) Math.cos(ang) + b[0] * (float) Math.sin(ang), a[1] * (float) Math.cos(ang) + b[1] * (float) Math.sin(ang),
                    a[2] * (float) Math.cos(ang) + b[2] * (float) Math.sin(ang)};
            float along = shape == 5 ? 0.85f : 0.4f;            // needles lie along the twig
            float[] ld = {dir[0] * along + side[0], dir[1] * along + side[1] - hang * 0.7f, dir[2] * along + side[2]};
            normalize(ld);
            // leaves face the sky with some scatter
            float[] up = {(rnd.nextFloat() - 0.5f) * 0.9f, 1f, (rnd.nextFloat() - 0.5f) * 0.9f};
            float dd = dot(up, ld);
            float[] nrm = {up[0] - ld[0] * dd, up[1] - ld[1] * dd, up[2] - ld[2] * dd};
            normalize(nrm);
            float sz = size * CARD * (0.8f + 0.4f * rnd.nextFloat());
            float tint = rnd.nextFloat() * 0.999f + (rnd.nextFloat() < mix ? 10f : 0f);
            out.add(new float[]{pos[0], pos[1], pos[2], sz, ld[0], ld[1], ld[2], shape,
                    nrm[1] >= 0 ? 1f : 2f, nrm[0], nrm[2], tint});
        }
    }

    // ------------------------------------------------------------------ packing + zone grid
    private void pack() {
        primCount = out.size();
        prims = new float[primCount * 12];
        minX = minY = minZ = Float.MAX_VALUE;
        maxX = maxY = maxZ = -Float.MAX_VALUE;
        float[][] box = new float[primCount][6];
        for (int i = 0; i < primCount; i++) {
            float[] p = out.get(i);
            System.arraycopy(p, 0, prims, i * 12, 12);
            float[] bb = box[i];
            if (p[8] < 0.5f) {
                float r = Math.max(p[3], p[7]);
                bb[0] = Math.min(p[0], p[4]) - r; bb[1] = Math.min(p[1], p[5]) - r; bb[2] = Math.min(p[2], p[6]) - r;
                bb[3] = Math.max(p[0], p[4]) + r; bb[4] = Math.max(p[1], p[5]) + r; bb[5] = Math.max(p[2], p[6]) + r;
            } else {
                // the card's four corners: from the stalk point out along its direction, half-width across
                float sz = p[3], asp = LEAF_ASPECT[(int) p[7]];
                float nx = p[9], nz = p[10], ny = (p[8] < 1.5f ? 1f : -1f) * (float) Math.sqrt(Math.max(0, 1 - nx * nx - nz * nz));
                float[] d = {p[4], p[5], p[6]}, n = {nx, ny, nz};
                float[] side = cross(n, d);
                normalize(side);
                float w = sz * asp * 0.5f;
                bb[0] = bb[1] = bb[2] = Float.MAX_VALUE;
                bb[3] = bb[4] = bb[5] = -Float.MAX_VALUE;
                for (int c = 0; c < 4; c++) {
                    float al = (c & 1) == 0 ? 0f : sz, ac = (c & 2) == 0 ? -w : w;
                    for (int k = 0; k < 3; k++) {
                        float v = p[k] + d[k] * al + side[k] * ac;
                        bb[k] = Math.min(bb[k], v);
                        bb[k + 3] = Math.max(bb[k + 3], v);
                    }
                }
            }
            minX = Math.min(minX, bb[0]); minY = Math.min(minY, bb[1]); minZ = Math.min(minZ, bb[2]);
            maxX = Math.max(maxX, bb[3]); maxY = Math.max(maxY, bb[4]); maxZ = Math.max(maxZ, bb[5]);
        }
        if (primCount == 0) { minX = minY = minZ = 0; maxX = maxY = maxZ = 1; }
        minY = Math.min(minY, -0.2f);
        float w = Math.max(maxX - minX, maxZ - minZ), h = maxY - minY;
        // ~0.6 m zones keep each zone's list short even with large leaf cards
        gx = Math.max(6, Math.min(24, Math.round((maxX - minX) / 0.6f)));
        gz = Math.max(6, Math.min(24, Math.round((maxZ - minZ) / 0.6f)));
        gy = Math.max(6, Math.min(28, Math.round(h / 0.6f)));
        float cx = (maxX - minX) / gx, cy = (maxY - minY) / gy, cz = (maxZ - minZ) / gz;
        int cells = gx * gy * gz;
        ArrayList<ArrayList<Integer>> lists = new ArrayList<>(cells);
        for (int c = 0; c < cells; c++) lists.add(new ArrayList<>());
        for (int i = 0; i < primCount; i++) {
            float[] bb = box[i];
            int x0 = clampi((int) ((bb[0] - minX) / cx), gx), x1 = clampi((int) ((bb[3] - minX) / cx), gx);
            int y0 = clampi((int) ((bb[1] - minY) / cy), gy), y1 = clampi((int) ((bb[4] - minY) / cy), gy);
            int z0 = clampi((int) ((bb[2] - minZ) / cz), gz), z1 = clampi((int) ((bb[5] - minZ) / cz), gz);
            for (int z = z0; z <= z1; z++)
                for (int y = y0; y <= y1; y++)
                    for (int x = x0; x <= x1; x++) lists.get((z * gy + y) * gx + x).add(i);
        }
        cellStart = new int[cells];
        cellCount = new int[cells];
        int total = 0;
        for (int c = 0; c < cells; c++) total += lists.get(c).size();
        index = new int[total];
        int o = 0;
        for (int c = 0; c < cells; c++) {
            cellStart[c] = o;
            cellCount[c] = lists.get(c).size();
            for (int id : lists.get(c)) index[o++] = id;
        }
        out.clear();
    }

    private static int clampi(int v, int n) {
        return v < 0 ? 0 : (v >= n ? n - 1 : v);
    }

    // ------------------------------------------------------------------ small vector helpers
    private static float sq(float x) { return x * x; }

    private static float dot(float[] a, float[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

    private static float[] cross(float[] a, float[] b) {
        return new float[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static void normalize(float[] v) {
        float l = (float) Math.sqrt(dot(v, v));
        if (l < 1e-6f) { v[0] = 0; v[1] = 1; v[2] = 0; return; }
        v[0] /= l; v[1] /= l; v[2] /= l;
    }

    private static float[] perpendicular(float[] d) {
        float[] a = Math.abs(d[1]) < 0.9f ? new float[]{0, 1, 0} : new float[]{1, 0, 0};
        float[] p = cross(d, a);
        normalize(p);
        return p;
    }
}
