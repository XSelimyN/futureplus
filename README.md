# Future+

Future+ is a helper (addon) utility client that runs alongside the **Future** client and adds modules Future doesn't have. It is a client-side only Fabric mod for Minecraft 26.1.2. In code it is called `futureplus`, since Java names can't contain `+`.

It sits in the same `mods` folder as Future and works together with it. For example, PistonAura's Grim mode only clicks a leftover block to break it; Future's SpeedMine does the mining. Future+ doesn't depend on any Future addon API. The module system, settings, ClickGUI, rotation manager, world rendering and text engine are this project's own base. The only external pieces are Fabric Loader, Fabric API and Sodium, which is embedded in the jar.

## Installation

1. Build with `./gradlew build`; the output is `build/libs/futureplus.jar`.
2. Put the jar in the `mods` folder next to Future (`future.jar`).
3. Turn modules on from the ClickGUI in game. PistonAura chat commands start with `,` (`,help`).

## Modules

| Category | Module | What it does |
| --- | --- | --- |
| Combat | PistonAura | Builds a piston + crystal setup next to the target and detonates it (NCP). The `Grim` setting (or `,grim on`) switches to the Grim anticheat friendly `GrimPiston` path (`Attack Delay (s)`: wait between two attacks, in seconds); it clicks leftover blocks so Future's SpeedMine breaks them |
| Combat | MaceAura | Climbs with an elytra and mace dives onto the target |
| Combat | AnchorAura | Places, charges and detonates respawn anchors |
| Combat | Suicide | Kills you with your own crystals: instantly types `.t offhand` in chat to turn off AutoTotem, runs the hidden `AutoCrystal` with target = you (place/break, Instant, Sequential, Silent swap); turns off on death and turns AutoTotem back on with `.t offhand` |
| Combat | Blocker | Covers threatened blocks with obsidian; the AntiCEV setting also covers the 3x3 layer above the head |
| Combat | AntiPhase | Stops the target from phasing into a block |
| Combat | FakePlayer+ | Spawns a fake player for testing |
| Movement | FakeFly | Flies upright in the air by swapping chestplate <-> elytra with periodic fireworks; forced on while MaceAura is on |
| Visual | Skybox+ | A custom sky drawn with shaders |
| Visual | Hand Tweaks | Hand/item position and swing settings |
| Visual | Hand Shader | Shader effects on the hand |
| Visual | KillEffects | Colored lightning where a killed player died (Global / Custom / Rainbow / Vanilla, 0.1–5 s) and a kill sound (`assets/futureplus/sounds/kill`) |
| Client | Font+ | A sharp (HD) Arial based text engine |

The ClickGUI opens with Right Shift by default. Chat commands use the `,` prefix (e.g. `,friend`).

## Building

Requires Java 25 or newer:

```sh
./gradlew build
```

Output: `build/libs/futureplus.jar`. For a development client, `./gradlew runClient`.

## Project layout

```
src/client/java/com/futureplus/client/         modules and the shared base
src/client/java/com/futureplus/client/mixin/   mixins hooking into Minecraft (futureplus$ prefixed)
src/client/resources/assets/minecraft/shaders/ sky and hand shaders (futureplus_ prefixed)
src/client/resources/assets/futureplus/lang/   key names
src/main/resources/assets/futureplus/          font, ClickGUI textures, icon
```

### Base classes

| Class | Role |
| --- | --- |
| `FutureplusClient` | Entry point; initializes the modules |
| `ClickGui` | The menu; module registration, key binding, saving settings to disk |
| `Setting` | The setting type shown in the ClickGUI (toggle, number, choice, color); with `when(condition)` it is hidden and locked while the setting it depends on is off, and the row animates open / closed when the condition changes |
| `CombatSupport` | Packet rotation, explosion damage calculation, slot/hand selection |
| `AutoCrystal` | Crystal aura behind Suicide (not in the module list) |
| `CrystalDamage`, `MotionPredictor` | Explosion damage / raycast and player movement prediction (for AutoCrystal) |
| `PlacementSupport` | Block placement helpers |
| `WorldDraw`, `Gfx` | In-world and on-screen drawing |
| `FutureLogo` | The Future logo on the loading screen: drawn as vectors, the texture is generated at its on-screen pixel size (`LoadingOverlayMixin`: dark background, red loading bar) |
| `FutureUi` | Theme colored drawing of all buttons, sliders and lists (`AbstractButtonMixin`, `AbstractSliderButtonMixin`, `AbstractSelectionListMixin`), inventory slots (`ContainerSlotMixin`), dark container textures and titles (`GuiTintMixin`), the hotbar (`GuiHotbarMixin`) and a theme colored menu background (instead of the vanilla panorama in every menu outside a world, `ScreenBackgroundMixin`); corners are rounded with anti-aliasing in screen pixels |
| `FutureTitleScreen`, `FutureMultiplayerScreen` | Screens that open instead of the vanilla main menu and server list (`MinecraftScreenMixin`) |
| `GrimPiston` | PistonAura Grim mode (hidden): damage/self-damage limits, breaker crystal, clicking leftover blocks for Future |
| `GrimSwapManager` | Grim slot swaps (hidden): prioritized silent slot swaps (3 per tick), latch, holding a slot on the server, slot echo cancelling |
| `GrimRotation` | Grim rotation (hidden): silent looks with a PosRot at the same position |
| `GrimPlacementManager` | Grim placement helpers (hidden): placeability, interactable blocks, look point |
| `GrimCrystalUtil` | Grim crystal damage (hidden) |
| `GrimDiag` | Grim diagnostics (hidden): with Debug on, logs the server's answer to every Grim placement |
| `BoxAnimator` | Open / fade animation of world boxes (PistonAura render) |
| `FontMod`, `HdText`, `AwtGlyphProvider`, `ScaledGlyphs` | Text engine |
| `SkyRender`, `SkyTextures` | Skybox drawing and texture generation |
| `VisualState` | Visual module state, color helpers, friend list |

### Adding a module

1. Write a class in the `client` package with `isEnabled()`, `setEnabled(boolean)`, `getSettings()` and `init()`.
2. Call `init()` in `FutureplusClient.onInitializeClient()`.
3. Add an `Entry` to the matching `Category` list in `ClickGui`.
4. If it has a key binding, add its name to `lang/en_us.json`.

## Config files

All of them live in the game's `config/` folder:

| File | Contents |
| --- | --- |
| `futureplus-gui.properties` | ClickGUI look and panel positions |
| `futureplus-settings.properties` | Module settings |
| `futureplus-pistonaura.properties` | PistonAura settings |
| `futureplus-visuals.properties` | On/off state of the visual modules |
| `futureplus-skybox.properties` | Skybox settings |
| `futureplus-font.properties` | Font+ settings |
| `futureplus-friends.txt` | Friend list |

## License

AGPL-3.0 (`LICENSE`).
