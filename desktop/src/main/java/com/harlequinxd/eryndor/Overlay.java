package com.harlequinxd.eryndor;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Locale;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;

/**
 * PC loading screen and HUD: drawn with Java2D into an image (only when something changed),
 * then blended over the game with premultiplied alpha.
 */
final class Overlay {

    static final class State {
        boolean loading, fastTime, help, pad, clickToLook, invertY;
        float progress, sinceReady, hour;
        String stage = "", weather = "";
    }

    private static final Color GOLD = new Color(236, 205, 130);
    private static final Color MIST = new Color(190, 215, 220);
    private static final Color TEXT = new Color(255, 236, 205);

    private BufferedImage img;
    private int tex, prog, texW, texH;
    private String lastKey = "";
    private float shownProgress;
    private final FloatBuffer quad;

    Overlay() {
        quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1}).flip();
        String vs = "#version 330\nin vec2 aPos;\nout vec2 vUv;\n"
                + "void main() { vUv = vec2(aPos.x * 0.5 + 0.5, 0.5 - aPos.y * 0.5); gl_Position = vec4(aPos, 0.0, 1.0); }\n";
        String fs = "#version 330\nin vec2 vUv;\nuniform sampler2D uTex;\nout vec4 o;\n"
                + "void main() { o = texture(uTex, vUv); }\n";
        prog = GLUtil.program(vs, fs);
        tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
    }

    void draw(int fbW, int fbH, State s) {
        // the image is at most 1080 lines; text is drawn at that size and scaled smoothly
        int h = Math.min(fbH, 1080);
        int w = Math.max(1, Math.round(h * (float) fbW / fbH));
        if (img == null || img.getWidth() != w || img.getHeight() != h) {
            img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
            lastKey = "";
        }
        if (s.loading) shownProgress += (s.progress - shownProgress) * 0.25f;
        String key = key(s);
        if (!key.equals(lastKey)) {
            paint(s, w, h);
            upload(w, h);
            lastKey = key;
        }
        GL11.glViewport(0, 0, fbW, fbH);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL20.glUseProgram(prog);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL20.glUniform1i(GL20.glGetUniformLocation(prog, "uTex"), 0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 0, quad);
        GL20.glEnableVertexAttribArray(0);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        GL11.glDisable(GL11.GL_BLEND);
    }

    /** Everything that changes the picture; repaint only when it changes. */
    private String key(State s) {
        if (s.loading) return "L" + Math.round(shownProgress * 400) + s.stage;
        int titleAlpha = s.sinceReady < 6f ? Math.round(32 * Math.max(0f, Math.min(1f, (6f - s.sinceReady) / 2f))) : 0;
        int hh = (int) s.hour, mm = (int) ((s.hour - hh) * 60f);
        return "G" + titleAlpha + s.weather + s.fastTime + hh + ":" + mm + s.help + s.pad + s.clickToLook + s.invertY;
    }

    private void paint(State s, int w, int h) {
        Graphics2D g = img.createGraphics();
        g.setComposite(AlphaComposite.Clear);
        g.fillRect(0, 0, w, h);
        g.setComposite(AlphaComposite.SrcOver);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        float u = h / 720f;     // layout unit: one pixel at 720p

        if (s.loading) {
            g.setColor(new Color(10, 12, 18));
            g.fillRect(0, 0, w, h);
            title(g, w, h, u, 255);
            float bw = Math.min(w * 0.5f, 560 * u), bh = 12 * u, by = h * 0.72f, bx = w / 2f - bw / 2f;
            float p = Math.max(0f, Math.min(1f, shownProgress));
            g.setColor(new Color(214, 176, 96, 230));
            g.fill(new RoundRectangle2D.Float(bx + 3 * u, by + 3 * u, (bw - 6 * u) * p, bh - 6 * u, 4 * u, 4 * u));
            g.setColor(new Color(236, 205, 130, 200));
            g.setStroke(new BasicStroke(1.6f * u));
            g.draw(new RoundRectangle2D.Float(bx, by, bw, bh, 7 * u, 7 * u));
            g.setFont(new Font(Font.SERIF, Font.ITALIC, Math.round(18 * u)));
            centred(g, s.stage + "…   " + Math.round(p * 100) + "%", w / 2f, by + bh + 34 * u, new Color(200, 212, 222, 220));
            g.dispose();
            return;
        }

        if (s.sinceReady < 6f) {
            int a = Math.round(255 * Math.max(0f, Math.min(1f, (6f - s.sinceReady) / 2f)));
            title(g, w, h, u, a);
        }

        // top right: weather, time speed, clock
        g.setFont(new Font(Font.SERIF, Font.BOLD, Math.round(17 * u)));
        int hh = (int) s.hour, mm = (int) ((s.hour - hh) * 60f);
        String line = String.format(Locale.US, "%02d:%02d   •   %s   •   Time ×%d", hh, mm, s.weather, s.fastTime ? 20 : 1);
        FontMetrics fm = g.getFontMetrics();
        float tw = fm.stringWidth(line), px = w - tw - 22 * u, py = 18 * u;
        g.setColor(new Color(30, 20, 12, 120));
        g.fill(new RoundRectangle2D.Float(px - 12 * u, py, tw + 24 * u, 32 * u, 10 * u, 10 * u));
        shadowText(g, line, px, py + 22 * u, TEXT);

        if (s.clickToLook) {
            g.setFont(new Font(Font.SERIF, Font.ITALIC, Math.round(20 * u)));
            centred(g, "Click to look around", w / 2f, h * 0.62f, new Color(255, 240, 220, 200));
        }

        if (s.help) {
            String[] lines = s.pad
                    ? new String[]{"Left stick  walk", "Right stick  look", "Y  change the weather", "X  speed up time",
                    "Click right stick  invert look" + (s.invertY ? " (on)" : ""), "View / Menu  hide this help"}
                    : new String[]{"WASD / arrows  walk", "Mouse  look (Esc frees the mouse)", "Q  change the weather",
                    "T  speed up time", "I  invert look" + (s.invertY ? " (on)" : ""), "F11  full screen", "F1  hide this help"};
            g.setFont(new Font(Font.SERIF, Font.PLAIN, Math.round(15 * u)));
            float lh = 21 * u, bx = 22 * u, by = h - 22 * u - lines.length * lh;
            g.setColor(new Color(20, 14, 10, 110));
            g.fill(new RoundRectangle2D.Float(bx - 10 * u, by - 22 * u, 300 * u, lines.length * lh + 18 * u, 10 * u, 10 * u));
            for (int i = 0; i < lines.length; i++) shadowText(g, lines[i], bx, by + i * lh, new Color(255, 240, 220, 215));
        }
        g.dispose();
    }

    private void title(Graphics2D g, int w, int h, float u, int alpha) {
        float cy = h * 0.40f;
        g.setFont(new Font(Font.SERIF, Font.BOLD, Math.round(46 * u)));
        centred(g, "Chronicles of Eryndor", w / 2f, cy, withAlpha(GOLD, alpha));
        g.setColor(withAlpha(GOLD, alpha * 3 / 4));
        g.setStroke(new BasicStroke(1.4f * u));
        float lw = 150 * u, ly = cy + 22 * u;
        g.draw(new java.awt.geom.Line2D.Float(w / 2f - lw, ly, w / 2f - 18 * u, ly));
        g.draw(new java.awt.geom.Line2D.Float(w / 2f + 18 * u, ly, w / 2f + lw, ly));
        g.draw(new java.awt.geom.Ellipse2D.Float(w / 2f - 4 * u, ly - 4 * u, 8 * u, 8 * u));
        Font sub = new Font(Font.SERIF, Font.ITALIC, Math.round(26 * u));
        g.setFont(sub.deriveFont(java.util.Collections.singletonMap(java.awt.font.TextAttribute.TRACKING, 0.35f)));
        centred(g, "HOLLOWSPIRE", w / 2f, cy + 64 * u, withAlpha(MIST, alpha));
    }

    private static void centred(Graphics2D g, String s, float cx, float y, Color c) {
        float tw = g.getFontMetrics().stringWidth(s);
        shadowText(g, s, cx - tw / 2f, y, c);
    }

    private static void shadowText(Graphics2D g, String s, float x, float y, Color c) {
        float o = Math.max(1f, g.getFont().getSize2D() / 14f);
        g.setColor(new Color(0, 0, 0, c.getAlpha() * 2 / 3));
        g.drawString(s, x + o * 0.6f, y + o);
        g.setColor(c);
        g.drawString(s, x, y);
    }

    private static Color withAlpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a)));
    }

    private void upload(int w, int h) {
        int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        if (w != texW || h != texH) {
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, px);
            texW = w;
            texH = h;
        } else {
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, w, h, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, px);
        }
    }
}
