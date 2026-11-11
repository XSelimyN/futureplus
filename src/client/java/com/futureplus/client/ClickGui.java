package com.futureplus.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Futureplus - ClickGUI.
 *
 * Multi-panel, animated, HD marble textured interface. ALL settings of every module are here.
 * Panels are narrow with a fixed max height; scrolled with the mouse wheel / scroll bar.
 *
 * Usage:
 *  - Module row: left click toggles, right click opens/closes the settings ("+" / "-"), middle click binds a key.
 *  - Opened rows: "Key: X" (click, then press a key), on/off settings (click),
 *    number settings (slider: click or hold and drag).
 *  - Drag a panel's header: moves the panel.
 *  - "Client" panel: Theme, Effect, Speed, Dim (left click forward, right click back).
 *  - Closes with Right Shift or Escape.
 *
 * Textures: assets/futureplus/textures/gui/fx_<effect>_<theme>.png  (effect: marble/gradient/sparkle, theme: blue/purple/green/red/orange/rainbow)
 * Settings: config/futureplus-gui.properties (look), config/futureplus-settings.properties (module settings)
 */
public final class ClickGui extends Screen {
    private record Entry(String name, BooleanSupplier getter, Consumer<Boolean> setter,
                         Supplier<KeyMapping> keyMapping, Supplier<List<Setting>> settings) {}

    private record Cycle(String name, Supplier<String> value, Runnable next, Runnable prev) {}

    private static final class Category {
        final String name;
        final List<Entry> entries;
        final List<Cycle> cycles;
        List<Setting> extra = List.of();
        int x, y;
        float scroll, scrollTarget;   // scroll (content pixels)
        int contentH, viewH;          // content and visible body height
        Category(String name, List<Entry> entries, List<Cycle> cycles) {
            this.name = name; this.entries = entries; this.cycles = cycles;
        }
    }

    // ------------------------------------------------------------- tema / ayarlar

    private static final String[] THEME_NAMES = {"Blue", "Purple-Pink", "Green", "Red", "Orange", "Rainbow"};
    private static final String[] THEME_TEX = {"blue", "purple", "green", "red", "orange", "rainbow"};
    private static final int[][] THEME_COLORS = {
        {0x3FA9D8, 0x1B4F9C},
        {0xB04ACB, 0xFF5FA8},
        {0x3CCB7F, 0x1B8F6A},
        {0xD8343A, 0x7E1A20},
        {0xFFA23C, 0xE0553A},
        {0, 0} // rainbow: dinamik
    };
    private static final String[] EFFECT_NAMES = {"Marble", "Gradient", "Sparkle", "Solid"};
    private static final String[] EFFECT_TEX = {"marble", "gradient", "sparkle"};
    private static final float[] SPEEDS = {0.5f, 1.0f, 1.5f, 2.0f};
    private static final int[] DIMS = {0, 25, 50, 70};

    private static int themeIdx = 3;                 // default: Red (Future red)
    private static int effectIdx = 0;
    private static int speedIdx = 1;
    private static int dimIdx = 1;
    private static int uiLevel = 14;      // applied row height (8 - 30); width and header depend on it
    private static int uiLevelDraft = 14; // the value the slider shows (applied on release)

    /** Computes panel sizes from the ClickGui size and font size (doesn't touch positions). */
    private static void refreshLayout() {
        // every step grows/shrinks both width and height together
        ROW_H = uiLevel;
        HEADER_H = uiLevel + 3;
        PANEL_W = (int) Math.round(uiLevel * 7.5);
    }

    private static void applySize() {
        refreshLayout();
        for (int i = 0; i < CATEGORIES.size(); i++) {
            CATEGORIES.get(i).x = 24 + i * (PANEL_W + 14);
            CATEGORIES.get(i).y = 28;
        }
    }

    // puts the text line box (lineHeight) in the middle of the row; FontMod does the baseline fix for TTF
    private static int ty() { return (ROW_H - FontMod.lineHeight()) / 2 + (FontMod.isEnabled() ? 0 : 1); }
    private static int hty() { return (HEADER_H - FontMod.lineHeight()) / 2 + (FontMod.isEnabled() ? 0 : 1); }

    // Textures: 512 wide, 60 frames stacked vertically (frame height 52). Drawn pixel for pixel (1:1).
    private static final int TEX_W = 512;
    private static final int FRAME_H = 52;
    private static final int FRAMES = 60;
    private static final Identifier[][] FX = new Identifier[EFFECT_TEX.length][THEME_TEX.length];
    static {
        for (int e = 0; e < EFFECT_TEX.length; e++) {
            for (int i = 0; i < THEME_TEX.length; i++) {
                FX[e][i] = Identifier.fromNamespaceAndPath("futureplus",
                    "textures/gui/fx_" + EFFECT_TEX[e] + "_" + THEME_TEX[i] + ".png");
            }
        }
    }

    private static Path guiCfgPath;
    private static Path modCfgPath;

    private static void loadSettings() {
        try {
            Path dir = FabricLoader.getInstance().getConfigDir();
            guiCfgPath = dir.resolve("futureplus-gui.properties");
            modCfgPath = dir.resolve("futureplus-settings.properties");
        } catch (Exception e) { return; }
        try {
            if (Files.exists(guiCfgPath)) {
                Properties p = new Properties();
                try (InputStream in = Files.newInputStream(guiCfgPath)) { p.load(in); }
                themeIdx = clampIdx(Integer.parseInt(p.getProperty("theme", "3").trim()), THEME_NAMES.length);
                effectIdx = clampIdx(Integer.parseInt(p.getProperty("effect", "0").trim()), EFFECT_NAMES.length);
                speedIdx = clampIdx(Integer.parseInt(p.getProperty("speed", "1").trim()), SPEEDS.length);
                dimIdx = clampIdx(Integer.parseInt(p.getProperty("dim", "1").trim()), DIMS.length);
                uiLevel = Math.max(8, Math.min(30, Integer.parseInt(p.getProperty("uilevel", "14").trim())));
                uiLevelDraft = uiLevel;
                applySize();
            }
        } catch (Exception ignored) { }
        try {
            if (Files.exists(modCfgPath)) {
                Properties p = new Properties();
                try (InputStream in = Files.newInputStream(modCfgPath)) { p.load(in); }
                for (Category cat : CATEGORIES) for (Entry e : cat.entries) for (Setting setting : e.settings().get()) {
                    String value = p.getProperty(e.name() + "." + setting.name);
                    if (value == null) continue;
                    try {
                        if (setting.kind == Setting.Kind.BOOL) setting.setBool(Boolean.parseBoolean(value.trim()));
                        else setting.setNum(Double.parseDouble(value.trim()));
                    } catch (Exception ignored) { }
                }
            }
        } catch (Exception ignored) { }
    }

    private static void saveGuiSettings() {
        try {
            if (guiCfgPath == null) return;
            Properties p = new Properties();
            p.setProperty("theme", String.valueOf(themeIdx));
            p.setProperty("effect", String.valueOf(effectIdx));
            p.setProperty("speed", String.valueOf(speedIdx));
            p.setProperty("dim", String.valueOf(dimIdx));
            p.setProperty("uilevel", String.valueOf(uiLevel));
            try (OutputStream out = Files.newOutputStream(guiCfgPath)) { p.store(out, "Futureplus GUI"); }
        } catch (Exception ignored) { }
    }

