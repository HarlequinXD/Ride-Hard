package com.harlequinxd.eryndor;

import android.content.res.AssetManager;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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
    private static final int LOW_RES_LINES = 160;
    private static final float FOV_Y = (float) Math.toRadians(68);
    private static final float EYE_HEIGHT = 1.68f;
    private static final float MAX_SLOPE = 1.1f;       // rise per metre the player can climb
    private static final float MIN_HEADROOM = 1.95f;
    private static final float WALK_SPEED = 4.2f;      // m/s at full stick
    private static final float LOOK_SENS = 0.0065f;    // radians per dp
    private static final int GROUND_CACHE_VERSION = 11;

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

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        Loading.set("Waking the realm", 0.01f);
        String vs = GLUtil.asset(assets, "shaders/fullscreen.vert");
        sceneProg = GLUtil.program(vs, GLUtil.asset(assets, "shaders/scene.frag"));
        blitProg = GLUtil.program(vs, GLUtil.asset(assets, "shaders/blit.frag"));
        sceneU.clear();
        blitU.clear();

        int[] heights = WorldTextures.heightTexture();             // shaping the land, carving Hollow Hill
        int[] ground = loadGround();                                // painting (or reading the saved world)
        Loading.set("Uploading the land", 0.84f);
        groundTex = GLUtil.texture(ground, WorldTextures.GROUND_SIZE, false);
        heightTex = GLUtil.texture(heights, WorldTextures.HEIGHT_SIZE, false, true);
        Loading.set("Uploading the fine relief", 0.89f);
        detailTex = GLUtil.luminance(WorldTextures.detail(), WorldTextures.GROUND_SIZE);
        matTex = GLUtil.luminance(WorldTextures.materials(), WorldTextures.GROUND_SIZE, true);
        fernTex = GLUtil.cells(WorldTextures.ferns(), WorldTextures.FERN_GRID);
        treeTex = GLUtil.cells(WorldTextures.trees(), WorldTextures.TREE_GRID);
        Loading.set("Gathering the clouds", 0.94f);
        cloudTex = GLUtil.texture(WorldTextures.clouds(), WorldTextures.CLOUD_SIZE, false);
        Loading.set("Lighting the lanterns", 0.98f);
        eyeY = Float.NaN;

        float[] q = {-1, -1, 1, -1, -1, 1, 1, 1};
        quad = ByteBuffer.allocateDirect(q.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(q).position(0);
        fbo = 0;
        fboW = fboH = 0;
        lastNanos = System.nanoTime();
        Loading.set("Ready", 1f);
        sky.worldReady = true;
    }

    /** The ground takes a few seconds to paint, so colours + detail heights are cached on disk. */
    private int[] loadGround() {
        int n = WorldTextures.GROUND_SIZE * WorldTextures.GROUND_SIZE;
        File f = new File(cacheDir, "ground_v" + GROUND_CACHE_VERSION + ".bin");
        File[] old = cacheDir.listFiles((dir, name) -> name.startsWith("ground_v") && !name.equals(f.getName()));
        if (old != null) for (File o : old) o.delete();
        int fb = WorldTextures.FERN_GRID * WorldTextures.FERN_GRID * 4;
        int tb = WorldTextures.TREE_GRID * WorldTextures.TREE_GRID * 4;
        if (f.length() == n * 6L + fb + tb) {
            Loading.set("Reading the saved world", 0.12f);
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] bytes = new byte[n * 6 + fb + tb];
                int off = 0, r;
                while (off < bytes.length && (r = in.read(bytes, off, Math.min(1 << 20, bytes.length - off))) > 0) {
                    off += r;
                    Loading.within(0.12f, 0.80f, off / (float) bytes.length);
                }
                if (off == bytes.length) {
                    int[] px = new int[n];
                    ByteBuffer.wrap(bytes, 0, n * 4).asIntBuffer().get(px);
                    byte[] det = new byte[n];
                    System.arraycopy(bytes, n * 4, det, 0, n);
                    byte[] mat = new byte[n];
                    System.arraycopy(bytes, n * 5, mat, 0, n);
                    byte[] fern = new byte[fb];
                    System.arraycopy(bytes, n * 6, fern, 0, fb);
                    byte[] tree = new byte[tb];
                    System.arraycopy(bytes, n * 6 + fb, tree, 0, tb);
                    WorldTextures.setCached(px, det, fern, mat, tree);
                    return px;
                }
            } catch (Exception ignored) {
            }
        }
        int[] px = WorldTextures.ground();
        byte[] det = WorldTextures.detail();
        byte[] fern = WorldTextures.ferns();
        byte[] mat = WorldTextures.materials();
        byte[] tree = WorldTextures.trees();
        Loading.set("Saving the world for next time", 0.81f);
        try (FileOutputStream out = new FileOutputStream(f)) {
            ByteBuffer bb = ByteBuffer.allocate(n * 6 + fb + tb);
            bb.asIntBuffer().put(px);
            bb.position(n * 4);
            bb.put(det);
            bb.put(mat);
            bb.put(fern);
            bb.put(tree);
            out.write(bb.array());
        } catch (Exception ignored) {
        }
        return px;
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
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        time += dt;
        if (time > 3600f) time -= 3600f;

        updatePlayer(dt);
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
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fernTex);
        GLES20.glUniform1i(u(sceneProg, "uFerns"), 4);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE5);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, matTex);
        GLES20.glUniform1i(u(sceneProg, "uMat"), 5);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE6);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, treeTex);
        GLES20.glUniform1i(u(sceneProg, "uTrees"), 6);

        float tanY = (float) Math.tan(FOV_Y / 2);
        GLES20.glUniform2f(u(sceneProg, "uTanHalf"), tanY * fboW / fboH, tanY);
        GLES20.glUniform1f(u(sceneProg, "uYaw"), yaw);
        GLES20.glUniform1f(u(sceneProg, "uPitch"), pitch);
        GLES20.glUniform3f(u(sceneProg, "uCam"), (float) mod(px, 256.0),
                eyeY + bobAmt * (float) Math.sin(bobPhase) * 0.045f, (float) mod(pz, 256.0));
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
        drawQuad();
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
        float ceil = WorldTextures.ceilingAt(px, pz);
        if (want > ceil - 0.25f) want = ceil - 0.25f;
        eyeY = Float.isNaN(eyeY) ? want : eyeY + (want - eyeY) * Math.min(1f, dt * 14f);
        float target = mag > 0.02f ? mag : 0f;
        bobAmt += (target - bobAmt) * Math.min(1f, dt * 6f);
        // keep doubles small; the world tiles every 256 m and the clouds every 2000 m
        // 32000 m is a multiple of both tile sizes, so the wrap is invisible
        if (Math.abs(px) > 64000.0) px = mod(px, 32000.0);
        if (Math.abs(pz) > 64000.0) pz = mod(pz, 32000.0);
    }

    /** Terrain plus fine relief (rocks, shrubs, walls) under a point. */
    private static float groundAt(double x, double z) {
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
        if ((ahead - here) / 0.35f > MAX_SLOPE) return;
        float ceil = WorldTextures.ceilingAt(ax, az);
        if (ceil - ahead < MIN_HEADROOM) return;
        float[] tr = WorldTextures.trunkNear(ax, az);           // tree trunks are solid
        if (tr != null) {
            double tx = ax - tr[0], tz = az - tr[1];
            if (tx * tx + tz * tz < (tr[2] + 0.3) * (tr[2] + 0.3)) return;
        }
        px = nx;
        pz = nz;
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
