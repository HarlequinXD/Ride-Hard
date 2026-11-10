package com.harlequinxd.eryndor;

/**
 * Bakes tree shadows into a copy of the ground colours from the actual branches and leaves of
 * each placed tree (cast to the south-east, matching the ground's baked light from the north-west).
 * Kept separate from the cached ground so edited species re-shadow without repainting the world.
 */
final class TreeShadows {
    private TreeShadows() {}

    /**
     * Shadow one painted chunk (packed ARGB, N x N texels whose top-left is global texel gx0, gy0)
     * from every tree near enough to cast onto it.
     */
    static void applyChunk(int[] ground, int gx0, int gy0, int N, TreeLibrary lib) {
        final float PPM = WorldTextures.PPM, CELL = WorldTextures.TREE_CELL;
        float[] dark = new float[N * N];
        float wx0 = gx0 / PPM, wz0 = gy0 / PPM, wx1 = (gx0 + N) / PPM, wz1 = (gy0 + N) / PPM;
        int c0x = (int) Math.floor((wx0 - 26f) / CELL), c1x = (int) Math.floor((wx1 + 26f) / CELL);
        int c0z = (int) Math.floor((wz0 - 26f) / CELL), c1z = (int) Math.floor((wz1 + 26f) / CELL);
        boolean any = false;
        for (int cz = c0z; cz <= c1z; cz++)
            for (int cx = c0x; cx <= c1x; cx++) {
                byte[] trees = WorldTextures.treeBytes(cx, cz);
                int a = trees[3] & 255;
                if (a < 128) continue;
                int bv = trees[2] & 255, sp = Math.min(9, bv / 25);
                float yaw = (bv - sp * 25) / 25f * (float) (Math.PI * 2);
                float sz = 0.85f + (a / 255f - 0.5f) * 0.7f;                 // same as the shader
                TreeModel m = lib.models[sp];
                float half = halfWidth(m);
                sz = Math.min(sz, TreeLibrary.MAX_HALF_WIDTH / Math.max(0.1f, half));
                float wx = (cx + 0.5f) * CELL + ((trees[0] & 255) / 255f - 0.5f) * 3f;
                float wz = (cz + 0.5f) * CELL + ((trees[1] & 255) / 255f - 0.5f) * 3f;
                float c = (float) Math.cos(yaw), s = (float) Math.sin(yaw);
                for (int i = 0; i < m.primCount; i++) {
                    float[] p = m.prims;
                    int b = i * 12;
                    boolean leaf = p[b + 8] > 0.5f;
                    int samples = leaf ? 1 : Math.max(1, (int) (dist(p, b) * sz * PPM));
                    for (int k = 0; k < samples; k++) {
                        float t = samples == 1 ? 0.5f : k / (float) (samples - 1);
                        float lx = leaf ? p[b] : p[b] + (p[b + 4] - p[b]) * t;
                        float ly = leaf ? p[b + 1] : p[b + 1] + (p[b + 5] - p[b + 1]) * t;
                        float lz = leaf ? p[b + 2] : p[b + 2] + (p[b + 6] - p[b + 2]) * t;
                        float r = leaf ? p[b + 3] * 0.6f : p[b + 3] + (p[b + 7] - p[b + 3]) * t;
                        // local -> world (undo yaw, scale), then cast along the light
                        float x = wx + sz * (lx * c + lz * s), z = wz + sz * (-lx * s + lz * c), y = ly * sz;
                        x += y * 0.35f * 0.75f;
                        z += y * 0.35f * 0.6f;
                        any |= splat(dark, x * PPM - gx0, z * PPM - gy0, Math.max(0.6f, r * sz * PPM), leaf ? 0.10f : 0.35f, N);
                    }
                }
            }
        if (!any) return;
        for (int i = 0; i < ground.length; i++) {
            if (dark[i] <= 0f) continue;
            float k = 1f - Math.min(0.55f, dark[i]);
            int v = ground[i];
            int r = Math.round(((v >> 16) & 255) * k), g = Math.round(((v >> 8) & 255) * k), b = Math.round((v & 255) * k);
            // alpha: 0 = water; land stores how lit it is (0.5..1), so textured ground (the grass
            // photo, which ignores the colour) still gets the tree shadows
            int a = (v >>> 24) == 0 ? 0 : Math.round(255f * (0.5f + 0.5f * k));
            ground[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
    }

    static float halfWidth(TreeModel m) {
        return Math.max(Math.max(-m.minX, m.maxX), Math.max(-m.minZ, m.maxZ));
    }

    private static float dist(float[] p, int b) {
        float dx = p[b + 4] - p[b], dy = p[b + 5] - p[b + 1], dz = p[b + 6] - p[b + 2];
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static boolean splat(float[] dark, float px, float pz, float rad, float amount, int S) {
        int x0 = Math.max(0, (int) Math.floor(px - rad)), x1 = Math.min(S - 1, (int) Math.ceil(px + rad));
        int z0 = Math.max(0, (int) Math.floor(pz - rad)), z1 = Math.min(S - 1, (int) Math.ceil(pz + rad));
        if (x0 > x1 || z0 > z1) return false;
        for (int z = z0; z <= z1; z++)
            for (int x = x0; x <= x1; x++) {
                float dx = x + 0.5f - px, dz = z + 0.5f - pz;
                float d = (float) Math.sqrt(dx * dx + dz * dz);
                if (d > rad + 0.5f) continue;
                float w = Math.min(1f, rad + 0.5f - d);
                int i = z * S + x;
                dark[i] = Math.min(0.9f, dark[i] + amount * w);
            }
        return true;
    }
}