    static void saveModSettings() {
        FontMod.applyPending();
        try {
            if (modCfgPath == null) return;
            Properties p = new Properties();
            for (Category cat : CATEGORIES) {
                for (Entry e : cat.entries) {
                    for (Setting s : e.settings().get()) {
                        String key = e.name() + "." + s.name;
                        p.setProperty(key, s.kind == Setting.Kind.BOOL
                            ? String.valueOf(s.getBool()) : String.valueOf(s.getNum()));
                    }
                }
            }
            try (OutputStream out = Files.newOutputStream(modCfgPath)) { p.store(out, "Futureplus modul ayarlari"); }
        } catch (Exception ignored) { }
    }

    /** .config save: modules' on/off state + all settings + GUI look. */
    static Properties exportConfig() {
        Properties p = new Properties();
        for (Category cat : CATEGORIES) {
            for (Entry e : cat.entries) {
                p.setProperty(e.name() + ".@on", String.valueOf(e.getter().getAsBoolean()));
                for (Setting s : e.settings().get()) {
                    p.setProperty(e.name() + "." + s.name, s.kind == Setting.Kind.BOOL
                        ? String.valueOf(s.getBool()) : String.valueOf(s.getNum()));
                }
            }
        }
        p.setProperty("@gui.theme", String.valueOf(themeIdx));
        p.setProperty("@gui.effect", String.valueOf(effectIdx));
        p.setProperty("@gui.speed", String.valueOf(speedIdx));
        p.setProperty("@gui.dim", String.valueOf(dimIdx));
        p.setProperty("@gui.uilevel", String.valueOf(uiLevel));
        return p;
    }

    /** .config load: settings first, then on/off states. Returns the number of values applied. */
    static int importConfig(Properties p) {
        int n = 0;
        for (Category cat : CATEGORIES) {
            for (Entry e : cat.entries) {
                for (Setting s : e.settings().get()) {
                    String v = p.getProperty(e.name() + "." + s.name);
                    if (v == null) continue;
                    try {
                        if (s.kind == Setting.Kind.BOOL) s.setBool(Boolean.parseBoolean(v.trim()));
                        else s.setNum(Double.parseDouble(v.trim()));
                        n++;
                    } catch (Exception ignored) { }
                }
            }
        }
        for (Category cat : CATEGORIES) {
            for (Entry e : cat.entries) {
                String v = p.getProperty(e.name() + ".@on");
                if (v == null) continue;
                boolean on = Boolean.parseBoolean(v.trim());
                try { if (e.getter().getAsBoolean() != on) e.setter().accept(on); n++; } catch (Exception ignored) { }
            }
        }
        try {
            themeIdx = clampIdx(Integer.parseInt(p.getProperty("@gui.theme", String.valueOf(themeIdx)).trim()), THEME_NAMES.length);
            effectIdx = clampIdx(Integer.parseInt(p.getProperty("@gui.effect", String.valueOf(effectIdx)).trim()), EFFECT_NAMES.length);
            speedIdx = clampIdx(Integer.parseInt(p.getProperty("@gui.speed", String.valueOf(speedIdx)).trim()), SPEEDS.length);
            dimIdx = clampIdx(Integer.parseInt(p.getProperty("@gui.dim", String.valueOf(dimIdx)).trim()), DIMS.length);
            uiLevel = Math.max(8, Math.min(30, Integer.parseInt(p.getProperty("@gui.uilevel", String.valueOf(uiLevel)).trim())));
            uiLevelDraft = uiLevel;
            applySize();
        } catch (Exception ignored) { }
        saveModSettings();
        saveGuiSettings();
        return n;
    }

    private static int clampIdx(int v, int len) { return Math.max(0, Math.min(len - 1, v)); }
    private static int cycleIdx(int v, int len, int d) { return ((v + d) % len + len) % len; }

    // ------------------------------------------------------------- panel layout

    private static int PANEL_W = 160;
    private static int ROW_H = 14;
    private static int HEADER_H = 17;
    private static final int OPEN_MS = 300;
    private static final int STAGGER_MS = 70;

    private static final List<Category> CATEGORIES = new ArrayList<>();
    static {
        CATEGORIES.add(new Category("Combat", List.of(
            new Entry("PistonAura", PistonAura::isEnabled, PistonAura::setEnabled,
                PistonAura::getKeyMapping, PistonAura::getSettings),
            new Entry("AntiPhase", AntiPhase::isEnabled, AntiPhase::setEnabled,
                AntiPhase::getKeyMapping, AntiPhase::getSettings),
            new Entry("Blocker", Blocker::isEnabled, Blocker::setEnabled,
                Blocker::getKeyMapping, Blocker::getSettings),
            new Entry("MaceAura", MaceAura::isEnabled, MaceAura::setEnabled,
                MaceAura::getKeyMapping, MaceAura::getSettings),
            new Entry("AnchorAura", AnchorAura::isEnabled, AnchorAura::setEnabled,
                AnchorAura::getKeyMapping, AnchorAura::getSettings),
            new Entry("Suicide", Suicide::isEnabled, Suicide::setEnabled,
                Suicide::getKeyMapping, Suicide::getSettings),
            new Entry("FakePlayer+", FakePlayerPlus::isEnabled, FakePlayerPlus::setEnabled,
                () -> null, FakePlayerPlus::getSettings)
        ), List.of()));

        CATEGORIES.add(new Category("Movement", List.of(
            new Entry("FakeFly", FakeFly::isEnabled, FakeFly::setEnabled,
                FakeFly::getKeyMapping, FakeFly::getSettings)
        ), List.of()));

        CATEGORIES.add(new Category("Visual", List.of(
            new Entry("Skybox+", Skybox::isEnabled, Skybox::setEnabled, () -> null, Skybox::getSettings),
            new Entry("Hand Tweaks", HandTweaks::isEnabled, HandTweaks::setEnabled, () -> null, HandTweaks::getSettings),
            new Entry("Hand Shader", HandShader::isEnabled, HandShader::setEnabled, () -> null, HandShader::getSettings),
            new Entry("KillEffects", KillEffects::isEnabled, KillEffects::setEnabled, () -> null, KillEffects::getSettings)
        ), List.of()));

        CATEGORIES.add(new Category("Client", List.of(
            new Entry("Font+", FontMod::isEnabled, FontMod::setEnabled, () -> null, FontMod::getSettings)
        ), List.of(
            new Cycle("Theme", () -> THEME_NAMES[themeIdx],
                () -> { themeIdx = cycleIdx(themeIdx, THEME_NAMES.length, 1); saveGuiSettings(); },
                () -> { themeIdx = cycleIdx(themeIdx, THEME_NAMES.length, -1); saveGuiSettings(); }),
            new Cycle("Effect", () -> EFFECT_NAMES[effectIdx],
                () -> { effectIdx = cycleIdx(effectIdx, EFFECT_NAMES.length, 1); saveGuiSettings(); },
                () -> { effectIdx = cycleIdx(effectIdx, EFFECT_NAMES.length, -1); saveGuiSettings(); }),
            new Cycle("Speed", () -> SPEEDS[speedIdx] + "x",
                () -> { speedIdx = cycleIdx(speedIdx, SPEEDS.length, 1); saveGuiSettings(); },
                () -> { speedIdx = cycleIdx(speedIdx, SPEEDS.length, -1); saveGuiSettings(); }),
            new Cycle("Dim", () -> "%" + DIMS[dimIdx],
                () -> { dimIdx = cycleIdx(dimIdx, DIMS.length, 1); saveGuiSettings(); },
                () -> { dimIdx = cycleIdx(dimIdx, DIMS.length, -1); saveGuiSettings(); })
        )));

        CATEGORIES.stream().filter(c -> c.name.equals("Client")).findFirst().orElseThrow().extra = List.of(
            Setting.num("ClickGUI size", 8, 30, 1, () -> uiLevelDraft, v -> uiLevelDraft = (int) v));

        for (int i = 0; i < CATEGORIES.size(); i++) {
            CATEGORIES.get(i).x = 24 + i * (PANEL_W + 14);
            CATEGORIES.get(i).y = 28;
        }
    }

