package com.futureplus.client;

import com.mojang.blaze3d.font.GlyphBitmap;
import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.font.UnbakedGlyph;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.gui.font.glyphs.EmptyGlyph;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * The game's glyph rasterizer for the Futureplus fonts (futureplus:*.ttf).
 *
 * The game makes TTF glyphs with FreeType; the Futureplus HD engine (main menu, HUD) with Java's font engine.
 * Since the two draw the same font differently, game text looked softer/hazier than the menu's. This provider
 * makes the game's glyphs the SAME way as the HD engine: the same anti-aliasing settings, the same contrast curve
 * (v^1.3), glyph advance rounded to a whole screen pixel, glyph box snapped to a whole pixel.
 * since oversample = GUI scale, every texture pixel lines up with a screen pixel.
 */
final class AwtGlyphProvider implements GlyphProvider {

    // Font module settings (read on reload): anti-aliasing, fractional metrics, contrast
    private final FontRenderContext FRC = new FontRenderContext(null, FontMod.antialias(), FontMod.fractional());
    private final byte[] LUT = new byte[256];
    {
        boolean aa = FontMod.antialias();
        double c = FontMod.contrast();
        for (int i = 0; i < 256; i++) LUT[i] = (byte) (aa ? Math.round(Math.pow(i / 255.0, c) * 255.0) : (i >= 128 ? 255 : 0));
    }

    private static final Map<String, java.awt.Font> BASES = new HashMap<>();
    private static final Map<String, IntSet> SUPPORTED = new HashMap<>();

    private final java.awt.Font font;
    private final float oversample;
    private final IntSet supported;
    private final Map<Integer, UnbakedGlyph> cache = new HashMap<>();

