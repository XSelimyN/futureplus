package com.futureplus.client.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.glyphs.BakedSheetGlyph;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * In GUI text drawn at a scaled/fractional position (e.g. Future's ClickGui: its own 0.8 scale, via Font.prepareText)
 * each glyph quad landed on a different fraction of a screen pixel, so glyphs were sampled differently and one looked sharp,
 * another blurry/dark. The glyph's top-left corner is snapped to a whole screen pixel after the transform (size unchanged).
 * Only for GUI transforms without rotation (scale 0.2-8); 3D world text (name tags, scale ~0.025) is left alone.
 */
@Mixin(BakedSheetGlyph.class)
public abstract class GlyphSnapMixin {
    @Shadow @Final private float left;
    @Shadow @Final private float up;

    private static float futureplus$snap(float v, float off, float scale, float trans) {
        double gs = Minecraft.getInstance().getWindow().getGuiScale();
        double screen = ((v + off) * scale + trans) * gs;
        double snapped = Math.round(screen);
        return v + (float) ((snapped - screen) / gs / scale);
    }

    private static boolean futureplus$gui(Matrix4fc p) {
        if (Math.abs(p.m01()) > 1e-5f || Math.abs(p.m10()) > 1e-5f || Math.abs(p.m03()) > 1e-6f || Math.abs(p.m13()) > 1e-6f) return false;
        float sx = Math.abs(p.m00()), sy = Math.abs(p.m11());
        return sx > 0.2f && sx < 8f && sy > 0.2f && sy < 8f;
    }

    @ModifyVariable(method = "render(ZFFFLorg/joml/Matrix4fc;Lcom/mojang/blaze3d/vertex/VertexConsumer;IZI)V",
                    at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private float futureplus$snapX(float x, @Local(argsOnly = true) Matrix4fc pose) {
        return futureplus$gui(pose) ? futureplus$snap(x, left, pose.m00(), pose.m30()) : x;
    }

    @ModifyVariable(method = "render(ZFFFLorg/joml/Matrix4fc;Lcom/mojang/blaze3d/vertex/VertexConsumer;IZI)V",
                    at = @At("HEAD"), argsOnly = true, ordinal = 1, require = 0)
    private float futureplus$snapY(float y, @Local(argsOnly = true) Matrix4fc pose) {
        return futureplus$gui(pose) ? futureplus$snap(y, up, pose.m11(), pose.m31()) : y;
    }
}
