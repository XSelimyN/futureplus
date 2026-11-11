package com.futureplus.client.mixin;

import com.futureplus.client.FontMod;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.FontDescription;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Font module in "all game text" mode: wherever the default font (minecraft:default) is requested, the
 * Futureplus font is given. Drawing (getGlyph) and measuring (StringSplitter) both go through here, so
 * text layout stays intact. Other fonts (enchanting table alphabet etc.) are left alone.
 */
@Mixin(Font.class)
public class FontGlobalMixin {
    @ModifyVariable(method = "getGlyphSource", at = @At("HEAD"), argsOnly = true)
    private FontDescription futureplus$globalFont(FontDescription desc) {
        if (desc == FontDescription.DEFAULT || FontDescription.DEFAULT.equals(desc)) {
            FontDescription g = FontMod.globalFont();
            if (g != null) return g;
        }
        return desc;
    }
}
