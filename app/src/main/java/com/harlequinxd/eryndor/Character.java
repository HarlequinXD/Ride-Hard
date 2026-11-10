package com.harlequinxd.eryndor;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.GZIPInputStream;

/**
 * The third-person character, drawn per pixel: no polygons. tools/build_character.py turned the
 * rigged FBX model into 16 rigid parts that follow the bones (hips, spine, chest, head with hair,
 * upper arms, forearms, hands, thighs, shins, feet), each a 1 cm grid of signed distances with the
 * texture coordinate of the nearest surface point, all packed into one 3D atlas. Each frame this
 * class poses the skeleton (walking, idling, swinging the pickaxe) and hands the shader one matrix
 * per part, from the world into that part's grid; the shader traces the union of the parts and
 * paints them with the model's own colour, normal and height maps.
 */
final class Character {
    static final int PARTS = 16;
    static final float VOX = 0.01f;                 // metres per cell
    // part indices (order of rig.txt)
    static final int HIPS = 0, SPINE = 1, CHEST = 2, HEAD = 3, L_ARM = 4, L_FORE = 5, L_HAND = 6,
            R_ARM = 7, R_FORE = 8, R_HAND = 9, L_THIGH = 10, L_SHIN = 11, L_FOOT = 12,
            R_THIGH = 13, R_SHIN = 14, R_FOOT = 15;

    final String[] name = new String[PARTS];
    final int[] parent = new int[PARTS];
    final double[][] bind = new double[PARTS][];    // bind-pose global, row-major 4x4, metres
    final int[] x0 = new int[PARTS], nx = new int[PARTS], ny = new int[PARTS], nz = new int[PARTS];
    final double[][] lo = new double[PARTS][];
    int ax, ay, az;
    ByteBuffer dist, uv;

    /** Posed part matrices, world -> part local (metres), row-major 4x4. */
    final double[][] world = new double[PARTS][16];
    /** For the shader: 3 rows per part, world (shader frame) -> atlas cell coordinates. */
    final float[] partM = new float[PARTS * 12];
    /** For the shader: per part atlas x0, nx, ny, nz. */
    final float[] partBox = new float[PARTS * 4];
    /** Bounding sphere of the posed character (shader frame): x, y, z, radius. */
    final float[] sphere = new float[4];

