package com.futureplus.client;

import java.util.Map;

/** Descriptions for the modules retained in the client menu. */
final class Tooltips {
    private Tooltips() {}

    private static final Map<String, String> TEXT = Map.ofEntries(
        Map.entry("PistonAura", "Attacks by placing a piston and crystal beside the target."),
        Map.entry("MaceAura", "Attacks the target from above with a mace."),
        Map.entry("FakeFly", "Server-side chestplate flight: same-tick Elytra pulses for fireworks; your model shows the pose the server sends."),
        Map.entry("Blocker", "Blocks the movement space around a nearby target."),
        Map.entry("Blocker.AntiCEV", "Covers the 3x3 layer one block above your head and breaks crystals placed on it."),
        Map.entry("AnchorAura", "Places a respawn anchor and detonates it with glowstone."),
        Map.entry("Suicide", "AutoCrystal ForceSuicide: types .t offhand to turn off AutoTotem, then places and breaks crystals on yourself. Turns off and types it again when you die."),
        Map.entry("AntiPhase", "Fills empty blocks the opponent phases into."),
        Map.entry("Skybox+", "Changes the appearance of the sky."),
        Map.entry("Font+", "Customizes game, interface, and 3D text."),
        Map.entry("FakePlayer+", "Creates a client-side player that can move."),
        Map.entry("Hand Tweaks", "Adjusts hand position, scale, and animation."),
        Map.entry("Hand Shader", "Applies shaders to held items."),
        Map.entry("KillEffects", "Strikes a colored lightning bolt where something you killed died. Only counts if your hit, crystal or anchor killed it."),
        Map.entry("KillEffects.Kill sound", "Sound played on a kill. Selecting one plays a preview."),
        Map.entry("KillEffects.Through walls", "Lightning stays visible behind blocks."),
        Map.entry("KillEffects.Bolt count", "How many lightning bolts strike at once."),
        Map.entry("KillEffects.Animals", "Also strike on animals you kill (for testing in singleplayer)."),
        Map.entry("KillEffects.Monsters", "Also strike on monsters you kill (for testing in singleplayer)."),
        Map.entry("KillEffects.Color mode", "Global: client theme color, Custom: HSV wheel, Rainbow: flowing gradient, Vanilla: default lightning."),
        Map.entry("KillEffects.Kill effect length", "How long the lightning stays (seconds). Click the value or right-click to type it."),
        Map.entry("*.Range", "Maximum distance at which the module operates."),
        Map.entry("*.Color", "Selects the render color.")
    );

    static String get(String module, String setting) {
        return TEXT.getOrDefault(module + "." + setting,
            TEXT.getOrDefault(module, TEXT.getOrDefault("*." + setting, null)));
    }
}
