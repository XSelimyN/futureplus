package com.futureplus.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.futureplus.client.HandTweaks;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hand Settings - a one-to-one counterpart of Soup's HeldItemRendererMixin.
 * Target: ItemInHandRenderer.renderArmWithItem(AbstractClientPlayer, float, float, InteractionHand, float swing,
 *        ItemStack, float equip, PoseStack, SubmitNodeCollector, int)
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {

    /** Right after pushPose: hand position + angle (HandOffsetEvent). */
    @Inject(method = "renderArmWithItem",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V", shift = At.Shift.AFTER, ordinal = 0),
        require = 0)
    private void futureplus$handOffset(AbstractClientPlayer player, float frameInterp, float xRot, InteractionHand hand,
                                  float attack, ItemStack stack, float equip, PoseStack pose,
                                  SubmitNodeCollector collector, int light, CallbackInfo ci) {
        HandTweaks.applyHandOffset(pose, hand == InteractionHand.MAIN_HAND, stack);
    }

    /** Item drawing: hand size (HandScaleEvent). */
    @WrapOperation(method = "renderArmWithItem",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V"),
        require = 0)
    private void futureplus$handScale(ItemInHandRenderer self, LivingEntity entity, ItemStack stack, ItemDisplayContext ctx,
                                 PoseStack pose, SubmitNodeCollector collector, int light, Operation<Void> original,
                                 @Local(argsOnly = true) InteractionHand hand) {
        float s = HandTweaks.handScale(hand == InteractionHand.MAIN_HAND);
        if (s != 1f) {
            pose.pushPose();
            pose.scale(s, s, s);
            original.call(self, entity, stack, ctx, pose, collector, light);
            pose.popPose();
        } else {
            original.call(self, entity, stack, ctx, pose, collector, light);
        }
    }

    /**
     * Swing style (HandAnimationEvent): swingArm in the plain item branch (3rd occurrence).
     * First undoes vanilla's applyItemArmTransform offset; if the style cancels, swingArm is skipped,
     * otherwise the offset is reapplied and vanilla runs.
     */
    @WrapOperation(method = "renderArmWithItem",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;swingArm(FLcom/mojang/blaze3d/vertex/PoseStack;ILnet/minecraft/world/entity/HumanoidArm;)V", ordinal = 2),
        require = 0)
    private void futureplus$swing(ItemInHandRenderer self, float attack, PoseStack pose, int invert, HumanoidArm arm,
                             Operation<Void> original,
                             @Local(argsOnly = true) AbstractClientPlayer player,
                             @Local(argsOnly = true) InteractionHand hand,
                             @Local(argsOnly = true, ordinal = 3) float equip) {
        int i = arm == HumanoidArm.RIGHT ? 1 : -1;
        float y = -0.52f + equip * -0.6f;
        pose.translate(-i * 0.56f, -y, 0.72f);                       // undo vanilla's offset
        boolean right = player.getMainArm() == HumanoidArm.RIGHT;
        if (HandTweaks.animate(pose, hand == InteractionHand.MAIN_HAND, right, attack)) return;
        pose.translate(i * 0.56f, y, -0.72f);                         // geri uygula
        original.call(self, attack, pose, invert, arm);
    }
}
