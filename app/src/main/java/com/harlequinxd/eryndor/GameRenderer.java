package com.harlequinxd.eryndor;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;

import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * First-person Mode 7 scene. The world is drawn as one full-screen shader pass into a low-res
 * buffer (240 lines), then scaled up with nearest filtering.
 */
final class GameRenderer implements GLSurfaceView.Renderer {

    /** GBA vertical resolution; width follows the screen's aspect so it fills the whole display. */
    private static final int LOW_RES_LINES = 240;           // PS1 resolution: 240 lines, width follows the screen
    private static final float FOV_Y = (float) Math.toRadians(68);
    private static final float EYE_HEIGHT = 1.68f;
    private static final float MAX_STEP_UP = 0.55f;     // tallest step the player climbs without jumping
    private static final float MIN_HEADROOM = 1.95f;
    private static final float WALK_SPEED = 4.2f;      // m/s at full stick
    private static final float LOOK_SENS = 0.0065f;    // radians per dp

    private final Input input;
    private final SkyState sky;
    private final AssetManager assets;
    private final File cacheDir;

    private int sceneProg, blitProg;
    private int groundTex, cloudTex, heightTex, detailTex, fernTex, matTex, treeTex;
    private int fbo, fboTex, fboW, fboH, scrW, scrH;
    private FloatBuffer quad;
    private final HashMap<String, Integer> sceneU = new HashMap<>(), blitU = new HashMap<>();

    // player
    private double px = 128.0, pz = 160.0;   // village street, facing north toward the plaza and the Hollowspire
    private float yaw = 0f, pitch = -0.04f;
    private float bobPhase, bobAmt, eyeY = Float.NaN;
    private double windX, windZ;
    private float time;
    private long lastNanos;
    private final float[] look = new float[2];

    GameRenderer(Input input, SkyState sky, AssetManager assets, File cacheDir) {
        this.input = input;
        this.sky = sky;
        this.assets = assets;
        this.cacheDir = cacheDir;
    }

    // world data built off the GL thread, then uploaded once ready
    private volatile boolean dataReady;
    private Thread builder;
    private int[] cloudData;
    /** Streams the world in 32 m chunks around the player (the GPU holds a window of it). */
    private final ChunkStreamer streamer = new ChunkStreamer();
    private final java.util.ArrayList<WorldTextures.Chunk2D> fresh2 = new java.util.ArrayList<>();
    private final java.util.ArrayList<Voxels.VolChunk> freshV = new java.util.ArrayList<>();
    volatile TreeLibrary library;
    private int treePrimTex, treeCellTex, treeIdxTex, leafAtlasTex, uploadedTreeVersion = -1;
    // first-person arms: colour / normal / height sheets (decoded off the GL thread)
    // first-person hands as voxels (tools/build_hands.py): two distance volumes and a colour volume
    private int handATex, handBTex, handCTex;
    private java.nio.ByteBuffer handA, handB, handC;
    private int handNx, handNy, handNz;
    private float handCell, handX0, handY0, handZ0;
    // terrain volume (tunnels, caves, overhangs): 3D RG8, distance + sky openness
    private int volTex;
    // ground texture sets, one array layer each (order = layer index in scene.frag):
    // colour, and normal map + height packed together; tiling, point sampled
    static final String[] SURFACES = {"grass", "cobble", "dirt", "rock", "forest", "gravel", "sand", "snow",
            "moss", "needles", "roots", "mud", "forestgrass", "cliff", "mossyrock"};
    private int surfColTex, surfNHTex;
    private int[][] surfCol, surfNH;
    private int surfSize;
    private final ArmRig arms = new ArmRig();
    private final float[] treeMeta = new float[TreeLibrary.SLOTS * TreeLibrary.META * 4];

    // Tree Builder preview: one species shown at a fixed spot, camera orbiting it
    volatile int previewSlot = -1;
    volatile float orbitYaw = 0.6f, orbitDist = 16f, orbitPitch = 0.15f, previewYaw;
    /** Where the preview tree sits on screen, in NDC x (−0.48 keeps it clear of the builder panel). */
    volatile float previewScreenX = 0f;
    /** Where the preview tree stands: picked a little ahead of you when the builder opens. */
    private double previewX = Double.NaN, previewZ;
    private boolean uploaded;

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        // Keep this fast: Android waits for the first frame before showing the window, so the
        // heavy world building runs on a background thread while we draw blank frames and the
        // loading screen (a normal View above us) shows progress.
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        sceneProg = blitProg = 0;
        sceneU.clear();
        blitU.clear();
        uploaded = false;
        float[] q = {-1, -1, 1, -1, -1, 1, 1, 1};
        quad = ByteBuffer.allocateDirect(q.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(q).position(0);
        fbo = 0;
        fboW = fboH = 0;
        lastNanos = System.nanoTime();
        if (builder == null) {
            builder = new Thread(this::buildWorld, "world-builder");
            builder.setPriority(Thread.NORM_PRIORITY - 1);
            builder.start();
        }
    }

