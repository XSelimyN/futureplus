package com.futureplus.client;

import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * Shared helpers of the Visual modules:
 *  - persistence of on/off states (config/futureplus-visuals.properties)
 *  - color animations (static / gradient / chroma), smoothing
 *  - the Future friend list (~/Future/friends.json, only player names are read)
 */
public final class VisualState {
    private VisualState() {}

    // ------------------------------------------------------------- on/off persistence

    private static Properties props;

    private static Path path() {
        try { return FabricLoader.getInstance().getConfigDir().resolve("futureplus-visuals.properties"); }
        catch (Throwable t) { return null; }
    }

    private static Properties props() {
        if (props != null) return props;
        props = new Properties();
        try {
            Path p = path();
            if (p != null && Files.exists(p)) try (InputStream in = Files.newInputStream(p)) { props.load(in); }
        } catch (Throwable ignored) { }
        return props;
    }

    static boolean load(String module) { return load(module, false); }

    /** def (default on/off) if there is no record. */
    static boolean load(String module, boolean def) {
        return Boolean.parseBoolean(props().getProperty(module, String.valueOf(def)).trim());
    }

    static void save(String module, boolean on) {
        props().setProperty(module, String.valueOf(on));
        try {
            Path p = path();
            if (p == null) return;
            try (OutputStream out = Files.newOutputStream(p)) { props.store(out, "Futureplus visual moduller"); }
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------- renkler

    public static final String[] COLOR_ANIM = {"Static", "Transition", "Chroma", "Pulse"};

    /** Linear blend between two 0xRRGGBB colors. */
    static int lerpRgb(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = (int) (((a >> 16) & 255) + ((((b >> 16) & 255) - ((a >> 16) & 255)) * t));
        int g = (int) (((a >> 8) & 255) + ((((b >> 8) & 255) - ((a >> 8) & 255)) * t));
        int bl = (int) ((a & 255) + (((b & 255) - (a & 255)) * t));
        return (r << 16) | (g << 8) | bl;
    }

    static int hsv(float h, float s, float v) {
        return java.awt.Color.HSBtoRGB(((h % 1f) + 1f) % 1f, s, v) & 0xFFFFFF;
    }

    /**
     * Animated color from a color list. pos: position 0..1 (along a trail, along a bar ...),
     * mode: 0 static, 1 gradient (slides between colors), 2 chroma (rainbow), 3 pulse (brightness beats).
     */
    static int animColor(int[] cols, int count, int mode, float pos, float speed) {
        count = Math.max(1, Math.min(count, cols.length));
        double t = System.nanoTime() / 1e9 * speed;
        switch (mode) {
            case 2: return hsv((float) (pos * 0.6 + t * 0.15), 0.65f, 1f);
            case 3: {
                int c = gradient(cols, count, pos);
                float k = (float) (0.65 + 0.35 * Math.sin(t * 3.0 + pos * 4.0));
                return scale(c, k);
            }
            case 1: return gradient(cols, count, (float) (pos + t * 0.25));
            default: return gradient(cols, count, pos);
        }
    }

    /** Cyclic blend over cols[0..count) (pos 0..1 is one full turn). */
    static int gradient(int[] cols, int count, float pos) {
        if (count <= 1) return cols[0];
        float p = (float) (pos - Math.floor(pos)) * count;
        int i = (int) p % count;
        float f = p - (int) p;
        f = f * f * (3 - 2 * f);
        return lerpRgb(cols[i], cols[(i + 1) % count], f);
    }

    static int scale(int c, float k) {
        int r = Math.min(255, (int) (((c >> 16) & 255) * k));
        int g = Math.min(255, (int) (((c >> 8) & 255) * k));
        int b = Math.min(255, (int) ((c & 255) * k));
        return (r << 16) | (g << 8) | b;
    }

    static int argb(int rgb, float alpha) {
        int a = Math.max(0, Math.min(255, (int) (alpha * 255f)));
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    /** FPS independent exponential smoothing: approaches the target at 'rate' per dt seconds. */
    static float approach(float cur, float target, float rate, float dt) {
        return target + (cur - target) * (float) Math.exp(-rate * dt);
    }

    // ------------------------------------------------------------- Future friends

    private static Set<String> friends = new HashSet<>();
    private static long friendsRead = 0;

    /** Names in Future's friends.json (reread every 5 s). Empty if the file doesn't exist. */
    static boolean isFriend(String name) {
        long now = System.currentTimeMillis();
        if (now - friendsRead > 5000) {
            friendsRead = now;
            Set<String> s = new HashSet<>();
            try {
                Path p = Path.of(System.getProperty("user.home"), "Future", "friends.json");
                if (Files.exists(p)) {
                    String txt = Files.readString(p, StandardCharsets.UTF_8);
                    // Whatever the JSON schema: take everything in quotes that looks like a player name
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([A-Za-z0-9_]{2,16})\"").matcher(txt);
                    while (m.find()) s.add(m.group(1).toLowerCase(Locale.ROOT));
                }
            } catch (Throwable ignored) { }
            friends = s;
        }
        return name != null && friends.contains(name.toLowerCase(Locale.ROOT));
    }
}
