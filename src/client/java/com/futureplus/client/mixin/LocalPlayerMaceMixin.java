package com.futureplus.client.mixin;

import com.futureplus.client.FakeFly;
import com.futureplus.client.MaceAura;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * MaceAura: decides at the start of the player's tick (before movement, so the elytra glides the wanted way that tick).
 * FakeFly: the pulse opens at HEAD (elytra + START_FALL_FLYING), the tick's movement packet goes out in the glide window,
 * and it closes at RETURN with a firework + chestplate.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMaceMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void futureplus$maceHead(CallbackInfo ci) {
        LocalPlayer p = (LocalPlayer) (Object) this;
        FakeFly.beforeTick(p);
        MaceAura.preTick(p);
        FakeFly.preTick(p);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void futureplus$maceTail(CallbackInfo ci) {
        FakeFly.postTick((LocalPlayer) (Object) this);
        MaceAura.postTick();
    }
}
