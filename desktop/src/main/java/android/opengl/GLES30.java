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
}
