package com.futureplus.client.mixin;

import com.futureplus.client.FutureUi;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dark theme: inventory / chest / furnace / creative... textures and sprites are drawn multiplied by a dark color
 * (light gray #C6C6C6 background -> #222227; bevelled edges keep their ratio). The dark gray title text on these screens
 * (#404040) is turned light, otherwise it can't be read on the dark background.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class GuiTintMixin {
    @Unique private static boolean futureplus$inTint;

    @Unique
    private static boolean futureplus$dark(Identifier id) {
        String p = id.getPath();
        return p.startsWith("textures/gui/container/") || p.startsWith("container/")
            || p.startsWith("textures/gui/recipe_book") || p.startsWith("recipe_book/");
    }

    @Unique
    private static int futureplus$tint(int argb) {
        int t = FutureUi.DARK_TINT;
        int r = ((argb >> 16) & 0xFF) * ((t >> 16) & 0xFF) / 255, g = ((argb >> 8) & 0xFF) * ((t >> 8) & 0xFF) / 255, b = (argb & 0xFF) * (t & 0xFF) / 255;
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    @Inject(method = "blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIII)V", at = @At("HEAD"), cancellable = true)
    private void futureplus$blit11(RenderPipeline p, Identifier id, int x, int y, float u, float v, int w, int h, int tw, int th, int color, CallbackInfo ci) {
        if (futureplus$inTint || !futureplus$dark(id)) return;
        ci.cancel();
        futureplus$inTint = true;
        try { ((GuiGraphicsExtractor) (Object) this).blit(p, id, x, y, u, v, w, h, tw, th, futureplus$tint(color)); }
        finally { futureplus$inTint = false; }
    }

    @Inject(method = "blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIIIII)V", at = @At("HEAD"), cancellable = true)
    private void futureplus$blit13(RenderPipeline p, Identifier id, int x, int y, float u, float v, int w, int h, int uw, int vh, int tw, int th, int color, CallbackInfo ci) {
        if (futureplus$inTint || !futureplus$dark(id)) return;
        ci.cancel();
        futureplus$inTint = true;
        try { ((GuiGraphicsExtractor) (Object) this).blit(p, id, x, y, u, v, w, h, uw, vh, tw, th, futureplus$tint(color)); }
        finally { futureplus$inTint = false; }
    }

    @Inject(method = "blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V", at = @At("HEAD"), cancellable = true)
    private void futureplus$sprite(RenderPipeline p, Identifier id, int x, int y, int w, int h, int color, CallbackInfo ci) {
        if (futureplus$inTint || !futureplus$dark(id)) return;
        ci.cancel();
        futureplus$inTint = true;
        try { ((GuiGraphicsExtractor) (Object) this).blitSprite(p, id, x, y, w, h, futureplus$tint(color)); }
        finally { futureplus$inTint = false; }
    }

    @Unique
    private static int futureplus$label(int color) {
        if ((color & 0xFFFFFF) != 0x404040 || !(Minecraft.getInstance().screen instanceof AbstractContainerScreen<?>)) return color;
        return (color & 0xFF000000) | 0xC8C8D0;
    }

    @ModifyVariable(method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V", at = @At("HEAD"), argsOnly = true, ordinal = 2)
    private int futureplus$labelComponent(int color) { return futureplus$label(color); }

    @ModifyVariable(method = "text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V", at = @At("HEAD"), argsOnly = true, ordinal = 2)
    private int futureplus$labelString(int color) { return futureplus$label(color); }

    @ModifyVariable(method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V", at = @At("HEAD"), argsOnly = true, ordinal = 2)
    private int futureplus$labelSeq(int color) { return futureplus$label(color); }
}
