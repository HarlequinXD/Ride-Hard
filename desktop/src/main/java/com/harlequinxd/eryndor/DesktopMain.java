package com.harlequinxd.eryndor;

import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;

import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWGamepadState;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Chronicles of Eryndor on Windows / Linux: a GLFW window running the same GameRenderer as the
 * Android app, with an Xbox controller (any controller GLFW maps as a gamepad), or keyboard and mouse.
 */
public final class DesktopMain {

    static final String TITLE = "Chronicles of Eryndor: Hollowspire";
    /** GameRenderer turns one look unit into this many radians. */
    private static final float LOOK_SENS = 0.0065f;
    private static final float MOUSE_SENS = 0.36f;          // look units per mouse count
    private static final float STICK_TURN = 3.1f;           // radians per second at full right stick
    private static final float DEADZONE = 0.18f;

    private final File dataDir;
    private final Input input = new Input();
    private final SkyState sky = new SkyState();
    private GameRenderer renderer;
    private Overlay overlay;
    private long window;

    // window / input state
    private int fbW, fbH;
    private boolean resized = true, fullscreen, mouseCaptured, helpShown = true, invertY;
    private int winX, winY, winW = 1280, winH = 720;
    private double lastMouseX = Double.NaN, lastMouseY;
    private long lastPadUse, lastMouseUse;
    private final boolean[] padPrev = new boolean[GLFW_GAMEPAD_BUTTON_LAST + 1];
    private final GLFWGamepadState pad = GLFWGamepadState.create();
    private long readyAt;
    // ERYNDOR_SHOTS=<folder> saves a screenshot every 5 s (for testing)
    private final String shotDir = System.getenv("ERYNDOR_SHOTS");
    private long nextShot;
    private int shotIndex;

    private void screenshot(File f) {
        java.nio.ByteBuffer px = org.lwjgl.BufferUtils.createByteBuffer(fbW * fbH * 4);
        org.lwjgl.opengl.GL11.glReadPixels(0, 0, fbW, fbH, org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, px);
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(fbW, fbH, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < fbH; y++) {
            for (int x = 0; x < fbW; x++) {
                int i = ((fbH - 1 - y) * fbW + x) * 4;
                img.setRGB(x, y, (px.get(i) & 255) << 16 | (px.get(i + 1) & 255) << 8 | (px.get(i + 2) & 255));
            }
        }
        try {
            javax.imageio.ImageIO.write(img, "png", f);
            System.err.println("screenshot " + f + " glError " + org.lwjgl.opengl.GL11.glGetError());
        } catch (java.io.IOException e) {
            e.printStackTrace();
        }
    }

    private DesktopMain(File dataDir) {
        this.dataDir = dataDir;
    }

