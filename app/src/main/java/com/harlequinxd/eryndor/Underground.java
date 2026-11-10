package com.harlequinxd.eryndor;

import java.util.List;

/**
 * The caves the game starts in, deep under the meadow south of the village, and the tunnels that
 * climb from them to the surface. (Filled in by the underground-start work; empty for now.)
 */
final class Underground {
    private Underground() { }

    /** Add the network's tunnels to the hand-made list. */
    static void addTunnels(List<Voxels.Tunnel> list) {
    }

    /** Signed distance to the air of the start caves' rooms (large when there are none). */
    static float roomAir(float x, float y, float z) {
        return 1e9f;
    }

    /** Whether any room reaches into the box. */
    static boolean near(float x0, float z0, float x1, float z1) {
        return false;
    }
}
