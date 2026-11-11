package com.futureplus.client.mixin;

import com.futureplus.client.ScaledGlyphs;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** ScaledGlyphs.inChat while chat is being drawn. */
@Mixin(ChatComponent.class)
public abstract class ChatScopeFlagsMixin {
    @Inject(method = "extractRenderState", at = @At("HEAD"), require = 0)
    private void futureplus$chatStart(CallbackInfo ci) { ScaledGlyphs.inChat = true; }

    @Inject(method = "extractRenderState", at = @At("RETURN"), require = 0)
    private void futureplus$chatEnd(CallbackInfo ci) { ScaledGlyphs.inChat = false; }
}
