package com.harlequinxd.eryndor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps the GPU's windows on the world filled as the player walks: 16 x 16 chunks (512 m) of
 * ground, heights and trees, and 8 x 8 chunks (256 m) of the voxel volume, centred on the player.
 * Chunks are generated on background threads, nearest first, and copied into the windows (and
 * uploaded) on the GL thread, a few per frame. Window slots use wrap-around addressing, so a chunk
 * always lands at (its position mod the window) and nothing ever has to move.
 */
final class ChunkStreamer {
    private static final int W2 = WorldTextures.WIN_CHUNKS, WV = Voxels.WIN_CHUNKS;
    private static final int NONE = Integer.MIN_VALUE;

    private final ExecutorService pool;
    private final ConcurrentLinkedQueue<Object> done = new ConcurrentLinkedQueue<>();
    private final Set<Long> pending2 = ConcurrentHashMap.newKeySet(), pendingV = ConcurrentHashMap.newKeySet();
    private final int[] have2X = new int[W2 * W2], have2Z = new int[W2 * W2];
    private final int[] haveVX = new int[WV * WV], haveVZ = new int[WV * WV];
    /** First chunk of each window (the window covers [x0, x0 + size) chunks). */
    private volatile int c2x0 = NONE, c2z0, cvx0, cvz0;
    private boolean dirty = true;
    volatile TreeLibrary lib;

    private static final class Cancelled {
        final long key;
        final boolean vol;

        Cancelled(long key, boolean vol) {
            this.key = key;
            this.vol = vol;
        }
    }

