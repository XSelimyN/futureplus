package com.futureplus.client;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.futureplus.client.mixin.BakedSheetGlyphAccessor;
import com.futureplus.client.mixin.GlyphInstanceAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Style;
import org.joml.Matrix3x2fc;
import org.joml.Matrix4fc;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Draws glyphs at their REAL screen size for scaled GUI text (e.g. Future's ClickGui: its own 0.8 scale).
 *
 * GUI glyphs are always sampled NEAREST (GlyphRenderState). Since the game font is rasterized at the GUI scale,
 * at scale 1.0 a texture pixel = a screen pixel; but at scale 0.8 a 27 px glyph shrinks nearest-pixel to 21.6 px,
 * and some columns get skipped: one stroke of an H is 2 pixels, the other 1 (a "calligraphic" look). Here the glyph is
 * rasterized again at that scale, put in its own atlas, snapped to a whole screen pixel and drawn 1:1.
 */
public final class ScaledGlyphs {
    private ScaledGlyphs() { }

    /** Baked game glyph -> source (provider, glyph, left/top offset at scale 1.0). */
    record Src(AwtGlyphProvider provider, int cp, float left1, float top1) { }
    private static final Map<Object, Src> SRC = new WeakHashMap<>();

    static void register(Object baked, Src src) { synchronized (SRC) { SRC.put(baked, src); } }

    /** A glyph in the atlas: pixel size, the bitmap's screen pixel offset from the baseline, UV. */
    record Entry(int gen, int w, int h, int dx, int dy, float u0, float v0, float u1, float v1) { }

    // ---- atlas (shelf packing; starts over when full) ----
    private static final int SIZE = 2048;   // 3D glyphs are large up close (~100 px per block)
    private static NativeImage image;
    private static DynamicTexture texture;
    private static int gen = 1, shelfX, shelfY, shelfH;
    private static boolean dirty;
    // changed region: uploading the whole atlas (2048² = 16 MB) for every new glyph crushed the FPS, so only this rectangle goes
    private static int dx0 = Integer.MAX_VALUE, dy0 = Integer.MAX_VALUE, dx1, dy1;
    // new rasterizations per frame limit (GuiRendererPrepareMixin resets it every frame)
    private static final int BUDGET = 48;
    private static int budget = BUDGET;

    public static void newFrame() { budget = BUDGET; }
    static boolean takeBudget() { return budget-- > 0; }

    private static void flush() {
        if (!dirty || texture == null) return;
        dirty = false;
        if (dx1 <= dx0 || dy1 <= dy0) { texture.upload(); }
        else com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder()
            .writeToTexture(texture.getTexture(), image, 0, 0, dx0, dy0, dx1 - dx0, dy1 - dy0, dx0, dy0);
        dx0 = dy0 = Integer.MAX_VALUE; dx1 = dy1 = 0;
    }

    private static final net.minecraft.resources.Identifier ATLAS_ID =
        net.minecraft.resources.Identifier.fromNamespaceAndPath("futureplus", "scaled_glyphs");
    private static final net.minecraft.client.gui.font.GlyphRenderTypes TYPES =
        net.minecraft.client.gui.font.GlyphRenderTypes.createForIntensityTexture(ATLAS_ID);

    static int gen() { return gen; }

    /** lum: glyph coverage (contrast curve applied), w*h. null: didn't fit in the atlas. */
    static Entry put(byte[] lum, int w, int h, int dx, int dy) {
        if (w > SIZE || h > SIZE) return null;
        if (image == null) {
            image = new NativeImage(SIZE, SIZE, true);
            texture = new DynamicTexture(() -> "futureplus scaled glyphs", image);
            Minecraft.getInstance().getTextureManager().register(ATLAS_ID, texture);   // 3D drawing is bound by id
        }
        if (shelfX + w + 1 > SIZE) { shelfX = 0; shelfY += shelfH + 1; shelfH = 0; }
        if (shelfY + h > SIZE) {                       // full: clear it, old entries are invalidated by gen
            image.fillRect(0, 0, SIZE, SIZE, 0);
            shelfX = shelfY = shelfH = 0;
            gen++;
            dx0 = dy0 = 0; dx1 = dy1 = 0;               // full upload
        }
        int x0 = shelfX, y0 = shelfY;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = lum[y * w + x] & 0xFF;
                image.setPixel(x0 + x, y0 + y, (c << 24) | (c << 16) | (c << 8) | c);
            }
        }
        if (dx0 != 0 || dx1 != 0 || dy0 != 0) {        // don't keep a region if a full upload is pending
            dx0 = Math.min(dx0, x0); dy0 = Math.min(dy0, y0); dx1 = Math.max(dx1, x0 + w); dy1 = Math.max(dy1, y0 + h);
        }
        shelfX += w + 1;
        shelfH = Math.max(shelfH, h);
        dirty = true;
        return new Entry(gen, w, h, dx, dy, x0 / (float) SIZE, y0 / (float) SIZE, (x0 + w) / (float) SIZE, (y0 + h) / (float) SIZE);
    }

    static void clear() { gen++; shelfX = shelfY = shelfH = 0; if (image != null) image.fillRect(0, 0, SIZE, SIZE, 0); dirty = true; dx0 = dy0 = dx1 = dy1 = 0; }

    // Scope: scaled GUI text (Future's ClickGui) always; at scale 1.0 only chat and inventory/item text
    // (with a 1 GUI pixel shadow, the look the user liked). The HUD's unscaled text keeps the game's 1 screen pixel
    // shadow. Which section we're in is recorded when the text state is created (GuiTextScopeMixin).
    public static boolean inHud, inChat, inItem;
    public static boolean current;
    /** Whether we're inside GuiRenderer.prepareText (glyphs are added at the end of the frame). Future adds its glyphs itself while drawing. */
    public static boolean inPrepare;

    public static boolean allowNow() {
        if (inChat) return true;
        if (inHud) return false;
        if (inItem) return true;
        var sc = Minecraft.getInstance().screen;
        return sc instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>
            || (sc != null && sc.getClass().getName().startsWith("net.futureclient"));
    }

    /** GuiRenderState: under a scaled transform, replace our font's glyph with its copy drawn at real size. */
    public static TextRenderable wrap(Matrix3x2fc pose, TextRenderable r) {
        if (!(r instanceof GlyphInstanceAccessor gi)) return r;
        if (Math.abs(pose.m01()) > 1e-4f || Math.abs(pose.m10()) > 1e-4f) return r;
        float k = pose.m00();
        if (k < 0.3f || k > 4f || Math.abs(k - pose.m11()) > 1e-3f) return r;
        // scaled text (Future's ClickGui is drawn at 0.8 scale inside the HUD) everywhere; at scale 1.0 only in scope
        if (Math.abs(k - 1f) < 0.01f && !(inPrepare ? current : allowNow())) return r;
        Style st = gi.futureplus$style();
        if (st.isItalic() || st.isBold()) return r;
        Src src;
        synchronized (SRC) { src = SRC.get(gi.futureplus$glyph()); }
        if (src == null) return r;
        double gs = Minecraft.getInstance().getWindow().getGuiScale();
        Entry e;
        try { e = src.provider().scaled(src.cp(), (float) (k * gs)); } catch (Throwable t) { return r; }
        if (e == null) return r;
        flush();
        BakedSheetGlyphAccessor g = (BakedSheetGlyphAccessor) gi.futureplus$glyph();
        // baseline/pen position at scale 1.0 (GUI units, in text space)
        float penX = gi.futureplus$x() + g.futureplus$left() - src.left1();
        float baseY = gi.futureplus$y() + g.futureplus$up() + src.top1();
        // in these sections the shadow is 1 GUI pixel (the look the user liked); outside, the game's 1 screen pixel shadow stays
        return new Scaled(r, e, pose, (float) gs, k, penX, baseY, gi.futureplus$color(), gi.futureplus$shadowColor(), 1f);
    }

    /** With Font+ active, also draws the game's text in the 3D world crisply with the same atlas. */
    public static TextRenderable wrap3D(Matrix4fc pose, TextRenderable r) {
        if (!FontMod.isEnabled() || !(r instanceof GlyphInstanceAccessor gi)) return r;
        float scale = pose.m00();
        if (scale <= 0f || Math.abs(pose.m01()) > scale * 1e-3f || Math.abs(pose.m02()) > scale * 1e-3f
            || Math.abs(pose.m10()) > scale * 1e-3f || Math.abs(pose.m12()) > scale * 1e-3f
            || Math.abs(Math.abs(pose.m11()) - scale) > scale * 1e-3f || pose.m11() > 0f) return r;
        Style style = gi.futureplus$style();
        if (style.isItalic() || style.isBold()) return r;
        Src src;
        synchronized (SRC) { src = SRC.get(gi.futureplus$glyph()); }
        if (src == null) return r;
        Minecraft mc = Minecraft.getInstance();
        BakedSheetGlyphAccessor glyph = (BakedSheetGlyphAccessor) gi.futureplus$glyph();
        float penX = gi.futureplus$x() + glyph.futureplus$left() - src.left1();
        float baseY = gi.futureplus$y() + glyph.futureplus$up() + src.top1();
        org.joml.Vector3f pen = pose.transformPosition(penX, baseY, 0f, new org.joml.Vector3f());
        float depth = -pen.z;
        if (depth < 0.05f) return r;
        float screenH = mc.getWindow().getHeight(), screenW = mc.getWindow().getWidth();
        float focal = (float) (screenH / 2.0 / Math.tan(Math.toRadians(mc.gameRenderer.getMainCamera().getFov()) / 2.0));
        float pixelsPerUnit = scale * focal / depth;
        if (pixelsPerUnit < 0.3f || pixelsPerUnit > 60f) return r;
        float quantized = (float) Math.exp(Math.round(Math.log(pixelsPerUnit) * 25.0) / 25.0);
        Entry entry;
        try { entry = src.provider().scaled(src.cp(), quantized); } catch (Throwable t) { return r; }
        if (entry == null) return r;
        flush();
        return new Scaled3D(r, entry, pen, focal, screenW / 2f, screenH / 2f, gi.futureplus$color(), gi.futureplus$shadowColor());
    }

    private record Scaled3D(TextRenderable orig, Entry e, org.joml.Vector3f pen, float focal, float cx, float cy,
                            int color, int shadowColor) implements TextRenderable.Styled {
        @Override public void render(Matrix4fc ignored, VertexConsumer buf, int light, boolean flat) {
            if (e.gen() != gen) return;
            float depth = -pen.z();
            int px = Math.round(cx + pen.x() * focal / depth);
            int py = Math.round(cy - pen.y() * focal / depth);
            if (shadowColor != 0) quad(buf, px + 1, py + 1, depth * 1.02f, shadowColor, light);
            quad(buf, px, py, depth, color, light);
        }
        private void quad(VertexConsumer b, int px, int py, float depth, int col, int light) {
            float x0 = px + e.dx(), y0 = py + e.dy(), x1 = x0 + e.w(), y1 = y0 + e.h();
            float k = depth / focal;
            b.addVertex((x0 - cx) * k, (cy - y0) * k, -depth).setColor(col).setUv(e.u0(), e.v0()).setLight(light);
            b.addVertex((x0 - cx) * k, (cy - y1) * k, -depth).setColor(col).setUv(e.u0(), e.v1()).setLight(light);
            b.addVertex((x1 - cx) * k, (cy - y1) * k, -depth).setColor(col).setUv(e.u1(), e.v1()).setLight(light);
            b.addVertex((x1 - cx) * k, (cy - y0) * k, -depth).setColor(col).setUv(e.u1(), e.v0()).setLight(light);
        }
        @Override public RenderType renderType(Font.DisplayMode m) { return TYPES.select(m); }
        @Override public GpuTextureView textureView() { return texture.getTextureView(); }
        @Override public RenderPipeline guiPipeline() { return orig.guiPipeline(); }
        @Override public float left() { return orig.left(); }
        @Override public float top() { return orig.top(); }
        @Override public float right() { return orig.right(); }
        @Override public float bottom() { return orig.bottom(); }
        @Override public Style style() { return ((Styled) orig).style(); }
        @Override public float activeLeft() { return ((Styled) orig).activeLeft(); }
        @Override public float activeTop() { return ((Styled) orig).activeTop(); }
        @Override public float activeRight() { return ((Styled) orig).activeRight(); }
        @Override public float activeBottom() { return ((Styled) orig).activeBottom(); }
    }

    private record Scaled(TextRenderable orig, Entry e, Matrix3x2fc pose, float gs, float k, float penX, float baseY,
                          int color, int shadowColor, float shadowOffset) implements TextRenderable.Styled {
        @Override
        public void render(Matrix4fc ignored, VertexConsumer buf, int light, boolean flat) {
            if (e.gen() != gen) return;                       // the atlas was reset meanwhile
            // pen and baseline on a whole screen pixel; glyph bitmap 1:1
            int px = Math.round((penX * k + pose.m20()) * gs);
            int py = Math.round((baseY * k + pose.m21()) * gs);
            if (shadowColor != 0) {
                int so = Math.max(1, Math.round(shadowOffset * k * gs));
                quad(buf, px + so, py + so, shadowColor, light);
            }
            quad(buf, px, py, color, light);
        }

        private void quad(VertexConsumer b, int px, int py, int col, int light) {
            float x0 = (px + e.dx()) / gs, y0 = (py + e.dy()) / gs;
            float x1 = x0 + e.w() / gs, y1 = y0 + e.h() / gs;
            b.addVertex(x0, y0, 0f).setColor(col).setUv(e.u0(), e.v0()).setLight(light);
            b.addVertex(x0, y1, 0f).setColor(col).setUv(e.u0(), e.v1()).setLight(light);
            b.addVertex(x1, y1, 0f).setColor(col).setUv(e.u1(), e.v1()).setLight(light);
            b.addVertex(x1, y0, 0f).setColor(col).setUv(e.u1(), e.v0()).setLight(light);
        }

        @Override public RenderType renderType(Font.DisplayMode m) { return orig.renderType(m); }
        @Override public GpuTextureView textureView() { return texture.getTextureView(); }
        @Override public RenderPipeline guiPipeline() { return orig.guiPipeline(); }
        @Override public float left() { return orig.left(); }
        @Override public float top() { return orig.top(); }
        @Override public float right() { return orig.right(); }
        @Override public float bottom() { return orig.bottom(); }
        @Override public Style style() { return ((Styled) orig).style(); }
        @Override public float activeLeft() { return ((Styled) orig).activeLeft(); }
        @Override public float activeTop() { return ((Styled) orig).activeTop(); }
        @Override public float activeRight() { return ((Styled) orig).activeRight(); }
        @Override public float activeBottom() { return ((Styled) orig).activeBottom(); }
    }
}
