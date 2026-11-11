package com.futureplus.client.mixin;

import com.futureplus.client.FutureUi;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Sliders: background same as a button, the handle is a thin theme bar. */
@Mixin(AbstractSliderButton.class)
public abstract class AbstractSliderButtonMixin {
    @Redirect(method = "extractWidgetRenderState", at = @At(value = "INVOKE", ordinal = 0,
        target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V"))
    private void futureplus$track(GuiGraphicsExtractor g, RenderPipeline p, Identifier id, int x, int y, int w, int h, int color) {
        AbstractSliderButton s = (AbstractSliderButton) (Object) this;
        FutureUi.button(g, s, x, y, w, h, s.active, s.isHoveredOrFocused(), ((color >>> 24) & 0xFF) / 255f);
    }

    @Redirect(method = "extractWidgetRenderState", at = @At(value = "INVOKE", ordinal = 1,
        target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V"))
    private void futureplus$handle(GuiGraphicsExtractor g, RenderPipeline p, Identifier id, int x, int y, int w, int h, int color) {
        FutureUi.sliderHandle(g, x, y, w, h, ((AbstractSliderButton) (Object) this).active, ((color >>> 24) & 0xFF) / 255f);
    }
}
