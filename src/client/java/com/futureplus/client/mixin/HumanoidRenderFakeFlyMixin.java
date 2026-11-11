package com.futureplus.client.mixin;

import com.futureplus.client.FakeFly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** FakeFly: the local model is always drawn upright, ignoring the held client glide and the pose the server sends. */
@Mixin(HumanoidMobRenderer.class)
public abstract class HumanoidRenderFakeFlyMixin {
    @Inject(method = "extractHumanoidRenderState", at = @At("TAIL"))
    private static void futureplus$upright(LivingEntity entity, HumanoidRenderState state, float partialTicks,
                                          ItemModelResolver itemModelResolver, CallbackInfo ci) {
        if (entity != Minecraft.getInstance().player || !FakeFly.isEnabled()) return;
        state.pose = Pose.STANDING;
        state.isFallFlying = false;
        state.isVisuallySwimming = false;
        state.swimAmount = 0.0F;
        state.speedValue = 1.0F;
    }
}
