package com.futureplus.client.mixin;

import com.mojang.blaze3d.font.GlyphProvider;
import com.futureplus.client.FontMod;
import net.minecraft.client.gui.font.providers.TrueTypeGlyphProviderDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.InputStream;

/**
 * Futureplus TTF fonts (namespace futureplus) are drawn the same way as the Futureplus HD engine instead of FreeType
 * (AwtGlyphProvider): all game text gets the same sharpness as the main menu / HUD text.
 * Other (vanilla / resource pack) TTF fonts are left alone. On any error it falls back to the vanilla FreeType path.
 */
@Mixin(TrueTypeGlyphProviderDefinition.class)
public abstract class FontHdProviderMixin {
    @Inject(method = "load", at = @At("HEAD"), cancellable = true)
    private void futureplus$hdProvider(ResourceManager rm, CallbackInfoReturnable<GlyphProvider> cir) {
        TrueTypeGlyphProviderDefinition self = (TrueTypeGlyphProviderDefinition) (Object) this;
        Identifier loc = self.location();
        if (!"futureplus".equals(loc.getNamespace()) || "freetype".equals(System.getProperty("futureplus.fontengine"))) return;
        try (InputStream in = rm.open(loc.withPrefix("font/"))) {
            GlyphProvider p = FontMod.hdGlyphProvider(loc.toString(), in, self.size(), self.oversample());
            if (p != null) cir.setReturnValue(p);
        } catch (Throwable t) {
            System.err.println("[Futureplus] HD font saglayicisi kurulamadi (" + loc + "), FreeType kullaniliyor: " + t);
        }
    }
}
