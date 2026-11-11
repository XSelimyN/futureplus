package com.futureplus.client.mixin;

import com.futureplus.client.ScaledGlyphs;
import net.minecraft.client.gui.font.TextRenderable;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** With Font+ on, re-rasterizes the glyphs of 3D text for the screen resolution. */
@Mixin(targets = "net.minecraft.client.gui.Font$GlyphVisitor$1")
public abstract class GlyphVisitor3DMixin {
    @Shadow(remap = false) @Final Matrix4fc val$pose;

    @ModifyVariable(method = "render(Lnet/minecraft/client/gui/font/TextRenderable;)V", at = @At("HEAD"), argsOnly = true, require = 0)
    private TextRenderable futureplus$scale3d(TextRenderable glyph) {
        try { return ScaledGlyphs.wrap3D(val$pose, glyph); } catch (Throwable t) { return glyph; }
    }
}
