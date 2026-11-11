package com.futureplus.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * FakeFly (server side): a chestplate is always worn on the server, while the elytra glide goes on on the client.
 *
 * The server (26.1.2) handles a client tick's packets in order between two ticks. Equipment changes are
 * compared in LivingEntity.tick; an elytra put on and taken off in the same packet group is never broadcast. So
 * every "pulse" ends within one client tick:
 *   LocalPlayer.tick HEAD  : put on elytra + START_FALL_FLYING     (the glide starts on the server)
 *   during the tick        : movement packet                       (isFallFlying: the floating counter resets,
 *                                                                    speed limit 300)
 *   LocalPlayer.tick RETURN: firework (when due) + chestplate back (FireworkRocketItem.use needs a glide)
 * Vanilla server tick order: level.tick (entity data broadcast) -> tickConnection (player doTick). If the glide flag
 * stays on in the packet group, the next broadcast can go out "gliding" for a tick; with "Instant glide stop" on, the pulse
 * closes with a second START_FALL_FLYING (arriving mid-glide, the server calls stopFallFlying). Grim takes that as the moment
 * the glide ends and pulls back on the climb (2btr test: 0 -> 96 pullbacks in Positioning), so it is off by default.
 *
 * On the client the glide flag comes from the server as "false"; while held it is pressed back at the start of every tick
 * (the firework boost only applies while the client is gliding).
 *
 * Server view: the server sends us our own pose and shared flags too (ClientboundSetEntityDataPacket).
 * They are kept in serverPose / serverFallFlying (debug log). The local model is always drawn upright while FakeFly is on
 * (HumanoidRenderFakeFlyMixin, AvatarRenderFakeFlyMixin, EntityFakeFlyMixin). If the server, even for a moment,
 * sends a glide or crawl pose, that counts as a "leak" and is written to chat with Debug on. Note: the packet we get
 * may not be the same as the broadcast to other players (Grim sends its own sync packets only to the player); for a sure
 * result look from a second account.
 *
 * With MaceAura on, FakeFly is forced on; MaceAura opens the pulse loop while climbing/taking off again,
 * asks for a firework with requestRocket, and lets go of the glide with release on the dive (the fall builds up on the server uninterrupted).
 */
public final class FakeFly {
    private FakeFly() {}

    private static boolean manuallyEnabled, maceForced;
    private static KeyMapping key;
    private static int tick, nextPulseTick, nextBoostTick, lastPulseTick = -100;
    private static int swapInterval = 2, fireworkInterval = 20;
    private static boolean holding, pulseOpen, rocketRequested, startRequested, pulseRequested, pulseRocket;
    private static boolean debug, instantStop;
    private static boolean uprightCamera = true;
    /** CameraFakeFlyMixin: while gliding the camera is at standing eye height (same as the upright model). */
    public static boolean uprightCamera() { return uprightCamera && isEnabled(); }
    private static volatile Pose serverPose = Pose.STANDING;
    private static volatile boolean serverFallFlying;
    private static int pulses, rockets, leaks, lastReportTick;

    public static boolean isEnabled() { return manuallyEnabled || maceForced; }
    public static boolean isManuallyEnabled() { return manuallyEnabled; }
    public static boolean isForcedByMaceAura() { return maceForced; }
    public static KeyMapping getKeyMapping() { return key; }
    /** The client glide is held by FakeFly (a chestplate is worn on the server). */
    public static boolean isHolding() { return holding && isEnabled(); }
    /** The last pose the server sent us (and other players). */
    public static Pose serverPose() { return serverPose; }
    /** The last glide flag the server sent (shared flag 7). */
    public static boolean serverFallFlying() { return serverFallFlying; }
    public static int leaks() { return leaks; }

    /** On the last client tick the server glided briefly (the fall distance may be clipped that tick). */
    public static boolean pulsedLastTick() { return tick - lastPulseTick <= 1; }

    public static void setEnabled(boolean value) {
        if (manuallyEnabled == value) return;
        manuallyEnabled = value;
        if (!isEnabled()) onDisable();
        else resetServerView();
        nextPulseTick = nextBoostTick = tick;
    }

    public static void setMaceForced(boolean value) {
        if (maceForced == value) return;
        maceForced = value;
        if (!isEnabled()) onDisable();
        else resetServerView();
        nextPulseTick = nextBoostTick = tick;
    }

