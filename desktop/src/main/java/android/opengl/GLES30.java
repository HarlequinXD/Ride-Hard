package android.opengl;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Desktop stand-in for android.opengl.GLES30: the float texture formats the trees use. */
public class GLES30 extends GLES20 {
    public static final int GL_R32F = GL30.GL_R32F;
    public static final int GL_RED = GL11.GL_RED;
    public static final int GL_RG = GL30.GL_RG;
    public static final int GL_RG32F = GL30.GL_RG32F;
    public static final int GL_RGBA32F = GL30.GL_RGBA32F;
    public static final int GL_RGBA8 = GL11.GL_RGBA8;
    public static final int GL_TEXTURE_2D_ARRAY = GL30.GL_TEXTURE_2D_ARRAY;

    public static void glTexImage3D(int target, int level, int internalFormat, int w, int h, int depth, int border,
                                    int format, int type, java.nio.Buffer pixels) {
        java.nio.ByteBuffer b = (java.nio.ByteBuffer) pixels;
        if (b != null && !b.isDirect()) {
            java.nio.ByteBuffer d = java.nio.ByteBuffer.allocateDirect(b.remaining()).order(java.nio.ByteOrder.nativeOrder());
            d.put(b.duplicate()).flip();
            b = d;
        }
        org.lwjgl.opengl.GL12.glTexImage3D(target, level, internalFormat, w, h, depth, border, format, type, b);
    }
}
