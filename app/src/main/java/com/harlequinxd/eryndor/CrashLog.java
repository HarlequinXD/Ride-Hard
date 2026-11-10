package com.harlequinxd.eryndor;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Finds out why the last run died, so a crash on a phone can be reported without a cable.
 * Three sources: a Java stack trace (written by an uncaught-exception handler), the last loading
 * stage reached (a breadcrumb written as the world builds, which survives driver crashes and
 * out-of-memory kills), and Android's own exit record (reason + description, Android 11+).
 * The report is shown on the loading screen and copied to the clipboard.
 */
final class CrashLog {
    private CrashLog() {}

    private static File dir;
    static volatile String gpu = "";
    /** What went wrong last time (or in this run, if the GL thread hit an error); null if all fine. */
    static volatile String report;

    static void init(Context ctx) {
        dir = ctx.getFilesDir();
        StringBuilder sb = new StringBuilder();
        String crash = read("crash.txt");
        String stage = read("stage.txt");
        String gl = read("gpu.txt");
        if (crash != null) sb.append(crash.trim()).append('\n');
        else if (stage != null && !stage.trim().equals("ok")) {
            sb.append("Last run stopped while: ").append(stage.trim()).append('\n');
        }
        if (sb.length() > 0 && Build.VERSION.SDK_INT >= 30) {
            try {
                ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
                List<ApplicationExitInfo> ex = am.getHistoricalProcessExitReasons(ctx.getPackageName(), 0, 1);
                if (!ex.isEmpty()) {
                    ApplicationExitInfo e = ex.get(0);
                    sb.append("Android exit: ").append(reasonName(e.getReason()))
                            .append(" (").append(e.getDescription()).append(")")
                            .append(", memory ").append(e.getPss() / 1024).append(" MB\n");
                }
            } catch (Throwable ignored) { }
        }
        if (sb.length() > 0) {
            if (gl != null) sb.append(gl.trim()).append('\n');
            sb.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                    .append(", Android ").append(Build.VERSION.RELEASE)
                    .append(", heap ").append(Runtime.getRuntime().maxMemory() >> 20).append(" MB");
            report = sb.toString();
        }
        new File(dir, "crash.txt").delete();
        stage("Starting");

        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            write("crash.txt", describe("Crashed on thread " + t.getName(), e));
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    /** Breadcrumb: the stage the game is in right now ("ok" once it is running). */
    static void stage(String what) {
        write("stage.txt", what);
    }

    static void gpuInfo(String info) {
        gpu = info;
        write("gpu.txt", info);
    }

    /** An error on the GL thread: shown on screen instead of killing the app. */
    static void fail(String where, Throwable e) {
        String s = describe(where, e) + (gpu.isEmpty() ? "" : "\n" + gpu)
                + "\nDevice: " + Build.MANUFACTURER + ' ' + Build.MODEL + ", Android " + Build.VERSION.RELEASE;
        report = s;
        write("stage.txt", "ok");     // already shown; don't repeat it next launch
    }

    static String describe(String where, Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String st = sw.toString();
        if (st.length() > 3000) st = st.substring(0, 3000);
        return where + " during \"" + Loading.stage + "\":\n" + st;
    }

    private static String reasonName(int r) {
        switch (r) {
            case 1: return "exited itself";
            case 2: return "signalled";
            case 3: return "LOW MEMORY";
            case 4: return "Java crash";
            case 5: return "NATIVE CRASH (driver)";
            case 6: return "ANR (froze)";
            case 7: return "init failure";
            case 8: return "permission change";
            case 9: return "excessive resource use";
            case 10: return "user requested";
            case 11: return "user stopped";
            case 12: return "dependency died";
            case 13: return "other";
            case 14: return "freezer";
            case 15: return "package state change";
            case 16: return "package updated";
            default: return "unknown (" + r + ")";
        }
    }

    private static String read(String name) {
        try {
            File f = new File(dir, name);
            if (!f.exists()) return null;
            try (FileInputStream in = new FileInputStream(f)) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                return new String(bo.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Throwable e) {
            return null;
        }
    }

    private static void write(String name, String text) {
        if (dir == null) return;
        try (FileOutputStream o = new FileOutputStream(new File(dir, name))) {
            o.write(text.getBytes(StandardCharsets.UTF_8));
            o.getFD().sync();
        } catch (Throwable ignored) { }
    }
}