    private static final Set<String> EXPANDED = new HashSet<>();

    /** If panels weren't moved by hand, fit them on screen: a panel that doesn't fit to the right goes under the shortest column. */
    private static boolean panelsMoved;

    private void fitPanels() {
        int cols = Math.max(1, Math.min(CATEGORIES.size(), (this.width - 24) / (PANEL_W + 14)));
        int[] bottom = new int[cols];
        for (int i = 0; i < CATEGORIES.size(); i++) {
            Category c = CATEGORIES.get(i);
            int col = 0;
            if (i < cols) col = i;
            else for (int k = 1; k < cols; k++) if (bottom[k] < bottom[col]) col = k;   // under the shortest column
            c.x = 24 + col * (PANEL_W + 14);
            c.y = i < cols ? 28 : bottom[col] + 10;
            bottom[col] = c.y + HEADER_H + Math.max(ROW_H, c.viewH);
        }
    }

    private static final int COLOR_HEADER = 0xE60C141C;
    private static final int COLOR_ROW_OFF = 0xA0101418;
    private static final int COLOR_SUB = 0xC00A0D11;
    private static final int COLOR_TEXT = 0xFFFFFF;
    private static final int COLOR_TEXT_OFF = 0xD2D9DE;
    private static final int COLOR_TEXT_DIM = 0x8FA3B0;

    /** Row layout: drawing and clicking use the same list. y values are inside the body (under the header). */
    private static final class RowInfo {
        int y;           // position inside the body (without scrolling)
        int cTop, cBot;  // the range this row can be visible in (for the opening block animation)
        float alpha = 1f;
        int h = ROW_H;           // row height (the color wheel row is taller)
        boolean picker;          // HSV color wheel row
        Entry entry;     // module row (or one of its sub rows)
        Cycle cycle;     // Client panel setting
        boolean bind;    // "Key:" sub row
        Setting setting; // module setting sub row
    }

    private static final int HOTBAR_SPACE = 26;

    private int maxBody(Category cat) {
        // the panel reaches down to the bottom of the screen, right above the hotbar (hotbar 22 px + gap)
        return Math.max(ROW_H * 3, this.height - HOTBAR_SPACE - cat.y - HEADER_H);
    }

    private RowInfo plain(int y) {
        RowInfo r = new RowInfo();
        r.y = y; r.cTop = y; r.cBot = y + ROW_H;
        return r;
    }

    /** Builds the rows; also updates the panel's content/visible height. */
    private List<RowInfo> rowsOf(Category cat) {
        List<RowInfo> rows = new ArrayList<>();
        int y = 0;
        for (Entry e : cat.entries) {
            RowInfo r = plain(y);
            r.entry = e;
            rows.add(r);
            y += ROW_H;
            float exp = animExp.getOrDefault(e.name(), EXPANDED.contains(e.name()) ? 1f : 0f);
            if (exp > 0.001f) {
                List<Setting> ss = e.settings().get();
                // a hidden setting (when() condition false) gives no row; when the condition changes its height animates open/closed
                float[] vis = new float[ss.size()];
                int total = ROW_H;
                for (int k = 0; k < ss.size(); k++) {
                    Setting st = ss.get(k);
                    vis[k] = animVis.getOrDefault(pickerKey(e, st), st.visible() ? 1f : 0f);
                    if (vis[k] <= 0.001f) continue;
                    total += Math.round(vis[k] * ROW_H);
                    if (st.kind == Setting.Kind.COLOR && pickerKey(e, st).equals(openPicker)) total += Math.round(vis[k] * pickerH());
                }
                int bh = Math.round(exp * total);
                int shift = Math.round((1f - exp) * total); // slides down like a drawer
                int off = 0;
                for (int k = 0; k <= ss.size(); k++) {
                    float v = k == 0 ? 1f : vis[k - 1];
                    if (v <= 0.001f) continue;
                    int vh = Math.round(v * ROW_H);
                    int top = y + off - shift;
                    RowInfo sr = new RowInfo();
                    sr.y = top - (ROW_H - vh);                  // when opening it slides out from under the row above
                    sr.cTop = Math.max(y, top); sr.cBot = Math.min(y + bh, top + vh);
                    sr.alpha = exp * v;
                    sr.entry = e;
                    if (k == 0) sr.bind = true; else sr.setting = ss.get(k - 1);
                    rows.add(sr);
                    off += vh;
                    if (k > 0 && sr.setting.kind == Setting.Kind.COLOR && pickerKey(e, sr.setting).equals(openPicker)) {
                        int ph = Math.round(v * pickerH());
                        int ptop = y + off - shift;
                        RowInfo pr = new RowInfo();
                        pr.y = ptop - (pickerH() - ph);
                        pr.cTop = Math.max(y, ptop); pr.cBot = Math.min(y + bh, ptop + ph);
                        pr.alpha = exp * v;
                        pr.entry = e;
                        pr.setting = sr.setting;
                        pr.picker = true;
                        pr.h = pickerH();
                        rows.add(pr);
                        off += ph;
                    }
                }
                y += bh;
            }
        }
        for (Cycle c : cat.cycles) {
            RowInfo r = plain(y);
            r.cycle = c;
            rows.add(r);
            y += ROW_H;
        }
        for (Setting s : cat.extra) {
            if (!s.visible()) continue;
            RowInfo r = plain(y);
            r.setting = s;
            rows.add(r);
            y += ROW_H;
        }
        cat.contentH = y;
        cat.viewH = Math.max(ROW_H, Math.min(y, maxBody(cat)));
        return rows;
    }

    /** The row's visible (clipped) vertical range on screen: {top, bottom}; null if not visible. */
    private int[] rowRect(Category cat, RowInfo r) {
        int sc = Math.round(cat.scroll);
        int by = cat.y + HEADER_H;
        int ct = Math.max(by, by + r.cTop - sc);
        int cb = Math.min(by + cat.viewH, by + r.cBot - sc);
        int top = Math.max(ct, by + r.y - sc);
        int bot = Math.min(cb, by + r.y - sc + r.h);
        return bot > top ? new int[]{top, bot} : null;
    }

    private int thumbH(Category cat) {
        return Math.max(ROW_H, cat.viewH * cat.viewH / Math.max(1, cat.contentH));
    }

    // ------------------------------------------------------------- durum

    private Entry awaitingBind = null;
    private Category dragging = null;
    private int dragOffX, dragOffY;
    private Setting sliding = null;
    private int slideX, slideW;
    private Setting editing = null;            // slider with a typed value (on the value or right click)
    private String editBuf = "";
    private final long openTime;
    private long closeTime = -1;
    private final Map<String, Float> animOn = new HashMap<>();
    private final Map<String, Float> animHover = new HashMap<>();
    private final Map<String, Float> animExp = new HashMap<>();  // visible (smoothed) open ratio
    private final Map<String, Float> expLin = new HashMap<>();   // linear progress 0..1
    private static final float EXP_S = 0.28f;                    // open/close time (s)
    private final Map<String, Float> animVis = new HashMap<>();  // visible ratio of a hideable setting row
    private final Map<String, Float> visLin = new HashMap<>();
    private long lastFrameNs = 0;
    private float frameDt = 0.016f;
    private Category barDrag = null;
    // description (tooltip)
    private String hovKey = null, curHovKey = null, curHovTip = null, tipText = null;
    private long hovSince;
    private float tipA = 0f;
    private int barGrab;

