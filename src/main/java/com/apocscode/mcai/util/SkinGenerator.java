package com.apocscode.mcai.util;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Run this standalone to generate the companion skin texture.
 * Output: src/main/resources/assets/mcai/textures/entity/companion.png
 *
 * This creates a 64x64 player skin with a blue/teal AI theme.
 * Replace with a custom skin later.
 */
public class SkinGenerator {
    public static void main(String[] args) throws Exception {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();

        // Transparent background
        g.setComposite(AlphaComposite.Clear);
        g.fillRect(0, 0, 64, 64);
        g.setComposite(AlphaComposite.SrcOver);

        Color skin = new Color(0xC4, 0xA8, 0x82);       // Light skin
        Color hair = new Color(0x1A, 0x1A, 0x2E);         // Dark blue-black hair
        Color shirt = new Color(0x34, 0x98, 0xDB);         // Blue shirt
        Color shirtDark = new Color(0x21, 0x7D, 0xBB);     // Dark blue accent
        Color pants = new Color(0x2C, 0x3E, 0x50);         // Dark slate pants
        Color shoes = new Color(0x1A, 0x1A, 0x1A);         // Black shoes
        Color eyes = new Color(0x2E, 0xCC, 0x71);          // Green eyes (AI glow)
        Color white = Color.WHITE;

        // === HEAD (front) 8x8 at (8, 8) ===
        // Fill head with skin
        fillRect(g, skin, 8, 8, 8, 8);
        // Hair top row
        fillRect(g, hair, 8, 8, 8, 1);
        // Hair sides
        fillRect(g, hair, 8, 9, 1, 3);
        fillRect(g, hair, 15, 9, 1, 3);
        // Eyes
        g.setColor(white);
        fillRect(g, white, 10, 12, 2, 1);
        fillRect(g, white, 13, 12, 2, 1);
        fillRect(g, eyes, 11, 12, 1, 1);
        fillRect(g, eyes, 14, 12, 1, 1);
        // Mouth
        fillRect(g, new Color(0xA0, 0x70, 0x50), 11, 14, 3, 1);

        // === HEAD (top) 8x8 at (8, 0) ===
        fillRect(g, hair, 8, 0, 8, 8);

        // === HEAD (back) 8x8 at (24, 8) ===
        fillRect(g, hair, 24, 8, 8, 8);

        // === HEAD (right) 8x8 at (0, 8) ===
        fillRect(g, skin, 0, 8, 8, 8);
        fillRect(g, hair, 0, 8, 8, 2);
        fillRect(g, hair, 0, 10, 2, 2);

        // === HEAD (left) 8x8 at (16, 8) ===
        fillRect(g, skin, 16, 8, 8, 8);
        fillRect(g, hair, 16, 8, 8, 2);
        fillRect(g, hair, 22, 10, 2, 2);

        // === HEAD (bottom) 8x8 at (16, 0) ===
        fillRect(g, skin, 16, 0, 8, 8);

        // === BODY (front) 8x12 at (20, 20) ===
        fillRect(g, shirt, 20, 20, 8, 12);
        // Stripe down center
        fillRect(g, shirtDark, 23, 20, 2, 12);
        // Collar
        fillRect(g, shirtDark, 20, 20, 8, 1);

        // === BODY (back) 8x12 at (32, 20) ===
        fillRect(g, shirt, 32, 20, 8, 12);
        fillRect(g, shirtDark, 35, 20, 2, 12);

        // === BODY (top) at (20, 16) ===
        fillRect(g, shirt, 20, 16, 8, 4);

        // === BODY (right) at (16, 20) ===
        fillRect(g, shirt, 16, 20, 4, 12);

        // === BODY (left) at (28, 20) ===
        fillRect(g, shirt, 28, 20, 4, 12);

        // === RIGHT ARM (front) at (44, 20) ===
        fillRect(g, shirt, 44, 20, 4, 12);
        fillRect(g, skin, 44, 28, 4, 4); // Hand

        // === LEFT ARM (front) at (36, 52) ===
        fillRect(g, shirt, 36, 52, 4, 12);
        fillRect(g, skin, 36, 60, 4, 4); // Hand

        // === RIGHT LEG (front) at (4, 20) ===
        fillRect(g, pants, 4, 20, 4, 12);
        fillRect(g, shoes, 4, 28, 4, 4);

        // === LEFT LEG (front) at (20, 52) ===
        fillRect(g, pants, 20, 52, 4, 12);
        fillRect(g, shoes, 20, 60, 4, 4);

        g.dispose();

        // Save
        File output = new File("src/main/resources/assets/mcai/textures/entity/companion.png");
        output.getParentFile().mkdirs();
        ImageIO.write(img, "png", output);
        System.out.println("Skin generated: " + output.getAbsolutePath());
    }

    private static void fillRect(Graphics2D g, Color c, int x, int y, int w, int h) {
        g.setColor(c);
        g.fillRect(x, y, w, h);
    }
}
