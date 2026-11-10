package com.harlequinxd.eryndor;

/**
 * Poses the two first-person arms in camera space (x right, y up, -z forward) every frame:
 * where each arm's shoulder end sits, how it is turned, and how far the fingers curl.
 * The arm shapes themselves are drawn per pixel in scene.frag.
 */
final class ArmRig {

    /** Arm-local -> camera rotation, column-major mat3 x 2 (right arm, left arm). */
    final float[] rot = new float[18];
    /** Shoulder-end position in camera space, vec3 x 2. */
    final float[] pos = new float[6];
    /** Per-finger curl as curvature (radians per grid unit), right arm then left, thumb first. */
    final float[] fingerK = new float[10];
    /** Relaxed curl of each finger in radians (thumb, index, middle, ring, little). */
    static final float[] RELAXED = {0.30f, 0.50f, 0.62f, 0.74f, 0.86f};

    private static final float WRIST_FROM_ORIGIN = 0.47f;  // grid u 376 * 1.25 mm

    // pose: forearms rising from the bottom corners, backs of the hands toward you
    static float WRIST_X = 0.18f, WRIST_Y = -0.22f, WRIST_Z = -0.32f;
    static float AIM_IN = 0.35f, AIM_UP = 0.60f, ROLL = 0.25f;

    private float swayX, swayY, time;

    /**
     * @param dt         frame time
     * @param lookDx     how far the view turned this frame (radians, + = right)
     * @param lookDy     how far the view pitched this frame (radians, + = up)
     * @param walk       0..1 walking amount
     * @param bobPhase   the camera's walk-bob phase
     */
    void update(float dt, float lookDx, float lookDy, float walk, float bobPhase) {
        time += dt;
        // the arms lag a little behind fast turns, then settle
        swayX += (-lookDx * 0.9f - swayX) * Math.min(1f, dt * 8f);
        swayY += (-lookDy * 0.9f - swayY) * Math.min(1f, dt * 8f);
        swayX = clamp(swayX, -0.05f, 0.05f);
        swayY = clamp(swayY, -0.05f, 0.05f);
        float breathe = (float) Math.sin(time * 1.7f);
        float bobX = (float) Math.sin(bobPhase) * 0.012f * walk;
        float bobY = (float) Math.abs(Math.cos(bobPhase)) * 0.014f * walk;

        // relaxed hands: each finger curls a little more than the one before, breathing slightly,
        // a little tighter while walking; the two hands drift out of step
        for (int arm = 0; arm < 2; arm++) {
            float drift = (float) Math.sin(time * (arm == 0 ? 1.7f : 1.3f) + arm);
            for (int f = 0; f < 5; f++) {
                float ang = RELAXED[f] * (1f + 0.10f * drift + 0.30f * walk);
                fingerK[arm * 5 + f] = ang / ArmFingers.LENGTH[arm * 5 + f];
            }
        }

        for (int arm = 0; arm < 2; arm++) {
            float side = arm == 0 ? 1f : -1f;
            // wrist low in the corner, forearm pointing ahead, a little inward and up
            float wx = side * WRIST_X + swayX + side * bobX;
            float wy = WRIST_Y + swayY - bobY + 0.004f * breathe;
            float wz = WRIST_Z;
            float[] ex = norm(-side * AIM_IN, AIM_UP, -1f);
            // back of the hand faces up and a little outward (palms down, thumbs in)
            float roll = ROLL;
            float[] up = {side * (float) Math.sin(roll), (float) Math.cos(roll), 0f};
            float dot = up[0] * ex[0] + up[1] * ex[1] + up[2] * ex[2];
            float[] ey = norm(up[0] - ex[0] * dot, up[1] - ex[1] * dot, up[2] - ex[2] * dot);
            float[] ez = cross(ex, ey);
            int r = arm * 9;
            System.arraycopy(ex, 0, rot, r, 3);
            System.arraycopy(ey, 0, rot, r + 3, 3);
            System.arraycopy(ez, 0, rot, r + 6, 3);
            pos[arm * 3] = wx - ex[0] * WRIST_FROM_ORIGIN;
            pos[arm * 3 + 1] = wy - ex[1] * WRIST_FROM_ORIGIN;
            pos[arm * 3 + 2] = wz - ex[2] * WRIST_FROM_ORIGIN;
        }
    }

    private static float clamp(float v, float a, float b) {
        return Math.max(a, Math.min(b, v));
    }

    private static float[] norm(float x, float y, float z) {
        float l = (float) Math.sqrt(x * x + y * y + z * z);
        return new float[]{x / l, y / l, z / l};
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }
}
