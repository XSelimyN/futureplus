package com.futureplus.client.mixin;

import com.futureplus.client.FutureUi;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lists (servers, worlds, packs...): dark rounded card, theme colored selection. */
@Mixin(AbstractSelectionList.class)
public abstract class AbstractSelectionListMixin {
    /** List background: a dark rounded card around the rows. */
    @Inject(method = "extractListBackground", at = @At("HEAD"), cancellable = true)
    private void futureplus$background(GuiGraphicsExtractor g, CallbackInfo ci) {
        ci.cancel();
        AbstractSelectionList<?> l = (AbstractSelectionList<?>) (Object) this;
        AbstractWidget w = (AbstractWidget) (Object) this;
        int x0 = Math.max(w.getX() + 2, l.getRowLeft() - 6), x1 = Math.min(w.getX() + w.getWidth() - 2, l.getRowRight() + 6);
        FutureUi.round(g, x0, w.getY() + 2, x1 - x0, w.getHeight() - 4, 3f, 0x66000000);
        FutureUi.roundEdge(g, x0, w.getY() + 2, x1 - x0, w.getHeight() - 4, 3f, FutureUi.argb(0.09f, 0xFFFFFF));
    }

    /** Vanilla separator textures: the card's edge is enough. */
    @Inject(method = "extractListSeparators", at = @At("HEAD"), cancellable = true)
    private void futureplus$separators(GuiGraphicsExtractor g, CallbackInfo ci) { ci.cancel(); }

    /** Outer fill of the selection (vanilla: white/gray frame): replaced by a theme colored selection. */
    @Redirect(method = "extractSelection", at = @At(value = "INVOKE", ordinal = 0,
        target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V"))
    private void futureplus$selection(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
        FutureUi.selection(g, x0, y0, x1 - x0, y1 - y0, color == -1);
    }

    /** Inner black fill of the selection: not drawn. */
    @Redirect(method = "extractSelection", at = @At(value = "INVOKE", ordinal = 1,
        target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V"))
    private void futureplus$selectionInner(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) { }
}
