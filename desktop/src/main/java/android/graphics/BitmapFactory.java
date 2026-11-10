package android.graphics;

import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;

/** Desktop stand-in: decodes with ImageIO. */
public class BitmapFactory {
    public static class Options {
        public boolean inScaled = true;
        public Bitmap.Config inPreferredConfig;
    }

    public static Bitmap decodeStream(InputStream in, Rect outPadding, Options opts) {
        try {
            java.awt.image.BufferedImage img = ImageIO.read(in);
            return img == null ? null : new Bitmap(img);
        } catch (IOException e) {
            return null;
        }
    }
}