    void load(InputStream rig, InputStream parts) throws java.io.IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(rig, "UTF-8"));
        String line;
        int i = 0;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("atlas")) continue;
            String[] t = line.split("\\s+");
            name[i] = t[0];
            parent[i] = Integer.parseInt(t[1]);
            double[] m = new double[16];
            for (int k = 0; k < 16; k++) m[k] = Double.parseDouble(t[2 + k]);
            bind[i] = m;
            x0[i] = Integer.parseInt(t[18]);
            nx[i] = Integer.parseInt(t[19]);
            ny[i] = Integer.parseInt(t[20]);
            nz[i] = Integer.parseInt(t[21]);
            lo[i] = new double[]{Double.parseDouble(t[22]), Double.parseDouble(t[23]), Double.parseDouble(t[24])};
            i++;
        }
        DataInputStream in = new DataInputStream(new GZIPInputStream(parts, 1 << 16));
        byte[] h = new byte[12];
        in.readFully(h);
        ByteBuffer hb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        ax = hb.getInt();
        ay = hb.getInt();
        az = hb.getInt();
        int n = ax * ay * az;
        byte[] d = new byte[n], u = new byte[n * 4];
        in.readFully(d);
        in.readFully(u);
        dist = ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder());
        dist.put(d).position(0);
        uv = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder());
        uv.put(u).position(0);
        for (int p = 0; p < PARTS; p++) {
            partBox[p * 4] = x0[p];
            partBox[p * 4 + 1] = nx[p];
            partBox[p * 4 + 2] = ny[p];
            partBox[p * 4 + 3] = nz[p];
        }
    }

    // ------------------------------------------------------------------ posing
    /** Per-part rotation in the bind pose's own (model) axes, about the bone's joint. */
    private final double[][] delta = new double[PARTS][];

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

    /** Inverse of a rigid (rotation + translation) 4x4. */
    private static double[] inv(double[] m) {
        double[] r = new double[16];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++) r[i * 4 + j] = m[j * 4 + i];
        for (int i = 0; i < 3; i++)
            r[i * 4 + 3] = -(r[i * 4] * m[3] + r[i * 4 + 1] * m[7] + r[i * 4 + 2] * m[11]);
        r[15] = 1;
        return r;
    }

    private static double[] rot4(double[] r3) {
        return new double[]{r3[0], r3[1], r3[2], 0, r3[3], r3[4], r3[5], 0, r3[6], r3[7], r3[8], 0, 0, 0, 0, 1};
    }

    /**
     * Pose the character.
     * @param x z feet position (world), feetY ground height under them, heading facing (radians, as yaw)
     * @param walk   0..1 how fast she walks; phase the stride phase (radians)
     * @param swing  pickaxe swing 0..1 (or < 0: not swinging); tool whether she holds the pickaxe
     * @param offX offZ the shader frame's shift
     */
    void pose(double x, double feetY, double z, float heading, float walk, float phase, float swing,
              boolean tool, float time, double offX, double offZ) {
        float s = (float) Math.sin(phase), c = (float) Math.cos(phase);
        float breathe = (float) Math.sin(time * 1.6f);
        for (int p = 0; p < PARTS; p++) delta[p] = rx(0);
        // legs: thighs swing, knees bend on the forward swing and as the foot passes under
        float th = 0.48f * walk;
        delta[L_THIGH] = rx(-th * s);
        delta[R_THIGH] = rx(th * s);
        delta[L_SHIN] = rx(walk * (0.15f + 0.65f * Math.max(0f, -(float) Math.sin(phase - 0.9f))));
        delta[R_SHIN] = rx(walk * (0.15f + 0.65f * Math.max(0f, (float) Math.sin(phase - 0.9f))));
        delta[L_FOOT] = rx(walk * 0.25f * c);
        delta[R_FOOT] = rx(-walk * 0.25f * c);
        // body: a little lean into the walk, the shoulders turn against the hips, breathing
        delta[HIPS] = ry(0.10f * walk * s);
        delta[SPINE] = m3(rx(0.06f * walk + 0.01f * breathe), ry(-0.08f * walk * s));
        delta[CHEST] = m3(rx(0.015f * breathe), ry(-0.07f * walk * s));
        delta[HEAD] = ry(0.06f * walk * s);
        // arms down by her sides (the model is in a T-pose), swinging against the legs
        float down = 1.28f;
        float as = 0.38f * walk * s;
        delta[L_ARM] = m3(rx(as), rz(-down));
        delta[R_ARM] = m3(rx(-as), rz(down));
        delta[L_FORE] = ry(-(0.25f + 0.25f * walk * Math.max(0f, -s)));
        delta[R_FORE] = ry(0.25f + 0.25f * walk * Math.max(0f, s));
        if (tool) {                                    // pickaxe carried in the right hand, forearm up
            delta[R_FORE] = ry(0.95f);
            delta[R_ARM] = m3(rx(-0.25f - as * 0.3f), rz(down));
        }
        if (swing >= 0f) {                             // two-handed overhead swing into the ground
            float raise;
            if (swing < 0.30f) raise = smooth(swing / 0.30f) * 2.7f;
            else if (swing < 0.50f) { float k = (swing - 0.30f) / 0.20f; raise = 2.7f - 2.2f * k * k; }
            else if (swing < 0.62f) raise = 0.5f;
            else raise = 0.5f * (1f - smooth((swing - 0.62f) / 0.38f));
            float bend = swing < 0.5f ? 0.9f * smooth(Math.min(1f, swing / 0.25f)) * (1f - smooth(Math.max(0f, (swing - 0.32f) / 0.18f))) : 0f;
            delta[R_ARM] = m3(rx(-raise), m3(rz(down * 0.85f), rx(0)));
            delta[L_ARM] = m3(rx(-raise * 0.95f), rz(-down * 0.75f));
            delta[R_FORE] = ry(0.35f + bend * 1.2f);
            delta[L_FORE] = ry(-0.35f - bend * 1.2f);
            float lean = raise > 1.5f ? 0f : (1.5f - raise) * 0.22f;
            delta[SPINE] = m3(rx(-0.10f * smooth(swing / 0.3f) + lean), delta[SPINE]);
        }

        // place: model faces +Z; turn it to the heading and stand it on the ground
        double hy = Math.PI - heading;
        double ch = Math.cos(hy), sh = Math.sin(hy);
        double bob = 0.025 * walk * Math.abs(c);
        double[] place = {ch, 0, sh, x - offX, 0, 1, 0, feetY - bob, -sh, 0, ch, z - offZ, 0, 0, 0, 1};
        double[][] g = new double[PARTS][];
        double minX = 1e9, minY = 1e9, minZ = 1e9, maxX = -1e9, maxY = -1e9, maxZ = -1e9;
        for (int p = 0; p < PARTS; p++) {
            // D = Rb^-1 * delta * Rb (a turn about the joint, given in the bind pose's axes)
            double[] rb = {bind[p][0], bind[p][1], bind[p][2], bind[p][4], bind[p][5], bind[p][6], bind[p][8], bind[p][9], bind[p][10]};
            double[] rbT = {rb[0], rb[3], rb[6], rb[1], rb[4], rb[7], rb[2], rb[5], rb[8]};
            double[] D = rot4(m3(rbT, m3(delta[p], rb)));
            double[] local = parent[p] < 0 ? bind[p] : m4(inv(bind[parent[p]]), bind[p]);
            g[p] = m4(parent[p] < 0 ? m4(place, local) : m4(g[parent[p]], local), D);
            double[] w = inv(g[p]);
            // world -> atlas cell: (local - lo) / VOX + (x0, 0, 0) + 0.5
            for (int r = 0; r < 3; r++) {
                for (int k = 0; k < 4; k++) partM[p * 12 + r * 4 + k] = (float) (w[r * 4 + k] / VOX);
                partM[p * 12 + r * 4 + 3] += (float) (-lo[p][r] / VOX + (r == 0 ? x0[p] : 0) + 0.5);
            }
            world[p] = g[p];
            minX = Math.min(minX, g[p][3]); maxX = Math.max(maxX, g[p][3]);
            minY = Math.min(minY, g[p][7]); maxY = Math.max(maxY, g[p][7]);
            minZ = Math.min(minZ, g[p][11]); maxZ = Math.max(maxZ, g[p][11]);
        }
        sphere[0] = (float) ((minX + maxX) / 2);
        sphere[1] = (float) ((minY + maxY) / 2);
        sphere[2] = (float) ((minZ + maxZ) / 2);
        double ex = (maxX - minX) / 2, ey = (maxY - minY) / 2, ez = (maxZ - minZ) / 2;
        sphere[3] = (float) Math.sqrt(ex * ex + ey * ey + ez * ez) + 0.45f;   // joints are inside the parts
    }

    private static float smooth(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    /**
     * The pickaxe in the right hand: world (shader frame) -> tool grid (1.25 mm), row-major 3 x 4,
     * tool axes x = head, z = haft. Grip centre a little past the hand bone, haft along the fist.
     */
    void toolMatrix(float[] out) {
        double[] h = world[R_HAND];
        // in the hand bone's frame (y runs along the hand toward the fingers)
        double[] grip = {0.0, 0.085, 0.015};
        double[] haft = {0.0, 0.0, -1.0};          // out of the thumb side of the fist
        double[] head = {1.0, 0.0, 0.0};
        double[] gw = mulP(h, grip), hw = mulD(h, haft), ew = mulD(h, head);
        double[] fw = {hw[1] * ew[2] - hw[2] * ew[1], hw[2] * ew[0] - hw[0] * ew[2], hw[0] * ew[1] - hw[1] * ew[0]};
        double[][] ax = {ew, fw, hw};
        for (int r = 0; r < 3; r++) {
            double sc = 1.0 / 0.00125;
            out[r * 4] = (float) (ax[r][0] * sc);
            out[r * 4 + 1] = (float) (ax[r][1] * sc);
            out[r * 4 + 2] = (float) (ax[r][2] * sc);
            out[r * 4 + 3] = (float) (-(ax[r][0] * gw[0] + ax[r][1] * gw[1] + ax[r][2] * gw[2]) * sc);
        }
    }

    /** Where the pickaxe head is (world, shader frame removed again by the caller). */
    double[] handPos() {
        double[] h = world[R_HAND];
        return new double[]{h[3], h[7], h[11]};
    }

    private static double[] mulP(double[] m, double[] p) {
        return new double[]{m[0] * p[0] + m[1] * p[1] + m[2] * p[2] + m[3], m[4] * p[0] + m[5] * p[1] + m[6] * p[2] + m[7],
                m[8] * p[0] + m[9] * p[1] + m[10] * p[2] + m[11]};
    }

    private static double[] mulD(double[] m, double[] d) {
        return new double[]{m[0] * d[0] + m[1] * d[1] + m[2] * d[2], m[4] * d[0] + m[5] * d[1] + m[6] * d[2],
                m[8] * d[0] + m[9] * d[1] + m[10] * d[2]};
    }
}
