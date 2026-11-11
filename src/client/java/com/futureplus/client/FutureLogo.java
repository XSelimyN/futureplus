package com.futureplus.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;

/**
 * The Future logo, as vectors: an F in a circle with a pentagon on top. Measurements taken from the emblem on the Future cape texture
 * (unit: outer circle radius 64.6, center 0,0, y down). The texture is generated at its on-screen pixel size, so
 * there is no scaling blur; it is redrawn when the size changes.
 */
public final class FutureLogo {
    private FutureLogo() {}

    public static final int COLOR = 0xA92527;                    // Future red (cape / icon)
    private static final double R_OUT = 64.6, R_RING = 43.0, R_DISC = 33.45, R_CUT = 44.1;
    private static final double TOP = -79.0, EXTENT = 146.0, MID_Y = (TOP + R_OUT) / 2;
    private static Area shape;
    /** A texture per size (the main menu draws two sizes in the same frame); at most 4 are kept. */
    private static final java.util.LinkedHashMap<Integer, Identifier> BUILT = new java.util.LinkedHashMap<>();

    /** px x px logo texture (square, centered). */
    public static Identifier texture(int px) {
        px = Math.max(16, Math.min(4096, px));
        Identifier have = BUILT.get(px);
        if (have != null) return have;
        Identifier id = Identifier.fromNamespaceAndPath("futureplus", "gui/future_logo_" + px);
        BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        double s = px / EXTENT;
        g.translate(px / 2.0, px / 2.0);
        g.scale(s, s);
        g.translate(0, -MID_Y);
        g.setColor(new java.awt.Color(COLOR));
        g.fill(shape());
        g.dispose();
        NativeImage ni = new NativeImage(px, px, false);
        for (int y = 0; y < px; y++) for (int x = 0; x < px; x++) ni.setPixel(x, y, img.getRGB(x, y));
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "futureplus logo", ni));
        BUILT.put(px, id);
        if (BUILT.size() > 4) {
            Integer oldest = BUILT.keySet().iterator().next();
            Minecraft.getInstance().getTextureManager().release(BUILT.remove(oldest));
        }
        return id;
    }

    private static Area shape() {
        if (shape != null) return shape;
        // outer ring: only below the 45° lines (y >= -|x|); the lines separate the ring from the pentagon
        Area ring = new Area(circle(R_OUT));
        ring.subtract(new Area(circle(R_RING)));
        ring.subtract(new Area(poly(0, 0, -300, -300, 300, -300)));
        // pentagon: top, side corners, bottom edges at 45° (x - y = 14.3), its bottom cut by the ring line
        double k = 1.502, off = 14.3;                             // top edge slope (dx/dy), gap offset
        double cy = (-off - k * -TOP) / (1 + k), cx = cy + off;   // left corner: x = y + off and x = -k (y - TOP)
        Area roof = new Area(poly(0, TOP, cx, cy, 0, -off, -cx, cy));
        roof.subtract(new Area(circle(R_CUT)));
        // inner circle, F gap (stem + two arms with slanted tips)
        Area disc = new Area(circle(R_DISC));
        disc.subtract(new Area(new Rectangle2D.Double(-14.0, -19.6, 9.0, 60.0)));
        disc.subtract(new Area(poly(-14.0, -19.6, 14.57, -19.6, 10.56, -11.3, -14.0, -11.3)));
        disc.subtract(new Area(poly(-5.5, 3.7, 10.87, 3.7, 6.08, 12.4, -5.5, 12.4)));
        Area a = new Area();
        a.add(ring);
        a.add(roof);
        a.add(disc);
        return shape = a;
    }

    private static Ellipse2D circle(double r) { return new Ellipse2D.Double(-r, -r, 2 * r, 2 * r); }

    private static Path2D poly(double... xy) {
        Path2D p = new Path2D.Double();
        p.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
        p.closePath();
        return p;
    }
}
