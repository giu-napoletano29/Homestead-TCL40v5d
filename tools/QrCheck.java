package com.homestead.launcher;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Writes QrCode's output as PNG files, to be read back by a separate decoder.
 *
 *   javac -d out src/com/homestead/launcher/QrCode.java tools/QrCheck.java
 *   java -cp out com.homestead.launcher.QrCheck <output directory>
 *
 * Writes <n>.png and <n>.txt (the text that must come back) for a set of lengths that
 * covers every version the encoder makes.
 */
public final class QrCheck {
    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        dir.mkdirs();
        String[] texts = {
                "hi",
                "http://192.168.1.119:8765/?c=123456",
                "http://192.168.100.100:8765/?c=987654",
                "http://10.0.0.5:8765/?c=000000&x=" + "a".repeat(20),
                "http://192.168.1.119:8765/?c=123456&" + "k".repeat(40),
                "http://192.168.1.119:8765/?c=123456&" + "z".repeat(60),
                "http://192.168.1.119:8765/?c=123456&" + "Q".repeat(70),
                "Città è già: perché",
        };
        for (int n = 0; n < texts.length; n++) {
            boolean[][] m = QrCode.encode(texts[n]);
            int scale = 10, quiet = 4, size = m.length;
            BufferedImage img = new BufferedImage((size + 2 * quiet) * scale,
                    (size + 2 * quiet) * scale, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    int mx = x / scale - quiet, my = y / scale - quiet;
                    boolean dark = mx >= 0 && my >= 0 && mx < size && my < size && m[my][mx];
                    img.setRGB(x, y, dark ? 0x000000 : 0xFFFFFF);
                }
            }
            ImageIO.write(img, "png", new File(dir, n + ".png"));
            java.nio.file.Files.write(new File(dir, n + ".txt").toPath(),
                    texts[n].getBytes("UTF-8"));
            System.out.println(n + ": " + texts[n].getBytes("UTF-8").length + " bytes, "
                    + size + "x" + size);
        }
    }
}
