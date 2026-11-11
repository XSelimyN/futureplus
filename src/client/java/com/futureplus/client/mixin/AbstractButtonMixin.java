package com.futureplus.client.mixin;

import com.futureplus.client.FutureUi;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** All buttons: FutureUi.button instead of the vanilla stone texture (theme colored, smooth hover fade). */
@Mixin(AbstractButton.class)
public abstract class AbstractButtonMixin {
    @Inject(method = "extractDefaultSprite", at = @At("HEAD"), cancellable = true)
    private void futureplus$button(GuiGraphicsExtractor g, CallbackInfo ci) {
        ci.cancel();
        AbstractButton b = (AbstractButton) (Object) this;
        FutureUi.button(g, b, b.getX(), b.getY(), b.getWidth(), b.getHeight(), b.active, b.isHoveredOrFocused(),
            ((AbstractWidgetAlphaAccessor) b).futureplus$alpha());
    }
}
