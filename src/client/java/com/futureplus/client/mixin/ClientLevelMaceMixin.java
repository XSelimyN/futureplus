package com.futureplus.client.mixin;

import com.futureplus.client.MaceAura;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** MaceAura packet rotation: the fake angle holds for the whole entity tick (fireworks included). */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMaceMixin {

    @Inject(method = "tickEntities", at = @At("HEAD"))
    private void futureplus$rotStart(CallbackInfo ci) { MaceAura.worldTickStart(); }

    @Inject(method = "tickEntities", at = @At("RETURN"))
    private void futureplus$rotEnd(CallbackInfo ci) { MaceAura.worldTickEnd(); }
}