    private static void resetServerView() {
        LocalPlayer p = Minecraft.getInstance().player;
        serverPose = p == null || p.isFallFlying() ? Pose.STANDING : p.getPose();
        serverFallFlying = false;
        pulses = rockets = leaks = 0;
        lastReportTick = tick;
    }

    /**
     * ClientPacketListenerMixin (main thread): a data packet for us. Flag 0 = shared flags (bit 7 glide),
     * the Pose typed value = DATA_POSE.
     */
    public static void onServerData(ClientboundSetEntityDataPacket packet) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        if (packet.id() != p.getId()) { observe(p, packet); return; }
        for (SynchedEntityData.DataValue<?> v : packet.packedItems()) {
            if (v.id() == 0 && v.value() instanceof Byte flags) serverFallFlying = (flags & (1 << 7)) != 0;
            else if (v.value() instanceof Pose pose) serverPose = pose;
        }
        if (!isEnabled()) return;
        boolean crawl = serverPose == Pose.SWIMMING && !p.isInWater();
        if (serverFallFlying || serverPose == Pose.FALL_FLYING || crawl) {
            leaks++;
            log(String.format(Locale.ROOT, "LEAK #%d: self packet pose=%s fallFlying=%s (holding=%s chest=%s)", leaks,
                serverPose, serverFallFlying, holding, isElytra(p.getItemBySlot(EquipmentSlot.CHEST)) ? "elytra" : "other"));
        }
    }

    /**
     * ClientPacketListenerMixin (RETURN): the server sends us our own pose (STANDING) and the glide flag off;
     * the client applies them to the local player. While the glide was held, this changed the box between 0.6 <-> 1.8 blocks and
     * the eye height between ticks and changed them back on the next tick (screen stutter). The held state is put back
     * right away; local only, nothing goes to the server.
     */
    public static void afterServerData(ClientboundSetEntityDataPacket packet) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || packet.id() != p.getId() || !isHolding()) return;
        if (!p.isFallFlying()) p.startFallFlying();
        if (p.getPose() != Pose.FALL_FLYING) p.setPose(Pose.FALL_FLYING);
    }

    private static int setbacks;

    /**
     * ClientPacketListenerMixin (main thread): the server corrected our position (Grim setback). With Debug on it is written
     * together with the pulse timing: which pulse step the setback came after is read from here.
     */
    public static void onServerTeleport(net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket packet) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (!debug || !isEnabled() || p == null) return;
        setbacks++;
        net.minecraft.world.phys.Vec3 to = packet.change().position(), dv = packet.change().deltaMovement();
        log(String.format(Locale.ROOT,
            "SETBACK #%d id=%d: client=(%.2f,%.2f,%.2f) server=(%.2f,%.2f,%.2f) serverVel=(%.2f,%.2f,%.2f) rel=%s | sinceLastPulse=%dt holding=%s gliding=%s chest=%s vel=(%.2f,%.2f,%.2f) ground=%s serverPose=%s serverFallFlying=%s",
            setbacks, packet.id(), p.getX(), p.getY(), p.getZ(), to.x, to.y, to.z, dv.x, dv.y, dv.z, packet.relatives(), tick - lastPulseTick, holding, p.isFallFlying(),
            isElytra(p.getItemBySlot(EquipmentSlot.CHEST)) ? "elytra" : MaceAura.isChestplate(p.getItemBySlot(EquipmentSlot.CHEST)) ? "chestplate" : "other",
            p.getDeltaMovement().x, p.getDeltaMovement().y, p.getDeltaMovement().z, p.onGround(), serverPose, serverFallFlying));
    }

    // ------------------------------------------------------------- izleyici (ikinci hesap)

    private static final java.util.Map<Integer, String> observed = new java.util.HashMap<>();

    /**
     * With Debug on, the glide flag, pose and chest equipment the server broadcasts for OTHER players are written as they
     * change. Turned on from a second account, it logs exactly how the main account looks to other players.
     */
    private static void observe(LocalPlayer self, ClientboundSetEntityDataPacket packet) {
        if (!debug || Minecraft.getInstance().level == null) return;
        if (!(Minecraft.getInstance().level.getEntity(packet.id()) instanceof net.minecraft.world.entity.player.Player other)) return;
        Boolean fly = null; Pose pose = null;
        for (SynchedEntityData.DataValue<?> v : packet.packedItems()) {
            if (v.id() == 0 && v.value() instanceof Byte flags) fly = (flags & (1 << 7)) != 0;
            else if (v.value() instanceof Pose pp) pose = pp;
        }
        if (fly == null && pose == null) return;
        String prev = observed.getOrDefault(other.getId(), "fallFlying=false pose=STANDING");
        boolean prevFly = prev.contains("fallFlying=true");
        String prevPose = prev.substring(prev.indexOf("pose=") + 5);
        String now = "fallFlying=" + (fly != null ? fly : prevFly) + " pose=" + (pose != null ? pose : prevPose);
        observed.put(other.getId(), now);
        if (!now.equals(prev)) log(String.format(Locale.ROOT, "OBSERVE %s: %s chest=%s dist=%.1f", other.getPlainTextName(), now,
            chestName(other), other.distanceTo(self)));
    }

    /** ClientPacketListenerMixin: another player's equipment packet (written if the chest slot changed). */
    public static void onServerEquipment(int entityId, java.util.List<com.mojang.datafixers.util.Pair<EquipmentSlot, ItemStack>> slots) {
        if (!debug || Minecraft.getInstance().level == null || Minecraft.getInstance().player == null) return;
        if (entityId == Minecraft.getInstance().player.getId()) return;
        if (!(Minecraft.getInstance().level.getEntity(entityId) instanceof net.minecraft.world.entity.player.Player other)) return;
        for (var pair : slots) if (pair.getFirst() == EquipmentSlot.CHEST)
            log(String.format(Locale.ROOT, "OBSERVE %s: chest -> %s", other.getPlainTextName(), describe(pair.getSecond())));
    }

    private static String chestName(net.minecraft.world.entity.player.Player pl) { return describe(pl.getItemBySlot(EquipmentSlot.CHEST)); }

    private static String describe(ItemStack s) {
        if (s.isEmpty()) return "empty";
        if (isElytra(s) || s.has(DataComponents.GLIDER)) return "ELYTRA";
        return MaceAura.isChestplate(s) ? "chestplate" : s.getItem().toString();
    }

    private static void log(String m) {
        if (!debug) return;
        System.out.println("[Futureplus] FakeFly: " + m);
        DebugLog.write("FF", m);
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) p.sendSystemMessage(FontMod.comp("[FF] " + m));
    }

    /** MaceAura: use a firework in this tick's pulse. */
    public static void requestRocket() { rocketRequested = true; }

    /** MaceAura: open a pulse this tick without waiting for the interval (to clip the fall distance on the server). */
    public static void requestPulse() { pulseRequested = true; }

    /** MaceAura: start the glide with a pulse while in the air (the elytra is worn on the server only in this packet group). */
    public static void requestStart() { startRequested = true; }

    /** MaceAura: let go of the glide (dive cut / hit). */
    public static void release() { holding = false; }

    public static List<Setting> getSettings() {
        List<Setting> settings = new ArrayList<>();
        settings.add(Setting.num("Swap interval (ticks)", 2, 40, 1, () -> swapInterval, v -> swapInterval = (int) v));
        settings.add(Setting.num("Firework interval", 5, 60, 1, () -> fireworkInterval, v -> fireworkInterval = (int) v));
        settings.add(Setting.bool("Instant glide stop", () -> instantStop, v -> instantStop = v));
        settings.add(Setting.bool("Upright camera", () -> uprightCamera, v -> uprightCamera = v));
        settings.add(Setting.bool("Debug", () -> debug, v -> debug = v));
        return settings;
    }

    public static void init() {
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("futureplus", "fakefly"));
        key = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.futureplus.fakefly",
            InputConstants.Type.KEYSYM, InputConstants.KEY_G, category));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> { while (key.consumeClick()) setEnabled(!manuallyEnabled); });
    }

    // ------------------------------------------------------------- LocalPlayer.tick hooks (LocalPlayerMaceMixin)

    private static boolean usable(Minecraft mc, LocalPlayer p) {
        return p == mc.player && mc.level != null && mc.gameMode != null && mc.getConnection() != null;
    }

    /** HEAD, before MaceAura: press the held glide back (the server may have turned the flag off). */
    public static void beforeTick(LocalPlayer p) {
        Minecraft mc = Minecraft.getInstance();
        if (!usable(mc, p)) return;
        if (!isEnabled()) { holding = false; clearStaleGlide(p); return; }
        tick++;
        if (tick - lastReportTick >= 20) {
            lastReportTick = tick;
            if (pulses > 0 || holding) log(String.format(Locale.ROOT,
                "server view: pose=%s fallFlying=%s | client: holding=%s gliding=%s chest=%s | 20t: pulses=%d rockets=%d | leaks=%d",
                serverPose, serverFallFlying, holding, p.isFallFlying(),
                isElytra(p.getItemBySlot(EquipmentSlot.CHEST)) ? "elytra" : MaceAura.isChestplate(p.getItemBySlot(EquipmentSlot.CHEST)) ? "chestplate" : "other",
                pulses, rockets, leaks));
            pulses = rockets = 0;
        }
        if (!holding) { clearStaleGlide(p); return; }
        if (p.onGround() || p.isInWater() || p.isPassenger() || p.getAbilities().flying || !p.isAlive()) {
            holding = false;
            clearStaleGlide(p);
            return;
        }
        if (!p.isFallFlying()) p.startFallFlying();
    }

    /**
     * The client never turns the glide flag off itself (LivingEntity.updateFallFlying only runs on the server) and
     * since the flag is already off on the server, no data packet comes to fix it either. If we aren't holding it and there is no glide
     * item on the chest, the flag is stale: if not cleared you stay in the FALL_FLYING pose on the ground (like crawling), and MaceAura
     * thinks we're gliding and climbs instead of taking off (a firework boost on the client only, the server pulls back).
     */
    private static void clearStaleGlide(LocalPlayer p) {
        if (p.isFallFlying() && !p.getItemBySlot(EquipmentSlot.CHEST).has(DataComponents.GLIDER)) p.stopFallFlying();
    }

    /** HEAD, after MaceAura (before the movement packet): open the pulse. */
    public static void preTick(LocalPlayer p) {
        Minecraft mc = Minecraft.getInstance();
        pulseOpen = false;
        boolean startWanted = startRequested, forced = pulseRequested;
        startRequested = pulseRequested = false;
        if (!usable(mc, p) || !isEnabled()) { rocketRequested = false; return; }
        if (p.isPassenger() || p.isInWater() || p.getAbilities().flying || p.onGround()
            || p.containerMenu != p.inventoryMenu || !p.containerMenu.getCarried().isEmpty()) return;
        // Two pulses in a row: the server broadcasts the flag on for two+ ticks, the counter grows on other clients and the body
        // shows horizontal (like crawling) with the chest armor. Requests are pushed to the next tick.
        if (tick - lastPulseTick < 2 && !isElytra(p.getItemBySlot(EquipmentSlot.CHEST))) {
            startRequested |= startWanted;
            pulseRequested |= forced;
            return;
        }

        boolean mace = MaceAura.isControllingFlight();
        // no pulse on the dive (the fall builds up); if MaceAura still asks for a firework or take off, a single pulse opens
        if (mace && !MaceAura.fakeFlyCruise() && !rocketRequested && !startWanted && !forced) return;

        boolean wearingElytra = isElytra(p.getItemBySlot(EquipmentSlot.CHEST));
        if (find(p, MaceAura::isChestplate) < 0 && !MaceAura.isChestplate(p.getItemBySlot(EquipmentSlot.CHEST))) return;

        boolean start = !holding && !(p.isFallFlying() && wearingElytra)
            && (mace ? startWanted : p.getDeltaMovement().y < -0.04);
        boolean gliding = holding || (p.isFallFlying() && wearingElytra);
        boolean boostDue = mace ? rocketRequested : tick >= nextBoostTick;
        rocketRequested = false;
        if (!start && !gliding) return;
        if (!start && tick < nextPulseTick && !boostDue && !wearingElytra && !forced) return;

        if (!wearingElytra) {
            int elytra = find(p, FakeFly::isElytra);
            if (elytra < 0) return;
            equip(mc, p, elytra);
            if (!isElytra(p.getItemBySlot(EquipmentSlot.CHEST))) return;
        }
        if (!p.isFallFlying()) p.tryToStartFallFlying();
        // the glide closed on the server on the previous tick: START_FALL_FLYING opens it again in this packet group
        if (!wearingElytra || holding)
            mc.getConnection().send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
        pulseOpen = true;
        pulseRocket = boostDue;
        lastPulseTick = tick;
        pulses++;
        nextPulseTick = tick + swapInterval;
    }

    /** RETURN (the movement packet went out): firework + chestplate back; hold the client glide. */
    public static void postTick(LocalPlayer p) {
        Minecraft mc = Minecraft.getInstance();
        if (!pulseOpen || !usable(mc, p)) { pulseOpen = false; return; }
        pulseOpen = false;
        if (pulseRocket) {
            boost(mc, p);
            if (!MaceAura.isControllingFlight()) nextBoostTick = tick + fireworkInterval;
        }
        pulseRocket = false;
        int chest = find(p, MaceAura::isChestplate);
        if (chest >= 0 && isElytra(p.getItemBySlot(EquipmentSlot.CHEST))) equip(mc, p, chest);
        holding = p.isFallFlying() && MaceAura.isChestplate(p.getItemBySlot(EquipmentSlot.CHEST));
        // START_FALL_FLYING arriving mid-glide: tryToStartFallFlying false -> stopFallFlying, the flag closes at once.
        if (holding && instantStop)
            mc.getConnection().send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
    }

    /** Firework while the elytra is worn (the server already started the glide in this packet group). */
    private static void boost(Minecraft mc, LocalPlayer p) {
        int rocket = hotbarOrOffhandRocket(p);
        if (rocket == -1) return;
        if (rocket == -2) mc.gameMode.useItem(p, InteractionHand.OFF_HAND);
        else useFromInventory(mc, p, rocket);
        p.swing(rocket == -2 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
        rockets++;
    }

    /** If we are holding a glide in the air while turning off, really put the elytra on (so we don't fall and die). */
    private static void onDisable() {
        boolean wasHolding = holding;
        holding = false;
        pulseOpen = false;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return;
        if (!wasHolding || mc.gameMode == null || mc.getConnection() == null || p.onGround()
            || p.containerMenu != p.inventoryMenu || isElytra(p.getItemBySlot(EquipmentSlot.CHEST))) { clearStaleGlide(p); return; }
        int elytra = find(p, FakeFly::isElytra);
        if (elytra < 0) { clearStaleGlide(p); return; }
        equip(mc, p, elytra);
        mc.getConnection().send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
    }

    private static boolean isElytra(ItemStack stack) {
        return !stack.isEmpty() && stack.has(DataComponents.GLIDER)
            && net.minecraft.world.entity.LivingEntity.canGlideUsing(stack, EquipmentSlot.CHEST)
            && !(stack.isDamageableItem() && stack.getMaxDamage() - stack.getDamageValue() <= 2);
    }

    private static int find(LocalPlayer p, java.util.function.Predicate<ItemStack> test) {
        for (int i = 0; i < 36; i++)
            if (test.test(p.getInventory().getItem(i))) return i;
        return -1;
    }

    /** -2 = offhand, -1 = none, otherwise inventory index. */
    private static int hotbarOrOffhandRocket(LocalPlayer p) {
        if (p.getOffhandItem().is(Items.FIREWORK_ROCKET)) return -2;
        return find(p, stack -> stack.is(Items.FIREWORK_ROCKET));
    }

    private static void useFromInventory(Minecraft mc, LocalPlayer p, int slot) {
        int selected = p.getInventory().getSelectedSlot();
        if (slot == selected) {
            mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
            return;
        }
        if (slot < 0 || slot > 35 || !p.containerMenu.getCarried().isEmpty()) return;
        int menuSlot = slot < 9 ? slot + 36 : slot;
        int menu = p.inventoryMenu.containerId;
        mc.gameMode.handleContainerInput(menu, menuSlot, selected, ContainerInput.SWAP, p);
        try { mc.gameMode.useItem(p, InteractionHand.MAIN_HAND); }
        finally { mc.gameMode.handleContainerInput(menu, menuSlot, selected, ContainerInput.SWAP, p); }
    }

    private static void equip(Minecraft mc, LocalPlayer p, int slot) {
        if (slot < 0 || slot > 35 || !p.containerMenu.getCarried().isEmpty()) return;
        int menu = p.inventoryMenu.containerId;
        if (slot < 9 && slot != p.getInventory().getSelectedSlot()) {
            mc.gameMode.handleContainerInput(menu, 6, slot, ContainerInput.SWAP, p);
        } else {
            int inventorySlot = slot < 9 ? slot + 36 : slot;
            mc.gameMode.handleContainerInput(menu, inventorySlot, 0, ContainerInput.PICKUP, p);
            mc.gameMode.handleContainerInput(menu, 6, 0, ContainerInput.PICKUP, p);
            mc.gameMode.handleContainerInput(menu, inventorySlot, 0, ContainerInput.PICKUP, p);
        }
    }
}
