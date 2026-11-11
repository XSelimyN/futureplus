package com.futureplus.client.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.prediction.PredictiveAction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Silent swap: to tell the server right away after taking the selected slot back.
 * Vanilla sequence number access for block interaction packets.
 */
@Mixin(MultiPlayerGameMode.class)
public interface MultiPlayerGameModeAccessor {
    @Invoker("ensureHasSentCarriedItem")
    void futureplus$syncCarried();

    @Invoker("startPrediction")
    void futureplus$predict(ClientLevel level, PredictiveAction action);

    /** Vanilla block breaking progress and state access. */
    @org.spongepowered.asm.mixin.gen.Accessor("destroyProgress")
    float futureplus$destroyProgress();

    @org.spongepowered.asm.mixin.gen.Accessor("destroyBlockPos")
    net.minecraft.core.BlockPos futureplus$destroyPos();

    /** The slot last told to the server (GrimSwapManager ghost swap). */
    @org.spongepowered.asm.mixin.gen.Accessor("carriedIndex")
    int futureplus$carriedIndex();

    @org.spongepowered.asm.mixin.gen.Accessor("carriedIndex")
    void futureplus$setCarriedIndex(int slot);
}
