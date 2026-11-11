package com.futureplus.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Suicide: Lure Client's AutoCrystal > Targeting > ForceSuicide. When turned on it types ".t offhand" in chat (turns off Future's
 * Offhand/AutoTotem; in Lure the Offhand holds no totem while ForceSuicide is on), then runs the hidden AutoCrystal
 * with ForceSuicide: you are the target, and crystals are placed and blown up where they hit you hardest.
 * On death (ClientboundPlayerCombatKillPacket) or leaving the server ForceSuicide turns off; Suicide turns off too and
 * ".t offhand" is typed again. AutoTotem is also turned back on if it is turned off by hand before dying.
 *
 * The command is not typed letter by letter: it goes through ChatScreen's send path in one go without opening the chat screen, so
 * Future catches the command (whether it hooks the chat screen or sendChat) and it never reaches the server.
 *
 * The settings are AutoCrystal's (Lure defaults; the only difference is AutoSwap = Silent, None in Lure: it doesn't place without a crystal in hand).
 */
public final class Suicide {
    private Suicide() {}

    static String command = ".t offhand";

    private static boolean enabled = false;
    private static boolean toggledOff;                             // ".t offhand" sent, not turned back on yet
    private static KeyMapping key;

    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean v) {
        if (v == enabled) return;
        enabled = v;
        Minecraft mc = Minecraft.getInstance();
        if (v) {
            if (mc.player == null) { enabled = false; return; }
            toggledOff = sendCommand(mc);
            AutoCrystal.forceSuicide = true;
            AutoCrystal.setEnabled(true);
        } else {
            AutoCrystal.forceSuicide = false;
            AutoCrystal.setEnabled(false);
            if (toggledOff) { sendCommand(mc); toggledOff = false; }
        }
    }
    public static KeyMapping getKeyMapping() { return key; }

    public static List<Setting> getSettings() {
        List<Setting> s = new ArrayList<>();
        s.add(Setting.bool("No armor", () -> AutoCrystal.forceSuicideNoArmor, v -> AutoCrystal.forceSuicideNoArmor = v));
        s.add(Setting.bool("Rotate", () -> AutoCrystal.rotate, v -> AutoCrystal.rotate = v));
        s.add(Setting.choice("Auto swap", AutoCrystal.SWAP, () -> AutoCrystal.autoSwap, v -> AutoCrystal.autoSwap = v));
        s.add(Setting.choice("Silent", AutoCrystal.SILENT, () -> AutoCrystal.silent, v -> AutoCrystal.silent = v).when(() -> AutoCrystal.autoSwap == 2));
        s.add(Setting.bool("Swap back", () -> AutoCrystal.swapBack, v -> AutoCrystal.swapBack = v).when(() -> AutoCrystal.autoSwap == 1));
        s.add(Setting.num("Place delay", 0, 1000, 1, () -> AutoCrystal.placeDelay, v -> AutoCrystal.placeDelay = v));
        s.add(Setting.num("Place range", 0, 6, 0.1, () -> AutoCrystal.placeRange, v -> AutoCrystal.placeRange = v));
        s.add(Setting.num("Place wall range", 0, 6, 0.1, () -> AutoCrystal.placeWallRange, v -> AutoCrystal.placeWallRange = v));
        s.add(Setting.choice("Sequential", AutoCrystal.SEQUENTIAL, () -> AutoCrystal.sequential, v -> AutoCrystal.sequential = v));
        s.add(Setting.num("Break delay", 0, 1000, 1, () -> AutoCrystal.breakDelay, v -> AutoCrystal.breakDelay = v));
        s.add(Setting.num("Break range", 0, 6, 0.1, () -> AutoCrystal.breakRange, v -> AutoCrystal.breakRange = v));
        s.add(Setting.num("Break wall range", 0, 6, 0.1, () -> AutoCrystal.breakWallRange, v -> AutoCrystal.breakWallRange = v));
        s.add(Setting.num("Factor", 1, 5, 1, () -> AutoCrystal.attackFactor, v -> AutoCrystal.attackFactor = v));
        s.add(Setting.choice("Instant", AutoCrystal.INSTANT, () -> AutoCrystal.instant, v -> AutoCrystal.instant = v));
        s.add(Setting.bool("Await", () -> AutoCrystal.await, v -> AutoCrystal.await = v).when(() -> AutoCrystal.instant != 2));
        s.add(Setting.num("Ticks existed", 0, 40, 1, () -> AutoCrystal.ticksExisted, v -> AutoCrystal.ticksExisted = v).when(() -> AutoCrystal.instant != 0));
        s.add(Setting.bool("Extrapolation", () -> AutoCrystal.prediction, v -> AutoCrystal.prediction = v));
        s.add(Setting.num("Extrapolation ticks", 1, 8, 1, () -> AutoCrystal.predictionTicks, v -> AutoCrystal.predictionTicks = v).when(() -> AutoCrystal.prediction));
        s.add(Setting.bool("Render", () -> AutoCrystal.render, v -> AutoCrystal.render = v));
        s.add(Setting.bool("Debug", () -> AutoCrystal.debug, v -> AutoCrystal.debug = v));
        return s;
    }

    public static void init() {
        KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("futureplus", "suicide"));
        key = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.futureplus.suicide",
            InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), cat));
        AutoCrystal.onSuicideOff = () -> setEnabled(false);       // death / leave: ForceSuicide turned off
        ClientTickEvents.END_CLIENT_TICK.register(Suicide::tick);
    }

    private static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.getConnection() == null) {
            if (enabled) { enabled = false; toggledOff = false; AutoCrystal.forceSuicide = false; AutoCrystal.setEnabled(false); }   // left the server
            return;
        }
        while (key.consumeClick()) setEnabled(!enabled);
    }

    // ------------------------------------------------------------- sohbet

    /** Sends the command in one go (no typing animation); the ChatScreen path goes through Future's command hook. */
    private static boolean sendCommand(Minecraft mc) {
        if (mc.player == null || mc.getConnection() == null) return false;
        new ChatScreen("", false).handleChatInput(command, false);
        return true;
    }
}
