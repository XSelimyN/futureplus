package com.futureplus.client;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Font module (ClickGui > Client > Font).
 *
 * While on, all the mod's text (HUD, ClickGui, module chat messages) is drawn with the Liberation Sans TTF font,
 * which has Arial's metrics. The font is loaded with Minecraft's own TTF provider
 * (assets/futureplus/font/*.json), and is HD sharp thanks to oversampling.
 *
 * Style: Bold / Italic switches -> Plain, Bold, Italic, BoldItalic.
 * Glow: draws semi-transparent copies around the text for a glow effect.
 *
 * To use real Arial, replace arial_regular.ttf, arial_bold.ttf,
 * arial_italic.ttf, arial_bolditalic.ttf in the font folder with your own Arial files.
 */
public final class FontMod {
    private static boolean enabled = false;
    private static boolean bold = false;
    private static boolean italic = false;
    private static int yShift = 0;         // -3..3 dikey ince ayar
    private static boolean glow = false;
    private static int glowStrength = 6;   // 1..10
    private static int glowRadius = 4;     // 1..8 (x0.5 GUI pixel)
    private static boolean glowWhite = false;
    private static boolean crispShadow = true; // sharp 1 screen pixel shadow
    // HD text rasterization (HdText + AwtGlyphProvider): anti-aliasing, fractional metrics, contrast curve
    // fractional metrics on = no hinting: closest to real Arial (the FreeType reference). Measured (27 px):
    // reference partial pixel ratio 0.396 / coverage 0.726; unhinted 0.395 / 0.696; hinted 0.247 / 0.821 (pixelated, bold)
    private static boolean antialias = true, fractional = true;
    private static double contrast = 0.5;   // the user's preference
    private static boolean reloadPending = false;

    static boolean antialias() { return antialias; }
    static boolean fractional() { return fractional; }
    static float contrast() { return (float) contrast; }

    /** Raster setting changed: rebuild the HD textures; the game font reloads when the ClickGui closes. */
    private static void rasterChanged() {
        CACHE.clear(); WIDTHS.clear();
        try { HdText.clear(); } catch (Throwable ignored) { }
        reloadPending = true;
    }

    /** UI font size settings. */
    static boolean bypassScaled = false;

    /**
     * GuiTextScaledMixin: draws text drawn scaled (without rotation) with the game font at its real size with the HD engine.
     * Returning true cancels vanilla drawing. Bold/italic/underlined/mixed font styles stay vanilla.
     */
    public static boolean drawScaledGame(GuiGraphicsExtractor g, Font font, net.minecraft.util.FormattedCharSequence str,
                                         int x, int y, int color, boolean shadow) {
        if (!enabled || onlyClient || bypassScaled || hdBroken || (color >>> 24) == 0) return false;
        Minecraft mc = Minecraft.getInstance();
        org.joml.Matrix3x2f m = g.pose();
        if (font != mc.font) return false;
        if (Math.abs(m.m01()) > 1e-4f || Math.abs(m.m10()) > 1e-4f) return false;
        float k = Math.abs(m.m00());
        if (Math.abs(k - Math.abs(m.m11())) > 1e-3f || Math.abs(k - 1f) < 0.01f || k < 0.3f || k > 4f) return false;
        java.util.List<String> texts = new java.util.ArrayList<>();
        java.util.List<Integer> cols = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int[] curCol = {Integer.MIN_VALUE};
        boolean[] plain = {true};
        str.accept((idx, style, cp) -> {
            if (style.isBold() || style.isItalic() || style.isUnderlined() || style.isStrikethrough() || style.isObfuscated()) {
                plain[0] = false; return false;
            }
            int c = style.getColor() != null ? (color & 0xFF000000) | style.getColor().getValue() : color;
            if (c != curCol[0] && cur.length() > 0) { texts.add(cur.toString()); cols.add(curCol[0]); cur.setLength(0); }
            curCol[0] = c;
            cur.appendCodePoint(cp);
            return true;
        });
        if (!plain[0]) return false;
        if (cur.length() > 0) { texts.add(cur.toString()); cols.add(curCol[0]); }
        try {
            HdText.Opt o = new HdText.Opt();
            o.style = styleIndex();
            o.sizeGui = Math.round(gameSize * HdText.lineRatio(o.style));   // same em size as the game font
            o.scale = guiScale();
            o.shadow = shadow;
            int dx = 0;
            g.pose().pushMatrix();
            // the HD engine's baseline is ~0.093 em lower than the game font's (measured): pull it to the same line
            g.pose().translate(0f, -0.093f * gameSize);
            for (int i = 0; i < texts.size(); i++) {
                String t = texts.get(i);
                if (!t.isBlank()) HdText.draw(g, t, x + dx, y, cols.get(i), null, o);
                dx += font.width(t);
            }
            g.pose().popMatrix();
            return true;
        } catch (Throwable t) { hdFail(t); return false; }
    }

    /** ClickGui.saveModSettings: if a font change is pending, reload the game font (AwtGlyphProvider). */
    static void applyPending() {
        if (!reloadPending) return;
        reloadPending = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getOverlay() == null) mc.reloadResourcePacks();
    }
    private static int glowEdge = 3;          // dark thin outline strength (0 = none, 10 = darkest)
    private static int glowQuality = 2;
    private static final boolean hd = true;    // HD text engine (HdText) always on
    private static boolean onlyClient = false; // true: only Futureplus text; false: ALL game text
    private static int gameSize = 8;           // text size (game and Future+ text; vanilla line height 9)
    private static boolean hdBroken = false;   // fall back to the old path automatically if the HD engine failed       // 1 low (fast), 2 medium, 3 high (slow)

    public static final int MIN_SIZE = 5, MAX_SIZE = 24, MAX_SCALE = 6;
    private static final String[] STYLE_NAMES = {"regular", "bold", "italic", "bolditalic"};
    private static final Map<String, Style> STYLES = new HashMap<>();
    private static final Map<String, Component> CACHE = new HashMap<>();
    private static final Map<String, Integer> WIDTHS = new HashMap<>();

    private FontMod() {}

    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean v) { enabled = v; CACHE.clear(); WIDTHS.clear(); saveState(); }

    /** Line height (GUI pixels): the font size if the module is on, else vanilla 9. */
    public static int lineHeight() { return enabled ? curSize() : 9; }

    /** The ClickGui temporarily sets the text size from its own row size (0 = off). */
    private static int uiOverride = 0;
    public static void setUiSize(int s) { uiOverride = s; }

    private static int curSize() {
        int s = uiOverride > 0 ? uiOverride : gameSize;
        return Math.max(MIN_SIZE, Math.min(MAX_SIZE, s));
    }

    public static List<Setting> getSettings() {
        return List.of(
                Setting.bool("Apply font only to Future+", () -> onlyClient, v -> onlyClient = v),
                Setting.bool("Bold", () -> bold, v -> bold = v),
                Setting.bool("Italic", () -> italic, v -> italic = v),
                Setting.num("Font size (pt)", 7, 12, 1, () -> gameSize, v -> gameSize = (int) v),
                Setting.num("Vertical text offset", -3, 3, 1, () -> yShift, v -> yShift = (int) v),
                Setting.bool("Crisp shadow", () -> crispShadow, v -> crispShadow = v),
                Setting.bool("Antialiasing", () -> antialias, v -> { if (antialias != v) { antialias = v; rasterChanged(); } }),
                Setting.bool("Fractional metrics", () -> fractional, v -> { if (fractional != v) { fractional = v; rasterChanged(); } }),
                Setting.num("Text contrast", 0.5, 2.0, 0.05, () -> contrast, v -> { if (Math.abs(contrast - v) > 1e-6) { contrast = v; rasterChanged(); } }),
                Setting.bool("Glow (HUD only)", () -> glow, v -> glow = v),
                Setting.num("Glow strength", 1, 10, 1, () -> glowStrength, v -> glowStrength = (int) v).when(() -> glow),
                Setting.num("Glow radius", 1, 8, 1, () -> glowRadius, v -> glowRadius = (int) v).when(() -> glow),
                Setting.bool("White glow", () -> glowWhite, v -> glowWhite = v).when(() -> glow),
                Setting.num("Glow edge darkness", 0, 10, 1, () -> glowEdge, v -> glowEdge = (int) v).when(() -> glow),
                Setting.num("Glow quality (FPS)", 1, 3, 1, () -> glowQuality, v -> glowQuality = (int) v).when(() -> glow));
    }

    // ------------------------------------------------------------- persistence (on/off)

    private static Path statePath() {
        try { return FabricLoader.getInstance().getConfigDir().resolve("futureplus-font.properties"); }
        catch (Exception e) { return null; }
    }

    static void loadState() {
        try {
            Path p = statePath();
            if (p == null || !Files.exists(p)) return;
            Properties pr = new Properties();
            try (InputStream in = Files.newInputStream(p)) { pr.load(in); }
            enabled = Boolean.parseBoolean(pr.getProperty("enabled", "false").trim());
        } catch (Exception ignored) { }
    }

    private static void saveState() {
        try {
            Path p = statePath();
            if (p == null) return;
            Properties pr = new Properties();
            pr.setProperty("enabled", String.valueOf(enabled));
            try (OutputStream out = Files.newOutputStream(p)) { pr.store(out, "Futureplus Font"); }
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------- font stili

    private static int styleIndex() { return (bold ? 1 : 0) + (italic ? 2 : 0); }

    private static int guiScale() {
        try {
            int sc = (int) Math.round(Minecraft.getInstance().getWindow().getGuiScale());
            return Math.max(1, Math.min(MAX_SCALE, sc));
        } catch (Throwable t) { return 2; }
    }

    /** Font id: style + size + GUI scale. Oversample = GUI scale, so the texture is 1:1 screen pixels. */
    private static String fontName() {
        int sz = curSize();
        return "arial_" + STYLE_NAMES[styleIndex()] + "_" + sz + "_" + guiScale();
    }

    /**
     * The Style#withFont signature changed between versions (Identifier or FontDescription),
     * so we find the right call with reflection.
     */
    private static Style buildStyle(String fontName) {
        try {
            Identifier id = Identifier.fromNamespaceAndPath("futureplus", fontName);
            for (Method m : Style.class.getMethods()) {
                if (!m.getName().equals("withFont") || m.getParameterCount() != 1) continue;
                Class<?> pt = m.getParameterTypes()[0];
                Object arg = null;
                if (pt.isAssignableFrom(Identifier.class)) {
                    arg = id;
                } else {
                    for (Class<?> c : pt.getDeclaredClasses()) {
                        if (c.getSimpleName().equals("Resource") || c.getSimpleName().equals("Font")) {
                            try { arg = c.getConstructor(Identifier.class).newInstance(id); }
                            catch (NoSuchMethodException ignored) { continue; }
                            break;
                        }
                    }
                }
                if (arg == null) continue;
                return (Style) m.invoke(Style.EMPTY, arg);
            }
        } catch (Throwable t) {
            System.err.println("[futureplus] Font stili kurulamadi: " + t);
        }
        return null;
    }

    private static final Style NONE = Style.EMPTY;

    /** FontHdProviderMixin: the same glyph rasterizer as the HD engine for the Futureplus TTF font (null = keep FreeType). */
    public static com.mojang.blaze3d.font.GlyphProvider hdGlyphProvider(String key, java.io.InputStream ttf, float size, float oversample) {
        try { return AwtGlyphProvider.create(key, ttf, size, oversample); }
        catch (Throwable t) { System.err.println("[Futureplus] HD harf saglayicisi hatasi: " + t); return null; }
    }

    // ------------------------------------------------------------- all game text (FontGlobalMixin)

    private static final Map<String, FontDescription> GLOBAL = new HashMap<>();

    /**
     * The font to use instead of the game's default font (minecraft:default); null if the module is off or
     * "Apply font only to Future+" is on (the vanilla font stays). Called in Font.getGlyphSource:
     * chat, menus, item names, name tags, signs... all drawn and measured with this font.
     * Missing glyphs (symbols etc.) come from vanilla through the "reference minecraft:default" in the font's json.
     */
    public static FontDescription globalFont() {
        if (!enabled || onlyClient) return null;
        String name = "arial_" + STYLE_NAMES[styleIndex()] + "_" + Math.max(MIN_SIZE, Math.min(MAX_SIZE, gameSize)) + "_" + guiScale();
        FontDescription d = GLOBAL.get(name);
        if (d == null) {
            d = new FontDescription.Resource(Identifier.fromNamespaceAndPath("futureplus", name));
            GLOBAL.put(name, d);
        }
        return d;
    }

    private static Style style(String name) {
        Style st = STYLES.get(name);
        if (st == null) {
            st = buildStyle(name);
            STYLES.put(name, st == null ? NONE : st);
        }
        return st == NONE ? null : st;
    }

    /** Mixes the glyph colors of multicolor text 60% toward white for the glow core. */
    private static Component whiten(Component src) {
        net.minecraft.network.chat.MutableComponent out = Component.empty();
        src.visit((Style st, String txt) -> {
            Style s2 = st;
            if (st.getColor() != null) {
                int v = st.getColor().getValue();
                int r = (v >> 16) & 255, g = (v >> 8) & 255, b = v & 255;
                r += (255 - r) * 3 / 5; g += (255 - g) * 3 / 5; b += (255 - b) * 3 / 5;
                s2 = st.withColor((r << 16) | (g << 8) | b);
            }
            out.append(Component.literal(txt).withStyle(s2));
            return java.util.Optional.empty();
        }, Style.EMPTY);
        return out;
    }

    // ------------------------------------------------------------- exposed helpers

    /** For chat messages etc.: with the font if the module is on, a plain Component if off. */
    public static Component comp(String s) {
        if (!enabled) return Component.literal(s);
        String name = fontName();
        Style st = style(name);
        if (st == null) return Component.literal(s);
        String key = name + "|" + s;
        Component c = CACHE.get(key);
        if (c == null) {
            if (CACHE.size() > 3000) CACHE.clear();
            c = Component.literal(s).withStyle(st);
            CACHE.put(key, c);
        }
        return c;
    }

    /**
     * Minecraft always puts the text baseline at y+7 (regardless of font size).
     * Adjusts so the given y is the top of a line box lineHeight() tall;
     * so small/large fonts don't slide out of the box.
     */
    private static int baselineShift() {
        return (int) Math.round(0.86 * curSize() - 7);
    }

    private static void drawOffset(GuiGraphicsExtractor g, Font f, Component c, int x, int y,
                                   float dx, float dy, int col) {
        g.pose().pushMatrix();
        g.pose().translate(dx, dy);
        g.text(f, c, x, y, col, false);
        g.pose().popMatrix();
    }

    // ------------------------------------------------------------- HD engine link

    private static boolean useHd() { return enabled && hd && !hdBroken; }

    private static void hdFail(Throwable t) {
        hdBroken = true;
        System.err.println("[Futureplus] HD yazi motoru hata verdi, eski yola donuluyor: " + t);
        t.printStackTrace();
    }

    private static HdText.Opt hdOpt() {
        HdText.Opt o = new HdText.Opt();
        o.style = styleIndex();
        o.sizeGui = curSize();
        o.scale = guiScale();
        return o;
    }

    /** HUD/ClickGui text: with the HD engine. */
    private static void hdDraw(GuiGraphicsExtractor g, String s, int x, int y, int argb, boolean shadow,
                               int maxRing, java.util.function.IntUnaryOperator rgbAt) {
        int a = argb >>> 24;
        boolean doGlow = glow && maxRing > 0 && a >= 24;
        HdText.Opt o = hdOpt();
        o.shadow = shadow && !doGlow;
        int main = argb;
        if (doGlow) {
            int sc = o.scale;
            o.glowSigma = Math.max(0.8f, glowRadius * 0.5f * sc * 0.5f);
            int rgb = glowWhite ? 0xFFFFFF : (argb & 0xFFFFFF);
            int ga = Math.min(255, (int) (a * glowStrength * 0.11f));
            o.glowArgb = (ga << 24) | rgb;
            o.edgeAlpha = glowEdge > 0 ? Math.min(255, glowEdge * 22) : 0;
            // core: mixed 35% toward white (bright but keeps its color)
            int r = (argb >> 16) & 255, gg = (argb >> 8) & 255, bb = argb & 255;
            r += (255 - r) * 7 / 20; gg += (255 - gg) * 7 / 20; bb += (255 - bb) * 7 / 20;
            main = (a << 24) | (r << 16) | (gg << 8) | bb;
            if (rgbAt != null) {
                java.util.function.IntUnaryOperator src = rgbAt;
                rgbAt = i -> {
                    int c = src.applyAsInt(i);
                    int cr = (c >> 16) & 255, cg = (c >> 8) & 255, cb = c & 255;
                    cr += (255 - cr) * 7 / 20; cg += (255 - cg) * 7 / 20; cb += (255 - cb) * 7 / 20;
                    return (cr << 16) | (cg << 8) | cb;
                };
            }
        }
        HdText.draw(g, s, x, y + yShift, main, rgbAt, o);
    }

    /** Text width (cached: the same text isn't measured again every frame). */
    public static int width(Font f, String s) {
        if (useHd()) {
            try { return HdText.width(s, styleIndex(), curSize(), guiScale()); }
            catch (Throwable t) { hdFail(t); }
        }
        String key = (enabled ? fontName() : "vanilla") + "|" + s;
        Integer w = WIDTHS.get(key);
        if (w == null) {
            if (WIDTHS.size() > 4000) WIDTHS.clear();
            w = enabled ? f.width(comp(s)) : f.width(s);
            WIDTHS.put(key, w);
        }
        return w;
    }

    /** The font's base style (an empty style if the module is off). For multicolor text. */
    public static Style baseStyle() {
        if (!enabled) return Style.EMPTY;
        Style st = style(fontName());
        return st == null ? Style.EMPTY : st;
    }

    /**
     * Draws per-letter colored (gradient) text in ONE call: the glow/shadow layers are plain colored,
     * the main text on top is a single Component with each letter colored separately. Much faster than drawing letter by letter.
     */
    public static void drawGradient(GuiGraphicsExtractor g, Font f, String s, int x, int y, int alpha,
                                    java.util.function.IntUnaryOperator rgbAt, boolean shadow, int maxRing) {
        if (s.isEmpty()) return;
        if (useHd()) {
            try {
                int mid = rgbAt.applyAsInt(s.length() / 2) & 0xFFFFFF;
                hdDraw(g, s, x, y, (Math.max(0, Math.min(255, alpha)) << 24) | mid, shadow && crispShadow, maxRing, rgbAt);
                return;
            } catch (Throwable t) { hdFail(t); }
        }
        Style base = baseStyle();
        net.minecraft.network.chat.MutableComponent mc = Component.empty();
        for (int i = 0; i < s.length(); i++) {
            mc.append(Component.literal(String.valueOf(s.charAt(i)))
                    .withStyle(base.withColor(rgbAt.applyAsInt(i) & 0xFFFFFF)));
        }
        int mid = rgbAt.applyAsInt(s.length() / 2) & 0xFFFFFF;
        draw(g, f, s, x, y, (Math.max(0, Math.min(255, alpha)) << 24) | mid, shadow, maxRing, mc);
    }

    /**
     * Draws text. maxRing: glow quality (3 rings if >=3, else 2; 1 in the ClickGui for performance).
     * Glow: many semi-transparent copies are drawn around the text, shifted by fractions of a GUI pixel
     * (sub-pixel); the edges turn into a soft halo of light. No shadow while glow is on.
     */
    public static void draw(GuiGraphicsExtractor g, Font f, String s, int x, int y, int argb,
                            boolean shadow, int maxRing) {
        draw(g, f, s, x, y, argb, shadow, maxRing, null);
    }

    /**
     * Draws with glow even if the glow setting is off (for the ClickGui description text).
     * Does a simple glow with the vanilla font even when the font module is off.
     */
    public static void drawGlowAlways(GuiGraphicsExtractor g, Font f, String s, int x, int y, int argb) {
        // Sharp text + a light halo standing AWAY from the text. The halo doesn't stick to the letter edges (the nearest
        // ring is 2 screen pixels out), and letters are separated by a 1 screen pixel dark outline; so
        // the text doesn't blur, only its surroundings glow. Independent of the user's glow settings.
        if (useHd()) {
            try {
                HdText.Opt o = hdOpt();
                o.glowSigma = 1.2f * o.scale;                       // soft halo
                o.glowArgb = (Math.min(255, (argb >>> 24) * 55 / 100) << 24) | (argb & 0xFFFFFF);
                o.edgeAlpha = 140;                                   // separates the letters from the halo
                HdText.draw(g, s, x, y + yShift, argb, null, o);
                return;
            } catch (Throwable t) { hdFail(t); }
        }
        Component c;
        if (enabled) { c = comp(s); y += baselineShift() + yShift; }
        else c = Component.literal(s);
        int a = argb >>> 24;
        int rgb = argb & 0xFFFFFF;
        float px = 1f / guiScale();
        // 1) halo: rings with 2, 3.5, 5 screen pixel radius, the outer ones dimmer
        float[] radii = {5f, 3.5f, 2f};
        float[] alphas = {0.035f, 0.05f, 0.07f};
        for (int k = 0; k < radii.length; k++) {
            int ca = (int) (a * alphas[k]);
            if (ca < 3) continue;
            int col = (ca << 24) | rgb;
            for (int j = 0; j < 12; j++) {
                double ang = Math.PI * 2.0 * (j + 0.5 * (k & 1)) / 12;
                drawOffset(g, f, c, x, y, (float) (Math.cos(ang) * radii[k] * px),
                        (float) (Math.sin(ang) * radii[k] * px), col);
            }
        }
        // 2) 1 screen pixel dark outline: separates the letters from the halo, gives crispness
        int oc = (Math.min(a, 150) << 24);
        drawOffset(g, f, c, x, y, px, 0, oc);
        drawOffset(g, f, c, x, y, -px, 0, oc);
        drawOffset(g, f, c, x, y, 0, px, oc);
        drawOffset(g, f, c, x, y, 0, -px, oc);
        // 3) sharp main text (at a whole pixel position)
        g.text(f, c, x, y, argb, false);
    }

    /** if main != null, that (multicolor) Component is drawn as the main text on top. */
    public static void draw(GuiGraphicsExtractor g, Font f, String s, int x, int y, int argb,
                            boolean shadow, int maxRing, Component main) {
        if (useHd() && main == null) {
            try { hdDraw(g, s, x, y, argb, shadow && crispShadow, maxRing, null); return; }
            catch (Throwable t) { hdFail(t); }
        }
        if (!enabled) {
            if (main != null) g.text(f, main, x, y, argb, shadow);
            else g.text(f, s, x, y, argb, shadow);
            return;
        }
        Component c = comp(s);
        y += baselineShift() + yShift;
        int a = argb >>> 24;
        float px = 1f / guiScale(); // 1 screen pixel (in GUI pixels)
        boolean doGlow = glow && maxRing > 0 && a >= 24;
        int mainCol = argb;

        if (doGlow) {
            float radius = glowRadius * 0.5f;
            int q = Math.min(glowQuality, maxRing >= 3 ? 3 : 1);
            int rings = q;                 // 1, 2 or 3 rings
            int taps = q >= 3 ? 12 : 8;    // copies per ring
            int rgb = glowWhite ? 0xFFFFFF : (argb & 0xFFFFFF);
            // 1) colored glow (outside in): dim copies drawn with small sub-pixel shifts
            for (int k = rings; k >= 1; k--) {
                float rel = (float) k / rings;
                float rho = radius * rel;
                float fall = (float) Math.exp(-0.6 * rel * rel);
                int ga = (int) (a * glowStrength * 0.05f * fall);
                if (ga < 16) ga = 16;
                int col = (ga << 24) | rgb;
                for (int j = 0; j < taps; j++) {
                    double ang = Math.PI * 2.0 * (j + 0.5 * (k & 1)) / taps;
                    drawOffset(g, f, c, x, y, (float) (Math.cos(ang) * rho), (float) (Math.sin(ang) * rho), col);
                }
            }
            // 2) dark thin outline: keeps it readable on light backgrounds
            if (glowEdge > 0) {
                int oc = (Math.min(a, glowEdge * 22) << 24);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        if (dx == 0 && dy == 0) continue;
                        if (q < 3 && dx != 0 && dy != 0) continue; // 4 directions are enough at low/medium quality
                        drawOffset(g, f, c, x, y, dx * px, dy * px, oc);
                    }
                }
            }
            // 3) main text: a bright near-white core (60% white)
            int r = (argb >> 16) & 255, gg = (argb >> 8) & 255, bb = argb & 255;
            r += (255 - r) * 3 / 5; gg += (255 - gg) * 3 / 5; bb += (255 - bb) * 3 / 5;
            mainCol = (a << 24) | (r << 16) | (gg << 8) | bb;
            if (main != null) main = whiten(main);
        } else if (shadow && crispShadow) {
            // Future style: exactly 1 screen pixel dark shadow (the vanilla shadow is 1 GUI pixel = thicker/blurrier)
            int sa = Math.max(12, a * 3 / 4);
            int scol = (sa << 24) | (((argb & 0xFCFCFC) >> 2) & 0xFFFFFF);
            drawOffset(g, f, c, x, y, px, px, scol);
        }

        g.text(f, main != null ? main : c, x, y, mainCol, !doGlow && shadow && !crispShadow);
    }
}