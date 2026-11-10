package android.graphics;

import java.awt.image.BufferedImage;

/** Desktop stand-in backed by a BufferedImage (only what the game uses). */
public class Bitmap {
    public enum Config { ARGB_8888 }

    final BufferedImage img;

    Bitmap(BufferedImage img) {
        this.img = img;
    }

    public int getWidth() { return img.getWidth(); }

    public int getHeight() { return img.getHeight(); }

    /** Non-premultiplied ARGB, like Android's getPixels. */
    public void getPixels(int[] pixels, int offset, int stride, int x, int y, int width, int height) {
        img.getRGB(x, y, width, height, pixels, offset, stride);
    }

    public void recycle() { }
}
