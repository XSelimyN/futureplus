package com.futureplus.client.mixin;

import com.mojang.blaze3d.font.TrueTypeGlyphProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * TTF text sharpness: FreeType returns fractional glyph advances (e.g. 13.4); since glyphs don't sit exactly
 * on screen pixels, edges look jagged/blurry. The advance is rounded to a whole pixel: since oversample = GUI scale
 * every glyph starts on a whole screen pixel (like the Futureplus HD engine).
 */
@Mixin(TrueTypeGlyphProvider.class)
public class FontCrispMixin {
    @ModifyVariable(method = "loadGlyph", at = @At("STORE"), ordinal = 0)
    private float futureplus$snapAdvance(float advance) {
        return Math.round(advance);
    }
}
