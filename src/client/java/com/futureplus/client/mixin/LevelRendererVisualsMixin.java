package com.futureplus.client.mixin;

import com.futureplus.client.WorldDraw;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Module world drawings in the main draw pass, right after entities and translucents are drawn
 * (finalizeGizmoCollection = when vanilla prepares its debug drawings) our world drawing.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererVisualsMixin {

    @Inject(method = "finalizeGizmoCollection", at = @At("TAIL"), require = 0)
    private void futureplus$world(CallbackInfo ci) {
        WorldDraw.render();
    }
}
