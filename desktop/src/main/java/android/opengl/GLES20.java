package android.opengl;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * Desktop stand-in for android.opengl.GLES20: the calls the game makes, forwarded to desktop
 * OpenGL 3.3 (compatibility profile) through LWJGL. Shaders written for GLSL ES 3.00 get their
 * version line swapped for desktop GLSL 3.30, which accepts the same language.
 */
public class GLES20 {
    public static final int GL_CLAMP_TO_EDGE = org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
    public static final int GL_COLOR_ATTACHMENT0 = GL30.GL_COLOR_ATTACHMENT0;
    public static final int GL_COLOR_BUFFER_BIT = GL11.GL_COLOR_BUFFER_BIT;
    public static final int GL_COMPILE_STATUS = GL20.GL_COMPILE_STATUS;
    public static final int GL_CULL_FACE = GL11.GL_CULL_FACE;
    public static final int GL_DEPTH_TEST = GL11.GL_DEPTH_TEST;
    public static final int GL_BLEND = GL11.GL_BLEND;
    public static final int GL_FLOAT = GL11.GL_FLOAT;
    public static final int GL_FRAGMENT_SHADER = GL20.GL_FRAGMENT_SHADER;
    public static final int GL_FRAMEBUFFER = GL30.GL_FRAMEBUFFER;
    public static final int GL_LINEAR = GL11.GL_LINEAR;
    public static final int GL_LINEAR_MIPMAP_LINEAR = GL11.GL_LINEAR_MIPMAP_LINEAR;
    public static final int GL_LINK_STATUS = GL20.GL_LINK_STATUS;
    public static final int GL_LUMINANCE = GL11.GL_LUMINANCE;
    public static final int GL_NEAREST = GL11.GL_NEAREST;
    public static final int GL_NEAREST_MIPMAP_LINEAR = GL11.GL_NEAREST_MIPMAP_LINEAR;
    public static final int GL_REPEAT = GL11.GL_REPEAT;
    public static final int GL_RGBA = GL11.GL_RGBA;
    public static final int GL_TEXTURE0 = GL13.GL_TEXTURE0;
    public static final int GL_TEXTURE1 = GL13.GL_TEXTURE1;
    public static final int GL_TEXTURE2 = GL13.GL_TEXTURE2;
    public static final int GL_TEXTURE3 = GL13.GL_TEXTURE3;
    public static final int GL_TEXTURE4 = GL13.GL_TEXTURE4;
    public static final int GL_TEXTURE5 = GL13.GL_TEXTURE5;
    public static final int GL_TEXTURE6 = GL13.GL_TEXTURE6;
    public static final int GL_TEXTURE_2D = GL11.GL_TEXTURE_2D;
    public static final int GL_TEXTURE_MAG_FILTER = GL11.GL_TEXTURE_MAG_FILTER;
    public static final int GL_TEXTURE_MIN_FILTER = GL11.GL_TEXTURE_MIN_FILTER;
    public static final int GL_TEXTURE_WRAP_S = GL11.GL_TEXTURE_WRAP_S;
    public static final int GL_TEXTURE_WRAP_T = GL11.GL_TEXTURE_WRAP_T;
    public static final int GL_TRIANGLE_STRIP = GL11.GL_TRIANGLE_STRIP;
    public static final int GL_UNPACK_ALIGNMENT = GL11.GL_UNPACK_ALIGNMENT;
    public static final int GL_UNSIGNED_BYTE = GL11.GL_UNSIGNED_BYTE;
    public static final int GL_VERTEX_SHADER = GL20.GL_VERTEX_SHADER;

    // ---- state
    public static void glClearColor(float r, float g, float b, float a) { GL11.glClearColor(r, g, b, a); }

    public static void glClear(int mask) { GL11.glClear(mask); }

    public static void glViewport(int x, int y, int w, int h) { GL11.glViewport(x, y, w, h); }

    public static void glDisable(int cap) { GL11.glDisable(cap); }

    public static void glEnable(int cap) { GL11.glEnable(cap); }

    public static void glPixelStorei(int pname, int param) { GL11.glPixelStorei(pname, param); }

    public static void glDrawArrays(int mode, int first, int count) { GL11.glDrawArrays(mode, first, count); }

    // ---- textures
    public static void glActiveTexture(int unit) { GL13.glActiveTexture(unit); }

    public static void glBindTexture(int target, int tex) { GL11.glBindTexture(target, tex); }

    public static void glGenTextures(int n, int[] out, int offset) {
        for (int i = 0; i < n; i++) out[offset + i] = GL11.glGenTextures();
    }

    public static void glDeleteTextures(int n, int[] tex, int offset) {
        for (int i = 0; i < n; i++) GL11.glDeleteTextures(tex[offset + i]);
    }

    public static void glTexParameteri(int target, int pname, int param) { GL11.glTexParameteri(target, pname, param); }

    public static void glGenerateMipmap(int target) { GL30.glGenerateMipmap(target); }

    public static void glTexImage2D(int target, int level, int internalFormat, int w, int h, int border,
                                    int format, int type, Buffer pixels) {
        if (pixels == null) {
            GL11.glTexImage2D(target, level, internalFormat, w, h, border, format, type, (ByteBuffer) null);
        } else if (pixels instanceof FloatBuffer) {
            GL11.glTexImage2D(target, level, internalFormat, w, h, border, format, type, direct((FloatBuffer) pixels));
        } else if (pixels instanceof IntBuffer) {
            GL11.glTexImage2D(target, level, internalFormat, w, h, border, format, type, direct((IntBuffer) pixels));
        } else {
            GL11.glTexImage2D(target, level, internalFormat, w, h, border, format, type, direct((ByteBuffer) pixels));
        }
    }