    public static void main(String[] args) {
        File dir = dataDir();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        try {
            OutputStream file = new FileOutputStream(new File(dir, "eryndor.log"));
            PrintStream console = System.err;
            System.setErr(new PrintStream(new OutputStream() {
                @Override public void write(int b) throws java.io.IOException { file.write(b); console.write(b); }
                @Override public void write(byte[] b, int o, int l) throws java.io.IOException { file.write(b, o, l); console.write(b, o, l); }
                @Override public void flush() throws java.io.IOException { file.flush(); console.flush(); }
            }, true));
        } catch (Exception ignored) {
            // no log file; stderr still works
        }
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            System.err.println("Uncaught in " + t.getName());
            e.printStackTrace();
        });
        try {
            new DesktopMain(dir).run();
        } catch (Throwable t) {
            t.printStackTrace();
            try {
                javax.swing.JOptionPane.showMessageDialog(null,
                        "The game stopped: " + t + "\n\nDetails are in " + new File(dir, "eryndor.log"),
                        TITLE, javax.swing.JOptionPane.ERROR_MESSAGE);
            } catch (Throwable ignored) {
                // headless
            }
        }
        System.exit(0);    // the world builder thread may still be running
    }

    /** Saved world cache, tree designs and the log: %APPDATA%\ChroniclesOfEryndor or ~/.eryndor. */
    private static File dataDir() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isEmpty()) return new File(appData, "ChroniclesOfEryndor");
        return new File(System.getProperty("user.home"), ".eryndor");
    }

    private void run() {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("Could not start GLFW");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        // core profile, the same everywhere (the GL stand-ins avoid everything core removed)
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        window = glfwCreateWindow(winW, winH, TITLE, MemoryUtil.NULL, MemoryUtil.NULL);
        if (window == MemoryUtil.NULL) {
            throw new IllegalStateException("This PC's graphics driver does not offer OpenGL 3.3. Updating the graphics driver usually fixes this.");
        }
        GLFWVidMode mode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        if (mode != null) {
            winX = (mode.width() - winW) / 2;
            winY = (mode.height() - winH) / 2;
            glfwSetWindowPos(window, winX, winY);
        }
        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        GL.createCapabilities();
        android.opengl.GLES20.ensureVao();
        System.err.println("OpenGL " + org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION)
                + " on " + org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER));

        glfwSetFramebufferSizeCallback(window, (w, width, height) -> resized = true);
        glfwSetKeyCallback(window, (w, key, sc, action, mods) -> onKey(key, action, mods));
        glfwSetMouseButtonCallback(window, (w, button, action, mods) -> {
            if (button == GLFW_MOUSE_BUTTON_LEFT) {
                if (action == GLFW_PRESS && !mouseCaptured) captureMouse(true);
                else if (mouseCaptured) mouseSwing = action != GLFW_RELEASE;   // swing the pickaxe
                if (action == GLFW_RELEASE) mouseSwing = false;
            }
        });
        glfwSetCursorPosCallback(window, (w, x, y) -> onMouse(x, y));
        glfwSetWindowFocusCallback(window, (w, focused) -> {
            if (!focused) captureMouse(false);
        });
        glfwShowWindow(window);

        // testing: ERYNDOR_HOUR=21.5 sets the clock, ERYNDOR_WEATHER=n steps the weather n times
        String hour = System.getenv("ERYNDOR_HOUR"), weather = System.getenv("ERYNDOR_WEATHER");
        if (hour != null) sky.hour = Float.parseFloat(hour);
        if (weather != null) for (int i = Integer.parseInt(weather); i > 0; i--) sky.requestNextWeather();
        renderer = new GameRenderer(input, sky, new AssetManager(), dataDir);
        String pos = System.getenv("ERYNDOR_POS");             // testing: ERYNDOR_POS=x,z
        if (pos != null) {
            String[] p = pos.split(",");
            if (p.length > 2) renderer.teleport(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Float.parseFloat(p[2]));
            else renderer.teleport(Double.parseDouble(p[0]), Double.parseDouble(p[1]));
        }
        renderer.onSurfaceCreated(null, null);
        overlay = new Overlay();

        long last = System.nanoTime();
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();
            long now = System.nanoTime();
            float dt = Math.min(0.1f, (now - last) / 1e9f);
            last = now;

            if (resized) {
                int[] w = new int[1], h = new int[1];
                glfwGetFramebufferSize(window, w, h);
                fbW = w[0];
                fbH = h[0];
                if (fbW > 0 && fbH > 0) renderer.onSurfaceChanged(null, fbW, fbH);
                resized = false;
            }
            if (fbW <= 0 || fbH <= 0) {       // minimised
                sleep(30);
                continue;
            }
            pollGamepad(dt);
            pollKeys();

            renderer.onDrawFrame(null);
            if (sky.worldReady && readyAt == 0) {
                readyAt = System.currentTimeMillis();
                // ERYNDOR_TURN=dx,dy turns the view once the world is ready (for testing), in degrees
                String turn = System.getenv("ERYNDOR_TURN");
                if ("sun".equals(turn)) {                  // face the sun, a little below it
                    float sx = sky.sunDir[0], sy = sky.sunDir[1], sz = sky.sunDir[2];
                    float ty = (float) Math.atan2(sx, -sz), tp = (float) Math.asin(sy) - 0.12f;
                    input.addLook(ty / LOOK_SENS, -(tp + 0.04f) / LOOK_SENS);
                    turn = null;
                }
                digTest = System.getenv("ERYNDOR_DIG") != null;     // testing: keep swinging the pickaxe
                if (turn != null) {
                    String[] p = turn.split(",");
                    input.addLook((float) Math.toRadians(Float.parseFloat(p[0])) / LOOK_SENS,
                            (float) Math.toRadians(Float.parseFloat(p[1])) / LOOK_SENS);
                }
            }
            overlay.draw(fbW, fbH, state());
            if (shotDir != null && System.currentTimeMillis() >= nextShot) {
                screenshot(new File(shotDir, String.format("shot_%03d.png", shotIndex++)));
                nextShot = System.currentTimeMillis() + 5000;
            }
            glfwSwapBuffers(window);
        }
        glfwDestroyWindow(window);
        glfwTerminate();
    }

    // ------------------------------------------------------------------ overlay state
    private Overlay.State state() {
        Overlay.State s = new Overlay.State();
        s.loading = !sky.worldReady;
        s.progress = Loading.progress;
        s.stage = Loading.stage;
        s.sinceReady = readyAt == 0 ? 0f : (System.currentTimeMillis() - readyAt) / 1000f;
        s.weather = SkyState.WEATHER_NAMES[Math.max(0, Math.min(SkyState.WEATHER_NAMES.length - 1, sky.weather))];
        s.fastTime = sky.fastTime;
        s.hour = sky.hour;
        s.help = helpShown;
        s.pad = lastPadUse > lastMouseUse;
        s.clickToLook = !mouseCaptured && !s.pad && sky.worldReady;
        s.invertY = invertY;
        s.sword = renderer.swordDrawn();
        return s;
    }

    // ------------------------------------------------------------------ keyboard and mouse
    private void onKey(int key, int action, int mods) {
        if (action != GLFW_PRESS) return;
        lastMouseUse = System.nanoTime();
        switch (key) {
            case GLFW_KEY_ESCAPE:
                if (mouseCaptured) captureMouse(false);
                else if (fullscreen) toggleFullscreen();
                break;
            case GLFW_KEY_F11:
                toggleFullscreen();
                break;
            case GLFW_KEY_ENTER:
                if ((mods & GLFW_MOD_ALT) != 0) toggleFullscreen();
                break;
            case GLFW_KEY_Q:
            case GLFW_KEY_1:
                sky.requestNextWeather();
                break;
            case GLFW_KEY_T:
            case GLFW_KEY_2:
                sky.fastTime = !sky.fastTime;
                break;
            case GLFW_KEY_F1:
            case GLFW_KEY_H:
                helpShown = !helpShown;
                break;
            case GLFW_KEY_I:
                invertY = !invertY;
                break;
            case GLFW_KEY_F:
                renderer.toggleSword();
                break;
            default:
                break;
        }
    }

    private void onMouse(double x, double y) {
        if (!mouseCaptured) {
            lastMouseX = Double.NaN;
            return;
        }
        if (!Double.isNaN(lastMouseX)) {
            float dx = (float) (x - lastMouseX), dy = (float) (y - lastMouseY);
            if (dx != 0 || dy != 0) {
                input.addLook(dx * MOUSE_SENS, (invertY ? -dy : dy) * MOUSE_SENS);
                lastMouseUse = System.nanoTime();
            }
        }
        lastMouseX = x;
        lastMouseY = y;
    }

    private void captureMouse(boolean on) {
        mouseCaptured = on;
        glfwSetInputMode(window, GLFW_CURSOR, on ? GLFW_CURSOR_DISABLED : GLFW_CURSOR_NORMAL);
        if (on && glfwRawMouseMotionSupported()) glfwSetInputMode(window, GLFW_RAW_MOUSE_MOTION, GLFW_TRUE);
        lastMouseX = Double.NaN;
        if (on) lastMouseUse = System.nanoTime();
    }

    private float keyMoveX, keyMoveY;

    private void pollKeys() {
        float mx = 0, my = 0;
        if (down(GLFW_KEY_W) || down(GLFW_KEY_UP)) my += 1;
        if (down(GLFW_KEY_S) || down(GLFW_KEY_DOWN)) my -= 1;
        if (down(GLFW_KEY_D) || down(GLFW_KEY_RIGHT)) mx += 1;
        if (down(GLFW_KEY_A) || down(GLFW_KEY_LEFT)) mx -= 1;
        float len = (float) Math.sqrt(mx * mx + my * my);
        if (len > 1) {
            mx /= len;
            my /= len;
        }
        keyMoveX = mx;
        keyMoveY = my;
        // keyboard and stick add up (stick written in pollGamepad), never beyond full speed
        float x = keyMoveX + padMoveX, y = keyMoveY + padMoveY;
        float l = (float) Math.sqrt(x * x + y * y);
        if (l > 1) {
            x /= l;
            y /= l;
        }
        input.moveX = x;
        input.swingHeld = mouseSwing || padSwing || digTest;
        input.moveY = y;
    }

    private boolean down(int key) {
        return glfwGetKey(window, key) == GLFW_PRESS;
    }

    private void toggleFullscreen() {
        long monitor = glfwGetPrimaryMonitor();
        GLFWVidMode mode = glfwGetVideoMode(monitor);
        if (mode == null) return;
        if (!fullscreen) {
            int[] x = new int[1], y = new int[1], w = new int[1], h = new int[1];
            glfwGetWindowPos(window, x, y);
            glfwGetWindowSize(window, w, h);
            winX = x[0];
            winY = y[0];
            winW = w[0];
            winH = h[0];
            glfwSetWindowMonitor(window, monitor, 0, 0, mode.width(), mode.height(), mode.refreshRate());
        } else {
            glfwSetWindowMonitor(window, MemoryUtil.NULL, winX, winY, winW, winH, GLFW_DONT_CARE);
        }
        fullscreen = !fullscreen;
        glfwSwapInterval(1);
        resized = true;
    }

    // ------------------------------------------------------------------ Xbox controller
    private float padMoveX, padMoveY;
    private volatile boolean mouseSwing;
    private boolean padSwing, digTest;

    private void pollGamepad(float dt) {
        padMoveX = padMoveY = 0;
        padSwing = false;
        int jid = -1;
        for (int j = GLFW_JOYSTICK_1; j <= GLFW_JOYSTICK_LAST; j++) {
            if (glfwJoystickPresent(j) && glfwJoystickIsGamepad(j)) {
                jid = j;
                break;
            }
        }
        if (jid < 0 || !glfwGetGamepadState(jid, pad)) return;

        // left stick walks, right stick looks (radial dead zone, then a gentle curve for aiming)
        float[] l = stick(pad.axes(GLFW_GAMEPAD_AXIS_LEFT_X), pad.axes(GLFW_GAMEPAD_AXIS_LEFT_Y), 1.0f);
        float[] r = stick(pad.axes(GLFW_GAMEPAD_AXIS_RIGHT_X), pad.axes(GLFW_GAMEPAD_AXIS_RIGHT_Y), 1.7f);
        padMoveX = l[0];
        padSwing = pad.axes(GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER) > 0.2f;   // RT swings the pickaxe
        padMoveY = -l[1];
        if (r[0] != 0 || r[1] != 0) {
            float turn = STICK_TURN * dt / LOOK_SENS;
            input.addLook(r[0] * turn, (invertY ? -r[1] : r[1]) * turn * 0.75f);
        }
        boolean active = l[0] != 0 || l[1] != 0 || r[0] != 0 || r[1] != 0;

        // buttons (on press)
        if (pressed(GLFW_GAMEPAD_BUTTON_Y)) sky.requestNextWeather();
        if (pressed(GLFW_GAMEPAD_BUTTON_X)) sky.fastTime = !sky.fastTime;
        if (pressed(GLFW_GAMEPAD_BUTTON_BACK) || pressed(GLFW_GAMEPAD_BUTTON_START)) helpShown = !helpShown;
        if (pressed(GLFW_GAMEPAD_BUTTON_RIGHT_THUMB)) invertY = !invertY;
        if (pressed(GLFW_GAMEPAD_BUTTON_B)) renderer.toggleSword();
        for (int b = 0; b <= GLFW_GAMEPAD_BUTTON_LAST; b++) {
            boolean d = pad.buttons(b) == GLFW_PRESS;
            if (d) active = true;
            padPrev[b] = d;
        }
        if (active) {
            lastPadUse = System.nanoTime();
            if (mouseCaptured) captureMouse(false);
        }
    }

    private boolean pressed(int button) {
        return pad.buttons(button) == GLFW_PRESS && !padPrev[button];
    }

    private static float[] stick(float x, float y, float curve) {
        float len = (float) Math.sqrt(x * x + y * y);
        if (len < DEADZONE) return new float[]{0, 0};
        float m = Math.min(1f, (len - DEADZONE) / (1f - DEADZONE));
        m = (float) Math.pow(m, curve);
        return new float[]{x / len * m, y / len * m};
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
