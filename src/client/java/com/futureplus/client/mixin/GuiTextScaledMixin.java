package com.futureplus.client.mixin;

import com.futureplus.client.FontMod;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GUI text drawn scaled (e.g. Future's ClickGui ~0.8): the game font's texture is made for 1:1 size, so it got
 * resampled and soft when shrunk. In that case the text is drawn by the HD engine at its real on-screen size.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class GuiTextScaledMixin {
    @Inject(method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void futureplus$scaled(Font font, FormattedCharSequence str, int x, int y, int color, boolean shadow, CallbackInfo ci) {
        if (FontMod.drawScaledGame((GuiGraphicsExtractor) (Object) this, font, str, x, y, color, shadow)) ci.cancel();
    }
}
