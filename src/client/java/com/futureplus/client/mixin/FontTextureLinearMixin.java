package com.futureplus.client.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.gui.font.FontTexture;
import net.minecraft.client.gui.font.GlyphRenderTypes;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

/**
 * The glyph textures of the Futureplus HD font (futureplus:* fonts) are sampled linearly. Vanilla nearest-pixel
 * sampling skipped and doubled glyph pixels when text was drawn at a scale other than the GUI scale (Future's ClickGui,
 * 3D name tags), making the text look choppy. One-to-one drawing looks the same; the vanilla
 * pixel font stays nearest-pixel.
 */
@Mixin(FontTexture.class)
public abstract class FontTextureLinearMixin {
    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void futureplus$linear(Supplier<String> label, GlyphRenderTypes renderTypes, boolean colored, CallbackInfo ci) {
        String name;
        try { name = label.get(); } catch (Throwable t) { return; }
        if (name == null || !name.startsWith("futureplus:")) return;
        ((AbstractTextureSamplerAccessor) (Object) this).futureplus$setSampler(
            RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
    }
}
