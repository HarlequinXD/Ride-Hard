package com.harlequinxd.eryndor;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.GZIPInputStream;

/**
 * The player, a woman explorer, drawn per pixel as ONE whole voxel model rigged to a skeleton.
 * tools/build_woman.py sculpts and dresses her and bakes her, in her bind pose, into a single grid:
 * signed distance, colour + material, and per cell the two bones that move it and their weights.
 * Each frame this class poses the 16-bone skeleton (idle breathing, walking) and hands the shader
 * one bind-pose-to-world matrix per bone; the shader maps each sample point back into the bind pose
 * by inverse linear-blend skinning, so the one model bends smoothly at every joint.
 */
final class Woman {
    static final int BONES = 16;
    static final int HIPS = 0, SPINE = 1, CHEST = 2, HEAD = 3, L_ARM = 4, L_FORE = 5, L_HAND = 6,
            R_ARM = 7, R_FORE = 8, R_HAND = 9, L_THIGH = 10, L_SHIN = 11, L_FOOT = 12,
            R_THIGH = 13, R_SHIN = 14, R_FOOT = 15;

    final String[] name = new String[BONES];
    final int[] parent = new int[BONES];
    final double[][] start = new double[BONES][], end = new double[BONES][];
    int nx, ny, nz;
    float cell, minX, minY, minZ;
    ByteBuffer dist, colour, skin;

    /** Per bone, 3 rows: bind pose -> world (shader frame). */
    final float[] skinM = new float[BONES * 12];
    /** Posed bones (shader frame), start and end, for the shader's first guess. */
    final float[] boneA = new float[BONES * 4], boneB = new float[BONES * 4];
    final float[] sphere = new float[4];

