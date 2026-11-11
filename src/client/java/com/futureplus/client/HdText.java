package com.futureplus.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.font.LineMetrics;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntUnaryOperator;

/**
 * HD text engine (Meteor style).
 *
 * Minecraft's TTF engine (stb_truetype) doesn't align glyph strokes to pixels (no hinting), so
 * small text looks "low-resolution". Here each text is drawn with Java's font engine (on Linux
 * FreeType, hinting ON) to an image at the screen's REAL pixel resolution and pasted to the screen 1:1
 * (every texture pixel = one screen pixel). It is as crisp as in desktop apps.
 *
 * Glow: the text's alpha channel is Gaussian blurred and kept as a separate layer
 * (a soft, smooth halo; a single draw). Dark outline: alpha widened by 1 pixel.
 *
 * Texture layout (vertical): [text] [dark outline] [glow] — only the requested layers are generated.
 * The same text is drawn once and kept in a cache (LRU, at most CACHE_MAX textures).
 */
final class HdText {
    private HdText() {}

    private static final int CACHE_MAX = 256;
    private static final String[] FILES = {"arial_regular", "arial_bold", "arial_italic", "arial_bolditalic"};
    private static final java.awt.Font[] BASE = new java.awt.Font[4];
    private static int idCounter = 0;

    private static final class E {
        Identifier id;
        int texW, texH;      // texture size (pixels)
        int w, h;            // single layer size
        int pad;             // the text's margin inside its layer
        int edgeRow = -1, glowRow = -1; // the layers' rows (-1 = none)
        float[] xs;          // glyph starts (device pixels, without pad), xs[n] = total width
    }

    private static final LinkedHashMap<String, E> CACHE = new LinkedHashMap<>(128, 0.75f, true);
    private static final Map<String, Float> ADV = new LinkedHashMap<>(256, 0.75f, true);

    // ------------------------------------------------------------- font