    private void buildWorld() {
        Loading.set("Waking the realm", 0.01f);
        Loading.set("Growing the trees", 0.03f);
        TreeLibrary lib = new TreeLibrary(cacheDir);
        lib.growAll();
        library = lib;
        streamer.lib = lib;
        Loading.set("Raising the land around you", 0.06f);
        streamer.loadAll(px, pz, 0.06f, 0.80f);
        Loading.set("Shaping your hands", 0.825f);
        loadHands();
        int ns = SURFACES.length;
        int[][] sc = new int[ns][], sn = new int[ns][];
        for (int i = 0; i < ns; i++) {
            for (int k = 0; k < 2; k++) {
                Bitmap b = readBitmap("surfaces/" + SURFACES[i] + (k == 0 ? "_col.png" : "_nh.png"));
                surfSize = b.getWidth();
                int[] px = new int[surfSize * surfSize];
                b.getPixels(px, 0, surfSize, 0, 0, surfSize, surfSize);
                b.recycle();
                if (k == 0) sc[i] = px; else sn[i] = px;
            }
        }
        surfCol = sc;
        surfNH = sn;
        Loading.set("Gathering the clouds", 0.83f);
        cloudData = WorldTextures.clouds();
        Loading.set("Compiling the shaders", 0.85f);
        dataReady = true;
    }

    /** Runs on the GL thread once the builder is done: compile shaders and upload everything. */
    private void uploadWorld() {
        String vs = GLUtil.asset(assets, "shaders/fullscreen.vert");
        sceneProg = GLUtil.program(vs, GLUtil.asset(assets, "shaders/scene.frag"));
        blitProg = GLUtil.program(vs, GLUtil.asset(assets, "shaders/blit.frag"));
        Loading.set("Uploading the land", 0.90f);
        streamer.drain(null, null, 1 << 20);                     // anything finished since loading
        groundTex = GLUtil.texture(WorldTextures.groundWin, WorldTextures.GROUND_SIZE, true);    // point sampled
        heightTex = GLUtil.texture(WorldTextures.heightTexture(), WorldTextures.HEIGHT_SIZE, false, true);
        Loading.set("Uploading the fine relief", 0.94f);
        detailTex = GLUtil.luminance(WorldTextures.detailWin, WorldTextures.GROUND_SIZE);
        matTex = GLUtil.luminance(WorldTextures.matWin, WorldTextures.GROUND_SIZE, true);
        fernTex = GLUtil.cells(WorldTextures.fernWin, WorldTextures.FERN_GRID);
        treeTex = GLUtil.cells(WorldTextures.treeWin, WorldTextures.TREE_GRID);
        cloudTex = GLUtil.texture(cloudData, WorldTextures.CLOUD_SIZE, false);
        leafAtlasTex = GLUtil.atlas(LeafAtlas.build(), LeafAtlas.W, LeafAtlas.H);
        handATex = GLUtil.texture3D(handA, handNx, handNy, handNz, GLES30.GL_RGBA8, GLES20.GL_RGBA, true);
        handBTex = GLUtil.texture3D(handB, handNx, handNy, handNz, GLES30.GL_RG8, GLES30.GL_RG, true);
        handCTex = GLUtil.texture3D(handC, handNx, handNy, handNz, GLES30.GL_RGBA8, GLES20.GL_RGBA, true);
        handA = handB = handC = null;
        surfColTex = GLUtil.textureArray(surfCol, surfSize);    // repeating, point sampled
        surfNHTex = GLUtil.textureArray(surfNH, surfSize);
        surfCol = surfNH = null;
        java.nio.ByteBuffer vb = ByteBuffer.allocateDirect(Voxels.vol.length).order(ByteOrder.nativeOrder());
        vb.put(Voxels.vol).position(0);
        volTex = GLUtil.texture3D(vb, Voxels.NX, Voxels.NY, Voxels.NZ);
        uploadTrees();
        Loading.set("Lighting the lanterns", 0.99f);
        eyeY = Float.isNaN(spawnFeet) ? Float.NaN : spawnFeet + EYE_HEIGHT;
        uploaded = true;
        lastNanos = System.nanoTime();
        Loading.set("Ready", 1f);
        sky.worldReady = true;
    }

