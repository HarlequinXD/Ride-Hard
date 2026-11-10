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
    /** Per-finger knuckle pivot (u, z) and bend direction (u, z) as drawn: the right hand's fingers
     *  bend around one shared grip line so they close on the sword's handle. */
    final float[] fingers = ArmFingers.PIVOT_DIR.clone();
    private final float[] gripFingers = ArmFingers.PIVOT_DIR.clone();
    /** The pickaxe in the right arm's grid space: grip centre, haft direction, head direction.
     *  (Named for the sword it replaced; the shapes live in scene.frag.) */
    final float[] swordO = new float[3], swordB = new float[3], swordE = new float[3];
    /** Pickaxe in hand. */
    volatile boolean sword = true;

    // ---- the swing: wind up, strike, recover
    static final float SWING_TIME = 0.62f, IMPACT_AT = 0.50f;
    private float swingT = -1f;
    private boolean impactDue;
    /** Recoil after the head bites into the ground (0..1), set by the renderer on a hit. */
    private float recoil;

    /** Start a swing if the pickaxe is in hand and not already swinging. */
    boolean startSwing() {
        if (!sword || swingT >= 0f) return false;
        swingT = 0f;
        impactDue = true;
        return true;
    }

    /** Where the swing is, 0..1, or -1 when not swinging. */
    float swingPhase() {
        return swingT < 0f ? -1f : Math.min(1f, swingT / SWING_TIME);
    }

    boolean swinging() {
        return swingT >= 0f;
    }

    /** True once per swing, the frame the head comes down. */
    boolean takeImpact() {
        if (impactDue && swingT >= SWING_TIME * IMPACT_AT) {
            impactDue = false;
            return true;
        }
        return false;
    }

    /** The head struck something solid: a short jolt back up. */
    void bite() {
        recoil = 1f;
    }

    /** Swing offsets: forearm pitch, wrist height, wrist depth (camera space). */
    private final float[] swingOff = new float[3];

    private void swingPose(float dt) {
        swingOff[0] = swingOff[1] = swingOff[2] = 0f;
        recoil = Math.max(0f, recoil - dt * 5f);
        if (swingT < 0f) return;
        swingT += dt;
        float p = swingT / SWING_TIME;
        if (p >= 1f) { swingT = -1f; return; }
        float up, y, z;
        if (p < 0.30f) {                                   // wind up: raise and draw back
            float k = smooth(p / 0.30f);
            up = 0.45f * k; y = 0.07f * k; z = 0.05f * k;
        } else if (p < IMPACT_AT) {                        // strike: fast and accelerating
            float k = (p - 0.30f) / (IMPACT_AT - 0.30f);
            k = k * k;
            up = 0.45f - 1.60f * k; y = 0.07f - 0.15f * k; z = 0.05f - 0.10f * k;
        } else if (p < 0.62f) {                            // the blow lands; hold
            up = -1.15f; y = -0.08f; z = -0.05f;
        } else {                                           // bring it back up
            float k = smooth((p - 0.62f) / 0.38f);
            up = -1.15f * (1f - k); y = -0.08f * (1f - k); z = -0.05f * (1f - k);
        }
        float r = recoil * recoil;
        swingOff[0] = up + 0.35f * r;
        swingOff[1] = y + 0.03f * r;
        swingOff[2] = z;
    }

    private static float smooth(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    /** Radius (grid units) the fingers wrap at: grip radius plus half a finger's thickness. */
    static final float GRIP_WRAP = 24f;
    static final float THUMB_CURL = 1.05f;
    /** Relaxed curl of each finger in radians (thumb, index, middle, ring, little). */
    static final float[] RELAXED = {0.30f, 0.50f, 0.62f, 0.74f, 0.86f};

    private static final float WRIST_FROM_ORIGIN = 0.47f;  // grid u 376 * 1.25 mm

    // pose: forearms rising from the bottom corners, backs of the hands toward you
    static float WRIST_X = 0.18f, WRIST_Y = -0.22f, WRIST_Z = -0.32f;
    static float AIM_IN = 0.35f, AIM_UP = 0.60f, ROLL = 0.25f;
    // sword arm: forearm level and pointing ahead, wrist rolled thumb-up so the blade stands up
    static float SW_X = 0.20f, SW_Y = -0.25f, SW_Z = -0.38f, SW_IN = 0.40f, SW_UP = -0.25f, SW_ROLL = 1.20f;
    /** The grip is round, so the sword can turn in the fist: this shows the blade's flat, not its edge. */
    static float SW_TWIST = 1.0f;

    private float swayX, swayY, time;

    ArmRig() {
        fitGrip();
    }

    /**
     * Fit a line through the right hand's four finger knuckles (u, z): the sword's grip lies along it,
     * GRIP_WRAP below the hand's mid-plane, and every finger bends at right angles to it.
     */
    private void fitGrip() {
        float mz = 0, mu = 0;
        for (int f = 1; f < 5; f++) {
            mu += ArmFingers.PIVOT_DIR[f * 4];
            mz += ArmFingers.PIVOT_DIR[f * 4 + 1];
        }
        mu /= 4;
        mz /= 4;
        float szz = 0, szu = 0;
        for (int f = 1; f < 5; f++) {
            float dz = ArmFingers.PIVOT_DIR[f * 4 + 1] - mz, du = ArmFingers.PIVOT_DIR[f * 4] - mu;
            szz += dz * dz;
            szu += dz * du;
        }
        float slope = szu / szz;                          // du per dz along the grip line
        float gl = (float) Math.sqrt(1 + slope * slope);
        float gu = slope / gl, gz = 1f / gl;              // grip line direction (u, z), toward the little finger
        float nu = gz, nz = -gu;                          // across it, toward the fingertips
        if (nu < 0) { nu = -nu; nz = -nz; }
        for (int f = 1; f < 5; f++) {
            float pu = ArmFingers.PIVOT_DIR[f * 4], pz = ArmFingers.PIVOT_DIR[f * 4 + 1];
            float off = (pu - mu) * nu + (pz - mz) * nz;  // knuckle's distance off the line
            gripFingers[f * 4] = pu - nu * off;
            gripFingers[f * 4 + 1] = pz - nz * off;
            gripFingers[f * 4 + 2] = nu;
            gripFingers[f * 4 + 3] = nz;
        }
        // grip centre between the index and little fingers, under the hand; blade out of the thumb side
        float zi = ArmFingers.PIVOT_DIR[4 + 1], zl = ArmFingers.PIVOT_DIR[16 + 1];
        swordO[0] = mu + slope * ((zi + zl) * 0.5f - mz);
        swordO[1] = -GRIP_WRAP;
        swordO[2] = (zi + zl) * 0.5f;
        swordB[0] = -gu; swordB[1] = 0; swordB[2] = -gz;  // toward the thumb (-z on the right hand)
        // edge faces where the fingers point, turned about the blade by SW_TWIST (F = B x E = -y)
        float c = (float) Math.cos(SW_TWIST), sn = (float) Math.sin(SW_TWIST);
        swordE[0] = nu * c; swordE[1] = -sn; swordE[2] = nz * c;
    }

    /**
     * @param dt         frame time
     * @param lookDx     how far the view turned this frame (radians, + = right)
     * @param lookDy     how far the view pitched this frame (radians, + = up)
     * @param walk       0..1 walking amount
     * @param bobPhase   the camera's walk-bob phase
     */
    void update(float dt, float lookDx, float lookDy, float walk, float bobPhase) {
        time += dt;
        swingPose(dt);
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
        System.arraycopy(sword ? gripFingers : ArmFingers.PIVOT_DIR, 0, fingers, 0, 20);
        if (sword) {                       // right hand closed around the grip
            for (int f = 1; f < 5; f++) fingerK[f] = 1f / GRIP_WRAP;
            fingerK[0] = THUMB_CURL / ArmFingers.LENGTH[0];
        }

        for (int arm = 0; arm < 2; arm++) {
            float side = arm == 0 ? 1f : -1f;
            // wrist low in the corner, forearm pointing ahead, a little inward and up
            boolean holds = sword && arm == 0;
            float wx = side * (holds ? SW_X : WRIST_X) + swayX + side * bobX;
            float wy = (holds ? SW_Y + swingOff[1] : WRIST_Y) + swayY - bobY + 0.004f * breathe;
            float wz = holds ? SW_Z + swingOff[2] : WRIST_Z;
            float[] ex = holds ? norm(-SW_IN, SW_UP + swingOff[0], -1f) : norm(-side * AIM_IN, AIM_UP, -1f);
            // back of the hand faces up and a little outward (palms down, thumbs in)
            float roll = holds ? SW_ROLL : ROLL;
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
