package com.harlequinxd.eryndor;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Writes the generated world's terrain materials as a palette mask you can paint over:
 *   java -cp eryndor.jar com.harlequinxd.eryndor.TerrainMaskExport terrain_mask.png
 * Put the result in app/src/main/assets/terrain/terrain_mask.png (palette in TerrainMask.java).
 */
public final class TerrainMaskExport {
    private TerrainMaskExport() { }

    public static void main(String[] args) throws Exception {
        WorldTextures.heightTexture();
        WorldTextures.ground();
        byte[] mat = WorldTextures.materials();
        int n = WorldTextures.GROUND_SIZE;
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, n, n, TerrainMask.encode(mat), 0, n);
        File out = new File(args.length > 0 ? args[0] : "terrain_mask.png");
        ImageIO.write(img, "png", out);
        System.out.println("wrote " + out.getAbsolutePath());
    }
}