    /** key: the font file's id (the same file is read once); size: GUI pixels; oversample: GUI scale. */
    static AwtGlyphProvider create(String key, InputStream ttf, float size, float oversample) throws Exception {
        java.awt.Font base;
        synchronized (BASES) {
            base = BASES.get(key);
            if (base == null) {
                base = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, ttf);
                BASES.put(key, base);
            }
        }
        IntSet sup;
        synchronized (SUPPORTED) {
            sup = SUPPORTED.get(key);
            if (sup == null) {
                sup = new IntOpenHashSet();
                for (int cp = 32; cp < 0x10000; cp++) {
                    if (cp >= 0xD800 && cp <= 0xDFFF) continue;
                    if (base.canDisplay(cp)) sup.add(cp);
                }
                SUPPORTED.put(key, sup);
            }
        }
        return new AwtGlyphProvider(base.deriveFont(Math.max(1f, size * oversample)), oversample, sup);
    }

    private AwtGlyphProvider(java.awt.Font font, float oversample, IntSet supported) {
        this.font = font;
        this.oversample = oversample;
        this.supported = supported;
    }

    @Override
    public IntSet getSupportedGlyphs() { return supported; }

    /**
     * While the game font loads (on every resource pack reload) it calls getGlyph + info() for EVERY supported glyph.
     * Drawing the glyph right here meant rasterizing tens of thousands of glyphs with AWT: the game froze while a server
     * pack loaded. Now glyphs are lazy: info() only measures the advance, and the bitmap is made on the first bake() (when the glyph first
     * shows on screen).
     */
    @Override
    public UnbakedGlyph getGlyph(int cp) {
        if (!supported.contains(cp)) return null;           // not in the font: the next provider (vanilla) gives it
        synchronized (cache) {
            UnbakedGlyph g = cache.get(cp);
            if (g == null) {
                g = new LazyGlyph(cp);
                cache.put(cp, g);
            }
            return g;
        }
    }

    private final class LazyGlyph implements UnbakedGlyph {
        private final int cp;
        private volatile GlyphInfo info;
        private volatile UnbakedGlyph built;

        LazyGlyph(int cp) { this.cp = cp; }

        @Override
        public GlyphInfo info() {
            GlyphInfo i = info;
            if (i == null) {
                GlyphVector gv;
                synchronized (font) { gv = font.createGlyphVector(FRC, new String(Character.toChars(cp))); }
                float adv = Math.round(gv.getGlyphMetrics(0).getAdvanceX());   // whole screen pixel
                info = i = new HdInfo(adv / oversample, 1f / oversample);
            }
            return i;
        }

        @Override
        public BakedGlyph bake(Stitcher stitcher) {
            UnbakedGlyph b = built;
            if (b == null) built = b = build(cp, info());
            BakedGlyph baked = b.bake(stitcher);
            // record the source so scaled GUI text (ScaledGlyphs) can redraw the glyph at its real size
            if (b instanceof AwtGlyph a) ScaledGlyphs.register(baked, new ScaledGlyphs.Src(AwtGlyphProvider.this, cp, a.left(), a.top()));
            return baked;
        }
    }

    /** Rasterizes the glyph with the given font; coverage passed through the LUT. null: empty glyph. */
    private record Raster(byte[] lum, int w, int h, int x0, int y0) { }   // x0/y0: the bitmap's offset from the baseline

    private Raster raster(java.awt.Font f, int cp) {
        String s = new String(Character.toChars(cp));
        GlyphVector gv;
        synchronized (f) { gv = f.createGlyphVector(FRC, s); }
        Rectangle b = gv.getPixelBounds(FRC, 0f, 0f);
        if (b.width <= 0 || b.height <= 0) return null;

        int pad = 1;
        int w = b.width + pad * 2, h = b.height + pad * 2;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, FontMod.antialias()
            ? RenderingHints.VALUE_TEXT_ANTIALIAS_ON : RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, FontMod.fractional()
            ? RenderingHints.VALUE_FRACTIONALMETRICS_ON : RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g2.setColor(Color.WHITE);
        g2.drawGlyphVector(gv, (float) (pad - b.x), (float) (pad - b.y));     // baseline on a whole pixel
        g2.dispose();

        // getRGB converts colors pixel by pixel; reading alpha from the raw array is much faster
        int[] argb = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        byte[] lum = new byte[w * h];
        for (int i = 0; i < lum.length; i++) lum[i] = LUT[argb[i] >>> 24];
        return new Raster(lum, w, h, b.x - pad, b.y - pad);
    }

    private UnbakedGlyph build(int cp, GlyphInfo info) {
        Raster r = raster(font, cp);
        if (r == null) return new HdEmpty(info, info.getAdvance());
        int w = r.w(), h = r.h();
        byte[] lum = r.lum();
        float left = r.x0() / oversample;               // GUI birimi
        float top = -r.y0() / oversample;               // up from the baseline (GUI units)
        return new AwtGlyph(info, lum, w, h, left, top, oversample);
    }

    /**
     * Glyph info. Vanilla shifts the shadow and the second copy of bold text by 1 GUI pixel (3 screen
     * pixels at GUI scale 3): with the HD font that looks like a shifted second text behind it and blurs everything.
     * Like the Futureplus HD engine, both are 1 SCREEN pixel.
     */
    // ScaledGlyphs: screen scale (GUI scale x text scale) -> glyphs rasterized at that size
    private final Map<Integer, java.awt.Font> scaledFonts = new HashMap<>();
    private final Map<Long, ScaledGlyphs.Entry> scaled = new HashMap<>();

    /** deviceScale: screen pixels per GUI unit (the scaled counterpart of oversample). null: empty glyph/outside the atlas. */
    ScaledGlyphs.Entry scaled(int cp, float deviceScale) {
        int key = Math.round(deviceScale * 100f);
        long k = ((long) key << 32) | (cp & 0xFFFFFFFFL);
        ScaledGlyphs.Entry e = scaled.get(k);
        if (e != null && e.gen() == ScaledGlyphs.gen()) return e;
        if (e == null && scaled.containsKey(k)) return null;
        if (!ScaledGlyphs.takeBudget()) return null;           // this frame's rasterization budget is used up (next frame)
        java.awt.Font f = scaledFonts.computeIfAbsent(key, kk -> font.deriveFont(font.getSize2D() * kk / 100f / oversample));
        Raster r = raster(f, cp);
        e = r == null ? null : ScaledGlyphs.put(r.lum(), r.w(), r.h(), r.x0(), r.y0());
        if (r != null && e == null) return null;              // didn't fit in the atlas: count as permanently "empty"
        scaled.put(k, e);
        return e;
    }

    private record HdInfo(float advance, float px) implements GlyphInfo {
        @Override public float getAdvance() { return advance; }
        @Override public float getBoldOffset() { return px; }
        // shadow is 1 screen pixel (the look liked on the HUD); in the ClickGui/chat/inventory ScaledGlyphs draws 1 GUI pixel
        @Override public float getShadowOffset() { return px; }
    }

    /** A glyph that isn't drawn, like a space (advance and bold offset from HdInfo). */
    private record HdEmpty(GlyphInfo info, float advance) implements UnbakedGlyph {
        @Override public BakedGlyph bake(Stitcher stitcher) { return new EmptyGlyph(advance).bake(stitcher); }
    }

    private record AwtGlyph(GlyphInfo info, byte[] lum, int w, int h, float left, float top, float oversample)
            implements UnbakedGlyph {
        @Override
        public BakedGlyph bake(Stitcher stitcher) {
            return stitcher.stitch(info, new GlyphBitmap() {
                @Override public int getPixelWidth() { return w; }
                @Override public int getPixelHeight() { return h; }
                @Override public float getOversample() { return oversample; }
                @Override public boolean isColored() { return false; }
                @Override public float getBearingLeft() { return left; }
                @Override public float getBearingTop() { return top; }
                @Override
                public void upload(int x, int y, GpuTexture tex) {
                    ByteBuffer buf = ByteBuffer.allocateDirect(lum.length);
                    buf.put(lum).flip();
                    RenderSystem.getDevice().createCommandEncoder()
                        .writeToTexture(tex, buf, NativeImage.Format.LUMINANCE, 0, 0, x, y, w, h);
                }
            });
        }
    }
}
