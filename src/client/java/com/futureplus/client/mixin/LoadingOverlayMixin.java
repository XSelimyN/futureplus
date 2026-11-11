package com.futureplus.client.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.futureplus.client.FutureLogo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Loading screen: the Future logo (red) instead of the Mojang Studios logo, a dark background, and a red loading bar
 * at the bottom. The logo is drawn as vectors by FutureLogo (no resource pack needed, works on the first load too).
 */
@Mixin(LoadingOverlay.class)
public abstract class LoadingOverlayMixin {
    @Unique private static final int futureplus$RED = FutureLogo.COLOR;

    @Shadow private float currentProgress;

    /** Background: dark, regardless of the "Monochrome logo" option. */
    @Inject(method = "lambda$static$0", at = @At("HEAD"), cancellable = true)
    private static void futureplus$background(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(ARGB.color(255, 12, 12, 14));
    }

    /** Left half of the Mojang logo: replaced by the Future logo (vanilla's alpha / fade is kept). */
    @Redirect(method = "extractRenderState", at = @At(value = "INVOKE", ordinal = 0,
        target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIIIII)V"))
    private void futureplus$logo(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier id, int x, int y, float u, float v,
                                 int w, int h, int uw, int vh, int tw, int th, int color) {
        // the vanilla logo is h tall in the middle of the screen; the Future logo is square, 2h tall, centered.
        // The texture is drawn at its on-screen pixel size (1:1, no scaling blur).
        int size = h * 2;
        int px = (int) Math.round(size * Minecraft.getInstance().getWindow().getGuiScale());
        int cx = g.guiWidth() / 2, cy = g.guiHeight() / 2;
        g.blit(RenderPipelines.GUI_TEXTURED, FutureLogo.texture(px), cx - size / 2, cy - size / 2, 0f, 0f, size, size,
            px, px, px, px, color);
    }

    /** Right half of the Mojang logo: not drawn. */
    @Redirect(method = "extractRenderState", at = @At(value = "INVOKE", ordinal = 1,
        target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIIIII)V"))
    private void futureplus$skipHalf(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier id, int x, int y, float u, float v,
                                     int w, int h, int uw, int vh, int tw, int th, int color) { }

    /** Loading bar: dark red background, red frame and fill. */
    @Inject(method = "extractProgressBar", at = @At("HEAD"), cancellable = true)
    private void futureplus$bar(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, float alpha, CallbackInfo ci) {
        ci.cancel();
        int a = Math.round(alpha * 255);
        if (a <= 0) return;
        int fill = (int) Math.ceil((x1 - x0 - 2) * currentProgress);
        g.fill(x0, y0, x1, y1, ARGB.color(Math.round(a * 0.35f), 60, 8, 10));
        g.fill(x0 + 1, y0 + 1, x0 + 1 + fill, y1 - 1, ARGB.color(a, (futureplus$RED >> 16) & 0xFF, (futureplus$RED >> 8) & 0xFF, futureplus$RED & 0xFF));
        int edge = ARGB.color(a, 205, 52, 55);
        g.fill(x0 + 1, y0, x1 - 1, y0 + 1, edge);
        g.fill(x0 + 1, y1 - 1, x1 - 1, y1, edge);
        g.fill(x0, y0, x0 + 1, y1, edge);
        g.fill(x1 - 1, y0, x1, y1, edge);
    }
}
