package com.futureplus.client.mixin;

import com.futureplus.client.FakeFly;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** FakeFly: the local player never enters the crawl look (see HumanoidRenderFakeFlyMixin). */
@Mixin(Entity.class)
public abstract class EntityFakeFlyMixin {
    @Inject(method = "isVisuallyCrawling", at = @At("HEAD"), cancellable = true)
    private void futureplus$serverCrawl(CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (self == Minecraft.getInstance().player && FakeFly.isEnabled())
            cir.setReturnValue(false);
    }
}