    /** Move the player (testing). */
    void teleport(double x, double z) {
        px = x;
        pz = z;
    }

    /** Move the player with the feet at height y (testing: inside tunnels). */
    void teleport(double x, double z, float y) {
        px = x;
        pz = z;
        spawnFeet = y;
    }

    private float spawnFeet = Float.NaN;

    /** Take out or put away the pickaxe. */
    void toggleSword() {
        arms.sword = !arms.sword;
    }

    boolean swordDrawn() {
        return arms.sword;
    }

    /** (Re)upload the packed species data whenever the library changes. */
    private void uploadTrees() {
        TreeLibrary lib = library;
        if (lib == null) return;
        synchronized (lib) {
            treePrimTex = GLUtil.floatTexture(treePrimTex, lib.primTex, TreeLibrary.TEX_W, lib.primRows, 4);
            treeCellTex = GLUtil.floatTexture(treeCellTex, lib.cellTex, TreeLibrary.TEX_W, lib.cellRows, 2);
            treeIdxTex = GLUtil.floatTexture(treeIdxTex, lib.indexTex, TreeLibrary.TEX_W, lib.indexRows, 1);
            System.arraycopy(lib.meta, 0, treeMeta, 0, treeMeta.length);
            uploadedTreeVersion = lib.version;
        }
    }

    /** After a species is saved, recast the tree shadows: the ground is repainted chunk by chunk. */
    void reshadow() {
        reshadowPending = true;
    }

    private volatile boolean reshadowPending;

