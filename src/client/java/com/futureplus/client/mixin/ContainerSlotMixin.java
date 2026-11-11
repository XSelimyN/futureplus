package com.futureplus.client.mixin;

import com.futureplus.client.FutureUi;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Inventory / chest / furnace... slots: a dark rounded cell over the square slot in the texture; the hovered
 * slot fades smoothly to the theme color. Vanilla's white highlight layers are not drawn.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerSlotMixin {
    @Shadow protected Slot hoveredSlot;

    @Inject(method = "extractSlot", at = @At("HEAD"))
    private void futureplus$slot(GuiGraphicsExtractor g, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        float hv = FutureUi.hover(slot, slot == hoveredSlot && slot.isHighlightable());
        g.fill(slot.x - 1, slot.y - 1, slot.x + 17, slot.y + 17, 0xFF000000 | FutureUi.DARK_BG);   // hide the corners of the square slot in the texture
        FutureUi.slot(g, slot.x - 1, slot.y - 1, 18, 18, hv, false);
    }

    @Redirect(method = "extractSlotHighlightBack", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
    private void futureplus$highlightBack(GuiGraphicsExtractor g, RenderPipeline p, Identifier id, int x, int y, int w, int h) { }

    @Redirect(method = "extractSlotHighlightFront", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
    private void futureplus$highlightFront(GuiGraphicsExtractor g, RenderPipeline p, Identifier id, int x, int y, int w, int h) { }
}
