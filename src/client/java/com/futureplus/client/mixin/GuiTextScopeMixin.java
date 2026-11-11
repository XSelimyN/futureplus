package com.futureplus.client.mixin;

import com.futureplus.client.ScaledGlyphs;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Saves the ScaledGlyphs scope when the text state is created; restores it right before its glyphs are added. */
@Mixin(GuiTextRenderState.class)
public abstract class GuiTextScopeMixin {
    @Unique private boolean futureplus$hd;

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void futureplus$mark(CallbackInfo ci) { futureplus$hd = ScaledGlyphs.allowNow(); }

    @Inject(method = "ensurePrepared", at = @At("RETURN"), require = 0)
    private void futureplus$scope(CallbackInfoReturnable<Font.PreparedText> cir) { ScaledGlyphs.current = futureplus$hd; }
}
