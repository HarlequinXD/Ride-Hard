package com.harlequinxd.eryndor;

import android.content.res.AssetManager;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

final class GLUtil {
    private static final String TAG = "Eryndor";

    private GLUtil() {}

    static String asset(AssetManager am, String path) {
        try (InputStream in = am.open(path)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Missing asset " + path, e);
        }
    }

    /** Running on a phone (Android's runtime): shaders are built with MOBILE defined. */
    static final boolean MOBILE = "Dalvik".equals(System.getProperty("java.vm.name"));

    static int program(String vs, String fs) {
        if (MOBILE) {                                  // right after the #version line
            int nl = fs.indexOf('\n');
            fs = fs.substring(0, nl + 1) + "#define MOBILE 1\n" + fs.substring(nl + 1);
        }
        int v = shader(GLES20.GL_VERTEX_SHADER, vs);
        int f = shader(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glBindAttribLocation(p, 0, "aPos");
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(p);
            Log.e(TAG, "link: " + log);
            throw new RuntimeException("Program link failed: " + log);
        }
        return p;
    }

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(s);
            Log.e(TAG, "compile: " + log);
            throw new RuntimeException("Shader compile failed: " + log);
        }
        return s;
    }

    /** Float data texture (1, 2 or 4 channels, 32-bit), nearest, read with texelFetch. Needs GLES 3. */
    static int floatTexture(int existing, float[] data, int w, int h, int channels) {
        java.nio.FloatBuffer fb = ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(data).position(0);
        int[] t = {existing};
        if (existing == 0) GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4);
        int internal = channels == 4 ? GLES30.GL_RGBA32F : (channels == 2 ? GLES30.GL_RG32F : GLES30.GL_R32F);
        int format = channels == 4 ? GLES20.GL_RGBA : (channels == 2 ? GLES30.GL_RG : GLES30.GL_RED);
        GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, internal, w, h, 0, format, GLES20.GL_FLOAT, fb);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return t[0];
    }

    /** 3D RG8 volume (x, y, z), trilinear, wrapping in x and z, clamped in y. */
    /** A clamped 3D data texture (the voxel hands): any format, linear or nearest. */
    static int texture3D(ByteBuffer data, int w, int h, int d, int internal, int format, boolean linear) {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES30.GL_TEXTURE_3D, t[0]);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, internal, w, h, d, 0, format, GLES20.GL_UNSIGNED_BYTE, data);
        int f = linear ? GLES20.GL_LINEAR : GLES20.GL_NEAREST;
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_MIN_FILTER, f);
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_MAG_FILTER, f);
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES20.GL_CLAMP_TO_EDGE);
        return t[0];
    }


    /** Square ARGB layers as one 2D texture array: repeating, mipmapped, point sampled. */
    static int textureArray(int[][] layers, int size) {
        ByteBuffer bb = ByteBuffer.allocateDirect(size * size * 4 * layers.length).order(ByteOrder.nativeOrder());
        for (int[] layer : layers) {
            for (int c : layer) bb.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) (c >>> 24));
        }
        bb.position(0);
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, t[0]);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_2D_ARRAY, 0, GLES30.GL_RGBA8, size, size, layers.length, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bb);
        GLES20.glGenerateMipmap(GLES30.GL_TEXTURE_2D_ARRAY);
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST_MIPMAP_NEAREST);
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT);
        return t[0];
    }

    /** Leaf sheet: RGBA, mipmapped, clamped, point sampled. */
    static int atlas(int[] argb, int w, int h) {
        return atlas(argb, w, h, true);
    }

    /** RGBA sheet, mipmapped, clamped; point sampled (the game's look) or smooth (shape data). */
    static int atlas(int[] argb, int w, int h, boolean point) {
        ByteBuffer bb = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        for (int c : argb) bb.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) (c >>> 24));
        bb.position(0);
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bb);
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                point ? GLES20.GL_NEAREST_MIPMAP_NEAREST : GLES20.GL_LINEAR_MIPMAP_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, point ? GLES20.GL_NEAREST : GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return t[0];
    }

    /** Small RGBA data texture, repeating, nearest (one texel per cell). */
    static int cells(byte[] rgba, int size) {
        ByteBuffer bb = ByteBuffer.allocateDirect(rgba.length).order(ByteOrder.nativeOrder());
        bb.put(rgba).position(0);
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, size, size, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bb);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT);
        return t[0];
    }

    /** Single-channel 8-bit texture, repeating, bilinear, no mipmaps (sampled inside ray loops). */
    static int luminance(byte[] data, int size) {
        return luminance(data, size, false);
    }

    static int luminance(byte[] data, int size, boolean nearest) {
        ByteBuffer bb = ByteBuffer.allocateDirect(data.length).order(ByteOrder.nativeOrder());
        bb.put(data).position(0);
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, size, size, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, bb);
        int filter = nearest ? GLES20.GL_NEAREST : GLES20.GL_LINEAR;
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT);
        return t[0];
    }

    /**
     * Square ARGB texture, repeating, with mipmaps. Uploaded as raw RGBA bytes (not via Bitmap) so
     * alpha is never premultiplied into the colour; the ground uses alpha as its water mask.
     */
    static int texture(int[] argb, int size, boolean nearestMag) {
        return texture(argb, size, nearestMag, false);
    }

    static int texture(int[] argb, int size, boolean nearestMag, boolean dataTexture) {
        ByteBuffer bb = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder());
        for (int c : argb) {
            bb.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) (c >>> 24));
        }
        bb.position(0);
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, size, size, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bb);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT);
        if (dataTexture) {          // heightmap: smooth bilinear, no mipmaps (sampled inside loops)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            return t[0];
        }
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                nearestMag ? GLES20.GL_NEAREST_MIPMAP_NEAREST : GLES20.GL_LINEAR_MIPMAP_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                nearestMag ? GLES20.GL_NEAREST : GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT);
        return t[0];
    }

    // ---- streaming: replace part of a texture (the world windows, one chunk at a time)
    private static ByteBuffer scratch;

    private static ByteBuffer scratch(int bytes) {
        if (scratch == null || scratch.capacity() < bytes)
            scratch = ByteBuffer.allocateDirect(Math.max(bytes, 1 << 20)).order(ByteOrder.nativeOrder());
        scratch.clear();
        return scratch;
    }

    /** Replace a w x h block of an ARGB texture (uploaded as RGBA bytes, alpha untouched). */
    static void subTexture(int tex, int[] argb, int x, int y, int w, int h) {
        ByteBuffer bb = scratch(w * h * 4);
        for (int i = 0; i < w * h; i++) {
            int c = argb[i];
            bb.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) (c >>> 24));
        }
        bb.flip();
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, x, y, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bb);
    }

    /** Replace a block of a single-channel texture. */
    static void subLuminance(int tex, byte[] data, int x, int y, int w, int h) {
        ByteBuffer bb = scratch(w * h);
        bb.put(data, 0, w * h).flip();
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, x, y, w, h, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, bb);
    }

    /** Replace a block of an RGBA cell texture. */
    static void subCells(int tex, byte[] rgba, int x, int y, int w, int h) {
        ByteBuffer bb = scratch(w * h * 4);
        bb.put(rgba, 0, w * h * 4).flip();
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, x, y, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bb);
    }

    /** Rebuild a texture's mipmaps after blocks of it changed. */
    static void remip(int tex) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
    }

}