    private Bitmap readBitmap(String path) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream in = assets.open(path)) {
            Bitmap b = BitmapFactory.decodeStream(in, null, o);
            if (b == null) throw new IllegalStateException("cannot decode " + path);
            return b;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("missing asset " + path, e);
        }
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int w, int h) {
        scrW = w;
        scrH = h;
        int lh = LOW_RES_LINES;
        int lw = Math.max(1, Math.round(lh * (float) w / h));
        if (lw != fboW || lh != fboH || fbo == 0) createFbo(lw, lh);
    }

    private void createFbo(int w, int h) {
        if (fbo != 0) {
            GLES20.glDeleteFramebuffers(1, new int[]{fbo}, 0);
            GLES20.glDeleteTextures(1, new int[]{fboTex}, 0);
        }
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        fboTex = t[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glGenFramebuffers(1, t, 0);
        fbo = t[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        fboW = w;
        fboH = h;
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        if (!uploaded) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(0, 0, Math.max(1, scrW), Math.max(1, scrH));
            GLES20.glClearColor(0.04f, 0.05f, 0.07f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            if (dataReady) uploadWorld();
            else {
                try { Thread.sleep(30); } catch (InterruptedException ignored) { }
                return;
            }
            if (fbo == 0 && scrW > 0) createFbo(Math.max(1, Math.round(LOW_RES_LINES * (float) scrW / scrH)), LOW_RES_LINES);
        }
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        time += dt;
        if (time > 3600f) time -= 3600f;

        if (library != null && library.version != uploadedTreeVersion) uploadTrees();
        int slot = previewSlot;
        streamChunks();
        if (slot < 0) {
            previewX = Double.NaN;
            if (streamer.ready(px, pz)) updatePlayer(dt);
            else input.consumeLook(look);
            if (input.swingHeld) arms.startSwing();
            arms.update(dt, look[0] * LOOK_SENS, -look[1] * LOOK_SENS, bobAmt, bobPhase);
            if (arms.takeImpact() && !Float.isNaN(eyeY)) {               // the pickaxe comes down where you look
                float cp = (float) Math.cos(pitch);
                float dx = (float) Math.sin(yaw) * cp, dy = (float) Math.sin(pitch), dz = -(float) Math.cos(yaw) * cp;
                if (Digging.strike(px, eyeY, pz, dx, dy, dz)) arms.bite();
            }
        } else input.consumeLook(look);
        Digging.update(dt);
        uploadDug();
        sky.update(dt);
        windX = (windX + sky.windSpeed * 0.92 * dt) % 2000.0;
        windZ = (windZ + sky.windSpeed * 0.38 * dt) % 2000.0;

        // ---- scene pass (low res)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glViewport(0, 0, fboW, fboH);
        GLES20.glUseProgram(sceneProg);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, groundTex);
        GLES20.glUniform1i(u(sceneProg, "uGround"), 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cloudTex);
        GLES20.glUniform1i(u(sceneProg, "uCloud"), 1);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, heightTex);
        GLES20.glUniform1i(u(sceneProg, "uHeight"), 2);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, detailTex);
        GLES20.glUniform1i(u(sceneProg, "uDetail"), 3);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE4);
        GLES20.glBindTexture(GLES30.GL_TEXTURE_3D, volTex);
        GLES20.glUniform1i(u(sceneProg, "uVol"), 4);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE5);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, matTex);
        GLES20.glUniform1i(u(sceneProg, "uMat"), 5);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE6);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, treeTex);
        GLES20.glUniform1i(u(sceneProg, "uTrees"), 6);
        bindTex(7, treePrimTex, "uTreePrims");
        bindTex(8, treeCellTex, "uTreeCells");
        bindTex(9, treeIdxTex, "uTreeIdx");
        bindTex(10, leafAtlasTex, "uLeafAtlas");
        GLES20.glUniform4fv(u(sceneProg, "uTreeMeta"), TreeLibrary.SLOTS * TreeLibrary.META, treeMeta, 0);

        float tanY = (float) Math.tan(FOV_Y / 2);
        GLES20.glUniform2f(u(sceneProg, "uTanHalf"), tanY * fboW / fboH, tanY);
        float camYaw = yaw, camPitch = pitch;
        double camX = px, camZ = pz;
        float camY = eyeY + bobAmt * (float) Math.sin(bobPhase) * 0.045f;
        float pvX = 0, pvY = 0, pvZ = 0;
        if (slot >= 0 && library != null) {
            float h = library.specs[slot].get(TreeSpec.HEIGHT);
            if (Double.isNaN(previewX)) {                       // a clear spot a little ahead of you
                previewX = px + Math.sin(yaw) * 9.0;
                previewZ = pz - Math.cos(yaw) * 9.0;
            }
            float base = WorldTextures.floorAt(previewX, previewZ);
            orbitYaw += look[0] * 0.008f;
            orbitPitch = Math.max(-0.1f, Math.min(1.2f, orbitPitch + look[1] * 0.006f));
            float dist = orbitDist;
            float tx = (float) previewX, ty = base + h * 0.45f, tz = (float) previewZ;
            camX = tx + Math.sin(orbitYaw) * Math.cos(orbitPitch) * dist;
            camZ = tz + Math.cos(orbitYaw) * Math.cos(orbitPitch) * dist;
            camY = ty + (float) Math.sin(orbitPitch) * dist;
            camY = Math.max(camY, WorldTextures.floorAt(camX, camZ) + 0.6f);
            double dx = tx - camX, dy = ty - camY, dz = tz - camZ;
            camYaw = (float) (Math.atan2(dx, -dz) - Math.atan(previewScreenX * tanY * fboW / fboH));
            camPitch = (float) Math.atan2(dy, Math.sqrt(dx * dx + dz * dz));
            pvX = tx; pvY = base - 0.05f; pvZ = tz;
        }
        // the shader works in a frame shifted by a multiple of 4096 m (a multiple of every window and
        // texture repeat), so its floats stay small and precise anywhere in the 32 km world
        double offX = camX - mod(camX, 4096.0), offZ = camZ - mod(camZ, 4096.0);
        pvX -= (float) offX;
        pvZ -= (float) offZ;
        GLES20.glUniform1f(u(sceneProg, "uYaw"), camYaw);
        projectSun(camYaw, camPitch, tanY * fboW / fboH, tanY);
        GLES20.glUniform1f(u(sceneProg, "uPitch"), camPitch);
        GLES20.glUniform4f(u(sceneProg, "uPreview"), pvX, pvY, pvZ, slot >= 0 ? slot : -1f);
        GLES20.glUniform1f(u(sceneProg, "uPreviewYaw"), previewYaw);
        bindTex3D(11, handATex, "uHandA");
        bindTex3D(12, handBTex, "uHandB");
        bindTex3D(13, handCTex, "uHandC");
        GLES20.glUniform3f(u(sceneProg, "uHandO"), handX0 - handCell * 0.5f, handY0 - handCell * 0.5f, handZ0 - handCell * 0.5f);
        GLES20.glUniform3f(u(sceneProg, "uHandS"), handNx * handCell, handNy * handCell, handNz * handCell);
        bindTexArray(14, surfColTex, "uSurfCol");
        bindTexArray(15, surfNHTex, "uSurfNH");
        GLES20.glUniformMatrix3fv(u(sceneProg, "uArmRot"), 2, false, arms.rot, 0);
        GLES20.glUniform3fv(u(sceneProg, "uArmPos"), 2, arms.pos, 0);
        GLES20.glUniform4fv(u(sceneProg, "uFing"), 10, arms.fingers, 0);
        GLES20.glUniform3f(u(sceneProg, "uSwordO"), arms.swordO[0], arms.swordO[1], arms.swordO[2]);
        GLES20.glUniform3f(u(sceneProg, "uSwordB"), arms.swordB[0], arms.swordB[1], arms.swordB[2]);
        GLES20.glUniform3f(u(sceneProg, "uSwordE"), arms.swordE[0], arms.swordE[1], arms.swordE[2]);
        GLES20.glUniform1f(u(sceneProg, "uSwordShow"), arms.sword ? 1f : 0f);
        int nClods = Digging.packClods(clodData, offX, offZ);
        GLES20.glUniform4fv(u(sceneProg, "uClods"), Digging.MAX_CLODS, clodData, 0);
        GLES20.glUniform1i(u(sceneProg, "uClodN"), nClods);
        GLES20.glUniform1fv(u(sceneProg, "uFingK"), 10, arms.fingerK, 0);
        GLES20.glUniform1f(u(sceneProg, "uArmShow"), slot < 0 ? 1f : 0f);
        GLES20.glUniform3f(u(sceneProg, "uCam"), (float) (camX - offX), camY, (float) (camZ - offZ));
        GLES20.glUniform2f(u(sceneProg, "uCloudO"), (float) mod(px + windX, 2000.0), (float) mod(pz + windZ, 2000.0));
        GLES20.glUniform3fv(u(sceneProg, "uSunDir"), 1, sky.sunDir, 0);
        GLES20.glUniform3fv(u(sceneProg, "uSkyTop"), 1, sky.skyTop, 0);
        GLES20.glUniform3fv(u(sceneProg, "uSkyHor"), 1, sky.skyHor, 0);
        GLES20.glUniform3fv(u(sceneProg, "uSunCol"), 1, sky.sunCol, 0);
        GLES20.glUniform3fv(u(sceneProg, "uAmbient"), 1, sky.ambient, 0);
        GLES20.glUniform3fv(u(sceneProg, "uFogCol"), 1, sky.fogCol, 0);
        GLES20.glUniform3fv(u(sceneProg, "uMtnCol"), 1, sky.mtnCol, 0);
        GLES20.glUniform1f(u(sceneProg, "uFog"), sky.fogDensity);
        GLES20.glUniform1f(u(sceneProg, "uStars"), sky.stars);
        GLES20.glUniform1f(u(sceneProg, "uCover"), sky.cover);
        GLES20.glUniform1f(u(sceneProg, "uCloudDark"), sky.cloudDark);
        GLES20.glUniform1f(u(sceneProg, "uRain"), sky.rain);
        GLES20.glUniform1f(u(sceneProg, "uWet"), sky.wetness);
        GLES20.glUniform1f(u(sceneProg, "uFlash"), sky.lightning);
        GLES20.glUniform1f(u(sceneProg, "uTime"), time);
        GLES20.glUniform1f(u(sceneProg, "uSunVis"), sky.sunVis);
        GLES20.glUniform1f(u(sceneProg, "uMist"), sky.mist);
        drawQuad();

        // ---- upscale to the screen
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, scrW, scrH);
        GLES20.glUseProgram(blitProg);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex);
        GLES20.glUniform1i(u(blitProg, "uTex"), 0);
        GLES20.glUniform2f(u(blitProg, "uSunUv"), sunU, sunV);
        GLES20.glUniform1f(u(blitProg, "uSunOn"), sunOn);
        GLES20.glUniform3fv(u(blitProg, "uSunTint"), 1, sky.sunCol, 0);
        GLES20.glUniform1f(u(blitProg, "uAspect"), fboW / (float) fboH);
        gradeFromSky();
        GLES20.glUniform4f(u(blitProg, "uGrade"), grade[0], grade[1], grade[2], grade[3]);
        drawQuad();
    }

    private final float[] grade = new float[4];

    /**
     * Realistic colour grade from the time of day and the weather: warm and rich at golden hour,
     * neutral at noon, cooler and softer under grey skies, rain and mist, moonlit blue at night.
     */
    private void gradeFromSky() {
        float e = sky.sunDir[1];                                       // sun elevation (sin)
        float day = clamp01((e + 0.05f) / 0.25f);
        float golden = day * clamp01(1f - Math.abs(e - 0.12f) / 0.22f) * (1f - sky.greyness);
        float grey = Math.max(sky.greyness, sky.mist * 0.8f);
        float night = sky.stars;
        grade[0] = Math.max(-1f, Math.min(1f, 0.15f * day + 0.75f * golden - 0.45f * grey - 0.7f * night));
        grade[1] = 1.08f + 0.10f * golden - 0.28f * grey - 0.30f * night;  // saturation
        grade[2] = 0.30f * day * (1f - 0.55f * grey) + 0.12f;              // contrast
        grade[3] = 0.22f + 0.10f * night;                                  // vignette
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    // sun on screen, for the shafts and glare in the blit pass
    private float sunU, sunV, sunOn;

    /** Project the sun direction with the inverse of the shader's view rotation (yaw, then pitch). */
    private void projectSun(float camYaw, float camPitch, float tanX, float tanY) {
        double x = sky.sunDir[0], y = sky.sunDir[1], z = sky.sunDir[2];
        double cyw = Math.cos(camYaw), syw = Math.sin(camYaw), cp = Math.cos(camPitch), sp = Math.sin(camPitch);
        double x1 = x * cyw + z * syw, z1 = -x * syw + z * cyw;
        double y2 = y * cp + z1 * sp, z2 = -y * sp + z1 * cp;
        if (z2 > -0.05 || previewSlot >= 0) {      // behind you (or the Tree Builder)
            sunOn = 0f;
            return;
        }
        float nx = (float) (x1 / (-z2 * tanX)), ny = (float) (y2 / (-z2 * tanY));
        sunU = nx * 0.5f + 0.5f;
        sunV = ny * 0.5f + 0.5f;
        float off = Math.max(Math.abs(nx), Math.abs(ny));             // fade as it leaves the screen
        float edge = Math.max(0f, Math.min(1f, (1.8f - off) / 0.8f));
        sunOn = sky.sunVis * edge * Math.max(0f, Math.min(1f, (float) y * 6f + 0.3f));
    }

    private final float[] clodData = new float[Digging.MAX_CLODS * 4];

    /** Window start and length of a cell range, split where the window wraps: (start, len) pairs. */
    private static int[] segments(int a0, int a1, int n) {
        int len = Math.min(n, a1 - a0 + 1), s = Math.floorMod(a0, n);
        return s + len <= n ? new int[]{s, len} : new int[]{s, n - s, 0, len - (n - s)};
    }

    /** Re-upload the parts of the volume and the dirt mask that digging changed this frame. */
    private void uploadDug() {
        if (Digging.volDirty()) {
            int[] b = Digging.dirtyVol;
            final int NX = Voxels.NX, NY = Voxels.NY, NZ = Voxels.NZ;
            int y0 = Math.max(0, b[1]), h = Math.min(NY - 1, b[4]) - y0 + 1;
            int[] sx = segments(b[0], b[3], NX), sz = segments(b[2], b[5], NZ);
            for (int i = 0; i < sx.length; i += 2)
                for (int k = 0; k < sz.length; k += 2) {
                    int x0 = sx[i], w = sx[i + 1], z0 = sz[k], d = sz[k + 1];
                    byte[] data = new byte[w * h * d * 2];
                    for (int z = 0; z < d; z++)
                        for (int y = 0; y < h; y++)
                            System.arraycopy(Voxels.vol, (((z0 + z) * NY + y0 + y) * NX + x0) * 2, data, ((z * h + y) * w) * 2, w * 2);
                    GLUtil.subTexture3D(volTex, data, x0, y0, z0, w, h, d);
                }
        }
        if (Digging.digDirty()) {
            int[] b = Digging.dirtyDig;
            final int S = WorldTextures.HEIGHT_SIZE;
            int[] sx = segments(b[0], b[2], S), sz = segments(b[1], b[3], S);
            for (int i = 0; i < sx.length; i += 2)
                for (int k = 0; k < sz.length; k += 2) {
                    int x0 = sx[i], w = sx[i + 1], z0 = sz[k], d = sz[k + 1];
                    int[] data = new int[w * d];
                    for (int z = 0; z < d; z++)
                        for (int x = 0; x < w; x++) {
                            int j = (z0 + z) * S + x0 + x;
                            data[z * w + x] = WorldTextures.heightTexel(WorldTextures.hFloor[j], Digging.digWin[j]);
                        }
                    GLUtil.subTexture(heightTex, data, x0, z0, w, d);
                }
        }
        Digging.clearDirty();
    }

    /** Queue chunks around the player and upload the ones that are ready (a few per frame). */
    private void streamChunks() {
        double sx = px, sz = pz;
        if (previewSlot >= 0 && !Double.isNaN(previewX)) { sx = previewX; sz = previewZ; }
        if (reshadowPending) {
            reshadowPending = false;
            streamer.invalidateGround();
        }
        streamer.update(sx, sz);
        fresh2.clear();
        freshV.clear();
        streamer.drain(fresh2, freshV, 6);
        if (fresh2.isEmpty() && freshV.isEmpty()) return;
        final int N = WorldTextures.CHUNK_PX, S = WorldTextures.GROUND_SIZE;
        for (WorldTextures.Chunk2D c : fresh2) {
            int ox = Math.floorMod(c.cx * N, S), oy = Math.floorMod(c.cz * N, S);
            GLUtil.subTexture(groundTex, c.ground, ox, oy, N, N);
            GLUtil.subLuminance(detailTex, c.detail, ox, oy, N, N);
            GLUtil.subLuminance(matTex, c.mat, ox, oy, N, N);
            int[] h = new int[64 * 64];
            for (int i = 0; i < h.length; i++) h[i] = WorldTextures.heightTexel(c.heights[i], c.dig[i]);
            GLUtil.subTexture(heightTex, h, Math.floorMod(c.cx * 64, WorldTextures.HEIGHT_SIZE), Math.floorMod(c.cz * 64, WorldTextures.HEIGHT_SIZE), 64, 64);
            for (int j = 0; j < 2; j++)
                for (int i = 0; i < 2; i++) {
                    byte[] t = new byte[4];
                    System.arraycopy(c.trees, (j * 2 + i) * 4, t, 0, 4);
                    GLUtil.subCells(treeTex, t, Math.floorMod(c.cx * 2 + i, WorldTextures.TREE_GRID), Math.floorMod(c.cz * 2 + j, WorldTextures.TREE_GRID), 1, 1);
                }
        }
        if (!fresh2.isEmpty()) GLUtil.remip(groundTex);
        final int C = Voxels.CHUNK_V;
        for (Voxels.VolChunk c : freshV)
            GLUtil.subTexture3D(volTex, c.rg, Math.floorMod(c.cx * C, Voxels.NX), 0, Math.floorMod(c.cz * C, Voxels.NZ), C, Voxels.NY, C);
    }

    private void updatePlayer(float dt) {
        input.consumeLook(look);
        yaw += look[0] * LOOK_SENS;
        pitch -= look[1] * LOOK_SENS;
        pitch = Math.max(-1.1f, Math.min(1.1f, pitch));
        if (yaw > Math.PI) yaw -= (float) (Math.PI * 2);
        if (yaw < -Math.PI) yaw += (float) (Math.PI * 2);

        float mx = input.moveX, my = input.moveY;
        float mag = Math.min(1f, (float) Math.sqrt(mx * mx + my * my));
        if (mag > 0.02f) {
            float s = (float) Math.sin(yaw), c = (float) Math.cos(yaw);
            // forward = (sin yaw, -cos yaw), right = (cos yaw, sin yaw) — matches the shader
            double vx = (s * my + c * mx) * WALK_SPEED;
            double vz = (-c * my + s * mx) * WALK_SPEED;
            tryMove(vx * dt, 0);
            tryMove(0, vz * dt);
            bobPhase += dt * (5.5f + 4.5f * mag);
        }
        float floor = groundAt(px, pz);
        float want = floor + EYE_HEIGHT;
        float ceil = Voxels.ceilingAbove(px, floor + 0.3, pz);
        if (want > ceil - 0.25f) want = ceil - 0.25f;
        eyeY = Float.isNaN(eyeY) ? want : eyeY + (want - eyeY) * Math.min(1f, dt * 14f);
        float target = mag > 0.02f ? mag : 0f;
        bobAmt += (target - bobAmt) * Math.min(1f, dt * 6f);
        // the land ends at the mountains, 16 km out from the village each way
        double lim = WorldTextures.WORLD_HALF - 60.0;
        px = Math.max(WorldTextures.CENTRE_X - lim, Math.min(WorldTextures.CENTRE_X + lim, px));
        pz = Math.max(WorldTextures.CENTRE_Z - lim, Math.min(WorldTextures.CENTRE_Z + lim, pz));
    }

    /** Terrain plus fine relief (rocks, shrubs, walls) under a point. */
    /**
     * The ground under you: the first rock surface below your feet (allowing a step up), so in a
     * tunnel it's the tunnel floor and on a ridge it's the ridge. On the open-air ground the 2D
     * relief (rocks, logs, walls) rides on top, as in the shader.
     */
    private float groundAt(double x, double z) {
        float top = WorldTextures.floorAt(x, z);
        double from = Float.isNaN(eyeY) ? top + 1.0 : eyeY - EYE_HEIGHT + 0.7;
        float f = Voxels.floorBelow(x, from, z);
        // the open-air ground's relief fades in as a tunnel floor rises to meet it (as in the shader)
        float k = Math.max(0f, Math.min(1f, (f - top + 0.7f) / 0.4f));
        k = k * k * (3f - 2f * k);
        f = f + (Math.max(f, top) + WorldTextures.detailAt(x, z) - f) * k;   
        return f;
    }

    /** Walk unless the ground ahead is too steep (a wall) or the rock overhead is too low. */
    private void tryMove(double mx, double mz) {
        double len = Math.sqrt(mx * mx + mz * mz);
        if (len < 1e-6) return;
        double nx = px + mx, nz = pz + mz;
        // probe a little ahead so we stop short of walls instead of clipping into them
        double ax = px + mx / len * 0.35, az = pz + mz / len * 0.35;
        float here = groundAt(px, pz);
        float ahead = groundAt(ax, az);
        // step up anything up to a stair's height (tunnel lips, rocks, banks); taller is a wall
        if (ahead - here > MAX_STEP_UP) return;
        float ceil = Voxels.ceilingAbove(ax, ahead + 0.3, az);
        if (ceil - ahead < MIN_HEADROOM) return;
        float[] tr = WorldTextures.trunkNear(ax, az);           // tree trunks are solid
        TreeLibrary lib = library;
        if (tr != null && lib != null) {
            int sp = (int) tr[2];
            TreeSpec spec = lib.specs[sp];
            float sz = Math.min(tr[3], TreeLibrary.MAX_HALF_WIDTH / Math.max(0.1f, TreeShadows.halfWidth(lib.models[sp])));
            float rad = spec.get(TreeSpec.TRUNK_R) * (1f + 0.5f * spec.get(TreeSpec.FLARE)) * sz;
            double tx = ax - tr[0], tz = az - tr[1];
            if (tx * tx + tz * tz < (rad + 0.3) * (rad + 0.3)) return;
        }
        px = nx;
        pz = nz;
    }

    private void bindTex3D(int unit, int tex, String name) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
        GLES20.glBindTexture(GLES30.GL_TEXTURE_3D, tex);
        GLES20.glUniform1i(u(sceneProg, name), unit);
    }

    /** Read the voxel hands: header, then the two distance volumes and the colour volume. */
    private void loadHands() {
        try (java.io.DataInputStream in = new java.io.DataInputStream(
                new java.util.zip.GZIPInputStream(assets.open("hands/hands.bin.gz"), 1 << 16))) {
            byte[] h = new byte[28];
            in.readFully(h);
            ByteBuffer hb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
            handNx = hb.getInt();
            handNy = hb.getInt();
            handNz = hb.getInt();
            handCell = hb.getFloat();
            handX0 = hb.getFloat();
            handY0 = hb.getFloat();
            handZ0 = hb.getFloat();
            int n = handNx * handNy * handNz;
            handA = readDirect(in, n * 4);
            handB = readDirect(in, n * 2);
            handC = readDirect(in, n * 4);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("missing hands/hands.bin.gz", e);
        }
    }

    private static ByteBuffer readDirect(java.io.DataInputStream in, int n) throws java.io.IOException {
        byte[] b = new byte[n];
        in.readFully(b);
        ByteBuffer bb = ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder());
        bb.put(b).position(0);
        return bb;
    }

    private void bindTexArray(int unit, int tex, String name) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
        GLES20.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, tex);
        GLES20.glUniform1i(u(sceneProg, name), unit);
    }

    private void bindTex(int unit, int tex, String name) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
        GLES20.glUniform1i(u(sceneProg, name), unit);
    }

    private void drawQuad() {
        GLES20.glEnableVertexAttribArray(0);
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private int u(int prog, String name) {
        HashMap<String, Integer> map = prog == sceneProg ? sceneU : blitU;
        Integer v = map.get(name);
        if (v == null) {
            v = GLES20.glGetUniformLocation(prog, name);
            map.put(name, v);
        }
        return v;
    }

    private static double mod(double a, double m) {
        double r = a % m;
        return r < 0 ? r + m : r;
    }
}
