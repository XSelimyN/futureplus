package com.futureplus.client.mixin;

import com.futureplus.client.FakePlayerPlus;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stops an attack on the fake player from being sent to the server */
@Mixin(MultiPlayerGameMode.class)
public abstract class FakePlayerAttackMixin {
    @Inject(method = "attack", at = @At("HEAD"), cancellable = true)
    private void futureplus$fakeAttack(Player player, Entity target, CallbackInfo ci) {
        if (!FakePlayerPlus.isFake(target)) return;
        if (player instanceof LocalPlayer local) FakePlayerPlus.onAttacked(local);
        ci.cancel();
    }
}
