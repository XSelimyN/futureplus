package com.futureplus.client.mixin;

import com.futureplus.client.HandTweaks;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hand Settings: swing speed (SwingDurationEvent). Only our own player, visual only. */
@Mixin(LivingEntity.class)
public abstract class LivingEntitySwingMixin {

    @Inject(method = "getCurrentSwingDuration", at = @At("HEAD"), cancellable = true, require = 0)
    private void futureplus$swingDuration(CallbackInfoReturnable<Integer> cir) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || (Object) this != mc.player) return;
        Integer d = HandTweaks.swingDuration(mc.player);
        if (d != null) cir.setReturnValue(d);
    }
}