    private static java.awt.Font base(int style) {
        java.awt.Font f = BASE[style];
        if (f != null) return f;
        try {
            Identifier id = Identifier.fromNamespaceAndPath("futureplus", "font/" + FILES[style] + ".ttf");
            Optional<Resource> r = Minecraft.getInstance().getResourceManager().getResource(id);
            if (r.isPresent()) {
                try (InputStream in = r.get().open()) {
                    f = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, in);
                }
            }
        } catch (Throwable t) {
            System.err.println("[Futureplus] HD font yuklenemedi (" + FILES[style] + "): " + t);
        }
        if (f == null) {
            int st = (style == 1 ? java.awt.Font.BOLD : 0) | (style == 2 ? java.awt.Font.ITALIC : 0)
                    | (style == 3 ? java.awt.Font.BOLD | java.awt.Font.ITALIC : 0);
            f = new java.awt.Font("Arial", st, 12);
            if (!"Arial".equalsIgnoreCase(f.getFamily())) f = new java.awt.Font(java.awt.Font.SANS_SERIF, st, 12);
        }
        BASE[style] = f;
        return f;
    }

    // AA + fractional metrics: the font's own "hinting" is off. Arial's Windows-tuned hinting rounded strokes
    // to 2 pixels at small sizes and made text look semibold; this way the real
    // thin weight is kept, and crispness comes from the contrast curve below.
    // Font module settings: "Anti-aliasing", "Fractional metrics", "Text contrast" (>1 edges get thinner, <1 fuller)
    private static FontRenderContext FRC = new FontRenderContext(null, true, true);
    private static final float[] CURVE = new float[256]; // alfa (0-255) -> v^kontrast
    private static float curveFor = -1f;

    /** Refresh the measuring context and contrast curve if the settings changed. */
    private static void sync() {
        boolean aa = FontMod.antialias(), fm = FontMod.fractional();
        if (FRC.isAntiAliased() != aa || FRC.usesFractionalMetrics() != fm) FRC = new FontRenderContext(null, aa, fm);
        float c = FontMod.contrast();
        if (c != curveFor) {
            curveFor = c;
            for (int i = 0; i < 256; i++) CURVE[i] = aa ? (float) Math.pow(i / 255.0, c) : (i >= 128 ? 1f : 0f);
        }
    }

    /** Font such that line height (device pixels) = ascent + descent. */
    /** Line height / em ratio (Arial ~1.117): for the same glyph size as the game font (em = size). */
    static float lineRatio(int style) {
        java.awt.Font b = base(style).deriveFont(100f);
        LineMetrics lm = b.getLineMetrics("Hg", FRC);
        return (lm.getAscent() + lm.getDescent()) / 100f;
    }

    private static java.awt.Font font(int style, int linePx) {
        java.awt.Font b = base(style).deriveFont(100f);
        LineMetrics lm = b.getLineMetrics("Hg", FRC);
        float ratio = (lm.getAscent() + lm.getDescent()) / 100f;
        return base(style).deriveFont(Math.max(4f, linePx / ratio));
    }

    // ------------------------------------------------------------- measuring

    /** Text width (GUI pixels, rounded up). */
    static int width(String s, int style, int sizeGui, int scale) {
        if (s.isEmpty()) return 0;
        String k = style + "|" + sizeGui + "|" + scale + "|" + s;
        Float adv = ADV.get(k);
        if (adv == null) {
            java.awt.Font f = font(style, sizeGui * scale);
            adv = (float) f.getStringBounds(s, FRC).getWidth();
            if (ADV.size() > 4000) ADV.clear();
            ADV.put(k, adv);
        }
        return (int) Math.ceil(adv / scale);
    }

    // ------------------------------------------------------------- texture generation

    private static E build(String s, int style, int sizeGui, int scale, float sigma, boolean edge) {
        sync();
        int linePx = sizeGui * scale;
        java.awt.Font f = font(style, linePx);
        LineMetrics lm = f.getLineMetrics(s, FRC);
        float asc = lm.getAscent(), desc = lm.getDescent();

        GlyphVector gv = f.createGlyphVector(FRC, s);
        float adv = (float) f.getStringBounds(s, FRC).getWidth();
        float[] xs = new float[s.length() + 1];
        if (gv.getNumGlyphs() == s.length()) {
            for (int i = 0; i < s.length(); i++) xs[i] = (float) gv.getGlyphPosition(i).getX();
        } else {
            for (int i = 0; i < s.length(); i++) xs[i] = (float) f.getStringBounds(s.substring(0, i), FRC).getWidth();
        }
        xs[s.length()] = adv;

        int blurR = sigma > 0 ? (int) Math.ceil(sigma * 3) : 0;
        int pad = 2 + blurR + (style >= 2 ? linePx / 4 : 0); // italic overhang + halo margin
        int w = (int) Math.ceil(adv) + pad * 2;
        int h = linePx + pad * 2;
        float baseline = pad + Math.round((linePx - (asc + desc)) / 2f + asc); // baseline on a whole pixel

        // 1) draw the text (white, alpha = coverage)
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, FontMod.antialias()
            ? RenderingHints.VALUE_TEXT_ANTIALIAS_ON : RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, FontMod.fractional()
            ? RenderingHints.VALUE_FRACTIONALMETRICS_ON : RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g2.setFont(f);
        g2.setColor(Color.WHITE);
        g2.drawString(s, (float) pad, baseline);
        g2.dispose();

        // Access pixels directly (getRGB converts the color model on every pixel), contrast curve from a table
        int n = w * h;
        int[] argb = ((java.awt.image.DataBufferInt) img.getRaster().getDataBuffer()).getData();
        float[] a = new float[n];
        for (int i = 0; i < n; i++) a[i] = CURVE[argb[i] >>> 24];

        float[] edgeA = edge ? dilate(a, w, h) : null;
        float[] glowA = sigma > 0 ? blur(a, w, h, sigma) : null;

        int rows = 1 + (edgeA != null ? 1 : 0) + (glowA != null ? 1 : 0);
        E e = new E();
        e.w = w; e.h = h; e.pad = pad; e.xs = xs;
        e.texW = w; e.texH = h * rows;
        int row = 1;
        if (edgeA != null) e.edgeRow = row++;
        if (glowA != null) e.glowRow = row;

        NativeImage ni = new NativeImage(e.texW, e.texH, true);
        put(ni, a, w, h, 0);
        if (edgeA != null) put(ni, edgeA, w, h, e.edgeRow);
        if (glowA != null) put(ni, glowA, w, h, e.glowRow);

        e.id = Identifier.fromNamespaceAndPath("futureplus", "hdtext/" + (idCounter++));
        DynamicTexture tex = new DynamicTexture(() -> "futureplus hd text", ni);
        Minecraft.getInstance().getTextureManager().register(e.id, tex);
        return e;
    }

    private static void put(NativeImage ni, float[] a, int w, int h, int row) {
        int oy = row * h;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int al = Math.round(Math.min(1f, a[y * w + x]) * 255f);
                ni.setPixel(x, oy + y, (al << 24) | 0xFFFFFF); // ARGB: white, the color is given when drawing
            }
        }
    }

    // A texture dropped from the cache may already have been drawn in the same frame (the GUI draws at the end of the frame). Releasing it
    // right away would make that frame draw a deleted texture; so it is released on the next client tick (after the frame is drawn).
    private static final java.util.ArrayList<Identifier> RELEASE = new java.util.ArrayList<>();
    static {
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.START_CLIENT_TICK.register(mc -> flushReleases());
    }

    private static void releaseLater(Identifier id) { RELEASE.add(id); }

    private static void flushReleases() {
        if (RELEASE.isEmpty()) return;
        for (Identifier id : RELEASE) {
            try { Minecraft.getInstance().getTextureManager().release(id); } catch (Throwable ignored) { }
        }
        RELEASE.clear();
    }

    private static float[] dilate(float[] a, int w, int h) {
        float[] o = new float[a.length];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            float m = 0;
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                int xx = x + dx, yy = y + dy;
                if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue;
                float v = a[yy * w + xx];
                if (dx != 0 && dy != 0) v *= 0.6f; // corners a bit soft
                if (v > m) m = v;
            }
            o[y * w + x] = m;
        }
        return o;
    }

    private static float[] blur(float[] a, int w, int h, float sigma) {
        int r = (int) Math.ceil(sigma * 3);
        float[] k = new float[r * 2 + 1];
        float sum = 0;
        for (int i = -r; i <= r; i++) { k[i + r] = (float) Math.exp(-(i * i) / (2 * sigma * sigma)); sum += k[i + r]; }
        for (int i = 0; i < k.length; i++) k[i] /= sum;
        float[] t = new float[a.length], o = new float[a.length];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            float v = 0;
            for (int i = -r; i <= r; i++) { int xx = x + i; if (xx >= 0 && xx < w) v += a[y * w + xx] * k[i + r]; }
            t[y * w + x] = v;
        }
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            float v = 0;
            for (int i = -r; i <= r; i++) { int yy = y + i; if (yy >= 0 && yy < h) v += t[yy * w + x] * k[i + r]; }
            o[y * w + x] = v;
        }
        // make the halo a bit fuller (the blur thins the alpha a lot)
        for (int i = 0; i < o.length; i++) o[i] = Math.min(1f, o[i] * 1.8f);
        return o;
    }

    private static E get(String s, int style, int sizeGui, int scale, float sigma, boolean edge) {
        String k = style + "|" + sizeGui + "|" + scale + "|" + Math.round(sigma * 10) + "|" + (edge ? 1 : 0) + "|" + s;
        E e = CACHE.get(k);
        if (e == null) {
            e = build(s, style, sizeGui, scale, sigma, edge);
            CACHE.put(k, e);
            if (CACHE.size() > CACHE_MAX) {
                Iterator<Map.Entry<String, E>> it = CACHE.entrySet().iterator();
                Map.Entry<String, E> old = it.next();
                it.remove();
                releaseLater(old.getValue().id);
            }
        }
        return e;
    }

    /** Release the old textures when the font/size changes. */
    static void clear() {
        curveFor = -1f;
        for (E e : CACHE.values()) releaseLater(e.id);
        CACHE.clear();
        ADV.clear();
    }

    // ------------------------------------------------------------- drawing

    /** Draw options. */
    static final class Opt {
        int style, sizeGui, scale;
        boolean shadow;          // 1 screen pixel dark shadow
        float glowSigma;         // 0 = no glow (device pixels)
        int glowArgb;            // glow color + alpha
        int edgeAlpha;           // dark outline alpha (0 = none)
    }

    private static void blitRow(GuiGraphicsExtractor g, E e, int row, int dx, int dy, int argb) {
        if ((argb >>> 24) == 0) return;
        g.blit(RenderPipelines.GUI_TEXTURED, e.id, dx, dy, 0f, (float) (row * e.h), e.w, e.h,
                e.w, e.h, e.texW, e.texH, argb);
    }

    /**
     * (x, y) = top left of the line box (GUI pixels). If rgbAt != null, per-letter color (gradient).
     */
    static void draw(GuiGraphicsExtractor g, String s, int x, int y, int argb, IntUnaryOperator rgbAt, Opt o) {
        if (s.isEmpty()) return;
        // If the outer transform is scaled (e.g. a HUD element's size, x0.65 small text) the text is rasterized at its REAL on-screen
        // size; otherwise the texture is resampled and blurs. The old path if there is rotation.
        org.joml.Matrix3x2f pm = g.pose();
        float k = 1f;
        boolean axis = Math.abs(pm.m01()) < 1e-4f && Math.abs(pm.m10()) < 1e-4f;
        if (axis) {
            float sx = Math.abs(pm.m00());
            if (sx > 0.2f && sx < 6f && Math.abs(sx - Math.abs(pm.m11())) < 1e-3f && Math.abs(sx - 1f) > 0.01f) k = sx;
        }
        int linePx = o.sizeGui * o.scale;
        int px = Math.max(4, Math.round(linePx * k));
        float sc = o.scale * k;                       // screen pixels / local unit
        E e = k == 1f ? get(s, o.style, o.sizeGui, o.scale, o.glowSigma, o.edgeAlpha > 0)
                      : get(s, o.style, px, 1, o.glowSigma * k, o.edgeAlpha > 0);
        int a = argb >>> 24;
        g.pose().pushMatrix();
        g.pose().translate((float) x, (float) y);
        g.pose().scale(1f / sc, 1f / sc); // from here on coordinates are SCREEN pixels: 1:1 drawing
        if (axis) {                       // snap the start point to a screen pixel (fractional position blur)
            double gs = Minecraft.getInstance().getWindow().getGuiScale();
            org.joml.Matrix3x2f m = g.pose();
            m.set(m.m00(), m.m01(), m.m10(), m.m11(), (float) (Math.round(m.m20() * gs) / gs), (float) (Math.round(m.m21() * gs) / gs));
        }
        int ox = -e.pad, oy = -e.pad;

        if (e.glowRow >= 0) blitRow(g, e, e.glowRow, ox, oy, o.glowArgb);
        if (e.edgeRow >= 0) blitRow(g, e, e.edgeRow, ox, oy, (Math.min(a, o.edgeAlpha) << 24));
        if (o.shadow) {
            int sa = Math.max(10, a / 2); // a light shadow: so the text doesn't look bold
            blitRow(g, e, 0, ox + 1, oy + 1, (sa << 24) | (((argb & 0xF8F8F8) >> 3) & 0xFFFFFF));
        }
        if (rgbAt == null) {
            blitRow(g, e, 0, ox, oy, argb);
        } else {
            // per letter: draw the matching slice of the texture with that letter's color
            int n = s.length();
            for (int i = 0; i < n; i++) {
                int x0 = (i == 0) ? 0 : e.pad + Math.round(e.xs[i]);
                int x1 = (i == n - 1) ? e.w : e.pad + Math.round(e.xs[i + 1]);
                if (x1 <= x0) continue;
                int col = (a << 24) | (rgbAt.applyAsInt(i) & 0xFFFFFF);
                g.blit(RenderPipelines.GUI_TEXTURED, e.id, ox + x0, oy, (float) x0, 0f, x1 - x0, e.h,
                        x1 - x0, e.h, e.texW, e.texH, col);
            }
        }
        g.pose().popMatrix();
    }
}