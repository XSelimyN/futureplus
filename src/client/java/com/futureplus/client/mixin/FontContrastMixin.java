package com.futureplus.client.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.ByteBuffer;

/**
 * TTF text sharpness: FreeType's gray edge pixels (anti-aliasing) look "hazy" when drawn linearly to the
 * screen. The contrast curve of the Futureplus HD engine (v^1.3) is applied: half-filled edge pixels get thinner,
 * letters get sharper. Done once while the glyph texture loads (no performance cost).
 */
@Mixin(targets = "com.mojang.blaze3d.font.TrueTypeGlyphProvider$Glyph$1")
public class FontContrastMixin {
    private static final byte[] LUT = new byte[256];
    static {
        for (int i = 0; i < 256; i++) LUT[i] = (byte) Math.round(Math.pow(i / 255.0, 1.3) * 255.0);
    }

    // A single buffer (render thread) instead of a new direct buffer per glyph: direct memory is only freed by the GC,
    // so allocating per glyph caused memory spikes.
    private static ByteBuffer futureplus$buf;

    @Redirect(method = "upload", at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/systems/CommandEncoder;writeToTexture(Lcom/mojang/blaze3d/textures/GpuTexture;Lcom/mojang/blaze3d/platform/NativeImage;IIIIIIII)V"))
    private void futureplus$contrast(CommandEncoder enc, GpuTexture tex, NativeImage img, int mip, int layer,
                                int x, int y, int w, int h, int sx, int sy) {
        try {
            ByteBuffer buf = futureplus$buf;
            if (buf == null || buf.capacity() < w * h) buf = futureplus$buf = ByteBuffer.allocateDirect(Math.max(4096, w * h));
            buf.clear();
            for (int j = 0; j < h; j++)
                for (int i = 0; i < w; i++)
                    buf.put(LUT[img.getLuminanceOrAlpha(sx + i, sy + j) & 255]);
            buf.flip();
            enc.writeToTexture(tex, buf, NativeImage.Format.LUMINANCE, mip, layer, x, y, w, h);
        } catch (Throwable t) {
            enc.writeToTexture(tex, img, mip, layer, x, y, w, h, sx, sy);   // vanilla path if anything goes wrong
        }
    }
}
