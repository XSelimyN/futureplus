package com.futureplus.client.mixin;

import com.futureplus.client.FutureUi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * All menus outside a world (world select, options, language...): the Future+ background instead of the vanilla panorama + blur
 * texture. In-game menus keep the vanilla blur.
 */
@Mixin(Screen.class)
public abstract class ScreenBackgroundMixin {
    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
    private void futureplus$background(GuiGraphicsExtractor g, int mouseX, int mouseY, float pt, CallbackInfo ci) {
        if (Minecraft.getInstance().level != null) return;
        ci.cancel();
        Screen s = (Screen) (Object) this;
        FutureUi.backdrop(g, s.width, s.height, false);
    }
}
