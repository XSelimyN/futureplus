package com.futureplus.client.mixin;

import com.futureplus.client.ScaledGlyphs;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** ScaledGlyphs.inItem while item count/bar text is drawn. */
@Mixin(GuiGraphicsExtractor.class)
public abstract class ItemScopeFlagsMixin {
    @Inject(method = "itemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V", at = @At("HEAD"), require = 0)
    private void futureplus$itemStart(CallbackInfo ci) { ScaledGlyphs.inItem = true; }

    @Inject(method = "itemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V", at = @At("RETURN"), require = 0)
    private void futureplus$itemEnd(CallbackInfo ci) { ScaledGlyphs.inItem = false; }
}
