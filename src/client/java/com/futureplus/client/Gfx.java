package com.futureplus.client;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sharp, anti-aliased shapes for the HUD. All drawn in SCREEN PIXELS (shrunk by 1/gs from the GUI scale),
 * so corners/rings stay smooth even at GUI scale 2-3.
 * Coordinates are given in GUI units (float) and converted to screen pixels inside.
 */
public final class Gfx {
    private Gfx() {}

    /** Inverted against the background (like the vanilla crosshair). */
    static final RenderPipeline INVERT = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
            .withLocation("pipeline/futureplus_gui_invert")
            .withColorTargetState(new ColorTargetState(BlendFunction.INVERT))
            .build());

    static void init() { }

    static double gs() { return Minecraft.getInstance().getWindow().getGuiScale(); }

    /** switches the pose to screen pixels; call end() when done. */
    static float begin(GuiGraphicsExtractor g) {
        float s = (float) gs();
        g.pose().pushMatrix();
        g.pose().scale(1f / s, 1f / s);
        return s;
    }

    static void end(GuiGraphicsExtractor g) { g.pose().popMatrix(); }

    static int mulA(int argb, float k) {
        int a = (int) (((argb >>> 24) & 255) * Math.max(0f, Math.min(1f, k)));
        return (a << 24) | (argb & 0xFFFFFF);
    }

    // ---------------------------------------------------------------- screen pixel level (inside begin())

    static void px(GuiGraphicsExtractor g, RenderPipeline pipe, int x0, int y0, int x1, int y1, int argb) {
        if (x1 <= x0 || y1 <= y0 || (argb >>> 24) == 0) return;
        if (pipe != null) g.fill(pipe, x0, y0, x1, y1, argb);
        else g.fill(x0, y0, x1, y1, argb);
    }

    /** Rounded rectangle (screen pixels). r: corner radius. */
    static void roundPx(GuiGraphicsExtractor g, RenderPipeline pipe, float x, float y, float w, float h, float r, int argb) {
        if (pipe == null && r >= 1f) {                              // fast path: a single texture if small, a 9-slice texture if large
            int X0 = Math.round(x), Y0 = Math.round(y), W = Math.round(x + w) - X0, H = Math.round(y + h) - Y0;
            int R = Math.max(1, Math.min(Math.round(r), Math.min(W, H) / 2));
            if (W > 0 && H > 0 && R >= 1) {
                if (W <= 96 && H <= 96) roundExact(g, X0, Y0, W, H, R, argb);
                else nine(g, tex(R, 0), X0, Y0, W, H, argb);
                return;
            }
        }
        r = Math.max(0f, Math.min(r, Math.min(w, h) / 2f));
        int X0 = Math.round(x), Y0 = Math.round(y), X1 = Math.round(x + w), Y1 = Math.round(y + h);
        int R = Math.round(r);
        if (R <= 0) { px(g, pipe, X0, Y0, X1, Y1, argb); return; }
        px(g, pipe, X0, Y0 + R, X1, Y1 - R, argb);                  // orta bant
        for (int k = 0; k < R; k++) {                               // top/bottom corner rows
            float dy = R - k - 0.5f;
            float dx = (float) Math.sqrt(Math.max(0f, r * r - dy * dy));
            float inset = r - dx;
            int in = (int) Math.ceil(inset);
            float frac = in - inset;                                // coverage of the edge pixel
            px(g, pipe, X0 + in, Y0 + k, X1 - in, Y0 + k + 1, argb);
            px(g, pipe, X0 + in, Y1 - k - 1, X1 - in, Y1 - k, argb);
            if (in > 0 && frac > 0.02f) {
                int c = mulA(argb, frac);
                px(g, pipe, X0 + in - 1, Y0 + k, X0 + in, Y0 + k + 1, c);
                px(g, pipe, X1 - in, Y0 + k, X1 - in + 1, Y0 + k + 1, c);
                px(g, pipe, X0 + in - 1, Y1 - k - 1, X0 + in, Y1 - k, c);
                px(g, pipe, X1 - in, Y1 - k - 1, X1 - in + 1, Y1 - k, c);
            }
        }
    }

    /** Ring (thick >= radius for a filled circle). Center and radius in screen pixels. Edges are anti-aliased. */
    static void ringPx(GuiGraphicsExtractor g, RenderPipeline pipe, float cx, float cy, float radius, float thick, int argb) {
        if (pipe == null && radius <= 64f) {                        // fast path: a cached texture, a single draw
            int d2 = Math.max(1, Math.round(radius * 2f)), t2 = Math.max(1, Math.round(Math.min(thick, radius) * 2f));
            Tex t = discTex(d2, t2);
            if ((argb >>> 24) != 0) {
                int X = Math.round(cx - t.n / 2f), Y = Math.round(cy - t.n / 2f);
                b(g, t, X, Y, t.n, t.n, 0, 0, t.n, t.n, argb);
            }
            return;
        }
        float ro = radius, ri = Math.max(0f, radius - thick);
        int y0 = (int) Math.floor(cy - ro - 1), y1 = (int) Math.ceil(cy + ro + 1);
        for (int y = y0; y < y1; y++) {
            float dy = Math.abs(y + 0.5f - cy);
            if (dy > ro + 0.5f) continue;
            float vcov = Math.min(1f, ro + 0.5f - dy);                       // vertical coverage at the top/bottom tip
            float xo = (float) Math.sqrt(Math.max(0f, ro * ro - Math.min(dy, ro) * Math.min(dy, ro)));
            int c = vcov < 1f ? mulA(argb, vcov) : argb;
            if (dy < ri) {
                float xi = (float) Math.sqrt(Math.max(0f, ri * ri - dy * dy));
                span(g, pipe, cx - xo, cx - xi, y, c);
                span(g, pipe, cx + xi, cx + xo, y, c);
            } else {
                span(g, pipe, cx - xo, cx + xo, y, c);
            }
        }
    }

    /** horizontal range [L, R); the end pixels are transparent by their coverage. */
    static void span(GuiGraphicsExtractor g, RenderPipeline pipe, float L, float R, int y, int argb) {
        if (R <= L) return;
        int l = (int) Math.ceil(L), r = (int) Math.floor(R);
        if (r > l) px(g, pipe, l, y, r, y + 1, argb);
        if (r < l) { px(g, pipe, r, y, l, y + 1, mulA(argb, R - L)); return; }   // narrower than one pixel
        float fl = l - L, fr = R - r;
        if (fl > 0.02f) px(g, pipe, l - 1, y, l, y + 1, mulA(argb, fl));
        if (fr > 0.02f) px(g, pipe, r, y, r + 1, y + 1, mulA(argb, fr));
    }

    /** A spreading soft halo (glow): a single pre-blurred texture stretched as 9 slices. */
    static void glowPx(GuiGraphicsExtractor g, float x, float y, float w, float h, float r, int rgb, float alpha, float spread) {
        int pad = Math.max(1, Math.min(24, Math.round(spread * 1.6f)));
        int X0 = Math.round(x), Y0 = Math.round(y), W = Math.round(x + w) - X0, H = Math.round(y + h) - Y0;
        if (W <= 0 || H <= 0) return;
        int R = Math.max(1, Math.min(Math.round(r), Math.min(W, H) / 2));
        Tex t = tex(R, pad);
        nine(g, t, X0 - pad, Y0 - pad, W + 2 * pad, H + 2 * pad, VisualState.argb(rgb, Math.min(1f, alpha)));
    }

    // ---------------------------------------------------------------- 9-slice textures

    private record Tex(Identifier id, int n, int c) {}             // n: texture size, c: corner slice size
    // LRU: textures keyed by size (exact/roundExact) piled up without limit in animations. A dropped texture may have been drawn
    // that frame, so it is released on the next client tick (after the frame is drawn).
    private static final int CACHE_MAX = 768;
    private static final List<Identifier> RELEASE = new ArrayList<>();
    private static final Map<Long, Tex> CACHE = new LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Tex> e) {
            if (size() <= CACHE_MAX) return false;
            RELEASE.add(e.getValue().id());
            return true;
        }
    };
    private static int texId = 0;

    static {
        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            if (RELEASE.isEmpty()) return;
            for (Identifier id : RELEASE) {
                try { mc.getTextureManager().release(id); } catch (Throwable ignored) { }
            }
            RELEASE.clear();
        });
    }

    /** Corner mask (corners to chamfer). */
    static final int TL = 1, TR = 2, BR = 4, BL = 8, ALL = 15;

    /** R: corner radius (px), pad: blur margin (0 = sharp, anti-aliased edge). */
    private static Tex tex(int R, int pad) { return shapeTex(R, 0, 0, 0, pad); }

    /**
     * Shape texture (stretched as 9 slices). R: corner roundness, cut: chamfer (45° cut) size, mask: which corners are cut,
     * sw: stroke width (0 = filled), pad: halo margin (0 = sharp). All in screen pixels.
     */
    private static Tex shapeTex(int R, int cut, int mask, int sw, int pad) {
        R = Math.min(R, 96); cut = Math.min(cut, 96); pad = Math.min(pad, 24); sw = Math.min(sw, 16);
        if (cut == 0) mask = 0;
        long key = R | ((long) cut << 8) | ((long) mask << 16) | ((long) sw << 20) | ((long) pad << 28);
        Tex t = CACHE.get(key);
        if (t != null) return t;
        int c = Math.max(R, cut) + pad + 1, n = 2 * c + 1;
        NativeImage img = new NativeImage(n, n, true);
        float sigma = Math.max(0.5f, pad / 2.2f);
        float half = n / 2f - pad;                                  // half size of the shape
        for (int yy = 0; yy < n; yy++) for (int xx = 0; xx < n; xx++) {
            float px = xx + 0.5f - n / 2f, py = yy + 0.5f - n / 2f;
            float ax = Math.abs(px), ay = Math.abs(py);
            float qx = ax - (half - R), qy = ay - (half - R);
            float ox = Math.max(qx, 0f), oy = Math.max(qy, 0f);
            float sd = (float) Math.sqrt(ox * ox + oy * oy) + Math.min(Math.max(qx, qy), 0f) - R;
            int q = py < 0 ? (px < 0 ? TL : TR) : (px < 0 ? BL : BR);
            if ((mask & q) != 0) sd = Math.max(sd, (ax + ay - (2 * half - cut)) * 0.70710677f);
            float a;
            if (sw > 0) a = cov(sd) - cov(sd + sw);
            else if (pad == 0) a = cov(sd);
            else a = sd <= 0 ? 1f : (float) Math.exp(-(sd * sd) / (2f * sigma * sigma));
            int al = Math.round(Math.max(0f, Math.min(1f, a)) * 255f);
            img.setPixel(xx, yy, (al << 24) | 0xFFFFFF);
        }
        t = new Tex(register(img), n, c);
        CACHE.put(key, t);
        return t;
    }

    /** Small shapes / shapes with a very large cut (triangle, arrow tip): can't be split into 9 slices, a full size texture (screen pixels). */
    private static void exact(GuiGraphicsExtractor g, int x, int y, int W, int H, int cut, int mask, int sw, int argb) {
        if (W > 256 || H > 256) { px(g, null, x, y, x + W, y + H, argb); return; }
        long key = (2L << 40) | W | ((long) H << 9) | ((long) cut << 18) | ((long) mask << 27) | ((long) sw << 31);
        Tex t = CACHE.get(key);
        if (t == null) {
            NativeImage img = new NativeImage(W, H, true);
            float hx = W / 2f, hy = H / 2f;
            for (int yy = 0; yy < H; yy++) for (int xx = 0; xx < W; xx++) {
                float px = xx + 0.5f - hx, py = yy + 0.5f - hy, ax = Math.abs(px), ay = Math.abs(py);
                float qx = ax - hx, qy = ay - hy;
                float sd = Math.max(qx, qy);
                int q = py < 0 ? (px < 0 ? TL : TR) : (px < 0 ? BL : BR);
                if ((mask & q) != 0) sd = Math.max(sd, (ax + ay - (hx + hy - cut)) * 0.70710677f);
                float a = sw > 0 ? cov(sd) - cov(sd + sw) : cov(sd);
                img.setPixel(xx, yy, (Math.round(Math.max(0f, Math.min(1f, a)) * 255f) << 24) | 0xFFFFFF);
            }
            t = new Tex(register(img), Math.max(W, H), 0);
            CACHE.put(key, t);
        }
        g.blit(RenderPipelines.GUI_TEXTURED, t.id, x, y, 0f, 0f, W, H, W, H, W, H, argb);
    }

    /** Small rounded box: a full size texture, a single draw (for shapes repeated a lot, like slots). */
    private static void roundExact(GuiGraphicsExtractor g, int x, int y, int W, int H, int R, int argb) {
        if ((argb >>> 24) == 0) return;
        long key = (3L << 40) | W | ((long) H << 8) | ((long) R << 16);
        Tex t = CACHE.get(key);
        if (t == null) {
            NativeImage img = new NativeImage(W, H, true);
            float hx = W / 2f, hy = H / 2f;
            for (int yy = 0; yy < H; yy++) for (int xx = 0; xx < W; xx++) {
                float qx = Math.abs(xx + 0.5f - hx) - (hx - R), qy = Math.abs(yy + 0.5f - hy) - (hy - R);
                float ox = Math.max(qx, 0f), oy = Math.max(qy, 0f);
                float sd = (float) Math.sqrt(ox * ox + oy * oy) + Math.min(Math.max(qx, qy), 0f) - R;
                img.setPixel(xx, yy, (Math.round(cov(sd) * 255f) << 24) | 0xFFFFFF);
            }
            t = new Tex(register(img), Math.max(W, H), 0);
            CACHE.put(key, t);
        }
        g.blit(RenderPipelines.GUI_TEXTURED, t.id, x, y, 0f, 0f, W, H, W, H, W, H, argb);
    }

    private static float cov(float d) { return Math.max(0f, Math.min(1f, 0.5f - d)); }

    /** Circle / ring texture. d2: outer radius x2, t2: thickness x2 (screen pixels). */
    private static Tex discTex(int d2, int t2) {
        long key = (1L << 40) | d2 | ((long) t2 << 12);
        Tex t = CACHE.get(key);
        if (t != null) return t;
        float ro = d2 / 2f, ri = Math.max(0f, ro - t2 / 2f);
        int n = (int) Math.ceil(ro * 2f) + 2;
        NativeImage img = new NativeImage(n, n, true);
        float c = n / 2f;
        for (int yy = 0; yy < n; yy++) for (int xx = 0; xx < n; xx++) {
            float dx = xx + 0.5f - c, dy = yy + 0.5f - c;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            float a = Math.max(0f, Math.min(1f, ro + 0.5f - d));
            if (ri > 0f) a = Math.min(a, Math.max(0f, Math.min(1f, d - ri + 0.5f)));
            img.setPixel(xx, yy, (Math.round(a * 255f) << 24) | 0xFFFFFF);
        }
        t = new Tex(register(img), n, n / 2);
        CACHE.put(key, t);
        return t;
    }

    private static Identifier register(NativeImage img) {
        Identifier id = Identifier.fromNamespaceAndPath("futureplus", "gfx/" + (texId++));
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "futureplus gfx", img));
        return id;
    }

    /** Stretches the texture over (x, y, w, h) without distorting the corners: 4 corners + 4 edges + middle. */
    private static void nine(GuiGraphicsExtractor g, Tex t, int x, int y, int w, int h, int argb) {
        if ((argb >>> 24) == 0) return;
        int c = Math.min(t.c, Math.min(w, h) / 2), n = t.n, k = t.c;   // corner size at the destination
        int mw = w - 2 * c, mh = h - 2 * c;
        // corners (source: k x k, destination: c x c)
        b(g, t, x, y, c, c, 0, 0, k, k, argb);
        b(g, t, x + w - c, y, c, c, n - k, 0, k, k, argb);
        b(g, t, x, y + h - c, c, c, 0, n - k, k, k, argb);
        b(g, t, x + w - c, y + h - c, c, c, n - k, n - k, k, k, argb);
        if (mw > 0) {
            b(g, t, x + c, y, mw, c, k, 0, 1, k, argb);
            b(g, t, x + c, y + h - c, mw, c, k, n - k, 1, k, argb);
        }
        if (mh > 0) {
            b(g, t, x, y + c, c, mh, 0, k, k, 1, argb);
            b(g, t, x + w - c, y + c, c, mh, n - k, k, k, 1, argb);
        }
        if (mw > 0 && mh > 0) b(g, t, x + c, y + c, mw, mh, k, k, 1, 1, argb);
    }

    private static void b(GuiGraphicsExtractor g, Tex t, int x, int y, int w, int h, int u, int v, int rw, int rh, int argb) {
        if (w <= 0 || h <= 0) return;
        g.blit(RenderPipelines.GUI_TEXTURED, t.id, x, y, (float) u, (float) v, w, h, rw, rh, t.n, t.n, argb);
    }

    // ---------------------------------------------------------------- GUI unit conveniences

    static void round(GuiGraphicsExtractor g, float x, float y, float w, float h, float r, int argb) {
        float s = begin(g);
        roundPx(g, null, x * s, y * s, w * s, h * s, r * s, argb);
        end(g);
    }

    /** Chamfered (corners cut at 45°) filled box. cut in GUI pixels, mask: TL/TR/BR/BL. */
    static void cut(GuiGraphicsExtractor g, float x, float y, float w, float h, float cut, int mask, int argb) {
        if ((argb >>> 24) == 0 || w <= 0 || h <= 0) return;
        float s = begin(g);
        int X0 = Math.round(x * s), Y0 = Math.round(y * s), W = Math.round((x + w) * s) - X0, H = Math.round((y + h) * s) - Y0;
        int C = Math.max(0, Math.min(Math.round(cut * s), Math.min(W, H) - 1));
        if (W > 0 && H > 0) {
            if (C == 0) px(g, null, X0, Y0, X0 + W, Y0 + H, argb);
            else if (2 * C + 2 >= Math.min(W, H)) exact(g, X0, Y0, W, H, C, mask, 0, argb);
            else nine(g, shapeTex(0, C, mask, 0, 0), X0, Y0, W, H, argb);
        }
        end(g);
    }

    /** Thin frame of a chamfered box; thickness in screen pixels (1 = thinnest, sharp). */
    static void cutLine(GuiGraphicsExtractor g, float x, float y, float w, float h, float cut, int mask, int thickPx, int argb) {
        if ((argb >>> 24) == 0 || w <= 0 || h <= 0) return;
        float s = begin(g);
        int X0 = Math.round(x * s), Y0 = Math.round(y * s), W = Math.round((x + w) * s) - X0, H = Math.round((y + h) * s) - Y0;
        int C = Math.max(0, Math.min(Math.round(cut * s), Math.min(W, H) - 1));
        if (W > 0 && H > 0) {
            if (2 * C + 2 >= Math.min(W, H)) exact(g, X0, Y0, W, H, C, mask, Math.max(1, thickPx), argb);
            else nine(g, shapeTex(0, C, C == 0 ? 0 : mask, Math.max(1, thickPx), 0), X0, Y0, W, H, argb);
        }
        end(g);
    }

    /** Thin rounded frame (GUI units). r in GUI pixels, thickness in screen pixels. */
    static void roundLine(GuiGraphicsExtractor g, float x, float y, float w, float h, float r, int thickPx, int argb) {
        if ((argb >>> 24) == 0 || w <= 0 || h <= 0) return;
        float s = begin(g);
        strokeRoundPx(g, x * s, y * s, w * s, h * s, r * s, thickPx, argb);
        end(g);
    }

    /** Rounded frame (screen pixels, inside begin()). sw: thickness. */
    static void strokeRoundPx(GuiGraphicsExtractor g, float x, float y, float w, float h, float r, float sw, int argb) {
        int X0 = Math.round(x), Y0 = Math.round(y), W = Math.round(x + w) - X0, H = Math.round(y + h) - Y0;
        int R = Math.max(0, Math.min(Math.round(r), Math.min(W, H) / 2));
        if (W > 0 && H > 0) nine(g, shapeTex(R, 0, 0, Math.max(1, Math.round(sw)), 0), X0, Y0, W, H, argb);
    }
}
