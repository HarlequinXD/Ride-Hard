package com.harlequinxd.eryndor;

/**
 * Bakes tree shadows into a copy of the ground colours from the actual branches and leaves of
 * each placed tree (cast to the south-east, matching the ground's baked light from the north-west).
 * Kept separate from the cached ground so edited species re-shadow without repainting the world.
 */
final class TreeShadows {
    private TreeShadows() {}

    static int[] apply(int[] ground, byte[] trees, TreeLibrary lib) {
        final int S = WorldTextures.GROUND_SIZE;
        final float PPM = WorldTextures.PPM;
        int[] out = ground.clone();
        float[] dark = new float[S * S];
        for (int cz = 0; cz < WorldTextures.TREE_GRID; cz++)
            for (int cx = 0; cx < WorldTextures.TREE_GRID; cx++) {
                int o = (cz * WorldTextures.TREE_GRID + cx) * 4;
                int a = trees[o + 3] & 255;
                if (a < 128) continue;
                int bv = trees[o + 2] & 255, sp = Math.min(9, bv / 25);
                float yaw = (bv - sp * 25) / 25f * (float) (Math.PI * 2);
                float sz = 0.85f + (a / 255f - 0.5f) * 0.7f;                 // same as the shader
                float half = halfWidth(lib.models[sp]);
                sz = Math.min(sz, TreeLibrary.MAX_HALF_WIDTH / Math.max(0.1f, half));
                float wx = (cx + 0.5f) * WorldTextures.TREE_CELL + ((trees[o] & 255) / 255f - 0.5f) * 3f;
                float wz = (cz + 0.5f) * WorldTextures.TREE_CELL + ((trees[o + 1] & 255) / 255f - 0.5f) * 3f;
                float c = (float) Math.cos(yaw), s = (float) Math.sin(yaw);
                TreeModel m = lib.models[sp];
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
                        splat(dark, x * PPM, z * PPM, Math.max(0.6f, r * sz * PPM), leaf ? 0.10f : 0.35f, S);
                    }
                }
            }
        for (int i = 0; i < out.length; i++) {
            if (dark[i] <= 0f) continue;
            float k = 1f - Math.min(0.55f, dark[i]);
            int v = out[i];
            int r = Math.round(((v >> 16) & 255) * k), g = Math.round(((v >> 8) & 255) * k), b = Math.round((v & 255) * k);
            out[i] = (v & 0xFF000000) | (r << 16) | (g << 8) | b;
        }
        return out;
    }

    static float halfWidth(TreeModel m) {
        return Math.max(Math.max(-m.minX, m.maxX), Math.max(-m.minZ, m.maxZ));
    }

    private static float dist(float[] p, int b) {
        float dx = p[b + 4] - p[b], dy = p[b + 5] - p[b + 1], dz = p[b + 6] - p[b + 2];
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static void splat(float[] dark, float px, float pz, float rad, float amount, int S) {
        int x0 = (int) Math.floor(px - rad), x1 = (int) Math.ceil(px + rad);
        int z0 = (int) Math.floor(pz - rad), z1 = (int) Math.ceil(pz + rad);
        for (int z = z0; z <= z1; z++)
            for (int x = x0; x <= x1; x++) {
                float dx = x + 0.5f - px, dz = z + 0.5f - pz;
                float d = (float) Math.sqrt(dx * dx + dz * dz);
                if (d > rad + 0.5f) continue;
                float w = Math.min(1f, rad + 0.5f - d);
                int i = Math.floorMod(z, S) * S + Math.floorMod(x, S);
                dark[i] = Math.min(0.9f, dark[i] + amount * w);
            }
    }
}
