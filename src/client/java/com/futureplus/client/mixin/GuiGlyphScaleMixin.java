package com.futureplus.client.mixin;

import com.futureplus.client.ScaledGlyphs;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.renderer.state.gui.GlyphRenderState;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Replaces the glyphs of scaled GUI text with copies rasterized at their real size (see ScaledGlyphs). */
@Mixin(GuiRenderState.class)
public abstract class GuiGlyphScaleMixin {
    @ModifyVariable(method = "addGlyphToCurrentLayer", at = @At("HEAD"), argsOnly = true, require = 0)
    private GuiElementRenderState futureplus$scaleGlyph(GuiElementRenderState s) {
        if (!(s instanceof GlyphRenderState g)) return s;
        TextRenderable r = ScaledGlyphs.wrap(g.pose(), g.renderable());
        return r == g.renderable() ? s : new GlyphRenderState(g.pose(), r, g.scissorArea());
    }
}
