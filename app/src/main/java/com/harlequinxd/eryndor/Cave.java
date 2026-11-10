package com.harlequinxd.eryndor;

/**
 * The cave in Hollow Hill, east of the village: a winding tunnel driven into the hillside that
 * opens into a domed chamber. Pure geometry, shared by the heightmap and the ground painter.
 */
final class Cave {
    private Cave() {}

    /** Tunnel centreline (metres), mouth first. */
    static final float[][] PATH = {
            {206f, 176f}, {218f, 177f}, {229f, 174f}, {239f, 179f}, {245f, 188f}, {250f, 197f},
    };
    static final float CHAMBER_X = 252f, CHAMBER_Z = 201f, CHAMBER_R = 9.5f;
    static final float HILL_X = 238f, HILL_Z = 186f;

    /** Results of {@link #query}: distance to the tunnel axis, distance along it from the mouth. */
    static float dist, along;

    static void query(float wx, float wz) {
        float best = 1e9f, bestS = 0f, s0 = 0f;
        for (int i = 0; i < PATH.length - 1; i++) {
            float ax = PATH[i][0], az = PATH[i][1], bx = PATH[i + 1][0], bz = PATH[i + 1][1];
            float dx = bx - ax, dz = bz - az, len = (float) Math.sqrt(dx * dx + dz * dz);
            float t = ((wx - ax) * dx + (wz - az) * dz) / (len * len);
            t = Math.max(0f, Math.min(1f, t));
            float px = ax + dx * t - wx, pz = az + dz * t - wz;
            float d = (float) Math.sqrt(px * px + pz * pz);
            if (d < best) { best = d; bestS = s0 + t * len; }
            s0 += len;
        }
        dist = best;
        along = bestS;
    }

    static float pathLength() {
        float s = 0f;
        for (int i = 0; i < PATH.length - 1; i++) {
            float dx = PATH[i + 1][0] - PATH[i][0], dz = PATH[i + 1][1] - PATH[i][1];
            s += (float) Math.sqrt(dx * dx + dz * dz);
        }
        return s;
    }

    /** Tunnel half-width with a little irregularity. */
    static float halfWidth(float along) {
        return 2.1f + 0.35f * (float) Math.sin(along * 0.45f) + 0.2f * (float) Math.sin(along * 1.3f + 1f);
    }

    static float chamberDist(float wx, float wz) {
        float dx = wx - CHAMBER_X, dz = wz - CHAMBER_Z;
        return (float) Math.sqrt(dx * dx + dz * dz);
    }
}
