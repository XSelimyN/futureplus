package com.futureplus.client.mixin;

import com.futureplus.client.ScaledGlyphs;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** ScaledGlyphs.inHud while the HUD is being drawn. */
@Mixin(Gui.class)
public abstract class GuiScopeFlagsMixin {
    @Inject(method = "extractRenderState", at = @At("HEAD"), require = 0)
    private void futureplus$hudStart(CallbackInfo ci) { ScaledGlyphs.inHud = true; ScaledGlyphs.inChat = false; ScaledGlyphs.inItem = false; }

    @Inject(method = "extractRenderState", at = @At("RETURN"), require = 0)
    private void futureplus$hudEnd(CallbackInfo ci) { ScaledGlyphs.inHud = false; }
}
