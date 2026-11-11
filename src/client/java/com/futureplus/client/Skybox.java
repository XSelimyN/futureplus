package com.futureplus.client;

import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * Skybox module (ClickGui > Visual > Skybox).
 *
 * 3 mod:
 *  Texture   : 4 color families x 15 designs, static, 4096x2048 generated from code (SkyTextures)
 *  Solid color: a single color picked from the HSV color wheel
 *  Shader    : 15 animated GLSL skies (black hole, aurora ...)
 *
 * Drawing: SkyRender (SkyRendererMixin skips the vanilla sky and calls this).
 */
public final class Skybox {
    private Skybox() {}

    public static final String[] MODES = {"Texture", "Solid color", "Shader"};
    public static final String[] SHADERS = {
        "Shooting stars", "Sea of clouds", "Long-exposure star trails", "Dark nebula", "Veil nebula",
        "Milky Way", "Aurora", "Moonlit clouds", "Black hole", "Spiral galaxy",
        "Nebula storm", "Sunset clouds", "Stardust", "Synthwave", "Light rays"
    };

    private static boolean enabled = false;
    static int mode = 2;          // 0 texture, 1 solid color, 2 shader
    static int family = 0;        // 0 red, 1 green, 2 blue, 3 black
    static int design = 0;        // texture design 0..14
    static int shader = 0;        // shader 0..14
    static int color = 0x2A0B0D;  // solid color
    static int shaderColor = 0xC8262C; // main color of the shader palette
    static double speed = 1.0;    // shader speed
    static int res = 0;           // shader resolution with the cache OFF: 0 = 100%, 1 = 75%, 2 = 50%
    static boolean cache = true;  // sky cache (shader mode): bake once, only read it every frame
    static double cacheHz = 20;   // how many times per second the cache is fully refreshed (animation smoothness)
    static int cacheRes = 0;      // 0 = 4096x2048, 1 = 3072x1536, 2 = 2048x1024
    public static final String[] CACHE_RES = {"4096 (sharp)", "3072", "2048 (fastest)"};
    static int cacheWidth() { return cacheRes == 2 ? 2048 : (cacheRes == 1 ? 3072 : 4096); }
    public static final String[] RES = {"%100", "%75", "%50"};
    static float resScale() { return res == 2 ? 0.5f : (res == 1 ? 0.75f : 1f); }
    static boolean sunMoon = false;

    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean v) { enabled = v; saveState(); }

    /** Whether the sky is being drawn by us. */
    public static boolean active() { return enabled; }
    public static boolean showSunMoon() { return sunMoon; }

    public static List<Setting> getSettings() {
        return List.of(
            Setting.choice("Mode", MODES, () -> mode, v -> mode = v),
            Setting.choice("Color family", SkyTextures.FAMILIES, () -> family, v -> family = v).when(() -> mode == 0),
            Setting.choice("Texture", SkyTextures.DESIGNS, () -> design, v -> design = v).when(() -> mode == 0),
            Setting.choice("Shader", SHADERS, () -> shader, v -> shader = v).when(() -> mode == 2),
            Setting.color("Shader color", () -> shaderColor, v -> shaderColor = v).when(() -> mode == 2),
            Setting.color("Solid color", () -> color, v -> color = v).when(() -> mode == 1),
            Setting.num("Shader speed", 0.1, 3.0, 0.1, () -> speed, v -> speed = v).when(() -> mode == 2),
            Setting.bool("Sky cache", () -> cache, v -> cache = v).when(() -> mode == 2),
            Setting.num("Animation refresh (Hz)", 5, 60, 1, () -> cacheHz, v -> cacheHz = v).when(() -> mode == 2 && cache),
            Setting.choice("Cache resolution", CACHE_RES, () -> cacheRes, v -> cacheRes = v).when(() -> mode == 2 && cache),
            Setting.choice("Shader resolution", RES, () -> res, v -> res = v).when(() -> mode == 2 && !cache),
            Setting.bool("Sun and moon", () -> sunMoon, v -> sunMoon = v));
    }

    // ------------------------------------------------------------- on/off persistence

    private static Path statePath() {
        try { return FabricLoader.getInstance().getConfigDir().resolve("futureplus-skybox.properties"); }
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
            try (OutputStream out = Files.newOutputStream(p)) { pr.store(out, "Futureplus Skybox"); }
        } catch (Exception ignored) { }
    }
}
