package com.futureplus.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/** HSV color wheel: texture (hue = angle, saturation = radius) and HSV <-> RGB conversions. */
final class ColorPicker {
    private ColorPicker() {}

    static final int TS = 256;
    private static Identifier wheelId;

    /** Wheel texture (V = 1). Brightness is given as a color multiplier when drawing. */
    static Identifier wheel() {
        if (wheelId != null) return wheelId;
        NativeImage img = new NativeImage(TS, TS, true);
        float c = (TS - 1) / 2f;
        for (int y = 0; y < TS; y++) {
            for (int x = 0; x < TS; x++) {
                float dx = (x - c) / c, dy = (y - c) / c;
                float r = (float) Math.sqrt(dx * dx + dy * dy);
                float a = Math.max(0f, Math.min(1f, (1f - r) * c + 0.5f));   // soft fade at the edge
                if (a <= 0f) { img.setPixel(x, y, 0); continue; }
                float h = (float) (Math.atan2(dy, dx) / (Math.PI * 2));
                if (h < 0) h += 1f;
                int rgb = fromHsv(h, Math.min(1f, r), 1f);
                img.setPixel(x, y, ((int) (a * 255) << 24) | rgb);
            }
        }
        wheelId = Identifier.fromNamespaceAndPath("futureplus", "gui/hsv_wheel");
        Minecraft.getInstance().getTextureManager().register(wheelId, new DynamicTexture(() -> "futureplus hsv wheel", img));
        return wheelId;
    }

    static int fromHsv(float h, float s, float v) {
        h = (h - (float) Math.floor(h)) * 6f;
        int i = (int) h;
        float f = h - i, p = v * (1 - s), q = v * (1 - s * f), t = v * (1 - s * (1 - f));
        float r, g, b;
        switch (i) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
    }

    static float[] toHsv(int rgb) {
        float r = ((rgb >> 16) & 255) / 255f, g = ((rgb >> 8) & 255) / 255f, b = (rgb & 255) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        float h = 0;
        if (d > 1e-6f) {
            if (max == r) h = ((g - b) / d) % 6f;
            else if (max == g) h = (b - r) / d + 2f;
            else h = (r - g) / d + 4f;
            h /= 6f;
            if (h < 0) h += 1f;
        }
        float s = max <= 0 ? 0 : d / max;
        return new float[]{h, s, max};
    }
}
