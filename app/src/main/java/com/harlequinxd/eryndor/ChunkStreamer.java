package com.harlequinxd.eryndor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps the GPU's window on the world filled as the player walks: 16 x 16 chunks (512 m) of
 * ground textures, heightmap and trees, centred on the player. Chunks are painted on background
 * threads, nearest first, and copied into the window (and uploaded) on the GL thread, a few per
 * frame. Window slots use wrap-around addressing, so a chunk always lands at (its position mod the
 * window) and nothing ever has to move. (No voxels: the land is a Mode 7 heightmap.)
 */
final class ChunkStreamer {
    private static final int W2 = WorldTextures.WIN_CHUNKS;
    private static final int NONE = Integer.MIN_VALUE;

    private final ExecutorService pool;
    private final ConcurrentLinkedQueue<Object> done = new ConcurrentLinkedQueue<>();
    private final Set<Long> pending = ConcurrentHashMap.newKeySet();
    private final int[] haveX = new int[W2 * W2], haveZ = new int[W2 * W2];
    /** First chunk of the window (it covers [x0, x0 + W2) chunks). */
    private volatile int cx0 = NONE, cz0;
    private boolean dirty = true;
    volatile TreeLibrary lib;

    private static final class Cancelled {
        final long key;

        Cancelled(long key) {
            this.key = key;
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
        java.util.Arrays.fill(haveX, NONE);
    }

    private static int chunkOf(double m) {
        return (int) Math.floor(m / WorldTextures.CHUNK);
    }

    private boolean wants(int cx, int cz) {
        return cx >= cx0 && cx < cx0 + W2 && cz >= cz0 && cz < cz0 + W2;
    }

    private static int slot(int cx, int cz) {
        return Math.floorMod(cz, W2) * W2 + Math.floorMod(cx, W2);
    }

    /** Re-centre on the player and queue every chunk the window is missing, nearest first. */
    void update(double px, double pz) {
        int pcx = chunkOf(px), pcz = chunkOf(pz);
        int nx = pcx - W2 / 2, nz = pcz - W2 / 2;
        if (!dirty && nx == cx0 && nz == cz0) return;
        dirty = false;
        cz0 = nz;
        cx0 = nx;
        ArrayList<int[]> want = new ArrayList<>();
        for (int cz = cz0; cz < cz0 + W2; cz++)
            for (int cx = cx0; cx < cx0 + W2; cx++) {
                int s = slot(cx, cz);
                if ((haveX[s] != cx || haveZ[s] != cz) && !pending.contains(WorldTextures.key(cx, cz)))
                    want.add(new int[]{cx, cz, (cx - pcx) * (cx - pcx) + (cz - pcz) * (cz - pcz)});
            }
        want.sort((a, b) -> Integer.compare(a[2], b[2]));
        for (int[] w : want) submit(w[0], w[1]);
    }

    private void submit(int cx, int cz) {
        long k = WorldTextures.key(cx, cz);
        pending.add(k);
        pool.execute(() -> {
            try {
                if (!wants(cx, cz)) {                       // walked away before it started
                    done.add(new Cancelled(k));
                    return;
                }
                done.add(WorldTextures.paintChunk(cx, cz, lib));
            } catch (Throwable t) {
                t.printStackTrace();
                done.add(new Cancelled(k));
            }
        });
    }

    /**
     * Copy up to max finished chunks that are still wanted into the window; they are added to the
     * list for uploading. Returns how many were applied. Call on the thread that owns the window.
     */
    int drain(List<WorldTextures.Chunk2D> out, int max) {
        int n = 0;
        Object o;
        while (n < max && (o = done.poll()) != null) {
            if (o instanceof Cancelled) {
                pending.remove(((Cancelled) o).key);
                dirty = true;
                continue;
            }
            WorldTextures.Chunk2D c = (WorldTextures.Chunk2D) o;
            pending.remove(WorldTextures.key(c.cx, c.cz));
            if (!wants(c.cx, c.cz)) { dirty = true; continue; }
            WorldTextures.store(c);
            int s = slot(c.cx, c.cz);
            haveX[s] = c.cx;
            haveZ[s] = c.cz;
            if (out != null) out.add(c);
            n++;
        }
        return n;
    }

    /** Whether the chunk under the player is in place. */
    boolean ready(double px, double pz) {
        int cx = chunkOf(px), cz = chunkOf(pz), s = slot(cx, cz);
        return haveX[s] == cx && haveZ[s] == cz;
    }

    private int resident() {
        int n = 0;
        for (int cz = cz0; cz < cz0 + W2; cz++)
            for (int cx = cx0; cx < cx0 + W2; cx++) {
                int s = slot(cx, cz);
                if (haveX[s] == cx && haveZ[s] == cz) n++;
            }
        return n;
    }

    /** Fill the window around a point, blocking (the loading screen shows progress). */
    void loadAll(double px, double pz, float p0, float p1) {
        int total = W2 * W2;
        update(px, pz);
        while (true) {
            drain(null, 1 << 20);
            update(px, pz);
            int r = resident();
            Loading.within(p0, p1, r / (float) total);
            if (r >= total) return;
            try { Thread.sleep(10); } catch (InterruptedException e) { return; }
        }
    }

    /** Repaint all the ground (after a tree species changes shape: new shadows). */
    void invalidateGround() {
        java.util.Arrays.fill(haveX, NONE);
        dirty = true;
    }
}
