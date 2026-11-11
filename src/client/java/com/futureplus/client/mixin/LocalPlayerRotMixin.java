package com.futureplus.client.mixin;

import com.futureplus.client.CombatSupport;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Server rotation for the PvP modules: while the movement packet is built the wanted look is written to the player for a moment,
 * and the real look is put back once the packet is sent (the camera doesn't turn). The last sent look is kept in CombatSupport.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerRotMixin {

    @Inject(method = "sendPosition", at = @At("HEAD"), require = 0)
    private void futureplus$rotBefore(CallbackInfo ci) {
        CombatSupport.beforeSend((LocalPlayer) (Object) this);
    }

    @Inject(method = "sendPosition", at = @At("RETURN"), require = 0)
    private void futureplus$rotAfter(CallbackInfo ci) {
        CombatSupport.afterSend((LocalPlayer) (Object) this);
    }
}
