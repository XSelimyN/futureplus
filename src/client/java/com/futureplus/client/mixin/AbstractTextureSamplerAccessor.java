package com.futureplus.client.mixin;

import com.mojang.blaze3d.textures.GpuSampler;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** FontTextureLinearMixin: to swap the texture sampler. */
@Mixin(AbstractTexture.class)
public interface AbstractTextureSamplerAccessor {
    @Accessor("sampler")
    void futureplus$setSampler(GpuSampler sampler);
}
