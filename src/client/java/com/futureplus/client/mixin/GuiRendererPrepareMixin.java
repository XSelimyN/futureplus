package com.futureplus.client.mixin;

import com.futureplus.client.ScaledGlyphs;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** GUI text glyphs are added here at the end of the frame; a glyph added from outside (Future) is judged by the current section. */
@Mixin(GuiRenderer.class)
public abstract class GuiRendererPrepareMixin {
    @Inject(method = "prepareText", at = @At("HEAD"), require = 0)
    private void futureplus$start(CallbackInfo ci) { ScaledGlyphs.inPrepare = true; ScaledGlyphs.newFrame(); }

    @Inject(method = "prepareText", at = @At("RETURN"), require = 0)
    private void futureplus$end(CallbackInfo ci) { ScaledGlyphs.inPrepare = false; }
}
