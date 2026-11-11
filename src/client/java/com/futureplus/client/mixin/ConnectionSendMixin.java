package com.futureplus.client.mixin;

import com.futureplus.client.KillEffects;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * KillEffects: outgoing attack and block click packets are read to credit kills to us. Every form of Connection.send
 * goes through here, so it is caught even when another client's crystal/anchor aura skips ClientPacketListener and sends
 * straight to the Connection.
 */
@Mixin(Connection.class)
public abstract class ConnectionSendMixin {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"))
    private void futureplus$killEffectsSend(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        KillEffects.onOutgoing(packet);
    }
}
