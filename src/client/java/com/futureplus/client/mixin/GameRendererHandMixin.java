package com.futureplus.client.mixin;

import com.futureplus.client.HandShader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hand Shader. In 26.1 GameRenderer.renderItemInHand only SUBMITs the hand; the actual drawing happens in the (single)
 * FeatureRenderDispatcher.renderAllFeatures() call inside renderLevel. So the effect is applied after that call.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererHandMixin {

    /** Before the hand is drawn (depth cleared, world ready): saves the background for the "hide hand" modes. */
    @Inject(method = "renderLevel",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderItemInHand(Lnet/minecraft/client/renderer/state/level/CameraRenderState;FLorg/joml/Matrix4fc;)V"),
        require = 0)
    private void futureplus$beforeHand(DeltaTracker deltaTracker, CallbackInfo ci) {
        HandShader.beforeHand();
    }

    /** The hand was actually drawn to the screen: apply the effect. */
    @Inject(method = "renderLevel",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;renderAllFeatures()V",
            shift = At.Shift.AFTER),
        require = 0)
    private void futureplus$afterHand(DeltaTracker deltaTracker, CallbackInfo ci) {
        HandShader.afterHand();
    }
}