    // ---- framebuffers
    public static void glGenFramebuffers(int n, int[] out, int offset) {
        for (int i = 0; i < n; i++) out[offset + i] = GL30.glGenFramebuffers();
    }

    public static void glDeleteFramebuffers(int n, int[] fb, int offset) {
        for (int i = 0; i < n; i++) GL30.glDeleteFramebuffers(fb[offset + i]);
    }

    public static void glBindFramebuffer(int target, int fb) { GL30.glBindFramebuffer(target, fb); }

    public static void glFramebufferTexture2D(int target, int attachment, int texTarget, int tex, int level) {
        GL30.glFramebufferTexture2D(target, attachment, texTarget, tex, level);
    }

    // ---- shaders
    public static int glCreateShader(int type) { return GL20.glCreateShader(type); }

    public static void glShaderSource(int shader, String src) { GL20.glShaderSource(shader, desktopGlsl(src)); }

    public static void glCompileShader(int shader) { GL20.glCompileShader(shader); }

    public static void glGetShaderiv(int shader, int pname, int[] out, int offset) {
        out[offset] = GL20.glGetShaderi(shader, pname);
    }

    public static String glGetShaderInfoLog(int shader) { return GL20.glGetShaderInfoLog(shader); }

    public static int glCreateProgram() { return GL20.glCreateProgram(); }

    public static void glAttachShader(int program, int shader) { GL20.glAttachShader(program, shader); }

    public static void glBindAttribLocation(int program, int index, String name) { GL20.glBindAttribLocation(program, index, name); }

    public static void glLinkProgram(int program) { GL20.glLinkProgram(program); }

    public static void glGetProgramiv(int program, int pname, int[] out, int offset) {
        out[offset] = GL20.glGetProgrami(program, pname);
    }

    public static String glGetProgramInfoLog(int program) { return GL20.glGetProgramInfoLog(program); }

    public static void glUseProgram(int program) { GL20.glUseProgram(program); }

    public static int glGetUniformLocation(int program, String name) { return GL20.glGetUniformLocation(program, name); }

    // ---- uniforms
    public static void glUniform1i(int loc, int v) { GL20.glUniform1i(loc, v); }

    public static void glUniform1f(int loc, float v) { GL20.glUniform1f(loc, v); }

    public static void glUniform2f(int loc, float x, float y) { GL20.glUniform2f(loc, x, y); }

    public static void glUniform3f(int loc, float x, float y, float z) { GL20.glUniform3f(loc, x, y, z); }

    public static void glUniform4f(int loc, float x, float y, float z, float w) { GL20.glUniform4f(loc, x, y, z, w); }

    public static void glUniform1fv(int loc, int count, float[] v, int offset) {
        GL20.glUniform1fv(loc, Arrays.copyOfRange(v, offset, offset + count));
    }

    public static void glUniform3fv(int loc, int count, float[] v, int offset) {
        GL20.glUniform3fv(loc, Arrays.copyOfRange(v, offset, offset + count * 3));
    }

    public static void glUniform4fv(int loc, int count, float[] v, int offset) {
        GL20.glUniform4fv(loc, Arrays.copyOfRange(v, offset, offset + count * 4));
    }

    public static void glUniformMatrix3fv(int loc, int count, boolean transpose, float[] v, int offset) {
        GL20.glUniformMatrix3fv(loc, transpose, Arrays.copyOfRange(v, offset, offset + count * 9));
    }

    // ---- vertex arrays (client-side arrays are fine in the compatibility profile)
    public static void glEnableVertexAttribArray(int index) { GL20.glEnableVertexAttribArray(index); }

    public static void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, Buffer ptr) {
        org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 0);
        if (ptr instanceof FloatBuffer) {
            GL20.glVertexAttribPointer(index, size, type, normalized, stride, direct((FloatBuffer) ptr));
        } else {
            GL20.glVertexAttribPointer(index, size, type, normalized, stride, direct((ByteBuffer) ptr));
        }
    }

    // ---- helpers
    /** GLSL ES 3.00 -> desktop GLSL 3.30 (the language is the same for everything the game uses). */
    static String desktopGlsl(String src) {
        String s = src.replaceFirst("#version\\s+300\\s+es", "#version 330");
        // desktop GLSL 3.30 has no default precision for sampler types; drop that one statement
        return s.replaceAll("(?m)^\\s*precision\\s+\\w+\\s+sampler2D\\s*;", "");
    }

    private static ByteBuffer direct(ByteBuffer b) {
        if (b.isDirect()) return b;
        ByteBuffer d = ByteBuffer.allocateDirect(b.remaining()).order(ByteOrder.nativeOrder());
        d.put(b.duplicate()).flip();
        return d;
    }

    private static FloatBuffer direct(FloatBuffer b) {
        if (b.isDirect()) return b;
        FloatBuffer d = ByteBuffer.allocateDirect(b.remaining() * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        d.put(b.duplicate()).flip();
        return d;
    }

    private static IntBuffer direct(IntBuffer b) {
        if (b.isDirect()) return b;
        IntBuffer d = ByteBuffer.allocateDirect(b.remaining() * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
        d.put(b.duplicate()).flip();
        return d;
    }
}
