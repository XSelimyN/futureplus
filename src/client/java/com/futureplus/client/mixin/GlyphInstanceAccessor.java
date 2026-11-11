package com.futureplus.client.mixin;

import net.minecraft.client.gui.font.glyphs.BakedSheetGlyph;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** ScaledGlyphs: position, color and style of a glyph instance to be drawn in the GUI. */
@Mixin(targets = "net.minecraft.client.gui.font.glyphs.BakedSheetGlyph$GlyphInstance")
public interface GlyphInstanceAccessor {
    @Accessor("x") float futureplus$x();
    @Accessor("y") float futureplus$y();
    @Accessor("color") int futureplus$color();
    @Accessor("shadowColor") int futureplus$shadowColor();
    @Accessor("glyph") BakedSheetGlyph futureplus$glyph();
    @Accessor("style") Style futureplus$style();
    @Accessor("shadowOffset") float futureplus$shadowOffset();
}
