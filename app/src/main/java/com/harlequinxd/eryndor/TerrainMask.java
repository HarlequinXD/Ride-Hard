package com.harlequinxd.eryndor;

/**
 * The terrain texture mask: a painted colour map where each palette colour picks the texture used
 * on that patch of ground (assets/terrain/terrain_mask.png, one pixel per 12.5 cm, 2048 x 2048).
 * Colours are matched to the nearest palette entry, so soft brush edges still work.
 */
final class TerrainMask {
    private TerrainMask() { }

    /** Palette, indexed by material id (WorldTextures.M_*). */
    static final int[] PALETTE = {
            0x2EB82E,   // 0 grass        green
            0x8C5A28,   // 1 earth / dirt brown
            0x808080,   // 2 stone        grey (cobblestone road texture set)
            0x4A2810,   // 3 wood         dark brown
            0x1E5AFF,   // 4 water        blue
            0x0F5A28,   // 5 plants       dark green (forest floor texture set)
            0x5A5650,   // 6 rock         dark grey (rock texture set: outcrops, cliffs)
            0x6E3C14,   // 7 forest floor rust (leaf litter)
            0xA0A096,   // 8 gravel       light grey
            0xE6D296,   // 9 sand         pale yellow
            0xFFFFFF,   // 10 snow        white
            0x7DC83C,   // 11 moss        lime
            0xB4643C,   // 12 pine needles terracotta
            0x503C28,   // 13 roots       dark umber
            0x3C3223,   // 14 mud         near-black brown
            0x46823C,   // 15 forest grass mid green
    };
    static final String[] NAMES = {"grass", "earth", "stone", "wood", "water", "plants", "rock", "forest floor",
            "gravel", "sand", "snow", "moss", "pine needles", "roots", "mud", "forest grass"};

    /** ARGB pixels -> material bytes (id * MAT_STEP, as uploaded to uMat), resampled to size x size. */
    static byte[] decode(int[] argb, int w, int h, int size) {
        byte[] out = new byte[size * size];
        for (int z = 0; z < size; z++) {
            int sy = Math.min(h - 1, z * h / size);
            for (int x = 0; x < size; x++) {
                int c = argb[sy * w + Math.min(w - 1, x * w / size)];
                out[z * size + x] = (byte) (nearest(c) * WorldTextures.MAT_STEP);
            }
        }
        return out;
    }

    static int nearest(int c) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        int best = 0, bestD = Integer.MAX_VALUE;
        for (int i = 0; i < PALETTE.length; i++) {
            int p = PALETTE[i];
            int dr = r - ((p >> 16) & 255), dg = g - ((p >> 8) & 255), db = b - (p & 255);
            int d = dr * dr * 3 + dg * dg * 4 + db * db * 2;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** Material bytes -> ARGB palette image (used by the export tool). */
    static int[] encode(byte[] mat) {
        int[] out = new int[mat.length];
        for (int i = 0; i < mat.length; i++) {
            int id = Math.max(0, Math.min(PALETTE.length - 1, Math.round((mat[i] & 255) / (float) WorldTextures.MAT_STEP)));
            out[i] = 0xFF000000 | PALETTE[id];
        }
        return out;
    }
}
