// Generates the launcher icon PNGs (an open book with an audio waveform above its spine).
// Usage, from the repository root:  java -Djava.awt.headless=true tools/MakeIcon.java
// Writes native/res/mipmap-*/ic_launcher.png. Pure JDK (Java 2D); no dependencies.
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

public class MakeIcon {
    /** Draws on a 512x512 design grid, scaled to size. */
    static BufferedImage draw(int size) {
        int ss = 4; // supersample, then downscale for clean edges
        int big = size * ss;
        BufferedImage im = new BufferedImage(big, big, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = im.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.scale(big / 512.0, big / 512.0);

        // background: rounded square in the app's green
        g.setPaint(new GradientPaint(0, 24, new Color(0x56A24D), 0, 488, new Color(0x2C5A27)));
        g.fill(new RoundRectangle2D.Double(24, 24, 464, 464, 112, 112));

        // open book: two pages meeting at the spine (x = 256)
        g.setColor(Color.WHITE);
        Path2D left = new Path2D.Double();
        left.moveTo(250, 400);
        left.lineTo(104, 360);
        left.lineTo(104, 246);
        left.quadTo(184, 238, 250, 282);
        left.closePath();
        g.fill(left);
        Path2D right = new Path2D.Double();
        right.moveTo(262, 400);
        right.lineTo(408, 360);
        right.lineTo(408, 246);
        right.quadTo(328, 238, 262, 282);
        right.closePath();
        g.fill(right);

        // audio waveform rising above the spine: seven rounded bars, tallest in the middle
        double midY = 160, barW = 24, pitch = 40;
        double[] height = {44, 84, 124, 164, 124, 84, 44};
        float[] alpha = {0.6f, 0.8f, 1f, 1f, 1f, 0.8f, 0.6f};
        for (int i = 0; i < height.length; i++) {
            double x = 256 + (i - 3) * pitch - barW / 2;
            g.setColor(new Color(1f, 1f, 1f, alpha[i]));
            g.fill(new RoundRectangle2D.Double(x, midY - height[i] / 2, barW, height[i], barW, barW));
        }
        g.dispose();

        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D o = out.createGraphics();
        o.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        o.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        o.drawImage(im.getScaledInstance(size, size, java.awt.Image.SCALE_AREA_AVERAGING), 0, 0, null);
        o.dispose();
        return out;
    }

    public static void main(String[] args) throws Exception {
        String[][] densities = {{"mdpi", "48"}, {"hdpi", "72"}, {"xhdpi", "96"}, {"xxhdpi", "144"}, {"xxxhdpi", "192"}};
        for (String[] d : densities) {
            File dir = new File("native/res/mipmap-" + d[0]);
            dir.mkdirs();
            ImageIO.write(draw(Integer.parseInt(d[1])), "png", new File(dir, "ic_launcher.png"));
            System.out.println("wrote " + dir + "/ic_launcher.png (" + d[1] + "px)");
        }
        ImageIO.write(draw(512), "png", new File("tools/icon-512.png")); // preview / store-size master
        System.out.println("wrote tools/icon-512.png");
    }
}
