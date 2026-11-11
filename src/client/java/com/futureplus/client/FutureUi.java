package com.futureplus.client;

import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Shared drawing for all of the game's buttons, sliders, lists and inventory slots. Corners slightly
 * rounded: drawn in screen pixels with anti-aliasing (crisp regardless of GUI scale). Hover fades are
 * time based. Colors come from the ClickGui theme (Future red by default).
 */
public final class FutureUi {
    private FutureUi() {}

    private static final float HOVER_S = 0.12f;
    private static final class Anim { float v; long last; }
    private static final Map<Object, Anim> HOVER = new WeakHashMap<>();

    /** Default corner radius (GUI units). */
    public static final float RADIUS = 2.5f;

    public static int accent() { return ClickGui.themeColor(0f) & 0xFFFFFF; }
    public static int accentDark() { return ClickGui.themeColor(1f) & 0xFFFFFF; }

    /** smoothed hover ratio for key (0..1). */
    public static float hover(Object key, boolean on) {
        long now = System.nanoTime();
        Anim s = HOVER.get(key);
        if (s == null) { s = new Anim(); s.v = on ? 1f : 0f; s.last = now; HOVER.put(key, s); }
        float dt = Math.min(0.1f, (now - s.last) / 1e9f);                // long difference: no float precision loss
        s.last = now;
        s.v = Math.max(0f, Math.min(1f, s.v + (on ? dt : -dt) / HOVER_S));
        return s.v * s.v * (3f - 2f * s.v);                               // a single curve: no jump when the direction changes
    }

    public static int argb(float a, int rgb) { return (Math.round(Math.max(0f, Math.min(1f, a)) * 255) << 24) | (rgb & 0xFFFFFF); }

