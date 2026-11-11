package com.futureplus.client.mixin;

import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Button drawing: the widget's fade (alpha) value. */
@Mixin(AbstractWidget.class)
public interface AbstractWidgetAlphaAccessor {
    @Accessor("alpha")
    float futureplus$alpha();
}
