package com.futureplus.client;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Hand Shader (Visual) - a port of Soup's HandsShader module. Soup's GLSL files are used as is
 * (resources/.../shaders/core/futureplus_hand_*.fsh; only the uniform declarations were turned into a Blaze3D std140 block).
 *
 * Method (same as Soup): after the hand is drawn, the main buffer's color is copied; a full screen pass reads the copy
 * and the DEPTH texture (depth is cleared before the hand is drawn, so pixels with depth < 1 = the hand),
 * and blends the effect into the main buffer by the mask. In dual shader mode the two effects are drawn to separate targets and
 * combined by a blend shader.
 *
 * In 26.1 renderItemInHand only "submits" the hand; the real drawing happens in the following
 * FeatureRenderDispatcher.renderAllFeatures() call inside renderLevel. So the effect is applied RIGHT AFTER THAT CALL
 * (the previous version didn't work because it ran before the hand was drawn).
 * "Hide hand" (Glass/Invert): Soup turns off the color mask; here a copy of the background from before the hand is drawn is used.
 */
public final class HandShader {
    private HandShader() {}

    public static final String[] SHADERS = {"None", "Solid", "Chroma", "Balatro", "Smoke", "Stripes", "Glow", "Glass", "Invert"};
    public static final String[] BLENDS = {"Mix", "Add", "Multiply", "Screen", "Overlay", "Difference"};

    private static boolean enabled = VisualState.load("HandShader");

    // --- general (Soup defaults)
    static boolean dual = true;
    static int main = 7, shader1 = 6, shader2 = 4, blend = 1;
    static double blendIntensity = 1, shader1Strength = 1, shader2Strength = 1;
    // Solid
    static int solid1 = 0xD8343A, solid2 = 0x7E1A20;
    static double gradientSpeed = 0.5, solidAlpha = 1.0;
    // Chroma
    static double chromaSpeed = 0.3, chromaSat = 0.5, chromaBright = 1.0;
    // Balatro
    static double balatroSpeed = 0.5;
    static int bal1 = 0xDE443B, bal2 = 0x006BB4, bal3 = 0x162325, bal4 = 0x596600;
    // Smoke
    static int smokeColor = 0x95A5A6;
    static double smokeIntensity = 1.0, smokeSpeed = 0.5;
    // Stripes
    static int stripes1 = 0x778889, stripes2 = 0x5A6566;
    static double stripesWidth = 0.3, stripesSpeed = 0.3;
    // Snow
    static int snowColor = 0xFFFFFF;
    static double snowIntensity = 1.0, snowSpeed = 1.0;
    // Glow
    static boolean fillGlow = true;
    static double glowRadius = 0.04, glowPower = 0.75, glowDispersion = 5.0;
    static int glowColor = 0xD8343A;
    // Glass
    static double glassBlur = 50, glassQuality = 10, glassDirection = 10, glassRefraction = 1, glassBright = 1.1, glassBgBlur = 70;
    static boolean glassDistortion = false, glassHide = true, glassChromatic = true;
    // Invert
    static boolean invHide = true, invHue = false, invSat = false, invBright = false;
    static double invIntensity = 1, invEdge = 0.5;
    static int invGlowColor = 0xFF4A50;

    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean v) { enabled = v; failed = false; VisualState.save("HandShader", v); }

    /** Whether one of the selected shaders is SHADERS[i] (Primary in single mode, Shader 1 / 2 in dual mode). */
    private static boolean uses(int i) { return dual ? shader1 == i || shader2 == i : main == i; }

    public static List<Setting> getSettings() {
        List<Setting> s = new ArrayList<>();
        s.add(Setting.bool("Dual shader mode", () -> dual, v -> dual = v));
        s.add(Setting.choice("Primary shader", SHADERS, () -> main, v -> main = v).when(() -> !dual));
        s.add(Setting.choice("Shader 1", SHADERS, () -> shader1, v -> shader1 = v).when(() -> dual));
        s.add(Setting.choice("Shader 2", SHADERS, () -> shader2, v -> shader2 = v).when(() -> dual));
        s.add(Setting.choice("Blend mode", BLENDS, () -> blend, v -> blend = v).when(() -> dual));
        s.add(Setting.num("Blend intensity", 0, 1, 0.05, () -> blendIntensity, v -> blendIntensity = v).when(() -> dual));
        s.add(Setting.num("Shader 1 strength", 0, 1, 0.05, () -> shader1Strength, v -> shader1Strength = v).when(() -> dual));
        s.add(Setting.num("Shader 2 strength", 0, 1, 0.05, () -> shader2Strength, v -> shader2Strength = v).when(() -> dual));
        // Solid
        s.add(Setting.color("Solid renk 1", () -> solid1, v -> solid1 = v).when(() -> uses(1)));
        s.add(Setting.color("Solid renk 2", () -> solid2, v -> solid2 = v).when(() -> uses(1)));
        s.add(Setting.num("Solid speed", 0.1, 1, 0.05, () -> gradientSpeed, v -> gradientSpeed = v).when(() -> uses(1)));
        s.add(Setting.num("Solid opacity", 0, 1, 0.05, () -> solidAlpha, v -> solidAlpha = v).when(() -> uses(1)));
        // Chroma
        s.add(Setting.num("Chroma speed", 0.05, 1, 0.05, () -> chromaSpeed, v -> chromaSpeed = v).when(() -> uses(2)));
        s.add(Setting.num("Chroma saturation", 0.1, 1, 0.05, () -> chromaSat, v -> chromaSat = v).when(() -> uses(2)));
        s.add(Setting.num("Chroma brightness", 0.5, 1.5, 0.05, () -> chromaBright, v -> chromaBright = v).when(() -> uses(2)));
        // Balatro
        s.add(Setting.num("Balatro speed", 0.1, 2, 0.1, () -> balatroSpeed, v -> balatroSpeed = v).when(() -> uses(3)));
        s.add(Setting.color("Balatro primary color", () -> bal1, v -> bal1 = v).when(() -> uses(3)));
        s.add(Setting.color("Balatro color 2", () -> bal2, v -> bal2 = v).when(() -> uses(3)));
        s.add(Setting.color("Balatro color 3", () -> bal3, v -> bal3 = v).when(() -> uses(3)));
        s.add(Setting.color("Balatro color 4", () -> bal4, v -> bal4 = v).when(() -> uses(3)));
        // Smoke
        s.add(Setting.color("Smoke color", () -> smokeColor, v -> smokeColor = v).when(() -> uses(4)));
        s.add(Setting.num("Smoke density", 0.1, 3, 0.1, () -> smokeIntensity, v -> smokeIntensity = v).when(() -> uses(4)));
        s.add(Setting.num("Smoke speed", 0.1, 2, 0.1, () -> smokeSpeed, v -> smokeSpeed = v).when(() -> uses(4)));
        // Stripes
        s.add(Setting.color("Stripe color 1", () -> stripes1, v -> stripes1 = v).when(() -> uses(5)));
        s.add(Setting.color("Stripe color 2", () -> stripes2, v -> stripes2 = v).when(() -> uses(5)));
        s.add(Setting.num("Stripe width", 0.05, 0.5, 0.05, () -> stripesWidth, v -> stripesWidth = v).when(() -> uses(5)));
        s.add(Setting.num("Stripe speed", 0.1, 2, 0.1, () -> stripesSpeed, v -> stripesSpeed = v).when(() -> uses(5)));
        // Snow
        s.add(Setting.color("Snow color", () -> snowColor, v -> snowColor = v));
        s.add(Setting.num("Snow density", 0.1, 3, 0.1, () -> snowIntensity, v -> snowIntensity = v));
        s.add(Setting.num("Snow speed", 0.1, 2, 0.1, () -> snowSpeed, v -> snowSpeed = v));
        // Glow
        s.add(Setting.bool("Glow fill", () -> fillGlow, v -> fillGlow = v).when(() -> uses(6)));
        s.add(Setting.num("Glow radius", 0.01, 0.2, 0.01, () -> glowRadius, v -> glowRadius = v).when(() -> uses(6)));
        s.add(Setting.num("Glow strength", 0.1, 2, 0.05, () -> glowPower, v -> glowPower = v).when(() -> uses(6)));
        s.add(Setting.color("Glow color", () -> glowColor, v -> glowColor = v).when(() -> uses(6)));
        s.add(Setting.num("Glow dispersion", 1, 10, 0.5, () -> glowDispersion, v -> glowDispersion = v).when(() -> uses(6)));
        // Glass
        s.add(Setting.num("Glass blur", 5, 200, 5, () -> glassBlur, v -> glassBlur = v).when(() -> uses(7)));
        s.add(Setting.num("Glass quality", 3, 20, 1, () -> glassQuality, v -> glassQuality = v).when(() -> uses(7)));
        s.add(Setting.num("Glass directions", 4, 16, 1, () -> glassDirection, v -> glassDirection = v).when(() -> uses(7)));
        s.add(Setting.num("Glass refraction", 0, 3, 0.1, () -> glassRefraction, v -> glassRefraction = v).when(() -> uses(7)));
        s.add(Setting.num("Glass brightness", 0.5, 2, 0.05, () -> glassBright, v -> glassBright = v).when(() -> uses(7)));
        s.add(Setting.bool("Glass distortion", () -> glassDistortion, v -> glassDistortion = v).when(() -> uses(7)));
        s.add(Setting.bool("Glass: hide hand", () -> glassHide, v -> glassHide = v).when(() -> uses(7)));
        s.add(Setting.bool("Glass chromatic aberration", () -> glassChromatic, v -> glassChromatic = v).when(() -> uses(7)));
        s.add(Setting.num("Glass background blur", 10, 100, 5, () -> glassBgBlur, v -> glassBgBlur = v).when(() -> uses(7)));
        // Invert
        s.add(Setting.bool("Invert: hide hand", () -> invHide, v -> invHide = v).when(() -> uses(8)));
        s.add(Setting.num("Invert intensity", 0, 1, 0.05, () -> invIntensity, v -> invIntensity = v).when(() -> uses(8)));
        s.add(Setting.bool("Invert hue", () -> invHue, v -> invHue = v).when(() -> uses(8)));
        s.add(Setting.bool("Invert saturation", () -> invSat, v -> invSat = v).when(() -> uses(8)));
        s.add(Setting.bool("Invert brightness", () -> invBright, v -> invBright = v).when(() -> uses(8)));
        s.add(Setting.num("Invert edge glow", 0, 2, 0.05, () -> invEdge, v -> invEdge = v).when(() -> uses(8)));
        s.add(Setting.color("Invert glow color", () -> invGlowColor, v -> invGlowColor = v).when(() -> uses(8)));
        return s;
    }

    // ------------------------------------------------------------- pipelines

    private static final Map<String, String[]> LAYOUT = new HashMap<>();
    private static final Map<String, String[]> SAMPLERS = new HashMap<>();
    private static final Map<String, RenderPipeline> PIPES = new HashMap<>();

    static {
        LAYOUT.put("solid", new String[]{"float time", "vec3 customColor1", "vec3 customColor2", "float effectAlpha"});
        SAMPLERS.put("solid", new String[]{"ColorTexture", "DepthTexture"});
        LAYOUT.put("chroma", new String[]{"float time", "float chromaSpeed", "float chromaSaturation", "float chromaBrightness"});
        SAMPLERS.put("chroma", new String[]{"ColorTexture", "DepthTexture"});
        LAYOUT.put("balatro", new String[]{"float time", "vec2 resolution", "vec3 color1", "vec3 color2", "vec3 color3", "vec3 color4"});
        SAMPLERS.put("balatro", new String[]{"ColorTexture", "DepthTexture"});
        LAYOUT.put("smoke", new String[]{"float time", "vec2 resolution", "vec3 smokeColor", "float smokeIntensity"});
        SAMPLERS.put("smoke", new String[]{"ColorTexture", "DepthTexture"});
        LAYOUT.put("stripes", new String[]{"float time", "vec2 resolution", "vec3 stripesColor1", "vec3 stripesColor2", "float stripesWidth", "float stripesSpeed"});
        SAMPLERS.put("stripes", new String[]{"ColorTexture", "DepthTexture"});
        LAYOUT.put("snow", new String[]{"float time", "vec2 resolution", "vec3 snowColor", "float snowIntensity", "float snowSpeed"});
        SAMPLERS.put("snow", new String[]{"ColorTexture", "DepthTexture"});
        LAYOUT.put("glow", new String[]{"float time", "vec2 resolution", "int fillGlow", "float glowRadius", "float glowPower", "float glowDispersion", "vec3 glowColor"});
        SAMPLERS.put("glow", new String[]{"ColorTexture", "DepthTexture"});
        LAYOUT.put("glass", new String[]{"float time", "vec2 resolution", "float blurSize", "float quality", "float direction", "float refraction", "float brightness", "int enableChromatic", "int enableDistortion", "int hideHand", "float backgroundBlur"});
        SAMPLERS.put("glass", new String[]{"ColorTexture", "DepthTexture"});
        LAYOUT.put("invert", new String[]{"float time", "vec2 resolution", "int hideHand", "float invertIntensity", "int invertHue", "int invertSaturation", "int invertBrightness", "float edgeGlow", "vec3 glowColor"});
        SAMPLERS.put("invert", new String[]{"ColorTexture", "DepthTexture"});
        LAYOUT.put("blend", new String[]{"int blendMode", "float blendIntensity", "float shader1Strength", "float shader2Strength"});
        SAMPLERS.put("blend", new String[]{"Shader1Texture", "Shader2Texture", "DepthTexture"});
        for (String n : LAYOUT.keySet()) {
            RenderPipeline.Builder b = RenderPipeline.builder()
                .withLocation("pipeline/futureplus_hand_" + n)
                .withVertexShader("core/futureplus_hand")
                .withFragmentShader("core/futureplus_hand_" + n)
                .withUniform("HandFx", UniformType.UNIFORM_BUFFER)
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                .withVertexFormat(DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS)
                .withCull(false);
            for (String sm : SAMPLERS.get(n)) b.withSampler(sm);
            PIPES.put(n, RenderPipelines.register(b.build()));
        }
    }

    /** Called when the ClickGui opens: triggers static initialization (pipeline registration). */
    static void init() { }

    // ------------------------------------------------------------- run state

    private static boolean failed = false, loggedOk = false, armed = false, preCaptured = false;
    private static final long T0 = System.nanoTime();
    private static TextureTarget copy, pre, t1, t2;
    private static GpuBuffer quad;
    private static final GpuBuffer[] ubo = new GpuBuffer[3];
    private static final ByteBuffer scratch = ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder());

    private static String name(int i) { return SHADERS[Math.max(0, Math.min(SHADERS.length - 1, i))]; }

    private static boolean usesPreHand(String shader) {
        return (shader.equals("Glass") && glassHide) || (shader.equals("Invert") && invHide);
    }

    private static boolean anyPreHand() {
        return dual ? (usesPreHand(name(shader1)) || usesPreHand(name(shader2))) : usesPreHand(name(main));
    }

    private static boolean anyActive() {
        return dual ? !(name(shader1).equals("None") && name(shader2).equals("None")) : !name(main).equals("None");
    }

    /** renderLevel: before the renderItemInHand call. Saves the background from before the hand is drawn if needed. */
    public static void beforeHand() {
        armed = false; preCaptured = false;
        if (!enabled || failed || !anyActive()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !mc.options.getCameraType().isFirstPerson() || mc.options.hideGui) return;
        armed = true;
        try {
            if (anyPreHand()) {
                RenderTarget m = mc.getMainRenderTarget();
                pre = ensure(pre, "pre", m.width, m.height);
                RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
                    m.getColorTexture(), pre.getColorTexture(), 0, 0, 0, 0, 0, m.width, m.height);
                preCaptured = true;
            }
        } catch (Throwable t) { fail(t); }
    }

    /** renderLevel: right after the renderAllFeatures() call that draws the hand. */
    public static void afterHand() {
        if (!armed) return;
        armed = false;
        if (!enabled || failed) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            RenderTarget m = mc.getMainRenderTarget();
            int w = m.width, h = m.height;
            copy = ensure(copy, "copy", w, h);
            CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
            enc.copyTextureToTexture(m.getColorTexture(), copy.getColorTexture(), 0, 0, 0, 0, 0, w, h);
            float t = (float) ((System.nanoTime() - T0) / 1.0e9);
            GpuTextureView depth = m.getDepthTextureView();

            if (!dual) {
                String s = name(main);
                if (s.equals("None")) return;
                Map<String, GpuTextureView> tex = new HashMap<>();
                tex.put("ColorTexture", colorFor(s));
                tex.put("DepthTexture", depth);
                pass(s.toLowerCase(), m, false, tex, 0, t, w, h);
            } else {
                t1 = ensure(t1, "s1", w, h);
                t2 = ensure(t2, "s2", w, h);
                renderTo(name(shader1), t1, depth, t, w, h, 0);
                renderTo(name(shader2), t2, depth, t, w, h, 1);
                Map<String, GpuTextureView> tex = new HashMap<>();
                tex.put("Shader1Texture", t1.getColorTextureView());
                tex.put("Shader2Texture", t2.getColorTextureView());
                tex.put("DepthTexture", depth);
                pass("blend", m, false, tex, 2, t, w, h);
            }
            if (!loggedOk) { loggedOk = true; System.out.println("[Futureplus] El shader'i calisiyor"); }
        } catch (Throwable t) {
            fail(t);
        }
    }

    private static GpuTextureView colorFor(String shader) {
        return (usesPreHand(shader) && preCaptured && pre != null) ? pre.getColorTextureView() : copy.getColorTextureView();
    }

    /** In dual mode draws one shader to its own target ("None" writes the copy, hand included, as is). */
    private static void renderTo(String shader, TextureTarget dest, GpuTextureView depth, float t, int w, int h, int slot) {
        if (shader.equals("None")) {
            RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
                copy.getColorTexture(), dest.getColorTexture(), 0, 0, 0, 0, 0, w, h);
            return;
        }
        Map<String, GpuTextureView> tex = new HashMap<>();
        tex.put("ColorTexture", colorFor(shader));
        tex.put("DepthTexture", depth);
        pass(shader.toLowerCase(), dest, true, tex, slot, t, w, h);
    }

    private static TextureTarget ensure(TextureTarget tt, String label, int w, int h) {
        if (tt == null) return new TextureTarget("futureplus hand " + label, w, h, false);
        if (tt.width != w || tt.height != h) tt.resize(w, h);
        return tt;
    }

    // ------------------------------------------------------------- single pass

    private static void pass(String shader, RenderTarget dest, boolean clear, Map<String, GpuTextureView> tex,
                             int slot, float t, int w, int h) {
        RenderPipeline pipe = PIPES.get(shader);
        if (ubo[slot] == null) {
            ubo[slot] = RenderSystem.getDevice().createBuffer(() -> "Futureplus hand fx ubo",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 256L);
        }
        // uniform block: in LAYOUT order (same as the declaration order in GLSL)
        scratch.clear();
        Std140Builder sb = Std140Builder.intoBuffer(scratch);
        for (String member : LAYOUT.get(shader)) {
            String[] p = member.split(" ");
            Object v = value(shader, p[1], t, w, h);
            switch (p[0]) {
                case "float" -> sb.putFloat(((Number) v).floatValue());
                case "int" -> sb.putInt(((Number) v).intValue());
                case "vec2" -> { float[] a = (float[]) v; sb.putVec2(a[0], a[1]); }
                case "vec3" -> { float[] a = (float[]) v; sb.putVec3(a[0], a[1], a[2]); }
                default -> throw new IllegalStateException("uniform tipi: " + p[0]);
            }
        }
        scratch.limit(256);
        scratch.position(0);
        CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
        enc.writeToBuffer(ubo[slot].slice(), scratch);

        GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        RenderSystem.AutoStorageIndexBuffer quads = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        GpuBuffer index = quads.getBuffer(6);
        try (RenderPass rp = enc.createRenderPass(() -> "Futureplus hand shader", dest.getColorTextureView(),
                clear ? OptionalInt.of(0) : OptionalInt.empty())) {
            rp.setPipeline(pipe);
            rp.setUniform("HandFx", ubo[slot]);
            for (String sm : SAMPLERS.get(shader)) {
                GpuTextureView view = tex.get(sm);
                rp.bindTexture(sm, view, sm.equals("DepthTexture") ? nearest : linear);
            }
            rp.setVertexBuffer(0, quad());
            rp.setIndexBuffer(index, quads.type());
            rp.drawIndexed(0, 0, 6, 1);
        }
    }

    // ------------------------------------------------------------- uniform values (Soup's render* methods)

    private static float[] rgb(int c) {
        return new float[]{((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f};
    }

    private static Object value(String shader, String n, float t, int w, int h) {
        switch (n) {
            case "time": return switch (shader) {
                case "solid" -> t * (float) gradientSpeed;
                case "balatro" -> t * (float) balatroSpeed;
                case "smoke" -> t * (float) smokeSpeed;
                default -> t;
            };
            case "resolution": return new float[]{w, h};
            case "customColor1": return rgb(solid1);
            case "customColor2": return rgb(solid2);
            case "effectAlpha": return (float) solidAlpha;
            case "chromaSpeed": return (float) chromaSpeed;
            case "chromaSaturation": return (float) chromaSat;
            case "chromaBrightness": return (float) chromaBright;
            case "color1": return rgb(bal1);
            case "color2": return rgb(bal2);
            case "color3": return rgb(bal3);
            case "color4": return rgb(bal4);
            case "smokeColor": return rgb(smokeColor);
            case "smokeIntensity": return (float) smokeIntensity;
            case "stripesColor1": return rgb(stripes1);
            case "stripesColor2": return rgb(stripes2);
            case "stripesWidth": return (float) stripesWidth;
            case "stripesSpeed": return (float) stripesSpeed;
            case "snowColor": return rgb(snowColor);
            case "snowIntensity": return (float) snowIntensity;
            case "snowSpeed": return (float) snowSpeed;
            case "fillGlow": return fillGlow ? 1 : 0;
            case "glowRadius": return (float) glowRadius;
            case "glowPower": return (float) glowPower;
            case "glowDispersion": return (float) glowDispersion;
            case "glowColor": return rgb(shader.equals("invert") ? invGlowColor : glowColor);
            case "blurSize": return (float) glassBlur;
            case "quality": return (float) glassQuality;
            case "direction": return (float) glassDirection;
            case "refraction": return (float) glassRefraction;
            case "brightness": return (float) glassBright;
            case "enableChromatic": return glassChromatic ? 1 : 0;
            case "enableDistortion": return glassDistortion ? 1 : 0;
            case "hideHand": return (shader.equals("glass") ? glassHide : invHide) ? 1 : 0;
            case "backgroundBlur": return (float) glassBgBlur;
            case "invertIntensity": return (float) invIntensity;
            case "invertHue": return invHue ? 1 : 0;
            case "invertSaturation": return invSat ? 1 : 0;
            case "invertBrightness": return invBright ? 1 : 0;
            case "edgeGlow": return (float) invEdge;
            case "blendMode": return blend;
            case "blendIntensity": return (float) blendIntensity;
            case "shader1Strength": return (float) shader1Strength;
            case "shader2Strength": return (float) shader2Strength;
            default: throw new IllegalStateException("bilinmeyen uniform: " + n);
        }
    }

    private static void fail(Throwable t) {
        failed = true;
        System.err.println("[Futureplus] El shader'i bu surumde calismadi, kapatildi: " + t);
        t.printStackTrace();
    }

    /** Full screen quad (-1..1, in clip space; used directly by futureplus_hand.vsh). */
    private static GpuBuffer quad() {
        if (quad != null) return quad;
        VertexFormat fmt = DefaultVertexFormat.POSITION;
        try (ByteBufferBuilder bb = ByteBufferBuilder.exactlySized(4 * fmt.getVertexSize())) {
            BufferBuilder b = new BufferBuilder(bb, VertexFormat.Mode.QUADS, fmt);
            b.addVertex(-1f, -1f, 0f);
            b.addVertex(1f, -1f, 0f);
            b.addVertex(1f, 1f, 0f);
            b.addVertex(-1f, 1f, 0f);
            try (MeshData mesh = b.buildOrThrow()) {
                quad = RenderSystem.getDevice().createBuffer(() -> "Futureplus screen quad", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
            }
        }
        return quad;
    }
}
