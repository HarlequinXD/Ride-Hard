package com.harlequinxd.eryndor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The ten tree species slots used by the world and edited in the Tree Builder. Each slot holds a
 * {@link TreeSpec} (saved as text in the app's files) and its grown {@link TreeModel}; everything is
 * packed into flat float arrays for the GPU. Pure Java apart from file access.
 */
final class TreeLibrary {
    static final int SLOTS = 10, TEX_W = 1024, META = 6;
    /** Trees must fit inside their 16 m world cell (tree centres may sit 1.5 m off-centre). */
    static final float MAX_HALF_WIDTH = 6.3f;

    final TreeSpec[] specs = new TreeSpec[SLOTS];
    final TreeModel[] models = new TreeModel[SLOTS];
    private final File dir;

    // packed GPU data (rebuilt by pack())
    float[] primTex, cellTex, indexTex, meta;
    int primRows, cellRows, indexRows;
    int version;

    TreeLibrary(File dir) {
        this.dir = dir;
        TreeSpec[] presets = TreeSpec.presets();
        for (int i = 0; i < SLOTS; i++) {
            TreeSpec s = presets[i];
            if (dir != null) {
                File f = file(i);
                if (f.exists()) {
                    try { s = TreeSpec.fromText(read(f), presets[i]); } catch (Exception ignored) { }
                }
            }
            specs[i] = s;
        }
    }

    private File file(int slot) {
        return new File(dir, "tree_slot" + slot + ".txt");
    }

    void growAll() {
        for (int i = 0; i < SLOTS; i++) models[i] = TreeModel.grow(specs[i]);
        pack();
    }

    /** Regrow one species (after an edit) and repack. */
    synchronized void regrow(int slot, TreeSpec spec) {
        specs[slot] = spec.copy();
        models[slot] = TreeModel.grow(specs[slot]);
        pack();
    }

    void save(int slot) {
        if (dir == null) return;
        try (FileOutputStream o = new FileOutputStream(file(slot))) {
            o.write(specs[slot].toText().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    void resetToPreset(int slot) {
        if (dir != null) file(slot).delete();
        regrow(slot, TreeSpec.presets()[slot]);
    }

    /** Uniform scale that keeps a species inside its world cell. */
    float fitScale(int slot) {
        TreeModel m = models[slot];
        float half = Math.max(Math.max(-m.minX, m.maxX), Math.max(-m.minZ, m.maxZ));
        return half > MAX_HALF_WIDTH ? MAX_HALF_WIDTH / half : 1f;
    }

    private static String read(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int o = 0, r;
            while (o < b.length && (r = in.read(b, o, b.length - o)) > 0) o += r;
            return new String(b, 0, o, StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------ GPU packing
    synchronized void pack() {
        int prims = 0, cells = 0, idx = 0;
        for (TreeModel m : models) { prims += m.primCount; cells += m.cellCount.length; idx += m.index.length; }
        primRows = Math.max(1, (prims * 3 + TEX_W - 1) / TEX_W);
        cellRows = Math.max(1, (cells + TEX_W - 1) / TEX_W);
        indexRows = Math.max(1, (idx + TEX_W - 1) / TEX_W);
        float[] pt = new float[primRows * TEX_W * 4];
        float[] ct = new float[cellRows * TEX_W * 2];
        float[] it = new float[indexRows * TEX_W];
        float[] mt = new float[SLOTS * META * 4];
        int pBase = 0, cBase = 0, iBase = 0;
        for (int s = 0; s < SLOTS; s++) {
            TreeModel m = models[s];
            TreeSpec sp = specs[s];
            System.arraycopy(m.prims, 0, pt, pBase * 12, m.primCount * 12);
            for (int c = 0; c < m.cellCount.length; c++) {
                ct[(cBase + c) * 2] = iBase + m.cellStart[c];
                ct[(cBase + c) * 2 + 1] = m.cellCount[c];
            }
            for (int k = 0; k < m.index.length; k++) it[iBase + k] = pBase + m.index[k];
            float half = Math.max(Math.max(-m.minX, m.maxX), Math.max(-m.minZ, m.maxZ));
            int o = s * META * 4;
            float[] row = {
                    m.minX, m.minY, m.minZ, m.gx,
                    m.maxX, m.maxY, m.maxZ, m.gy,
                    cBase, m.gz, sp.whole(TreeSpec.BARK_TYPE), half,
                    sp.get(TreeSpec.LEAF_R), sp.get(TreeSpec.LEAF_G), sp.get(TreeSpec.LEAF_B), m.trunkRadius,
                    sp.get(TreeSpec.LEAF2_R), sp.get(TreeSpec.LEAF2_G), sp.get(TreeSpec.LEAF2_B), 0f,
                    sp.get(TreeSpec.BARK_R), sp.get(TreeSpec.BARK_G), sp.get(TreeSpec.BARK_B), sp.get(TreeSpec.HEIGHT),
            };
            System.arraycopy(row, 0, mt, o, row.length);
            pBase += m.primCount;
            cBase += m.cellCount.length;
            iBase += m.index.length;
        }
        primTex = pt;
        cellTex = ct;
        indexTex = it;
        meta = mt;
        version++;
    }
}
