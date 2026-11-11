package com.futureplus.client.mixin;

import com.futureplus.client.AnchorAura;
import com.futureplus.client.AutoCrystal;
import com.futureplus.client.FakeFly;
import com.futureplus.client.MaceAura;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "handleSetEntityMotion", at = @At("HEAD"), cancellable = true)
    private void futureplus$motion(ClientboundSetEntityMotionPacket packet, CallbackInfo ci) {
        if (MaceAura.ignoreSelfMotion(packet.id())) ci.cancel();
    }

    /** GrimSwapManager: the echo of a slot we passed through during a silent swap must not refresh the held stack and restart its animation. */
    @Inject(method = "handleContainerSetSlot", at = @At("HEAD"), cancellable = true)
    private void futureplus$setSlot(net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        if (!Minecraft.getInstance().isSameThread()) return;
        com.futureplus.client.GrimDiag.onSlotUpdate(packet.getContainerId());
        if (com.futureplus.client.GrimSwapManager.cancelSlotEcho(packet)) ci.cancel();
    }

    @Inject(method = "handleSetEntityData", at = @At("HEAD"))
    private void futureplus$entityData(ClientboundSetEntityDataPacket packet, CallbackInfo ci) {
        if (Minecraft.getInstance().isSameThread()) FakeFly.onServerData(packet);
    }

    @Inject(method = "handleSetEntityData", at = @At("RETURN"))
    private void futureplus$entityDataAfter(ClientboundSetEntityDataPacket packet, CallbackInfo ci) {
        if (Minecraft.getInstance().isSameThread()) FakeFly.afterServerData(packet);
    }

    @Inject(method = "handleSetEquipment", at = @At("HEAD"))
    private void futureplus$equipment(ClientboundSetEquipmentPacket packet, CallbackInfo ci) {
        if (Minecraft.getInstance().isSameThread()) FakeFly.onServerEquipment(packet.getEntity(), packet.getSlots());
    }

    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void futureplus$teleport(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        if (Minecraft.getInstance().isSameThread()) { FakeFly.onServerTeleport(packet); MaceAura.onServerTeleport(packet); AutoCrystal.onSetback(); com.futureplus.client.GrimDiag.onSetback(); }
    }

    /** AutoCrystal: a crystal spawned (Instant break / Sequential). From the packet, before the entity is added. */
    @Inject(method = "handleAddEntity", at = @At("HEAD"))
    private void futureplus$addEntity(net.minecraft.network.protocol.game.ClientboundAddEntityPacket packet, CallbackInfo ci) {
        if (Minecraft.getInstance().isSameThread()) {
            AutoCrystal.onAddEntity(packet); com.futureplus.client.KillEffects.onAddEntity(packet);
            if (packet.getType() == net.minecraft.world.entity.EntityType.END_CRYSTAL)
                com.futureplus.client.GrimDiag.onCrystalAdded(net.minecraft.core.BlockPos.containing(packet.getX(), packet.getY(), packet.getZ()));
        }
    }

    @Inject(method = "handleEntityEvent", at = @At("HEAD"))
    private void futureplus$entityEvent(net.minecraft.network.protocol.game.ClientboundEntityEventPacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread() && mc.level != null) AutoCrystal.onEntityEvent(packet.getEntity(mc.level), packet.getEventId());
    }

    /** AutoCrystal: we died, ForceSuicide turns off. */
    @Inject(method = "handlePlayerCombatKill", at = @At("HEAD"))
    private void futureplus$combatKill(net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket packet, CallbackInfo ci) {
        if (Minecraft.getInstance().isSameThread()) AutoCrystal.onCombatKill();
    }

    @Inject(method = "handleBlockUpdate", at = @At("RETURN"), require = 0)
    private void futureplus$blockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        if (Minecraft.getInstance().isSameThread()) {
            AnchorAura.onBlockUpdate(packet.getPos(), packet.getBlockState());
            com.futureplus.client.GrimDiag.onBlockUpdate(packet.getPos(), packet.getBlockState());
        }
    }

    /** GrimDiag: sequenced acknowledgement of a block interaction. */
    @Inject(method = "handleBlockChangedAck", at = @At("HEAD"), require = 0)
    private void futureplus$blockAck(net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket packet, CallbackInfo ci) {
        if (Minecraft.getInstance().isSameThread()) com.futureplus.client.GrimDiag.onBlockAck(packet.sequence());
    }
}