    private ClickGui() {
        super(Component.literal("Future+"));
        openTime = System.currentTimeMillis();
        // settings already open when the GUI opens start open without animation
        for (Category c : CATEGORIES) {
            for (Entry e : c.entries) {
                float v = EXPANDED.contains(e.name()) ? 1f : 0f;
                expLin.put(e.name(), v);
                animExp.put(e.name(), v);
                for (Setting st : e.settings().get()) {
                    float sv = st.visible() ? 1f : 0f;
                    visLin.put(pickerKey(e, st), sv);
                    animVis.put(pickerKey(e, st), sv);
                }
            }
        }
    }

    /** Open animation of a settings block: time based (independent of FPS), easeOutCubic. */
    private void stepExp(String key, boolean open) {
        float p = expLin.getOrDefault(key, 0f);
        p += (open ? 1f : -1f) * frameDt / EXP_S;
        p = Math.max(0f, Math.min(1f, p));
        expLin.put(key, p);
        animExp.put(key, open ? easeOutCubic(p) : 1f - easeOutCubic(1f - p));
    }

    /** Hideable setting row: opens / closes with the same time and curve as stepExp when its condition changes. */
    private void stepVis(Entry e) {
        for (Setting st : e.settings().get()) {
            String key = pickerKey(e, st);
            boolean show = st.visible();
            float p = visLin.getOrDefault(key, show ? 1f : 0f);
            p = Math.max(0f, Math.min(1f, p + (show ? 1f : -1f) * frameDt / EXP_S));
            visLin.put(key, p);
            animVis.put(key, show ? easeOutCubic(p) : 1f - easeOutCubic(1f - p));
            if (!show && sliding == st) sliding = null;
            if (!show && p <= 0f && key.equals(openPicker)) openPicker = "";
        }
    }

    private static KeyMapping guiKey;

    /** ClickGUI screen factory. */
    static Screen create() { return new ClickGui(); }

    /** Return to the GUI. */
    static void openGui() {
        Minecraft.getInstance().setScreen(new ClickGui());
    }

    /** Call once from the client entrypoint (AFTER the modules' init()). */
    public static void register() {
        FontMod.loadState();
        Skybox.loadState();
        SkyRender.init(); // register the sky pipelines early
        HandShader.init(); WorldDraw.init(); Gfx.init();
        loadSettings();

        KeyMapping.Category category = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("futureplus", "clickgui"));

        guiKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.futureplus.clickgui",
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_RSHIFT,
            category));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.screen == null && guiKey.consumeClick()) {
                mc.setScreen(new ClickGui());
            }
        });
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Don't draw the default blurred background; we draw the dimming. */
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // empty on purpose
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ------------------------------------------------------------- color helpers

    private static int argb(int alpha, int rgb) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0xFFFFFF);
    }

    private static int lerpRgb(int c1, int c2, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = (int) (((c1 >> 16) & 255) * (1 - t) + ((c2 >> 16) & 255) * t);
        int g = (int) (((c1 >> 8) & 255) * (1 - t) + ((c2 >> 8) & 255) * t);
        int b = (int) ((c1 & 255) * (1 - t) + (c2 & 255) * t);
        return (r << 16) | (g << 8) | b;
    }

    private static int hsv(float h, float s, float v) {
        h = h - (float) Math.floor(h);
        float c = v * s;
        float x = c * (1 - Math.abs((h * 6) % 2 - 1));
        float m = v - c;
        float r, g, b;
        int sector = (int) (h * 6);
        switch (sector) {
            case 0 -> { r = c; g = x; b = 0; }
            case 1 -> { r = x; g = c; b = 0; }
            case 2 -> { r = 0; g = c; b = x; }
            case 3 -> { r = 0; g = x; b = c; }
            case 4 -> { r = x; g = 0; b = c; }
            default -> { r = c; g = 0; b = x; }
        }
        return ((int) ((r + m) * 255) << 16) | ((int) ((g + m) * 255) << 8) | (int) ((b + m) * 255);
    }

    /** The theme's color at mix m (0..1); u = horizontal position, t = time (seconds). */
    private static int themeMix(float m, float u, float t) {
        if (themeIdx == THEME_NAMES.length - 1) { // Rainbow
            return hsv(u * 0.35f + t * 0.12f + m * 0.12f, 0.62f, 1.0f);
        }
        return lerpRgb(THEME_COLORS[themeIdx][0], THEME_COLORS[themeIdx][1], m);
    }

    private static int accent(float t) { return themeMix(0.0f, 0.0f, t); }

    /** For the HUD: the selected theme's animated color (RGB, no alpha). u = position 0..1. */
    static int hudColor(float u) {
        float t = (System.nanoTime() % 3_600_000_000_000L) / 1_000_000_000f * SPEEDS[speedIdx];
        return themeMix(0.5f + 0.5f * (float) Math.sin(u * 3.2f - t * 1.6f), u, t);
    }

    /** For menus: the theme's fixed (non-waving) color at mix m (0..1). Cycles over time with Rainbow. */
    static int themeColor(float m) {
        float t = (System.nanoTime() % 3_600_000_000_000L) / 1_000_000_000f * SPEEDS[speedIdx];
        return themeMix(m, m, t);
    }

    private static float easeOutCubic(float x) {
        x = Math.max(0f, Math.min(1f, x));
        float a = 1f - x;
        return 1f - a * a * a;
    }

    private float ease(Map<String, Float> map, String key, boolean target) {
        float goal = target ? 1f : 0f;
        float cur = map.getOrDefault(key, goal);
        cur += (goal - cur) * (1f - (float) Math.exp(-frameDt * 16f)); // independent of FPS
        if (Math.abs(cur - goal) < 0.01f) cur = goal;
        map.put(key, cur);
        return cur;
    }

    // ------------------------------------------------------------- drawing

    private float ga = 1f; // overall alpha (open/close animation)

    private void fill(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int argb) {
        int a = (int) (((argb >>> 24) & 255) * ga);
        if (a <= 0 || x2 <= x1 || y2 <= y1) return;
        g.fill(x1, y1, x2, y2, (a << 24) | (argb & 0xFFFFFF));
    }

    private void text(GuiGraphicsExtractor g, String s, int x, int y, int rgb) {
        int a = (int) (255 * ga);
        if (a < 12) return; // very low alpha draws opaque in vanilla, skip
        FontMod.draw(g, font, s, x, y, (a << 24) | (rgb & 0xFFFFFF), true, 0); // no glow in the ClickGui
    }

    /**
     * HD effect (marble / gradient / sparkle): drawn from a ready texture so that there is 1 texture pixel per SCREEN PIXEL
     * (region size = GUI size x GUI scale), so there is no resampling
     * blur/jaggies. It scrolls horizontally; with a 60 frame loop (60 frames/s) the shape changes smoothly too.
     */
    private void fxTexture(GuiGraphicsExtractor g, int kind, int x, int y, int w, int h,
                           float strength, float seed, float t) {
        fxStatic(g, kind, x, y, w, h, Math.min(strength, ga), seed, t);
    }

    /** fxTexture core; fade: 1 = full effect, 0 = fully the background color. */
    private static void fxStatic(GuiGraphicsExtractor g, int kind, int x, int y, int w, int h,
                                 float fade, float seed, float t) {
        if (w <= 0 || h <= 0) return;
        Identifier tex = FX[kind][themeIdx];
        double scale = Minecraft.getInstance().getWindow().getGuiScale();
        int wt = Math.max(1, (int) Math.round(w * scale));
        int ht = Math.max(1, (int) Math.round(h * scale));

        int frame = (int) ((t * 60f) % FRAMES);
        int base = frame * FRAME_H;
        int total = FRAMES * FRAME_H;
        int u0 = (int) (t * 60f + seed * 97f);
        int v0 = (int) (seed * 11f);

        // tile the target region by texture pixels: if the texture width/frame height is exceeded
        // it continues seamlessly (textures are seamless in x and y), each piece drawn 1:1
        int dx = 0;
        while (dx < wt) {
            int u = Math.floorMod(u0 + dx, TEX_W);
            int segW = Math.min(TEX_W - u, wt - dx);
            int gx0 = x + (int) Math.round(dx / scale);
            int gx1 = x + (int) Math.round((dx + segW) / scale);
            int dy = 0;
            while (dy < ht) {
                int vv = Math.floorMod(v0 + dy, FRAME_H);
                int segH = Math.min(FRAME_H - vv, ht - dy);
                int gy0 = y + (int) Math.round(dy / scale);
                int gy1 = y + (int) Math.round((dy + segH) / scale);
                if (gx1 > gx0 && gy1 > gy0) {
                    g.blit(RenderPipelines.GUI_TEXTURED, tex, gx0, gy0, u, base + vv,
                        gx1 - gx0, gy1 - gy0, segW, segH, TEX_W, total);
                }
                dy += segH;
            }
            dx += segW;
        }

        // fade/open: the background color over the texture (while fade < 1)
        if (fade < 0.99f) g.fill(x, y, x + w, y + h, argb((int) (255 * (1f - fade)), 0x101418));
    }

    /** ClickGUI background fill helper. */
    static void effect(GuiGraphicsExtractor g, int x, int y, int w, int h, float strength, float seed) {
        if (strength <= 0.01f || w <= 0 || h <= 0) return;
        float t = (System.nanoTime() % 3_600_000_000_000L) / 1_000_000_000f * SPEEDS[speedIdx];
        if (effectIdx < EFFECT_TEX.length) fxStatic(g, effectIdx, x, y, w, h, strength, seed, t);
        else g.fill(x, y, x + w, y + h, argb((int) (225 * strength), themeMix(0.25f, 0.2f, t)));
    }

    /** ClickGui value text color (option values). */
    static int valueColor() {
        float t = (System.nanoTime() % 3_600_000_000_000L) / 1_000_000_000f * SPEEDS[speedIdx];
        return themeMix(0.2f, 0.5f, t);
    }

    /** Effect fill: the selected effect (all HD textures; except "Solid"). */
    private void effectFill(GuiGraphicsExtractor g, int x, int y, int w, int h,
                            float strength, int baseAlpha, float seed, float t) {
        if (effectIdx < EFFECT_TEX.length) {
            fxTexture(g, effectIdx, x, y, w, h, strength, seed, t);
            return;
        }
        // Plain: a single color
        fill(g, x, y, x + w, y + h, argb((int) (baseAlpha * strength), themeMix(0.25f, 0.2f, t)));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // ClickGui text size depends on the row height (default 14 -> 10 pt)
        FontMod.setUiSize(Math.max(FontMod.MIN_SIZE, uiLevel - 4));
        try {
            renderInner(g, mouseX, mouseY, partialTick);
        } finally {
            FontMod.setUiSize(0);
        }
    }

    private void renderInner(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        refreshLayout();
        long ns = System.nanoTime();
        frameDt = lastFrameNs == 0 ? 0.016f : Math.min(0.1f, (ns - lastFrameNs) / 1_000_000_000f);
        lastFrameNs = ns;
        long now = System.currentTimeMillis();
        float t = (System.nanoTime() % 3_600_000_000_000L) / 1_000_000_000f * SPEEDS[speedIdx];

        float closeP = closeTime < 0 ? 0f : (now - closeTime) / (float) OPEN_MS;
        if (closeTime >= 0 && closeP >= 1f) {
            Minecraft.getInstance().setScreen(null);
            return;
        }

        float globalOpen = easeOutCubic((now - openTime) / (float) OPEN_MS);
        float globalAlpha = closeTime < 0 ? globalOpen : 1f - easeOutCubic(closeP);

        ga = globalAlpha;
        curHovKey = null;
        curHovTip = null;
        fill(g, 0, 0, this.width, this.height, argb((int) (DIMS[dimIdx] * 2.55f), 0x000000));
        text(g, "Future+", 4, 4, accent(t));

        if (!panelsMoved) fitPanels();
        if (dragging != null) {
            panelsMoved = true;
            dragging.x = mouseX - dragOffX;
            dragging.y = mouseY - dragOffY;
        }
        if (sliding != null && slideW > 0) {
            double ratio = (mouseX - slideX) / (double) slideW;
            ratio = Math.max(0.0, Math.min(1.0, ratio));
            sliding.setNum(sliding.min + ratio * (sliding.max - sliding.min));
        }

        for (int ci = 0; ci < CATEGORIES.size(); ci++) {
            Category cat = CATEGORIES.get(ci);

            float p = easeOutCubic((now - openTime - ci * STAGGER_MS) / (float) OPEN_MS);
            float panelAlpha = closeTime < 0 ? p : 1f - easeOutCubic(closeP);
            ga = panelAlpha;
            int offY = (int) ((1f - panelAlpha) * -24);
            int px = cat.x;
            int py = cat.y + offY;

            for (Entry e : cat.entries) { stepExp(e.name(), EXPANDED.contains(e.name())); stepVis(e); }
            List<RowInfo> rows = rowsOf(cat);

            // scrolling: clamp, take the position from the mouse if the bar is dragged, smooth
            int maxS = Math.max(0, cat.contentH - cat.viewH);
            int by = py + HEADER_H;
            int vh = cat.viewH;
            if (barDrag == cat && maxS > 0) {
                int track = vh - thumbH(cat);
                float ratio = track > 0 ? (mouseY - barGrab - by) / (float) track : 0f;
                cat.scrollTarget = cat.scroll = Math.max(0f, Math.min(1f, ratio)) * maxS;
            }
            cat.scrollTarget = Math.max(0f, Math.min(maxS, cat.scrollTarget));
            cat.scroll += (cat.scrollTarget - cat.scroll) * 0.35f;
            if (Math.abs(cat.scrollTarget - cat.scroll) < 0.3f) cat.scroll = cat.scrollTarget;
            cat.scroll = Math.max(0f, Math.min(maxS, cat.scroll));
            int sc = Math.round(cat.scroll);

            boolean scrollable = maxS > 0;
            int padR = scrollable ? 9 : 5;
            int totalH = HEADER_H + vh;

            // header: dark background + marble + dimming (so the text reads)
            fill(g, px, py, px + PANEL_W, py + HEADER_H, COLOR_HEADER);
            effectFill(g, px, py, PANEL_W, HEADER_H, 1f, 150, ci * 1.7f, t);
            fill(g, px, py, px + PANEL_W, py + HEADER_H, 0x40000000);
            text(g, cat.name, px + 5, py + hty(), COLOR_TEXT);

            int active = 0;
            for (Entry e : cat.entries) if (e.getter().getAsBoolean()) active++;
            if (!cat.entries.isEmpty()) {
                String count = active + "/" + cat.entries.size();
                text(g, count, px + PANEL_W - FontMod.width(font, count) - 5, py + hty(), COLOR_TEXT);
            }

            // body: rows clipped to the visible range
            for (RowInfo r : rows) {
                int ry = by + r.y - sc;
                int ct = Math.max(by, by + r.cTop - sc);
                int cb = Math.min(by + vh, by + r.cBot - sc);
                if (cb <= ct || ry + r.h <= ct || ry >= cb) continue;
                boolean clip = ry < ct || ry + r.h > cb;
                if (clip) g.enableScissor(px - 1, ct, px + PANEL_W + 1, cb);
                float saveGa = ga;
                ga = ga * r.alpha;
                drawRow(g, r, ci, px, ry, padR, mouseX, mouseY, ct, cb, t);
                ga = saveGa;
                if (clip) g.disableScissor();
            }

            // scroll bar (thin)
            if (scrollable) {
                int bx = px + PANEL_W - 4;
                boolean hovBar = barDrag == cat || inside(mouseX, mouseY, px + PANEL_W - 8, by, 8, vh);
                int th = thumbH(cat);
                int track = vh - th;
                int ty0 = by + (maxS > 0 ? Math.round(track * sc / (float) maxS) : 0);
                fill(g, bx, by, bx + 2, by + vh, 0x55000000);
                fill(g, bx, ty0, bx + 2, ty0 + th, argb(hovBar ? 240 : 170, hovBar ? 0xFFFFFF : accent(t)));
            }

            int bc = argb(110, accent(t));
            fill(g, px - 1, py, px, py + totalH, bc);
            fill(g, px + PANEL_W, py, px + PANEL_W + 1, py + totalH, bc);
            fill(g, px - 1, py + totalH, px + PANEL_W + 1, py + totalH + 1, bc);
        }

        // description: fades in softly at the top center 30 ms after hovering a row
        if (!java.util.Objects.equals(curHovKey, hovKey)) { hovKey = curHovKey; hovSince = now; }
        boolean showTip = hovKey != null && curHovTip != null && now - hovSince >= 30;
        if (showTip) tipText = curHovTip;
        tipA += ((showTip ? 1f : 0f) - tipA) * (1f - (float) Math.exp(-frameDt * 30f)); // comes down in ~0.12 s
        if (tipA < 0.01f) tipA = 0f;
        if (tipA > 0.02f && tipText != null) drawTip(g, tipText, tipA * globalAlpha, t);
        ga = 1f;
    }

    /** Description: no box, just white + glow text; slides down quickly from the very top of the screen. */
    private void drawTip(GuiGraphicsExtractor g, String tip, float a, float t) {
        int maxW = Math.max(80, this.width - 40);
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String w : tip.split(" ")) {
            String tryS = cur.length() == 0 ? w : cur + " " + w;
            if (FontMod.width(font, tryS) > maxW && cur.length() > 0) { lines.add(cur.toString()); cur = new StringBuilder(w); }
            else { cur = new StringBuilder(tryS); }
        }
        if (cur.length() > 0) lines.add(cur.toString());
        int lh = FontMod.lineHeight() + 1;
        int total = lines.size() * lh + 2;
        float e = easeOutCubic(a);
        int top = Math.round(-total + (total + 2) * e); // drops in from outside the top edge of the screen
        int al = (int) (255 * Math.min(1f, a * 1.5f));
        if (al < 16) return;
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            int lx = (this.width - FontMod.width(font, l)) / 2;
            FontMod.drawGlowAlways(g, font, l, lx, top + i * lh, (al << 24) | 0xFFFFFF);
        }
    }

    // ------------------------------------------------------------- HSV color wheel

    private static String openPicker = "";                    // key of the open wheel
    private Setting pickSetting = null;                        // the color setting being dragged
    private int pickPart = 0;                                  // 1 wheel, 2 brightness bar
    private int pickX, pickY, pickS, pickBarX;                 // positions from the last draw
    private static final Map<String, float[]> HSV = new HashMap<>();

    private static String pickerKey(Entry e, Setting s) { return (e == null ? "Client" : e.name()) + "." + s.name; }
    private static int pickerH() { return Math.max(40, Math.round(PANEL_W * 0.62f)); }

    private float[] hsvOf(Setting s) {
        float[] h = HSV.get(s.name);
        int rgb = s.getColor();
        if (h == null || ColorPicker.fromHsv(h[0], h[1], h[2]) != rgb) {
            h = ColorPicker.toHsv(rgb);
            HSV.put(s.name, h);
        }
        return h;
    }

    private void startPick(Setting s, double mx, double my) {
        if (pickS <= 0) return;
        if (mx >= pickX && mx < pickX + pickS && my >= pickY && my < pickY + pickS) pickPart = 1;
        else if (mx >= pickBarX - 2 && mx < pickBarX + 10 && my >= pickY && my < pickY + pickS) pickPart = 2;
        else return;
        pickSetting = s;
        applyPick(mx, my);
    }

    private void applyPick(double mx, double my) {
        Setting s = pickSetting;
        if (s == null) return;
        float[] h = hsvOf(s);
        if (pickPart == 1) {
            double c = pickS / 2.0;
            double dx = (mx - pickX - c) / c, dy = (my - pickY - c) / c;
            double r = Math.min(1.0, Math.sqrt(dx * dx + dy * dy));
            double a = Math.atan2(dy, dx) / (Math.PI * 2);
            if (a < 0) a += 1;
            h[0] = (float) a;
            h[1] = (float) r;
        } else {
            h[2] = (float) Math.max(0, Math.min(1, 1 - (my - pickY) / (double) pickS));
        }
        s.setNum(ColorPicker.fromHsv(h[0], h[1], h[2]));
        HSV.put(s.name, h);
    }

    private void drawPicker(GuiGraphicsExtractor g, RowInfo r, int px, int ry, int mouseX, int mouseY, float t) {
        int H = r.h;
        fill(g, px, ry, px + PANEL_W, ry + H, COLOR_SUB);
        fill(g, px, ry, px + 1, ry + H, argb(255, accent(t)));
        Setting s = r.setting;
        if (pickSetting == s) applyPick(mouseX, mouseY);       // update live while dragging
        float[] h = hsvOf(s);
        int S = H - 8;
        int wx = px + 6, wy = ry + 4;
        int barX = wx + S + 6;
        pickX = wx; pickY = wy; pickS = S; pickBarX = barX;
        // wheel (brightness as a color multiplier)
        int vv = Math.round(h[2] * 255);
        int a = Math.round(255 * ga);
        g.blit(RenderPipelines.GUI_TEXTURED, ColorPicker.wheel(), wx, wy, 0f, 0f, S, S,
            ColorPicker.TS, ColorPicker.TS, ColorPicker.TS, ColorPicker.TS, (a << 24) | (vv << 16) | (vv << 8) | vv);
        // the selection point on the wheel
        double ang = h[0] * Math.PI * 2, rad = h[1] * S / 2.0;
        int mx = wx + (int) Math.round(S / 2.0 + Math.cos(ang) * rad), my = wy + (int) Math.round(S / 2.0 + Math.sin(ang) * rad);
        fill(g, mx - 2, my - 2, mx + 3, my + 3, 0xFF000000);
        fill(g, mx - 1, my - 1, mx + 2, my + 2, 0xFFFFFFFF);
        // brightness bar
        for (int i = 0; i < S; i++) {
            int c = ColorPicker.fromHsv(h[0], h[1], 1f - i / (float) S);
            fill(g, barX, wy + i, barX + 8, wy + i + 1, argb(255, c));
        }
        int by = wy + Math.round((1 - h[2]) * S);
        fill(g, barX - 2, by - 1, barX + 10, by + 1, 0xFFFFFFFF);
        // preview box
        int sx = barX + 14, sw = Math.max(6, px + PANEL_W - 6 - sx);
        fill(g, sx - 1, wy - 1, sx + sw + 1, wy + S + 1, 0xFF3A4650);
        fill(g, sx, wy, sx + sw, wy + S, argb(255, s.getColor()));
    }

    /** Fits the text into the given width (adds ".." at the end if it doesn't fit). */
    private String fit(String s, int maxW) {
        if (maxW <= 0) return "";
        if (FontMod.width(font, s) <= maxW) return s;
        int n = s.length();
        while (n > 1 && FontMod.width(font, s.substring(0, n) + "..") > maxW) n--;
        return s.substring(0, n) + "..";
    }

    private void drawRow(GuiGraphicsExtractor g, RowInfo r, int ci, int px, int ry, int padR,
                         int mouseX, int mouseY, int ct, int cb, float t) {
        if (r.picker) { drawPicker(g, r, px, ry, mouseX, mouseY, t); return; }
        int hy0 = Math.max(ry, ct), hy1 = Math.min(ry + ROW_H, cb);
        boolean hover = mouseY >= hy0 && mouseY < hy1 && mouseX >= px && mouseX < px + PANEL_W;
        int rx = px + PANEL_W - padR; // right edge (for text)
        if (hover && dragging == null && sliding == null && barDrag == null) {
            String mod, set;
            if (r.cycle != null) { mod = "Client"; set = r.cycle.name(); }
            else if (r.setting != null) { mod = r.entry != null ? r.entry.name() : "Client"; set = r.setting.name; }
            else if (r.bind) { mod = r.entry.name(); set = "Key"; }
            else { mod = r.entry.name(); set = null; }
            curHovKey = mod + "|" + set;
            curHovTip = Tooltips.get(mod, set);
        }

        if (r.cycle != null) {
            float hovT = ease(animHover, "C" + r.cycle.name(), hover);
            fill(g, px, ry, px + PANEL_W, ry + ROW_H, COLOR_ROW_OFF);
            if (hovT > 0.01f) fill(g, px, ry, px + PANEL_W, ry + ROW_H, argb((int) (38 * hovT), 0xFFFFFF));
            String v = r.cycle.value().get();
            int vw = FontMod.width(font, v);
            text(g, fit(r.cycle.name(), rx - vw - 8 - (px + 5)), px + 5, ry + ty(), COLOR_TEXT_OFF);
            text(g, v, rx - vw, ry + ty(), themeMix(0.2f, 0.5f, t));
            return;
        }

        Entry e = r.entry;

        if (r.setting != null) {
            Setting s = r.setting;
            fill(g, px, ry, px + PANEL_W, ry + ROW_H, COLOR_SUB);
            fill(g, px, ry, px + 1, ry + ROW_H, argb(255, accent(t)));
            int nameMax;
            if (s.kind == Setting.Kind.COLOR) {
                int sw = 14, sh = ROW_H - 6;
                int bx = rx - sw;
                fill(g, bx - 1, ry + 2, bx + sw + 1, ry + 3 + sh, 0xFF3A4650);
                fill(g, bx, ry + 3, bx + sw, ry + 3 + sh - 1, argb(255, s.getColor()));
                nameMax = bx - 4 - (px + 8);
            } else if (s.isChoice()) {
                nameMax = rx - FontMod.width(font, s.display()) - 6 - (px + 8);
            } else if (s.kind == Setting.Kind.NUM) {
                double ratio = (s.getNum() - s.min) / (s.max - s.min);
                ratio = Math.max(0.0, Math.min(1.0, ratio));
                int fw = (int) ((PANEL_W - 1) * ratio);
                fill(g, px + 1, ry, px + 1 + fw, ry + ROW_H, argb(150, themeMix(0.3f, 0.3f, t)));
                fill(g, px + fw, ry, px + fw + 1, ry + ROW_H, argb(210, 0xFFFFFF));
                nameMax = rx - FontMod.width(font, s.display()) - 6 - (px + 8);
            } else {
                boolean on = s.getBool();
                int bx = rx - 9;
                int cy = ry + (ROW_H - 8) / 2;
                fill(g, bx, cy, bx + 8, cy + 8, 0xFF3A4650);
                fill(g, bx + 1, cy + 1, bx + 7, cy + 7, on ? argb(255, accent(t)) : 0xFF0A0D11);
                nameMax = bx - 4 - (px + 8);
            }
            if (hover) fill(g, px, ry, px + PANEL_W, ry + ROW_H, 0x22FFFFFF);
            text(g, fit(s.name, nameMax), px + 8, ry + ty(), COLOR_TEXT_OFF);
            if (s.kind == Setting.Kind.NUM) {
                boolean ed = editing == s;
                String v = ed ? editBuf + ((System.currentTimeMillis() / 500) % 2 == 0 ? "_" : " ") : s.display();
                if (ed) {
                    int vw = FontMod.width(font, v);
                    fill(g, rx - vw - 3, ry + 1, rx + 2, ry + ROW_H - 1, 0xC0000000);
                }
                text(g, v, rx - FontMod.width(font, v), ry + ty(), s.isChoice() ? themeMix(0.2f, 0.5f, t) : COLOR_TEXT);
            }
            return;
        }

        if (r.bind) {
            fill(g, px, ry, px + PANEL_W, ry + ROW_H, COLOR_SUB);
            fill(g, px, ry, px + 1, ry + ROW_H, argb(255, accent(t)));
            if (hover) fill(g, px, ry, px + PANEL_W, ry + ROW_H, 0x22FFFFFF);
            KeyMapping km = e.keyMapping().get();
            String keyName = km != null ? km.getTranslatedKeyMessage().getString() : "-";
            text(g, fit(awaitingBind == e ? "Key: ... (ESC: clear)" : "Key: " + keyName, rx - (px + 8)),
                px + 8, ry + ty(), COLOR_TEXT_DIM);
            return;
        }

        boolean on = e.getter().getAsBoolean();
        boolean open = EXPANDED.contains(e.name());
        float onT = ease(animOn, e.name(), on);
        float hovT = ease(animHover, e.name(), hover);

        fill(g, px, ry, px + PANEL_W, ry + ROW_H, COLOR_ROW_OFF);
        if (onT > 0.01f) {
            // open module: marble, drawn like a "card" with a 1 px dark gap around it;
            // so the marble of open modules one under the other doesn't blend together
            // 3arthh4ck style: a plain marble strip 2 px in from the sides with a 1 px gap at the top/bottom
            // (no line, frame or white bar on the left); a thin dark gap stays between open modules one under the other
            int ix = px + 2, iy = ry + 1, iw = PANEL_W - 4, ih = ROW_H - 2;
            effectFill(g, ix, iy, iw, ih, onT, 225, ci * 1.7f + r.y * 0.37f, t);
        }
        if (hovT > 0.01f) drawHoverGlow(g, px, ry, hovT, t);
        // line between modules: the same size as the hover glow's line (2 px in from the sides, 1 px)
        if (r.y > 0) fill(g, px + 2, ry, px + PANEL_W - 2, ry + 1, argb(110, accent(t)));

        String sign = open ? "-" : "+";
        int signX = rx - FontMod.width(font, sign);
        text(g, sign, signX, ry + ty(), COLOR_TEXT);

        String label = awaitingBind == e ? e.name() + " (press a key, ESC to clear)" : e.name();
        int tx = px + 5 + (int) (2 * onT + 2 * hovT);
        int labelMax = signX - 4 - tx;

        KeyMapping km0 = e.keyMapping().get();
        if (km0 != null && !km0.isUnbound() && awaitingBind != e) {
            String kn = "[" + km0.getTranslatedKeyMessage().getString() + "]";
            int kw = FontMod.width(font, kn);
            if (FontMod.width(font, label) + kw + 8 <= labelMax) { // show the key label if it fits
                text(g, kn, signX - kw - 4, ry + ty(), COLOR_TEXT_DIM);
            }
        }
        String shown = fit(label, labelMax);
        if (hovT > 0.05f) {                                   // the text glows too while hovered
            int a = (int) (255 * hovT * ga);
            if (a > 16) FontMod.drawGlowAlways(g, font, shown, tx, ry + ty(), (a << 24) | 0xFFFFFF);
        }
        text(g, shown, tx, ry + ty(), on || hovT > 0.5f ? COLOR_TEXT : COLOR_TEXT_OFF);
    }

    /**
     * Glow while the mouse is over the module: a thin frame in the theme color, light fading inward from the edges
     * and a slight pulse. hovT 0..1 (fades in softly when the mouse arrives, fades out when it leaves).
     */
    private void drawHoverGlow(GuiGraphicsExtractor g, int px, int ry, float hovT, float t) {
        // soft glow: no frame; the row lights up slightly, theme colored light fading toward the edges
        int acc = accent(t);
        float k = hovT * (0.85f + 0.15f * (float) Math.sin(System.nanoTime() / 1e9 * 4.0));
        int ix = px + 2, iy = ry + 1, iw = PANEL_W - 4, ih = ROW_H - 2;
        fill(g, ix, iy, ix + iw, iy + ih, argb((int) (30 * k), 0xFFFFFF));
        for (int i = 0; i < 3; i++) {
            int a = (int) ((45 - i * 14) * k);
            fill(g, ix + i, iy + i, ix + iw - i, iy + i + 1, argb(a, acc));
            fill(g, ix + i, iy + ih - i - 1, ix + iw - i, iy + ih - i, argb(a, acc));
        }
    }

    // ------------------------------------------------------------- girdi

    private boolean interactive() {
        return closeTime < 0 && System.currentTimeMillis() - openTime > OPEN_MS / 2;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!interactive()) return true;
        commitEdit();

        double mx = event.x();
        double my = event.y();
        int button = event.button();

        for (Category cat : CATEGORIES) {
            if (inside(mx, my, cat.x, cat.y, PANEL_W, HEADER_H)) {
                if (button == 0) {
                    dragging = cat;
                    dragOffX = (int) mx - cat.x;
                    dragOffY = (int) my - cat.y;
                }
                return true;
            }

            List<RowInfo> rows = rowsOf(cat);

            // scroll bar: click on the handle = hold, click on the gap = jump there and drag
            int maxS = Math.max(0, cat.contentH - cat.viewH);
            if (button == 0 && maxS > 0
                && inside(mx, my, cat.x + PANEL_W - 8, cat.y + HEADER_H, 8, cat.viewH)) {
                int th = thumbH(cat);
                int track = cat.viewH - th;
                int thumbY = cat.y + HEADER_H + Math.round(track * Math.round(cat.scroll) / (float) maxS);
                barGrab = (my < thumbY || my >= thumbY + th) ? th / 2 : (int) my - thumbY;
                barDrag = cat;
                return true;
            }

            for (RowInfo r : rows) {
                int[] rr = rowRect(cat, r);
                if (rr == null || my < rr[0] || my >= rr[1] || mx < cat.x || mx >= cat.x + PANEL_W) continue;

                if (r.cycle != null) {
                    if (button == 0) r.cycle.next().run();
                    else if (button == 1) r.cycle.prev().run();
                    return true;
                }
                if (r.setting != null && !r.setting.visible()) return true;   // a hidden setting that is closing can't be changed
                if (r.picker) {
                    if (button == 0) startPick(r.setting, mx, my);
                    return true;
                }
                if (r.setting != null) {
                    Setting s = r.setting;
                    if (s.kind == Setting.Kind.COLOR) {
                        String k = pickerKey(r.entry, s);
                        openPicker = k.equals(openPicker) ? "" : k;   // click: open/close the wheel
                        return true;
                    }
                    if (s.isChoice()) {
                        s.cycle(button == 1 ? -1 : 1);                // left: forward, right: back
                        saveModSettings();
                        return true;
                    }
                    if (s.kind == Setting.Kind.NUM) {
                        // left click on the value or right click on the row: type it; left click elsewhere: slider
                        int vw = FontMod.width(font, s.display());
                        int rx = cat.x + PANEL_W - (Math.max(0, cat.contentH - cat.viewH) > 0 ? 9 : 5);
                        if (button == 1 || (button == 0 && mx >= rx - vw - 4)) {
                            if (editing != s) commitEdit();
                            editing = s;
                            editBuf = s.display();
                            return true;
                        }
                    }
                    if (button == 0) {
                        if (s.kind == Setting.Kind.BOOL) {
                            s.setBool(!s.getBool());
                            saveModSettings();
                        } else {
                            sliding = s;
                            slideX = cat.x + 1;
                            slideW = PANEL_W - 1;
                        }
                    }
                    return true;
                }
                Entry e = r.entry;
                if (r.bind) {
                    if (button == 0) awaitingBind = (awaitingBind == e) ? null : e;
                    return true;
                }
                if (button == 0) {
                    e.setter().accept(!e.getter().getAsBoolean());
                } else if (button == 1) {
                    if (!EXPANDED.remove(e.name())) EXPANDED.add(e.name()); // animasyon renderda
                } else if (button == 2) {
                    awaitingBind = (awaitingBind == e) ? null : e;
                }
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** Mouse wheel: scrolls the panel under the cursor. */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!interactive()) return true;
        for (Category cat : CATEGORIES) {
            if (inside(mouseX, mouseY, cat.x, cat.y, PANEL_W, HEADER_H + cat.viewH)) {
                int maxS = Math.max(0, cat.contentH - cat.viewH);
                cat.scrollTarget = Math.max(0f, Math.min(maxS, cat.scrollTarget - (float) scrollY * ROW_H * 2));
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        dragging = null;
        barDrag = null;
        if (pickSetting != null) { pickSetting = null; saveModSettings(); }
        if (sliding != null) {
            sliding = null;
            if (uiLevelDraft != uiLevel) { uiLevel = uiLevelDraft; applySize(); }
            saveModSettings();
            saveGuiSettings();
        }
        return super.mouseReleased(event);
    }

    /** Applies the typed value (the old value stays if invalid). */
    private void commitEdit() {
        Setting s = editing;
        if (s == null) return;
        editing = null;
        try {
            s.setNum(Double.parseDouble(editBuf.trim().replace(',', '.')));
            saveModSettings();
        } catch (NumberFormatException ignored) { }
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (editing == null) return super.charTyped(event);
        int c = event.codepoint();
        if (editBuf.length() < 12 && ((c >= '0' && c <= '9') || c == '.' || c == ',' || c == '-')) editBuf += (char) c;
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (editing != null) {
            int k = event.key();
            if (k == InputConstants.KEY_RETURN || k == InputConstants.KEY_NUMPADENTER) commitEdit();
            else if (k == InputConstants.KEY_ESCAPE) editing = null;
            else if (k == InputConstants.KEY_BACKSPACE && !editBuf.isEmpty()) editBuf = editBuf.substring(0, editBuf.length() - 1);
            return true;
        }
        if (awaitingBind != null) {
            // ESC while waiting for a key: removes the module's key binding; any other key: binds to that key
            KeyMapping km = awaitingBind.keyMapping().get();
            if (km != null) {
                km.setKey(event.key() == InputConstants.KEY_ESCAPE ? InputConstants.UNKNOWN : InputConstants.Type.KEYSYM.getOrCreate(event.key()));
                KeyMapping.resetMapping();
                Minecraft.getInstance().options.save();
            }
            awaitingBind = null;
            return true;
        }
        if (event.key() == InputConstants.KEY_ESCAPE || event.key() == InputConstants.KEY_RSHIFT) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    /** Starts the close animation; the screen closes when the animation ends. */
    @Override
    public void onClose() {
        if (closeTime < 0) closeTime = System.currentTimeMillis();
    }
}
