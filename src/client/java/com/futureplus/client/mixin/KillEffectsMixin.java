package com.futureplus.client.mixin;

import com.futureplus.client.KillEffects;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** KillEffects: when a player's death event (entity event 3) reaches the client. */
@Mixin(LivingEntity.class)
public abstract class KillEffectsMixin {
    @Inject(method = "handleEntityEvent", at = @At("HEAD"))
    private void futureplus$death(byte id, CallbackInfo ci) {
        if (id == 3) KillEffects.onDeath((LivingEntity) (Object) this);
    }
}