    public static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int g = (int) (((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
        int bl = (int) ((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return (r << 16) | (g << 8) | bl;
    }

    // ------------------------------------------------------------- rounded shape (in screen pixels)

    /** Filled rounded rectangle; coordinates in GUI units. */
    public static void round(GuiGraphicsExtractor g, float x, float y, float w, float h, float r, int argb) {
        shape(g, x, y, w, h, r, -1f, argb);
    }

    /** Rounded edge 1 screen pixel thick. */
    public static void roundEdge(GuiGraphicsExtractor g, float x, float y, float w, float h, float r, int argb) {
        shape(g, x, y, w, h, r, 1f, argb);
    }

    private static void shape(GuiGraphicsExtractor g, float x, float y, float w, float h, float r, float ring, int argb) {
        if ((argb >>> 24) == 0 || w <= 0 || h <= 0) return;
        float gs = (float) Minecraft.getInstance().getWindow().getGuiScale();
        int X0 = Math.round(x * gs), Y0 = Math.round(y * gs), X1 = Math.round((x + w) * gs), Y1 = Math.round((y + h) * gs);
        if (X1 - X0 < 2 || Y1 - Y0 < 2) return;
        float R = Math.min(r * gs, Math.min(X1 - X0, Y1 - Y0) / 2f);
        int Ri = (int) Math.ceil(R);
        int a = argb >>> 24, rgb = argb & 0xFFFFFF;
        int t = ring > 0 ? Math.max(1, Math.round(ring)) : 0;
        float cx = (X0 + X1) / 2f, cy = (Y0 + Y1) / 2f, hx = (X1 - X0) / 2f, hy = (Y1 - Y0) / 2f;
        g.pose().pushMatrix();
        g.pose().scale(1f / gs, 1f / gs);
        // Small shapes (slots, buttons): drawn to a texture once, then a single blit. A pixel by pixel fill
        // meant thousands of rectangles per frame in the inventory and made the game stutter.
        int W = X1 - X0, H = Y1 - Y0;
        if (W <= MASK_MAX && H <= MASK_MAX) {
            Identifier id = mask(W, H, R, t);
            g.blit(RenderPipelines.GUI_TEXTURED, id, X0, Y0, 0f, 0f, W, H, W, H, W, H, argb);
            g.pose().popMatrix();
            return;
        }
        // middle band without corners
        if (Y1 - Ri > Y0 + Ri) {
            if (t == 0) g.fill(X0, Y0 + Ri, X1, Y1 - Ri, argb);
            else { g.fill(X0, Y0 + Ri, X0 + t, Y1 - Ri, argb); g.fill(X1 - t, Y0 + Ri, X1, Y1 - Ri, argb); }
        }
        for (int py = Y0; py < Y1; py++) {
            if (py >= Y0 + Ri && py < Y1 - Ri) { py = Y1 - Ri - 1; continue; }
            // straight part between the corners
            boolean edgeRow = py - Y0 < t || Y1 - 1 - py < t;
            if (t == 0 || edgeRow) g.fill(X0 + Ri, py, X1 - Ri, py + 1, argb);
            row(g, X0, X0 + Ri, py, cx, cy, hx, hy, R, t, a, rgb);
            row(g, X1 - Ri, X1, py, cx, cy, hx, hy, R, t, a, rgb);
        }
        g.pose().popMatrix();
    }

    private static final int MASK_MAX = 256;
    private static final Map<Long, Identifier> MASKS = new HashMap<>();
    private static int maskId = 0;

    /** W x H (screen pixels) white shape mask; alpha = coverage. Generated once per size / radius / thickness. */
    private static Identifier mask(int W, int H, float R, int t) {
        long key = W | ((long) H << 9) | ((long) t << 18) | ((long) Math.round(R * 16f) << 24);
        Identifier id = MASKS.get(key);
        if (id != null) return id;
        NativeImage img = new NativeImage(W, H, true);
        float hx = W / 2f, hy = H / 2f;
        for (int yy = 0; yy < H; yy++) for (int xx = 0; xx < W; xx++) {
            float cov = cover(xx + 0.5f, yy + 0.5f, hx, hy, hx, hy, R);
            if (t > 0) cov -= cover(xx + 0.5f, yy + 0.5f, hx, hy, hx - t, hy - t, Math.max(0f, R - t));
            img.setPixel(xx, yy, (Math.round(Math.max(0f, Math.min(1f, cov)) * 255f) << 24) | 0xFFFFFF);
        }
        id = Identifier.fromNamespaceAndPath("futureplus", "ui_mask/" + (maskId++));
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "futureplus ui mask", img));
        MASKS.put(key, id);
        return id;
    }

    /** Corner row: fully covered pixels as one piece, the edge ones with partial alpha. */
    private static void row(GuiGraphicsExtractor g, int xa, int xb, int py, float cx, float cy, float hx, float hy,
                            float R, int t, int a, int rgb) {
        int runStart = -1;
        for (int px = xa; px < xb; px++) {
            float cov = cover(px + 0.5f, py + 0.5f, cx, cy, hx, hy, R);
            if (t > 0) cov -= cover(px + 0.5f, py + 0.5f, cx, cy, hx - t, hy - t, Math.max(0f, R - t));
            if (cov >= 0.995f) { if (runStart < 0) runStart = px; continue; }
            if (runStart >= 0) { g.fill(runStart, py, px, py + 1, (a << 24) | rgb); runStart = -1; }
            if (cov > 0.004f) g.fill(px, py, px + 1, py + 1, (Math.round(a * cov) << 24) | rgb);
        }
        if (runStart >= 0) g.fill(runStart, py, xb, py + 1, (a << 24) | rgb);
    }

    private static float cover(float px, float py, float cx, float cy, float hx, float hy, float R) {
        if (hx <= 0 || hy <= 0) return 0f;
        float qx = Math.abs(px - cx) - (hx - R), qy = Math.abs(py - cy) - (hy - R);
        float ox = Math.max(qx, 0f), oy = Math.max(qy, 0f);
        float d = (float) Math.sqrt(ox * ox + oy * oy) + Math.min(Math.max(qx, qy), 0f) - R;
        return Math.max(0f, Math.min(1f, 0.5f - d));
    }

    // ------------------------------------------------------------- components

    /** Button surface (vanilla draws the text). */
    public static void button(GuiGraphicsExtractor g, Object key, int x, int y, int w, int h,
                              boolean active, boolean hovered, float alpha) {
        float hv = hover(key, active && hovered);
        int acc = accent();
        int base = mix(0x16161A, mix(0x16161A, accentDark(), 0.45f), hv);
        round(g, x, y, w, h, RADIUS, argb((active ? 0.82f : 0.55f) * alpha, base));
        // a very light glow on the top half: so the surface doesn't look flat
        round(g, x + 1, y + 1, w - 2, h / 2f - 1, RADIUS - 1, argb((0.035f + 0.04f * hv) * alpha, 0xFFFFFF));
        int edge = active ? mix(0x3C3C44, acc, hv) : 0x2A2A30;
        roundEdge(g, x, y, w, h, RADIUS, argb((active ? 0.85f + 0.15f * hv : 0.6f) * alpha, edge));
    }

    /** Slider handle: a thin, rounded theme bar. */
    public static void sliderHandle(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean active, float alpha) {
        float cx = x + w / 2f;
        round(g, cx - 2f, y + 3f, 4f, h - 6f, 2f, argb(alpha, active ? accent() : 0x55555C));
    }

    /** List selection: a light theme fill, a thin edge, an accent bar on the left. */
    public static void selection(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean focused) {
        int acc = accent();
        round(g, x, y, w, h, RADIUS, argb(focused ? 0.16f : 0.10f, acc));
        roundEdge(g, x, y, w, h, RADIUS, argb(focused ? 0.6f : 0.35f, acc));
        round(g, x + 2, y + 3, 2f, h - 6, 1f, argb(1f, acc));
    }

    /** The plain background color of the dark container texture (GuiTintMixin: vanilla #C6C6C6 x DARK_TINT). */
    public static final int DARK_TINT = 0x2C2C33, DARK_BG = 0x222227;

    /** Inventory / hotbar slot (18x18 cell): darker than the background, inset. hv: hover (0..1), selected: the selected hotbar slot. */
    public static void slot(GuiGraphicsExtractor g, float x, float y, float w, float h, float hv, boolean selected) {
        int acc = accent();
        float e = selected ? 1f : hv;
        round(g, x, y, w, h, 2f, argb(1f, mix(0x141418, mix(0x141418, accentDark(), 0.45f), Math.max(hv, selected ? 0.5f : 0f))));
        roundEdge(g, x, y, w, h, 2f, argb(1f, mix(0x34343C, acc, e)));
    }

    // ------------------------------------------------------------- background

    /**
     * Future+ menu background (instead of the vanilla panorama), made entirely from the theme color: a dark background, a fixed faint
     * grid the size of a Minecraft block (16) with a light wave slowly flowing diagonally across its cells, small square sparks
     * drifting up, a large breathing emblem in the middle, shadow at the edges.
     */
    public static void backdrop(GuiGraphicsExtractor g, int w, int h, boolean emblem) {
        float t = (System.nanoTime() % 3_600_000_000_000L) / 1e9f;
        int dark = accentDark(), acc = accent();
        g.fillGradient(0, 0, w, h, argb(1f, mix(0x060607, dark, 0.20f)), argb(1f, mix(0x060607, dark, 0.38f)));

        // block grid: each cell in its own shade, a diagonal light wave passes over it
        final int cell = 16;
        int cols = w / cell + 2, rows = h / cell + 2;
        int ox = (w % cell) / 2 - cell, oy = (h % cell) / 2 - cell;       // the grid is fixed, the light flows (no stepwise shifting)
        for (int cy = 0; cy < rows; cy++) {
            for (int cx = 0; cx < cols; cx++) {
                int gx = cx;
                float n = hash(gx, cy);
                float wave = 0.5f + 0.5f * (float) Math.sin(t * 0.7f - (gx * 0.35f + cy * 0.55f));
                float a = n * 0.035f + wave * wave * 0.05f * (0.4f + n);
                if (a < 0.006f) continue;
                int x = ox + cx * cell, y = oy + cy * cell;
                g.fill(x + 1, y + 1, x + cell, y + cell, argb(a, acc));
            }
        }
        int line = argb(0.035f, 0xFFFFFF);
        for (int x = ox; x < w; x += cell) g.fill(x, 0, x + 1, h, line);
        for (int y = oy; y < h; y += cell) g.fill(0, y, w, y + 1, line);

        // sparks: drift up, sway slightly sideways, fade out near the top.
        // Drawn in screen pixels: doesn't snap to GUI pixels and move choppily.
        float gs = (float) Minecraft.getInstance().getWindow().getGuiScale();
        g.pose().pushMatrix();
        g.pose().scale(1f / gs, 1f / gs);
        for (int i = 0; i < 34; i++) {
            float sp = 6f + hash(i, 7) * 14f;
            float life = (t * sp / h + hash(i, 3)) % 1f;
            float x = hash(i, 11) * w + (float) Math.sin(t * 0.8f + i) * 6f;
            float y = h - life * (h + 20);
            float a = (float) Math.sin(life * Math.PI) * (0.25f + 0.35f * hash(i, 5));
            int sz = Math.round((hash(i, 9) > 0.7f ? 2 : 1) * gs);
            int X = Math.round(x * gs), Y = Math.round(y * gs);
            g.fill(X, Y, X + sz, Y + sz, argb(a, mix(acc, 0xFFFFFF, 0.25f)));
        }
        g.pose().popMatrix();

        if (emblem) {
            float breathe = 0.5f + 0.5f * (float) Math.sin(t * 0.6f);
            int size = Math.round(h * 0.95f);
            float cx = w * 0.5f, cy = h * 0.47f;
            int px = (int) Math.round(size * Minecraft.getInstance().getWindow().getGuiScale());
            g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, FutureLogo.texture(px),
                Math.round(cx - size / 2f), Math.round(cy - size / 2f), 0f, 0f, size, size, px, px, px, px,
                argb(0.10f + 0.04f * breathe, 0xFFFFFF));
        }

        // shadow: top / bottom and sides
        g.fillGradient(0, 0, w, h / 4, 0x80000000, 0x00000000);
        g.fillGradient(0, h - h / 3, w, h, 0x00000000, 0x90000000);
        int band = Math.max(1, w / 10);
        for (int i = 0; i < 10; i++) {
            float a = 0.5f * (1f - i / 10f) * (1f - i / 10f);
            g.fill(i * band / 10, 0, (i + 1) * band / 10, h, argb(a, 0x000000));
            g.fill(w - (i + 1) * band / 10, 0, w - i * band / 10, h, argb(a, 0x000000));
        }
    }

    /** A fixed 0..1 value per cell / particle. */
    private static float hash(int x, int y) {
        int n = x * 374761393 + y * 668265263;
        n = (n ^ (n >>> 13)) * 1274126177;
        return ((n ^ (n >>> 16)) & 0xFFFF) / 65535f;
    }
}
