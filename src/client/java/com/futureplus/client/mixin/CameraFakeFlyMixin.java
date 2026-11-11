package com.futureplus.client.mixin;

import com.futureplus.client.FakeFly;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FakeFly: the camera also stays at standing eye height like the upright model (see HumanoidRenderFakeFlyMixin). With the client
 * glide held, the pose was FALL_FLYING and the eye stayed at 0.4 blocks.
 *
 * Eye height used to be picked from the pose in Camera.tick; when the pose flipped for a moment between ticks (server data, pulse)
 * the target jumped between 0.4 and 1.62, and vanilla smoothing drew that as jitter.
 * Now while gliding (or crawling) both the old and new eye height are pinned to the standing value.
 */
@Mixin(Camera.class)
public abstract class CameraFakeFlyMixin {
    @Shadow private Entity entity;
    @Shadow private float eyeHeight;
    @Shadow private float eyeHeightOld;

    @Inject(method = "tick", at = @At("TAIL"))
    private void futureplus$standingEye(CallbackInfo ci) {
        Entity e = entity;
        if (e == null || e != Minecraft.getInstance().player || !FakeFly.uprightCamera()) return;
        boolean glide = FakeFly.isHolding() || (e instanceof net.minecraft.world.entity.LivingEntity l && l.isFallFlying())
            || e.hasPose(Pose.FALL_FLYING) || (e.hasPose(Pose.SWIMMING) && !e.isInWater());
        if (!glide) return;
        eyeHeight = eyeHeightOld = e.getEyeHeight(Pose.STANDING);
    }
}