    ChunkStreamer() {
        int n = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
        pool = Executors.newFixedThreadPool(n, r -> {
            Thread t = new Thread(r, "chunk-gen");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        java.util.Arrays.fill(have2X, NONE);
        java.util.Arrays.fill(haveVX, NONE);
    }

    private static int chunkOf(double m) {
        return (int) Math.floor(m / WorldTextures.CHUNK);
    }

    private boolean wants2(int cx, int cz) {
        return cx >= c2x0 && cx < c2x0 + W2 && cz >= c2z0 && cz < c2z0 + W2;
    }

    private boolean wantsV(int cx, int cz) {
        return cx >= cvx0 && cx < cvx0 + WV && cz >= cvz0 && cz < cvz0 + WV;
    }

    private static int slot(int cx, int cz, int w) {
        return Math.floorMod(cz, w) * w + Math.floorMod(cx, w);
    }

    /** Re-centre on the player and queue every chunk the windows are missing, nearest first. */
    void update(double px, double pz) {
        int pcx = chunkOf(px), pcz = chunkOf(pz);
        int nx = pcx - W2 / 2, nz = pcz - W2 / 2;
        if (!dirty && nx == c2x0 && nz == c2z0) return;
        dirty = false;
        cvx0 = pcx - WV / 2;
        cvz0 = pcz - WV / 2;
        c2z0 = nz;
        c2x0 = nx;
        ArrayList<int[]> want = new ArrayList<>();
        for (int cz = c2z0; cz < c2z0 + W2; cz++)
            for (int cx = c2x0; cx < c2x0 + W2; cx++) {
                int s = slot(cx, cz, W2);
                if ((have2X[s] != cx || have2Z[s] != cz) && !pending2.contains(WorldTextures.key(cx, cz)))
                    want.add(new int[]{cx, cz, 0, (cx - pcx) * (cx - pcx) + (cz - pcz) * (cz - pcz)});
            }
        for (int cz = cvz0; cz < cvz0 + WV; cz++)
            for (int cx = cvx0; cx < cvx0 + WV; cx++) {
                int s = slot(cx, cz, WV);
                if ((haveVX[s] != cx || haveVZ[s] != cz) && !pendingV.contains(WorldTextures.key(cx, cz)))
                    want.add(new int[]{cx, cz, 1, (cx - pcx) * (cx - pcx) + (cz - pcz) * (cz - pcz) - 2});
            }
        want.sort((a, b) -> Integer.compare(a[3], b[3]));
        for (int[] w : want) submit(w[0], w[1], w[2] == 1);
    }

    private void submit(int cx, int cz, boolean vol) {
        long k = WorldTextures.key(cx, cz);
        (vol ? pendingV : pending2).add(k);
        pool.execute(() -> {
            try {
                if (vol ? !wantsV(cx, cz) : !wants2(cx, cz)) {        // walked away before it started
                    done.add(new Cancelled(k, vol));
                    return;
                }
                done.add(vol ? Voxels.fillChunk(cx, cz) : WorldTextures.paintChunk(cx, cz, lib));
            } catch (Throwable t) {
                t.printStackTrace();
                done.add(new Cancelled(k, vol));
            }
        });
    }

    /**
     * Copy up to max finished chunks that are still wanted into the windows; they are added to the
     * lists for uploading. Returns how many were applied. Call on the thread that owns the windows.
     */
    int drain(List<WorldTextures.Chunk2D> out2, List<Voxels.VolChunk> outV, int max) {
        int n = 0;
        Object o;
        while (n < max && (o = done.poll()) != null) {
            if (o instanceof Cancelled) {
                Cancelled c = (Cancelled) o;
                (c.vol ? pendingV : pending2).remove(c.key);
                dirty = true;
            } else if (o instanceof WorldTextures.Chunk2D) {
                WorldTextures.Chunk2D c = (WorldTextures.Chunk2D) o;
                pending2.remove(WorldTextures.key(c.cx, c.cz));
                if (!wants2(c.cx, c.cz)) { dirty = true; continue; }
                WorldTextures.store(c);
                int s = slot(c.cx, c.cz, W2);
                have2X[s] = c.cx;
                have2Z[s] = c.cz;
                if (out2 != null) out2.add(c);
                n++;
            } else {
                Voxels.VolChunk c = (Voxels.VolChunk) o;
                pendingV.remove(WorldTextures.key(c.cx, c.cz));
                if (!wantsV(c.cx, c.cz)) { dirty = true; continue; }
                Voxels.store(c);
                int s = slot(c.cx, c.cz, WV);
                haveVX[s] = c.cx;
                haveVZ[s] = c.cz;
                if (outV != null) outV.add(c);
                n++;
            }
        }
        return n;
    }

    /** Whether the chunks under the player are in place (ground and volume). */
    boolean ready(double px, double pz) {
        int cx = chunkOf(px), cz = chunkOf(pz);
        int s = slot(cx, cz, W2), v = slot(cx, cz, WV);
        return have2X[s] == cx && have2Z[s] == cz && haveVX[v] == cx && haveVZ[v] == cz;
    }

    /** How many window slots hold what they should. */
    private int resident() {
        int n = 0;
        for (int cz = c2z0; cz < c2z0 + W2; cz++)
            for (int cx = c2x0; cx < c2x0 + W2; cx++) {
                int s = slot(cx, cz, W2);
                if (have2X[s] == cx && have2Z[s] == cz) n++;
            }
        for (int cz = cvz0; cz < cvz0 + WV; cz++)
            for (int cx = cvx0; cx < cvx0 + WV; cx++) {
                int s = slot(cx, cz, WV);
                if (haveVX[s] == cx && haveVZ[s] == cz) n++;
            }
        return n;
    }

    /** Fill both windows around a point, blocking (the loading screen shows progress). */
    void loadAll(double px, double pz, float p0, float p1) {
        int total = W2 * W2 + WV * WV;
        update(px, pz);
        while (true) {
            drain(null, null, 1 << 20);
            update(px, pz);
            int r = resident();
            Loading.within(p0, p1, r / (float) total);
            if (r >= total) return;
            try { Thread.sleep(10); } catch (InterruptedException e) { return; }
        }
    }

    /** Repaint all the ground (after a tree species changes shape: new shadows). */
    void invalidateGround() {
        java.util.Arrays.fill(have2X, NONE);
        dirty = true;
    }
}
