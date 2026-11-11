package com.futureplus.client.mixin;

import com.futureplus.client.FutureMultiplayerScreen;
import com.futureplus.client.FutureTitleScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Futureplus designs open instead of the vanilla main menu and server list. */
@Mixin(Minecraft.class)
public abstract class MinecraftScreenMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void futureplus$replaceScreen(Screen screen, CallbackInfo ci) {
        if (screen == null) return;
        Screen repl = null;
        if (screen.getClass() == TitleScreen.class) repl = new FutureTitleScreen();
        else if (screen.getClass() == JoinMultiplayerScreen.class)
            repl = new FutureMultiplayerScreen(((JoinMultiplayerScreenAccessor) screen).futureplus$lastScreen());
        if (repl == null) return;
        ci.cancel();
        ((Minecraft) (Object) this).setScreen(repl);
    }
}
