package com.futureplus.client.mixin;

import com.futureplus.client.FakeFly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** FakeFly: no glide tilt on the local model (see HumanoidRenderFakeFlyMixin). */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRenderFakeFlyMixin {
    @Inject(method = "extractFlightData", at = @At("TAIL"))
    private void futureplus$noFlightTilt(Avatar entity, AvatarRenderState state, float partialTicks, CallbackInfo ci) {
        if (entity == Minecraft.getInstance().player && FakeFly.isEnabled()) state.fallFlyingTimeInTicks = 0.0F;
    }
}
