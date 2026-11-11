package com.futureplus.client.mixin;

import com.futureplus.client.FutureUi;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Hotbar: 9 separate rounded cells instead of the vanilla strip; the selected cell is theme colored; the offhand cell matches. */
@Mixin(Gui.class)
public abstract class GuiHotbarMixin {
    @org.spongepowered.asm.mixin.Unique private static final String BLIT = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V";

    /** Strip (182x22): cells 20 px apart, the item 1 px inside its cell. */
    @Redirect(method = "extractItemHotbar", at = @At(value = "INVOKE", ordinal = 0, target = BLIT))
    private void futureplus$bar(GuiGraphicsExtractor g, RenderPipeline p, Identifier id, int x, int y, int w, int h) {
        FutureUi.round(g, x, y, w, h, 3f, 0x59000000);
        for (int i = 0; i < 9; i++) FutureUi.slot(g, x + 2 + i * 20, y + 2, 18, 18, 0f, false);
    }

    /** Selection frame (24x23, 1 px outside the strip). */
    @Redirect(method = "extractItemHotbar", at = @At(value = "INVOKE", ordinal = 1, target = BLIT))
    private void futureplus$selected(GuiGraphicsExtractor g, RenderPipeline p, Identifier id, int x, int y, int w, int h) {
        FutureUi.slot(g, x + 2, y + 2, 20, 20, 0f, true);
    }

    /** Offhand cell, on the left (29x24; item at 3,4). */
    @Redirect(method = "extractItemHotbar", at = @At(value = "INVOKE", ordinal = 2, target = BLIT))
    private void futureplus$offLeft(GuiGraphicsExtractor g, RenderPipeline p, Identifier id, int x, int y, int w, int h) {
        FutureUi.round(g, x, y + 1, 22, 22, 3f, 0x59000000);
        FutureUi.slot(g, x + 2, y + 3, 18, 18, 0f, false);
    }

    /** Offhand cell, on the right (29x24; item at 10,4). */
    @Redirect(method = "extractItemHotbar", at = @At(value = "INVOKE", ordinal = 3, target = BLIT))
    private void futureplus$offRight(GuiGraphicsExtractor g, RenderPipeline p, Identifier id, int x, int y, int w, int h) {
        FutureUi.round(g, x + 7, y + 1, 22, 22, 3f, 0x59000000);
        FutureUi.slot(g, x + 9, y + 3, 18, 18, 0f, false);
    }
}