    void load(InputStream rig, InputStream data) throws java.io.IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(rig, "UTF-8"));
        String line;
        int i = 0;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] t = line.split("\\s+");
            name[i] = t[0];
            parent[i] = Integer.parseInt(t[1]);
            start[i] = new double[]{Double.parseDouble(t[2]), Double.parseDouble(t[3]), Double.parseDouble(t[4])};
            end[i] = new double[]{Double.parseDouble(t[5]), Double.parseDouble(t[6]), Double.parseDouble(t[7])};
            i++;
        }
        DataInputStream in = new DataInputStream(new GZIPInputStream(data, 1 << 16));
        byte[] h = new byte[28];
        in.readFully(h);
        ByteBuffer hb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        nx = hb.getInt();
        ny = hb.getInt();
        nz = hb.getInt();
        cell = hb.getFloat();
        minX = hb.getFloat();
        minY = hb.getFloat();
        minZ = hb.getFloat();
        int n = nx * ny * nz;
        dist = read(in, n);
        colour = read(in, n * 4);
        skin = read(in, n * 4);
    }

    private static ByteBuffer read(DataInputStream in, int n) throws java.io.IOException {
        byte[] b = new byte[n];
        in.readFully(b);
        ByteBuffer bb = ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder());
        bb.put(b).position(0);
        return bb;
    }

    // ------------------------------------------------------------------ posing
    private final double[][] delta = new double[BONES][];

    private static double[] rx(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[]{1, 0, 0, 0, c, -s, 0, s, c};
    }

    private static double[] ry(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[]{c, 0, s, 0, 1, 0, -s, 0, c};
    }

    private static double[] rz(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[]{c, -s, 0, s, c, 0, 0, 0, 1};
    }

    private static double[] m3(double[] a, double[] b) {
        double[] r = new double[9];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                r[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
        return r;
    }

    private static double[] m4(double[] a, double[] b) {
        double[] r = new double[16];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++)
                r[i * 4 + j] = a[i * 4] * b[j] + a[i * 4 + 1] * b[4 + j] + a[i * 4 + 2] * b[8 + j] + a[i * 4 + 3] * b[12 + j];
        return r;
    }

    private static double[] tr(double x, double y, double z) {
        return new double[]{1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z, 0, 0, 0, 1};
    }

    private static double[] rot4(double[] r) {
        return new double[]{r[0], r[1], r[2], 0, r[3], r[4], r[5], 0, r[6], r[7], r[8], 0, 0, 0, 0, 1};
    }

    private static float smooth(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    /**
     * Pose her: feet at (x, feetY, z), facing heading (as the camera's yaw), walking 0..1 at stride
     * phase. offX / offZ: the shader frame's shift.
     */
    void pose(double x, double feetY, double z, float heading, float walk, float phase, float time, double offX, double offZ) {
        float s = (float) Math.sin(phase), c = (float) Math.cos(phase);
        float breathe = (float) Math.sin(time * 1.6f);
        float idle = (float) Math.sin(time * 0.7f);
        for (int b = 0; b < BONES; b++) delta[b] = rx(0);
        // legs: thighs swing, knees bend on the forward swing and as the foot passes under
        float th = 0.48f * walk;
        delta[L_THIGH] = rx(-th * s);
        delta[R_THIGH] = rx(th * s);
        delta[L_SHIN] = rx(walk * (0.12f + 0.70f * Math.max(0f, -(float) Math.sin(phase - 0.9f))));
        delta[R_SHIN] = rx(walk * (0.12f + 0.70f * Math.max(0f, (float) Math.sin(phase - 0.9f))));
        delta[L_FOOT] = rx(walk * 0.22f * c);
        delta[R_FOOT] = rx(-walk * 0.22f * c);
        // body: hips sway, shoulders turn against them, breathing, a slow idle shift of weight
        delta[HIPS] = m3(ry(0.10f * walk * s), rz(0.025f * idle * (1f - walk)));
        delta[SPINE] = m3(rx(0.05f * walk + 0.012f * breathe), ry(-0.08f * walk * s));
        delta[CHEST] = m3(rx(0.015f * breathe), ry(-0.07f * walk * s));
        delta[HEAD] = m3(ry(0.06f * walk * s + 0.05f * idle * (1f - walk)), rx(-0.03f * walk));
        // arms: from the sculpted A-pose down by her sides, swinging against the legs
        float down = 0.62f;
        float as = 0.40f * walk * s;
        delta[L_ARM] = m3(rx(as), rz(-down - 0.02f * breathe));
        delta[R_ARM] = m3(rx(-as), rz(down + 0.02f * breathe));
        delta[L_FORE] = ry(-(0.20f + 0.30f * walk * Math.max(0f, -s)));
        delta[R_FORE] = ry(0.20f + 0.30f * walk * Math.max(0f, s));
        delta[L_HAND] = rz(-0.15f);
        delta[R_HAND] = rz(0.15f);

        double hy = Math.PI - heading;
        double ch = Math.cos(hy), sh = Math.sin(hy);
        double bob = 0.022 * walk * Math.abs(c);
        double[] place = {ch, 0, sh, x - offX, 0, 1, 0, feetY - bob, -sh, 0, ch, z - offZ, 0, 0, 0, 1};
        double[][] g = new double[BONES][];
        double minx = 1e9, miny = 1e9, minz = 1e9, maxx = -1e9, maxy = -1e9, maxz = -1e9;
        for (int b = 0; b < BONES; b++) {
            // bone frames: no rotation in the bind pose, so the local transform is the joint offset
            double[] local = parent[b] < 0 ? tr(start[b][0], start[b][1], start[b][2])
                    : tr(start[b][0] - start[parent[b]][0], start[b][1] - start[parent[b]][1], start[b][2] - start[parent[b]][2]);
            g[b] = m4(parent[b] < 0 ? m4(place, local) : m4(g[parent[b]], local), rot4(delta[b]));
            // skinning matrix: bind pose -> world = g * inverse(bind)
            double[] S = m4(g[b], tr(-start[b][0], -start[b][1], -start[b][2]));
            for (int r = 0; r < 3; r++)
                for (int k = 0; k < 4; k++) skinM[b * 12 + r * 4 + k] = (float) S[r * 4 + k];
            double ex = end[b][0] - start[b][0], ey = end[b][1] - start[b][1], ez = end[b][2] - start[b][2];
            double ax = g[b][3], ay = g[b][7], az = g[b][11];
            double bx = ax + g[b][0] * ex + g[b][1] * ey + g[b][2] * ez;
            double by = ay + g[b][4] * ex + g[b][5] * ey + g[b][6] * ez;
            double bz = az + g[b][8] * ex + g[b][9] * ey + g[b][10] * ez;
            boneA[b * 4] = (float) ax; boneA[b * 4 + 1] = (float) ay; boneA[b * 4 + 2] = (float) az;
            boneB[b * 4] = (float) bx; boneB[b * 4 + 1] = (float) by; boneB[b * 4 + 2] = (float) bz;
            minx = Math.min(minx, Math.min(ax, bx)); maxx = Math.max(maxx, Math.max(ax, bx));
            miny = Math.min(miny, Math.min(ay, by)); maxy = Math.max(maxy, Math.max(ay, by));
            minz = Math.min(minz, Math.min(az, bz)); maxz = Math.max(maxz, Math.max(az, bz));
        }
        sphere[0] = (float) ((minx + maxx) / 2);
        sphere[1] = (float) ((miny + maxy) / 2);
        sphere[2] = (float) ((minz + maxz) / 2);
        double rx_ = (maxx - minx) / 2, ry_ = (maxy - miny) / 2, rz_ = (maxz - minz) / 2;
        sphere[3] = (float) Math.sqrt(rx_ * rx_ + ry_ * ry_ + rz_ * rz_) + 0.22f;   // flesh round the bones
    }
}
