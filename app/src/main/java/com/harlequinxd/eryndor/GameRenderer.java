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
    /** Lines actually rendered: phones start lower, and it follows the frame rate (120..240). */
    private int lines = GLUtil.MOBILE ? 180 : LOW_RES_LINES;
    private float frameAcc;
    private int frameCount;
    private static final float FOV_Y = (float) Math.toRadians(68);
    private static final float EYE_HEIGHT = 1.68f;
    private static final float MAX_STEP_UP = 0.55f;     // tallest step the player climbs without jumping
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
    volatile TreeLibrary library;
    private int treePrimTex, treeCellTex, treeIdxTex, leafAtlasTex, uploadedTreeVersion = -1;
    // first-person arms: colour / normal / height sheets (decoded off the GL thread)
    // first-person hands as voxels (tools/build_hands.py): two distance volumes and a colour volume
    // the player: one voxel woman rigged to a skeleton (tools/build_woman.py), seen from behind
    private final Woman woman = new Woman();
    private int womanDTex, womanCTex, womanBTex;
    private int townTex;
    private float charYaw = 0f, walkPhase, walkAmt;
    private double lastPx = Double.NaN, lastPz;
    private double camDist = 2.8;
    // terrain volume (tunnels, caves, overhangs): 3D RG8, distance + sky openness
    // ground texture sets, one array layer each (order = layer index in scene.frag):
    // colour, and normal map + height packed together; tiling, point sampled
    static final String[] SURFACES = {"grass", "cobble", "dirt", "rock", "forest", "gravel", "sand", "snow",
            "moss", "needles", "roots", "mud", "forestgrass", "cliff", "mossyrock",
            // the player's detail maps (height + normal), traced with parallax on her clothes and skin
            "w_shirt", "w_cargo", "w_leather", "w_skin", "w_hair"};
    private int surfColTex, surfNHTex;
    private int[][] surfCol, surfNH;
    private int surfSize;
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
        try (InputStream rig = assets.open("woman/rig.txt"); InputStream data = assets.open("woman/woman.bin.gz")) {
            woman.load(rig, data);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("missing woman/", e);
        }
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
        streamer.drain(null, 1 << 20);                     // anything finished since loading
        groundTex = GLUtil.texture(WorldTextures.groundWin, WorldTextures.GROUND_SIZE, true);    // point sampled
        heightTex = GLUtil.texture(WorldTextures.heightTexture(), WorldTextures.HEIGHT_SIZE, false, true);
        Loading.set("Uploading the fine relief", 0.94f);
        detailTex = GLUtil.luminance(WorldTextures.detailWin, WorldTextures.GROUND_SIZE);
        matTex = GLUtil.luminance(WorldTextures.matWin, WorldTextures.GROUND_SIZE, true);
        fernTex = GLUtil.cells(WorldTextures.fernWin, WorldTextures.FERN_GRID);
        treeTex = GLUtil.cells(WorldTextures.treeWin, WorldTextures.TREE_GRID);
        townTex = GLUtil.cells(Town.texture(), Town.N);
        cloudTex = GLUtil.texture(cloudData, WorldTextures.CLOUD_SIZE, false);
        leafAtlasTex = GLUtil.atlas(LeafAtlas.build(), LeafAtlas.W, LeafAtlas.H);
        womanDTex = GLUtil.texture3D(woman.dist, woman.nx, woman.ny, woman.nz, GLES30.GL_R8, GLES30.GL_RED, true);
        womanCTex = GLUtil.texture3D(woman.colour, woman.nx, woman.ny, woman.nz, GLES30.GL_RGBA8, GLES20.GL_RGBA, true);
        womanBTex = GLUtil.texture3D(woman.skin, woman.nx, woman.ny, woman.nz, GLES30.GL_RGBA8, GLES20.GL_RGBA, false);
        woman.dist = woman.colour = woman.skin = null;
        surfColTex = GLUtil.textureArray(surfCol, surfSize);    // repeating, point sampled
        surfNHTex = GLUtil.textureArray(surfNH, surfSize);
        surfCol = surfNH = null;
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
        int lh = lines;
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
            if (fbo == 0 && scrW > 0) createFbo(Math.max(1, Math.round(lines * (float) scrW / scrH)), lines);
        }
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        time += dt;
        if (time > 3600f) time -= 3600f;
        adaptResolution(dt);

        if (library != null && library.version != uploadedTreeVersion) uploadTrees();
        int slot = previewSlot;
        streamChunks();
        if (slot < 0) {
            previewX = Double.NaN;
            if (streamer.ready(px, pz)) updatePlayer(dt);
            else input.consumeLook(look);
            animateCharacter(dt);
        } else input.consumeLook(look);
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
        float camY = eyeY;
        boolean third = slot < 0 && !Float.isNaN(eyeY);
        if (third) {
            // behind her and a little above, over her right shoulder; pulled in where rock is in the way
            double tx = px, ty = eyeY - EYE_HEIGHT + 1.55, tz = pz;
            double cpt = Math.cos(pitch);
            double fx = Math.sin(yaw) * cpt, fy = Math.sin(pitch), fz = -Math.cos(yaw) * cpt;
            tx += Math.cos(yaw) * 0.36;
            tz += Math.sin(yaw) * 0.36;
            double want = 2.8, dist = 0.3;
            while (dist < want) {
                double qx = tx - fx * dist, qz = tz - fz * dist;
                double qy = ty - fy * dist;
                if (qy - (WorldTextures.floorAt(qx, qz) + WorldTextures.detailAt(qx, qz)) < 0.3) break;
                if (Town.dist(qx, qy, qz) < 0.3f) break;                // nor through the town's walls
                dist += 0.1;
            }
            dist = Math.max(0.3, dist - 0.1);
            camDist += (dist - camDist) * (dist < camDist ? 1f : Math.min(1f, dt * 4f));
            camX = tx - fx * camDist;
            camY = (float) (ty - fy * camDist);
            camZ = tz - fz * camDist;
        }
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
        GLES20.glActiveTexture(GLES20.GL_TEXTURE4);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, townTex);
        GLES20.glUniform1i(u(sceneProg, "uTown"), 4);
        GLES20.glUniform3f(u(sceneProg, "uTownO"), (float) (Town.X0 - offX), (float) (Town.Z0 - offZ), Town.GROUND);
        GLES20.glUniform3f(u(sceneProg, "uPlayer"), (float) (px - offX), Float.isNaN(eyeY) ? 0f : eyeY - EYE_HEIGHT, (float) (pz - offZ));
        bindTex3D(11, womanDTex, "uWomanD");
        bindTex3D(12, womanCTex, "uWomanC");
        bindTex3D(13, womanBTex, "uWomanB");
        bindTexArray(14, surfColTex, "uSurfCol");
        bindTexArray(15, surfNHTex, "uSurfNH");
        // (when a wall pins the camera right behind her, she steps out of the way: a first-person view)
        GLES20.glUniform1f(u(sceneProg, "uCharShow"), third && camDist > 0.8 ? 1f : 0f);
        GLES20.glUniform1f(u(sceneProg, "uCamNear"), third ? (float) Math.max(0.0, camDist - 0.7) : 0f);
        if (third) {
            woman.pose(px, eyeY - EYE_HEIGHT, pz, charYaw, walkAmt, walkPhase, time, offX, offZ);
            GLES20.glUniform4fv(u(sceneProg, "uSkin"), Woman.BONES * 3, woman.skinM, 0);
            GLES20.glUniform4fv(u(sceneProg, "uBoneA"), Woman.BONES, woman.boneA, 0);
            GLES20.glUniform4fv(u(sceneProg, "uBoneB"), Woman.BONES, woman.boneB, 0);
            GLES20.glUniform4fv(u(sceneProg, "uCharSphere"), 1, woman.sphere, 0);
            GLES20.glUniform3f(u(sceneProg, "uWomanMin"), woman.minX - woman.cell * 0.5f, woman.minY - woman.cell * 0.5f, woman.minZ - woman.cell * 0.5f);
            GLES20.glUniform3f(u(sceneProg, "uWomanSize"), woman.nx * woman.cell, woman.ny * woman.cell, woman.nz * woman.cell);
            GLES20.glUniform3f(u(sceneProg, "uWomanDims"), woman.nx, woman.ny, woman.nz);
        }
        GLES20.glUniform3f(u(sceneProg, "uCam"), (float) (camX - offX), camY, (float) (camZ - offZ));
        GLES20.glUniform2f(u(sceneProg, "uCloudO"), (float) mod(px + windX, 2000.0), (float) mod(pz + windZ, 2000.0));
        GLES20.glUniform3fv(u(sceneProg, "uSunDir"), 1, sky.sunDir, 0);
        // an alien sky: the blue turned toward teal overhead, the horizon toward rose and peach
        GLES20.glUniform3fv(u(sceneProg, "uSkyTop"), 1, hueTurn(sky.skyTop, -0.95f, 1.15f), 0);
        GLES20.glUniform3fv(u(sceneProg, "uSkyHor"), 1, hueTurn(sky.skyHor, 2.6f, 1.35f), 0);
        GLES20.glUniform3fv(u(sceneProg, "uSunCol"), 1, sky.sunCol, 0);
        GLES20.glUniform3fv(u(sceneProg, "uAmbient"), 1, sky.ambient, 0);
        GLES20.glUniform3fv(u(sceneProg, "uFogCol"), 1, hueTurn(sky.fogCol, 2.6f, 1.25f), 0);
        GLES20.glUniform3fv(u(sceneProg, "uMtnCol"), 1, hueTurn(sky.mtnCol, 2.2f, 1.3f), 0);
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


    /** Window start and length of a cell range, split where the window wraps: (start, len) pairs. */
    private static int[] segments(int a0, int a1, int n) {
        int len = Math.min(n, a1 - a0 + 1), s = Math.floorMod(a0, n);
        return s + len <= n ? new int[]{s, len} : new int[]{s, n - s, 0, len - (n - s)};
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
        streamer.drain(fresh2, 12);
        if (fresh2.isEmpty()) return;
        final int N = WorldTextures.CHUNK_PX, S = WorldTextures.GROUND_SIZE;
        for (WorldTextures.Chunk2D c : fresh2) {
            int ox = Math.floorMod(c.cx * N, S), oy = Math.floorMod(c.cz * N, S);
            GLUtil.subTexture(groundTex, c.ground, ox, oy, N, N);
            GLUtil.subLuminance(detailTex, c.detail, ox, oy, N, N);
            GLUtil.subLuminance(matTex, c.mat, ox, oy, N, N);
            final int H = WorldTextures.HPC, TP = WorldTextures.TPC;
            int[] h = new int[H * H];
            for (int i = 0; i < h.length; i++) h[i] = WorldTextures.heightTexel(c.heights[i]);
            GLUtil.subTexture(heightTex, h, Math.floorMod(c.cx * H, WorldTextures.HEIGHT_SIZE), Math.floorMod(c.cz * H, WorldTextures.HEIGHT_SIZE), H, H);
            for (int j = 0; j < TP; j++)
                for (int i = 0; i < TP; i++) {
                    byte[] t = new byte[4];
                    System.arraycopy(c.trees, (j * TP + i) * 4, t, 0, 4);
                    GLUtil.subCells(treeTex, t, Math.floorMod(c.cx * TP + i, WorldTextures.TREE_GRID), Math.floorMod(c.cz * TP + j, WorldTextures.TREE_GRID), 1, 1);
                }
        }
        if (!fresh2.isEmpty()) GLUtil.remip(groundTex);
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
        eyeY = Float.isNaN(eyeY) ? want : eyeY + (want - eyeY) * Math.min(1f, dt * 14f);
        float target = mag > 0.02f ? mag : 0f;
        bobAmt += (target - bobAmt) * Math.min(1f, dt * 6f);
        // the land ends at the mountains, 16 km out from the village each way
        double lim = WorldTextures.WORLD_HALF - 60.0;
        px = Math.max(WorldTextures.CENTRE_X - lim, Math.min(WorldTextures.CENTRE_X + lim, px));
        pz = Math.max(WorldTextures.CENTRE_Z - lim, Math.min(WorldTextures.CENTRE_Z + lim, pz));
    }

    /** The Mode 7 ground under a point: the heightmap plus the fine relief (rocks, logs, walls). */
    private float groundAt(double x, double z) {
        return WorldTextures.floorAt(x, z) + WorldTextures.detailAt(x, z);
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
        // the town's walls are solid (doorways are open, the doors slide aside as she nears them)
        if (Town.dist(ax, ahead + 0.4, az) < 0.25f || Town.dist(ax, ahead + 1.4, az) < 0.25f) return;
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

    /**
     * Keep it smooth: every second or so, render fewer lines if frames are slow (under ~30 fps) and
     * more again when there is room, between 120 and 240 lines (the image always fills the screen).
     */
    private void adaptResolution(float dt) {
        frameAcc += dt;
        if (++frameCount < 40) return;
        float avg = frameAcc / frameCount;
        frameAcc = 0f;
        frameCount = 0;
        int want = lines;
        if (avg > 1f / 30f) want = Math.max(120, lines - 20);
        else if (avg < 1f / 50f) want = Math.min(LOW_RES_LINES, lines + 20);
        if (want != lines && scrW > 0) {
            lines = want;
            createFbo(Math.max(1, Math.round(lines * (float) scrW / scrH)), lines);
        }
    }

    /** Her heading follows where she walks; the stride follows how far she has gone. */
    private void animateCharacter(float dt) {
        if (Double.isNaN(lastPx)) { lastPx = px; lastPz = pz; }
        double vx = (px - lastPx) / Math.max(dt, 1e-4), vz = (pz - lastPz) / Math.max(dt, 1e-4);
        lastPx = px;
        lastPz = pz;
        float speed = (float) Math.sqrt(vx * vx + vz * vz);
        if (speed > 0.3f) {
            float want = (float) Math.atan2(vx, -vz);
            float dy = want - charYaw;
            while (dy > Math.PI) dy -= (float) (Math.PI * 2);
            while (dy < -Math.PI) dy += (float) (Math.PI * 2);
            charYaw += dy * Math.min(1f, dt * 9f);
        }
        walkAmt += (Math.min(1f, speed / WALK_SPEED) - walkAmt) * Math.min(1f, dt * 8f);
        walkPhase += dt * speed / 1.45f * (float) Math.PI;
        if (walkPhase > 1000f) walkPhase -= (float) (Math.PI * 200);
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

    /** Turn a colour round the hue circle (about the grey axis) and push its saturation. */
    private static float[] hueTurn(float[] c, float a, float sat) {
        double k = 0.5773503, ca = Math.cos(a), sa = Math.sin(a), d = k * (c[0] + c[1] + c[2]);
        double cx = k * (c[1] - c[2]), cy = k * (c[2] - c[0]), cz = k * (c[0] - c[1]);   // cross(k, c)
        float[] o = new float[3];
        float l = 0.3f * c[0] + 0.59f * c[1] + 0.11f * c[2];
        for (int i = 0; i < 3; i++) {
            double cr = i == 0 ? cx : (i == 1 ? cy : cz);
            double v = c[i] * ca + cr * sa + k * d * (1 - ca);
            o[i] = (float) Math.max(0.0, Math.min(1.5, l + (Math.max(0.0, v) - l) * sat));
        }
        return o;
    }

    private static double mod(double a, double m) {
        double r = a % m;
        return r < 0 ? r + m : r;
    }
}
