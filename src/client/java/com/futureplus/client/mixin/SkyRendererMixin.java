package com.futureplus.client.mixin;

import com.futureplus.client.SkyRender;
import com.futureplus.client.Skybox;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * With Skybox on, skips the vanilla sky and draws our own.
 * Order (LevelRenderer.addSkyPass): renderSkyDisc -> renderSunriseAndSunset -> renderSunMoonAndStars -> renderDarkDisc
 */
@Mixin(SkyRenderer.class)
public abstract class SkyRendererMixin {

    @Inject(method = "renderSkyDisc", at = @At("HEAD"), cancellable = true)
    private void futureplus$sky(CallbackInfo ci) {
        if (!Skybox.active()) return;
        SkyRender.render();
        ci.cancel();
    }

    @Inject(method = "renderEndSky", at = @At("HEAD"), cancellable = true)
    private void futureplus$endSky(CallbackInfo ci) {
        if (!Skybox.active()) return;
        SkyRender.render();
        ci.cancel();
    }

    @Inject(method = "renderSunriseAndSunset", at = @At("HEAD"), cancellable = true)
    private void futureplus$sunrise(CallbackInfo ci) {
        if (Skybox.active()) ci.cancel();
    }

    @Inject(method = "renderSunMoonAndStars", at = @At("HEAD"), cancellable = true)
    private void futureplus$sunMoon(CallbackInfo ci) {
        if (Skybox.active() && !Skybox.showSunMoon()) ci.cancel();
    }

    @Inject(method = "renderDarkDisc", at = @At("HEAD"), cancellable = true)
    private void futureplus$dark(CallbackInfo ci) {
        if (Skybox.active()) ci.cancel();
    }
}
