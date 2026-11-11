package com.futureplus.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * MaceAura v8: hotbar controlled firework dive with an instant hit.
 *
 *  0. Prep     : an enemy is searched every tick (no wait without a target). If there's no mace + firework + elytra in the hotbar
 *                (or worn) and a chestplate (in the hotbar or worn), the module does NOT turn off, it waits; the tick the items
 *                are complete it goes on the attack.
 *  Instant hit: on the dive, if eye -> target box is within hit range, in the SAME tick:
 *                chestplate (silent hotbar swap) -> START_FALL_FLYING packet (if the server is already gliding,
 *                tryToStartFallFlying returns false and calls stopFallFlying: the glide ends on the server at once)
 *                -> mace (silent swap) -> smash. No wait tick.
 *
 *  1. Track    : velocity + acceleration from the target's last positions; lag compensation by ping (walking/running/jumping/flying).
 *  2. Position : with elytra + fireworks, to point Q, A blocks above the target's predicted spot, from where it can dive steeper than "Dive angle".
 *  3. Dive     : with the firework active it dives looking at least 25° down; since vertical speed stays faster than -0.5 on the server,
 *                the fall distance builds up while gliding too. Each tick it steers to the intercept point (our speed x the target's path).
 *  4. Release  : every tick an "if I equipped a chestplate now" ballistic simulation; if the target enters range on the earliest
 *                hittable tick, the chestplate goes on (inventory click, the tool in hand doesn't change).
 *  5. Hit      : after the server ends the glide (Swap tick), a rotation packet facing the target + a smash with the mace.
 *  6. Loop     : elytra back, glide, up with a firework, again.
 *
 * Mace damage (26.1.2 MaceItem): d<=3: 4d, d<=8: 12+2(d-3), beyond 22+(d-8); Density: +0.5*level*d.
 * For a smash: fall > 1.5 and not gliding (MaceItem.canSmashAttack).
 * Needed: a mace, elytra, chestplate and fireworks anywhere in the inventory. The offhand is left free for a totem;
 * the mace and fireworks are used with inventory swaps (without changing the selected slot).
 */
public final class MaceAura {
    private MaceAura() {}

    // ------------------------------------------------------------- ayarlar
    public static final String[] MODES = {"Auto", "Manual"};
    public static final String[] ROT_MODES = {"Packet", "Camera"};

    static int mode = 0;
    static double range = 40;
    static double height = 25;                 // Kontrol
    static double minHeight = 10, maxHeight = 28, extraHeight = 1;   // Oto
    static double hitRange = 4.5;
    /*
     * NCP compatible (per the Updated-NoCheatPlus docs, sources in the MaceAura answer):
     *  - Fight.Reach: the client is 3.0 blocks, the server accepts more with a latency margin; NCP "reduce" punishes repeated hits
     *    at the limit -> effective hit range at most 3.6.
     *  - Fight.Direction: at the moment of the hit the player's LOOK must pass through the target's box -> if the last sent angle
     *    doesn't touch the target, don't hit, wait a tick.
     *  - Combined.YawRate: a very fast yaw turn right before a hit gives a penalty/Improbable -> yaw <= 20 degrees per tick.
     *  - Inventory.InventoryMove/FastClick: an inventory click while moving is an "impossible click" -> the armor swap
     *    is done not with an inventory click but by RIGHT CLICKING it from the hotbar to wear it, as in vanilla (Equippable swap).
     *    For that the elytra and chestplate have to be in the hotbar.
     */
    static boolean ncp = true;
    static boolean debug = false;
    static boolean instant = true;            // in range, chestplate + mace + smash in the same tick
    /*
     * FAST DIVE: NO firework is used on the dive. A firework pulls elytra speed to ~1.5-2 blocks/tick in the look direction; on a steep dive
     * that slows it down. A steep glide without fireworks reaches ~3.5-4 blocks/tick with gravity. Positioning is steeper too (at least 65°).
     * FAST ESCAPE: elytra back + glide + firework in the SAME tick as the hit: not even 1 tick is spent near the ground, the enemy
     * gets no chance to blow a crystal. The cost: while escaping an elytra is worn instead of a chestplate (low armor), so it leaves right away.
     */
    static boolean fastDive = true, fastEscape = true;
    static boolean grimCompat = false;
    static boolean autoBestAngle = true;
    static double hr() { return ncp ? Math.min(hitRange, 3.8) : hitRange; }
    private static double bestDiveAngle(double altitude, double horizontal, double verticalSpeed) {
        double geometric = Math.toDegrees(Math.atan2(Math.max(1.0, altitude - 1.5), Math.max(1.0, horizontal)));
        double momentumBrake = Math.max(0.0, -verticalSpeed) * 2.0;
        return Mth.clamp(geometric - momentumBrake, 35.0, 55.0);
    }
    private static double diveAbortHeight(double horizontalSpeed) {
        // The old 2 + speed*2 threshold aborted at ~5 blocks, often before
        // the target entered the predicted mace-hit window.
        return Math.max(3.0, horizontalSpeed * 1.7);
    }
    private static int effectiveFireworkDelay() { return grimCompat ? Math.max(16, fireworkDelay) : fireworkDelay; }
    /*
     * Attack range (eye -> target box <= 3.0). The last argument of Player.isWithinAttackRange is NOT the range,
     * it is a latency margin added to the range: the vanilla server accepts up to 6 blocks with a 3.0 margin, while Grim's Reach check
     * silently cancels anything past 3 blocks (2btr test: 4 hits went from 4-6 blocks, none counted).
     */
    private static boolean inAttackRange(LocalPlayer p, int mace, AABB box) {
        return p.isWithinAttackRange(p.getInventory().getItem(mace), box, 0.0)
            && boxDist(p.getEyePosition(), box) <= Math.min(hr(), 3.0);
    }
    private static int findMaceSlot(LocalPlayer p) {
        for (int slot = 0; slot < p.getInventory().getContainerSize(); slot++)
            if (p.getInventory().getItem(slot).is(Items.MACE)) return slot;
        return p.getInventory().getSelectedSlot();
    }
    /*
     * HOVER LOOP: approach the open point above the target's pop altitude (or Hover altitude) (APPROACHING), once inside
     * the dive cone climb steeply (ASCENDING, -CLIMB_PITCH + firework), and if the gap is enough and the attack cooldown is over
     * dive (keep climbing in place while the cooldown runs); at DIVE_CUT_GAP cut the glide, wear the chestplate, smash.
     * After the hit, elytra + START_FALL_FLYING + firework in the same tick and straight up from above the target.
     */
    public static final String[] FLIGHT_MODES = {"Hover loop", "Angle dive"};
    static final double HOVER_ALTITUDE = 16.0;
    static final double POSITION_THRESHOLD = 2.5;
    static final double DIVE_CUT_GAP = 5.0;
    static final double SMASH_FALL_DISTANCE = 1.5;
    /*
     * Shortest time between hits. Before, the mace's attack cooldown (34 ticks) was awaited, and if the cooldown
     * wasn't over it climbed up to Max. height (log: first dive from 11, the rest always from 28 blocks, a loop of ~37 ticks).
     * The smash bonus doesn't scale with attack strength (Player.attack, getAttackDamageBonus is added after scaling);
     * an under-charged hit only loses the small base damage, and popAltitude accounts for it at its worst.
     * The limit is the target's invulnerability: for 10 ticks after damage, damage that isn't larger is ignored.
     */
    static final float MIN_HIT_INTERVAL = 11.0f;
    static final float CLIMB_PITCH = 80.0f;
    static final float VERTICAL_DIVE_PITCH = 89.0f;
    static int flightMode = 0;
    static double hoverAltitude = HOVER_ALTITUDE;
    /*
     * POP ALTITUDE (Auto mode): instead of a fixed Hover altitude, the shortest fall that beats the target's health + absorption (enough to
     * pop a totem) in one hit is computed; since we'll be at most range above it at the hit, that margin is added too.
     * Limited by Max. height. On reaching this altitude it dives if inside DIVE_CONE, without waiting to be right above the target.
     */
    static final double DIVE_CONE = 0.7;       // if horizontal <= gap * 0.7 (steeper than ~55°) the firework dive hits from there
    static double attackHeight = 1.5;
    static double delayMultiplier = 1.0;
    /*
     * Dive style (hover loop):
     *  Powered  : the FakeFly glide is held, pulses go on (Grim accepts the glide), fireworks are used looking down,
     *             the direction is corrected by gliding; at DIVE_CUT_GAP the glide is let go. Always a chestplate on the server, upright pose.
     *  Free fall: free fall from the top (bomb drop), direction only with the forward key.
     */
    public static final String[] DIVE_STYLES = {"Powered", "Free fall"};
    static int diveStyle = 0;
    static double diveSpeed = 2.0;
    private static boolean hoverLoop() { return flightMode == 0; }
    private static boolean poweredDive() { return hoverLoop() && diveStyle == 0; }

    static double minFall = 3.0;
    static int swapTicks = 2;
    static int toolPenalty = 11;              // NCP fight.toolchangepenalty 500 ms = 10 ticks (+1 margin)
    static int rotMode = 0;
    static double rotSpeed = 60;
    static boolean simulate = true, coverScan = true, silent = true, autoFirework = true;
    // Through walls: the vanilla server doesn't check line of sight on an attack (range only); the block ray check is skipped
    static boolean throughWalls = true;
    static double flySpeed = 1.6;
    static int fireworkDelay = 8;
    static double divePitch = 40;             // the dive starts at least at this angle (down)
    static boolean lagComp = true, themeColor = true, renderPath = true;
    static boolean skipFriends = true, skipNaked = false, skipInvisible = false;
    static boolean targetPlayers = true, targetAnimals = false, targetMonsters = false;
    static boolean renderBox = true, renderLine = true;
    static int renderColor = 0xD8343A;

    private static boolean enabled = false;
    private static KeyMapping key;
    private static int lastDebugSnapshot = -1;
    private static volatile int takeoffPauseUntil = -1;
    private static boolean jumpKeyPulse, jumpKeyWasDown;
    private static int climbSideTarget = -1;
    private static Vec3 climbSide = Vec3.ZERO;
    private static int climbAnchorTarget = -1;
    private static Vec3 climbAnchor = null;
    private static List<Vec3> navPath = List.of();
    private static int navPathIndex, navTargetId = Integer.MIN_VALUE;
    private static Vec3 navGoal;
    private static double approachHeightOffset;
    private static boolean navUnreachable;
    private static int navFailureUntil;
    private static boolean rearmBoostPending;
    private static int attackCooldownLeft;              // MIN_HIT_INTERVAL * Delay multiplier
    private static boolean attackedThisDive;
    private static Vec3 preCutVelocity = Vec3.ZERO;     // speed before the glide is cut (momentum compensation)
    private static String hoverPhase;                   // HUD: Approaching / Ascending / Orbit

    public static boolean isEnabled() { return enabled; }
    /** True while MaceAura owns chest-slot and Elytra state transitions. */
    public static boolean isControllingFlight() { return enabled && st != St.IDLE; }
    public static void setEnabled(boolean v) {
        if (v == enabled) return;
        enabled = v;
        FakeFly.setMaceForced(v);
        if (!v) { if (debug) dbg("disabled by user"); onDisable(); }
        else {
            st = St.IDLE; target = null; failed = false; missingMsg = null; lastDebugSnapshot = -1; takeoffPauseUntil = -1;
            climbSideTarget = -1; climbSide = Vec3.ZERO;
            climbAnchorTarget = -1; climbAnchor = null;
            navPath = List.of(); navPathIndex = 0; navTargetId = Integer.MIN_VALUE; navGoal = null; approachHeightOffset = 0; navUnreachable = false; navFailureUntil = 0;
            attackCooldownLeft = 0; attackedThisDive = false; preCutVelocity = Vec3.ZERO; hoverPhase = null;
            if (debug) dbg("enabled; waiting for tick diagnostics");
        }
    }
    public static KeyMapping getKeyMapping() { return key; }

    public static List<Setting> getSettings() {
        List<Setting> s = new ArrayList<>();
        s.add(Setting.choice("Mode", MODES, () -> mode, v -> mode = v));
        s.add(Setting.choice("Flight", FLIGHT_MODES, () -> flightMode, v -> flightMode = v));
        s.add(Setting.num("Hover altitude", 8, 40, 1, () -> hoverAltitude, v -> hoverAltitude = v).when(() -> mode == 1 && hoverLoop()));
        s.add(Setting.choice("Dive style", DIVE_STYLES, () -> diveStyle, v -> diveStyle = v).when(() -> hoverLoop()));
        s.add(Setting.num("Dive speed", 1, 3, 0.1, () -> diveSpeed, v -> diveSpeed = v).when(() -> poweredDive()));
        s.add(Setting.num("Attack height", 0, 6, 0.5, () -> attackHeight, v -> attackHeight = v));
        s.add(Setting.num("Delay multiplier", 0.1, 3, 0.1, () -> delayMultiplier, v -> delayMultiplier = v));
        s.add(Setting.num("Range", 8, 128, 1, () -> range, v -> range = v));
        s.add(Setting.num("Height", 5, 100, 1, () -> height, v -> height = v).when(() -> mode == 1 && flightMode == 1));
        s.add(Setting.num("Min. height", 5, 50, 1, () -> minHeight, v -> minHeight = v).when(() -> mode == 0 && flightMode == 1));
        s.add(Setting.num("Max. height", 10, 150, 1, () -> maxHeight, v -> maxHeight = v).when(() -> mode == 0));
        s.add(Setting.num("Extra height", 0, 20, 0.5, () -> extraHeight, v -> extraHeight = v));
        s.add(Setting.bool("NCP compatible", () -> ncp, v -> ncp = v));
        s.add(Setting.num("Hit range", 2, 6, 0.1, () -> hitRange, v -> hitRange = v));
        s.add(Setting.bool("Instant hit", () -> instant, v -> instant = v));
        s.add(Setting.bool("Fast dive", () -> fastDive, v -> fastDive = v));
        s.add(Setting.bool("Fast escape", () -> fastEscape, v -> fastEscape = v));
        s.add(Setting.bool("Grim compatibility", () -> grimCompat, v -> grimCompat = v));
        s.add(Setting.bool("Auto Best Angle", () -> autoBestAngle, v -> autoBestAngle = v));
        s.add(Setting.num("Min. fall distance", 1.5, 10, 0.5, () -> minFall, v -> minFall = v));
        s.add(Setting.num("Swap delay (ticks)", 1, 6, 1, () -> swapTicks, v -> swapTicks = (int) v));
        s.add(Setting.choice("Rotation", ROT_MODES, () -> rotMode, v -> rotMode = v));
        s.add(Setting.num("Rotation speed", 10, 180, 5, () -> rotSpeed, v -> rotSpeed = v));
        s.add(Setting.bool("Simulation", () -> simulate, v -> simulate = v));
        s.add(Setting.bool("Lag compensation", () -> lagComp, v -> lagComp = v));
        s.add(Setting.num("Dive angle", 30, 85, 1, () -> divePitch, v -> divePitch = v).when(() -> !autoBestAngle));
        s.add(Setting.bool("Path checking", () -> coverScan, v -> coverScan = v));
        s.add(Setting.bool("Through walls", () -> throughWalls, v -> throughWalls = v));
        s.add(Setting.bool("Silent swap", () -> silent, v -> silent = v));
        s.add(Setting.num("Tool swap penalty (ticks)", 0, 20, 1, () -> toolPenalty, v -> toolPenalty = (int) v).when(() -> ncp));
        s.add(Setting.num("Flight speed", 0.5, 3, 0.1, () -> flySpeed, v -> flySpeed = v));
        s.add(Setting.bool("Auto fireworks", () -> autoFirework, v -> autoFirework = v));
        s.add(Setting.num("Firework interval", 5, 60, 1, () -> fireworkDelay, v -> fireworkDelay = (int) v).when(() -> autoFirework));
        s.add(Setting.bool("Target players", () -> targetPlayers, v -> targetPlayers = v));
        s.add(Setting.bool("Target animals", () -> targetAnimals, v -> targetAnimals = v));
        s.add(Setting.bool("Target monsters", () -> targetMonsters, v -> targetMonsters = v));
        s.add(Setting.bool("Skip friends", () -> skipFriends, v -> skipFriends = v));
        s.add(Setting.bool("Skip unarmored", () -> skipNaked, v -> skipNaked = v));
        s.add(Setting.bool("Skip invisible", () -> skipInvisible, v -> skipInvisible = v));
        s.add(Setting.bool("Target box", () -> renderBox, v -> renderBox = v));
        s.add(Setting.bool("Dive line", () -> renderLine, v -> renderLine = v));
        s.add(Setting.bool("Render orbit", () -> renderPath, v -> renderPath = v));
        s.add(Setting.bool("Theme color", () -> themeColor, v -> themeColor = v).when(() -> renderBox || renderLine || renderPath));
        s.add(Setting.color("Color", () -> renderColor, v -> renderColor = v).when(() -> !themeColor && (renderBox || renderLine || renderPath)));
        s.add(Setting.bool("Debug", () -> debug, v -> debug = v));
        return s;
    }

    // ------------------------------------------------------------- kurulum

    /** FutureplusClient: call BEFORE PistonAura.init() (so the ,friend command is caught before its command handler). */
    public static void init() {
        KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("futureplus", "maceaura"));
        key = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.futureplus.maceaura",
            InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), cat));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> { while (key.consumeClick()) setEnabled(!enabled); });
        ClientSendMessageEvents.ALLOW_CHAT.register(MaceAura::onChat);
        loadFriends();
    }

    // ------------------------------------------------------------- durum

    enum St { IDLE, TAKEOFF, CLIMB, DIVE, SMASH, REARM }
    private static final String[] ST_NAMES = {"Idle", "Takeoff", "Positioning", "Dive", "Strike", "Rearming"};

    // our own velocity packet from the server after a smash / escape is ignored (ignoreSelfMotion)
    private static volatile int tpMotionUntil = -1, tpMotionId = -1;

    private static St st = St.IDLE;
    private static LivingEntity target;
    private static int stTicks, fwCooldown, pickCooldown, swapCooldown, hits;
    private static double diveHeight = 20;
    private static Vec3 aimPoint;
    private static boolean failed;
    private static String missingMsg;                   // items missing from the hotbar (null = all there)
    private static int lastSel = -1;
    private static int swapTick = -100000, tickCount, lastSlotChange = -100000, lastFwTick = -100000;
    private static double lastDist = 1e9, estFall, lastY;
    private static int hitTick;                         // the predicted hit tick at release (relative to the release)

    // for drawing (filled in the logic tick)
    private static volatile List<Vec3> drawPred = List.of(), drawSim = List.of();
    private static volatile Vec3 drawIntercept, drawDiveStart;

    /** The fall we want built up on the server at the hit (Auto: enough to kill the target in one hit). */
    private static double needFall() { return Math.max(minFall + 0.5, diveHeight - hr() - extraHeight); }

    // ------------------------------------------------------------- target tracking (velocity + acceleration)

    private static final Vec3[] hist = new Vec3[4];
    private static int histId = Integer.MIN_VALUE, histN;
    private static Vec3 tVel = Vec3.ZERO, tAcc = Vec3.ZERO;
    private static double lead;                         // gecikme telafisi (tick)

    /** Once per tick: velocity and acceleration from the target's last positions (crowd jitter smoothed). */
    private static void track(Minecraft mc, LivingEntity t) {
        if (t == null) { histN = 0; histId = Integer.MIN_VALUE; tVel = tAcc = Vec3.ZERO; predPath = null; return; }
        if (t.getId() != histId) { histN = 0; histId = t.getId(); }
        System.arraycopy(hist, 0, hist, 1, hist.length - 1);
        hist[0] = t.position();
        if (histN < hist.length) histN++;
        if (histN >= 3) {
            Vec3 v1 = hist[0].subtract(hist[1]), v2 = hist[1].subtract(hist[2]);
            tVel = v1.scale(0.65).add(v2.scale(0.35));
            Vec3 a = v1.subtract(v2);
            double al = a.length();
            tAcc = al > 0.12 ? a.scale(0.12 / al) : a;          // so a sudden stop/teleport doesn't ruin the prediction
            if (tVel.length() > 4) tVel = Vec3.ZERO;           // teleported
        } else if (histN == 2) {
            tVel = hist[0].subtract(hist[1]); tAcc = Vec3.ZERO;
        }
        if (t.onGround()) { tVel = new Vec3(tVel.x, 0, tVel.z); tAcc = new Vec3(tAcc.x, 0, tAcc.z).scale(0.5); }
        lead = 0;
        if (lagComp && mc.getConnection() != null) {
            PlayerInfo pi = mc.getConnection().getPlayerInfo(mc.player.getUUID());
            int ms = pi != null ? pi.getLatency() : 80;
            lead = Mth.clamp(ms / 50.0 + 1.0, 0, 8);           // round trip + the remote player's interpolation delay
        }
        predPath = simulate ? simulateTargetMovement(mc, t, PRED_TICKS) : null;
    }

    private static final int PRED_TICKS = 40;
    private static Vec3[] predPath;                     // track() fills it once per tick; predict() interpolates

    /** The target's position ticks later (physics simulation; lag compensation included). */
    private static Vec3 predict(LivingEntity t, double ticks) {
        Vec3 pos = t.position();
        Vec3[] path = predPath;
        if (!simulate || path == null || t.getId() != histId) return pos;
        double k = Mth.clamp(ticks + lead, 0, PRED_TICKS);
        int i = (int) Math.floor(k);
        if (i >= PRED_TICKS) return path[PRED_TICKS];
        return path[i].lerp(path[i + 1], k - i);
    }

    /**
     * Simulation of the target's movement (tick by tick):
     *  - acceleration = current velocity - previous velocity (smoothed in track, valid short term)
     *  - if horizontal velocity is aligned with the look direction, a 25% look + 75% velocity mix, else the raw velocity
     *  - a state multiplier is applied to the acceleration: sprinting 1.3, sneaking 0.3, elytra 1.5, airborne 0.95, walking 1.0; a standing target's
     *    leftover drift is damped by 0.1
     *  - gravity -0.08 (elytra: -0.04, terminal speed limited), air drag 0.98, ground friction 0.6; the player's input
     *    keeps the measured speed (push = v0 * (1 - f) / f), friction only damps the change in speed
     *  - the AABB is swept axis by axis; if it hits on X or Z that axis is zeroed, and movement goes on sliding along the open axis
     *    (0.6 block step up included)
     * Returned array [0] = current position, [n] = n ticks later.
     */
    private static Vec3[] simulateTargetMovement(Minecraft mc, LivingEntity t, int simulationTicks) {
        Vec3[] out = new Vec3[simulationTicks + 1];
        Vec3 pos = t.position();
        out[0] = pos;
        boolean elytra = t.isFallFlying();
        boolean ground = t.onGround();
        Vec3 vel = tVel, acc = tAcc;
        Vec3 hv = new Vec3(vel.x, 0, vel.z);
        Vec3 look = t.getLookAngle();
        Vec3 hl = new Vec3(look.x, 0, look.z);
        if (hv.lengthSqr() > 1.0e-4 && hl.lengthSqr() > 1.0e-4) {
            Vec3 hvn = hv.normalize(), hln = hl.normalize();
            if (hvn.dot(hln) > 0.5) {
                Vec3 blend = hln.scale(0.25).add(hvn.scale(0.75)).normalize().scale(hv.length());
                vel = new Vec3(blend.x, vel.y, blend.z);
            }
        }
        boolean stationary = hv.lengthSqr() < 0.0004 && !elytra;
        double mod = elytra ? 1.5 : t.isCrouching() ? 0.3 : t.isSprinting() ? 1.3 : !ground ? 0.95 : stationary ? 0.1 : 1.0;
        if (stationary) vel = new Vec3(vel.x * 0.1, vel.y, vel.z * 0.1);
        Vec3 intent = new Vec3(vel.x, 0, vel.z);               // the input speed the player keeps up
        AABB box = t.getBoundingBox();
        for (int i = 1; i <= simulationTicks; i++) {
            double vx = vel.x, vy = vel.y, vz = vel.z;
            if (i <= 8) { vx += acc.x * mod; vz += acc.z * mod; if (elytra || !ground) vy += acc.y * mod * 0.5; }
            if (elytra) {
                vy = Math.max(vy - 0.04, -2.0);
                vx *= 0.99; vz *= 0.99;
            } else {
                double f = ground ? 0.6 : 0.98;
                vx = (vx + intent.x * (1 - f) / f) * f;
                vz = (vz + intent.z * (1 - f) / f) * f;
                if (!ground) vy = (vy - 0.08) * 0.98;
                else vy = Math.max(vy, 0);                     // on the ground: no vertical speed apart from the jump impulse
            }
            // sweep collision (big steps are split)
            int steps = Math.max(1, (int) Math.ceil(Math.max(Math.abs(vx), Math.max(Math.abs(vy), Math.abs(vz))) / 0.5));
            double sx = vx / steps, sy = vy / steps, sz = vz / steps;
            for (int s = 0; s < steps; s++) {
                AABB b = box.move(0, sy, 0);
                if (mc.level.noCollision(t, b)) box = b;
                else { vy = 0; sy = 0; }
                b = box.move(sx, 0, 0);
                if (mc.level.noCollision(t, b)) box = b;
                else if (ground && mc.level.noCollision(t, box.move(sx, 0.6, 0))) box = box.move(sx, 0.6, 0);
                else { vx = 0; sx = 0; }                         // X blocked: keep sliding on Z
                b = box.move(0, 0, sz);
                if (mc.level.noCollision(t, b)) box = b;
                else if (ground && mc.level.noCollision(t, box.move(0, 0.6, sz))) box = box.move(0, 0.6, sz);
                else { vz = 0; sz = 0; }                         // Z blocked: keep sliding on X
            }
            if (vx == 0) intent = new Vec3(0, 0, intent.z);
            if (vz == 0) intent = new Vec3(intent.x, 0, 0);
            boolean wasGround = ground;
            ground = !elytra && !mc.level.noCollision(t, box.move(0, -0.05, 0));
            if (ground && !wasGround) vy = 0;
            vel = new Vec3(vx, vy, vz);
            out[i] = new Vec3((box.minX + box.maxX) * 0.5, box.minY, (box.minZ + box.maxZ) * 0.5);
        }
        return out;
    }

    /**
     * Momentum compensated aim: so the dive speed doesn't swing us past behind the target.
     *  horizon      = clamp(horizontalDistance / ourHorizontalSpeed, 0.25, 3.0)
     *  futurePlayer = eye + (vx, 0, vz) * horizon
     *  aimPoint     = eye + (predictedTarget - futurePlayer)
     * Result: the point of the target's (predicted) box closest to aimPoint.
     */
    private static Vec3 momentumAim(Vec3 eye, Vec3 pv, AABB predictedBox) {
        Vec3 c = predictedBox.getCenter();
        double hd = Math.hypot(c.x - eye.x, c.z - eye.z);
        double hs = Math.hypot(pv.x, pv.z);
        double horizon = Mth.clamp(hd / Math.max(hs, 1.0e-3), 0.25, 3.0);
        Vec3 futurePlayer = eye.add(pv.x * horizon, 0.0, pv.z * horizon);
        Vec3 aim = eye.add(c.subtract(futurePlayer));
        return new Vec3(Mth.clamp(aim.x, predictedBox.minX, predictedBox.maxX),
            Mth.clamp(aim.y, predictedBox.minY, predictedBox.maxY),
            Mth.clamp(aim.z, predictedBox.minZ, predictedBox.maxZ));
    }

    /*
     * DIVE POINT: first right above the target (x, y + dive height, z); if that is covered (tree, roof, eave), tilted points
     * around the target inside the dive cone are tried; failing that, raised by 4 blocks at a time. The point is the first one from where
     * the GLIDE BOX can come down to the target's head without touching a block (before: a thin ray + vertical only:
     * a target with a roof over it was never dived on). The pathfinder solves the way from us to the point. The chosen direction is kept per
     * target; it is kept while still open, otherwise the direction changed every tick and the point jumped.
     */
    private static final double[] DIVE_RADII = {0, 2.5, 4.5, 7};
    private static final double[] DIVE_RAISE = {0, 4, 8, 12, 16};
    private static int divePointTarget = Integer.MIN_VALUE;
    private static Vec3 divePointOffset;

    private static Vec3 clearAimPoint(Minecraft mc, LocalPlayer p, LivingEntity t, Vec3 at) {
        if (divePointTarget == t.getId() && divePointOffset != null && diveLineClear(mc, p, t, at, at.add(divePointOffset)))
            return at.add(divePointOffset);
        Vec3 found = findDivePoint(mc, p, t, at, diveHeight, !poweredDive());
        divePointTarget = t.getId();
        divePointOffset = found != null ? found.subtract(at) : null;
        return found != null ? found : new Vec3(at.x, at.y + diveHeight + DIVE_RAISE[DIVE_RAISE.length - 1], at.z);
    }

    /** The lowest / steepest dive point from which we can glide down onto the target; null if none. verticalOnly: free fall. */
    private static Vec3 findDivePoint(Minecraft mc, LocalPlayer p, LivingEntity t, Vec3 at, double height, boolean verticalOnly) {
        // the first direction is our side: entered without turning to dive
        double sx = p.getX() - at.x, sz = p.getZ() - at.z, sl = Math.hypot(sx, sz);
        double base = sl > 0.5 ? Math.atan2(sz, sx) : 0;
        for (double raise : DIVE_RAISE) {
            double h = height + raise;
            for (double r : DIVE_RADII) {
                if (r > 0 && (verticalOnly || r > h * DIVE_CONE)) break;
                int dirs = r == 0 ? 1 : 8;
                for (int d = 0; d < dirs; d++) {
                    // 0, +45, -45, +90, -90 ... : directions near us first
                    double ang = base + Math.PI / 4 * ((d + 1) / 2) * (d % 2 == 0 ? 1 : -1);
                    Vec3 point = new Vec3(at.x + Math.cos(ang) * r, at.y + h, at.z + Math.sin(ang) * r);
                    if (diveLineClear(mc, p, t, at, point)) return point;
                }
            }
        }
        return null;
    }

    /** Whether the glide box can come down from point (feet) to hit distance of the target's head without touching a block. */
    private static boolean diveLineClear(Minecraft mc, LocalPlayer p, LivingEntity t, Vec3 at, Vec3 point) {
        Vec3 head = at.add(0, t.getBbHeight(), 0);
        Vec3 dir = point.subtract(head);
        double len = dir.length();
        if (len < 2.5) return true;
        Vec3 end = head.add(dir.scale(1.8 / len));           // the last 1.8 blocks: within smash range, no check
        if (mc.level.clip(new ClipContext(point, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p)).getType() != HitResult.Type.MISS)
            return false;                                     // cheap pre-filter
        if (!mc.level.noCollision(p, navBox(p, point).inflate(0.3, 0.0, 0.3))) return false;   // no y margin: it touched the floor while on the ground
        return sweepClear(mc, p, point, end, 0.1);
    }

    /*
     * THE SERVER'S BOX: with FakeFly a chestplate is worn on the server and we aren't gliding, the pose is standing (1.8 blocks); the
     * client glide box (0.6) is only on our side. The route and dive line are checked against the server's collision box, otherwise
     * routes through 1 block gaps were drawn and the server pulled back. 0.6 while gliding with a real elytra.
     */
    private static double navHeight(LocalPlayer p) {
        return p.isFallFlying() && isElytra(p.getItemBySlot(EquipmentSlot.CHEST)) ? 0.6 : 1.8;
    }

    private static AABB navBox(LocalPlayer p, Vec3 feet) {
        double w = p.getBbWidth() / 2.0;
        return new AABB(feet.x - w, feet.y, feet.z - w, feet.x + w, feet.y + navHeight(p), feet.z + w);
    }

    /** Whether there is no block between the eye -> target box (COLLIDER). */
    private static boolean diveClear(Minecraft mc, LocalPlayer p, Vec3 to) {
        return mc.level.clip(new ClipContext(p.getEyePosition(), to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p)).getType() == HitResult.Type.MISS;
    }

    private static AABB boxAt(LivingEntity t, Vec3 at) { return t.getBoundingBox().move(at.subtract(t.position())); }

    private record Sim(boolean hit, int ticks, double fall, List<Vec3> path) {}

    /**
     * "If I switched to a chestplate now" simulation. The glide ends; normal air physics: first move by velocity, then
     * vertical (v-0.08)*0.98, horizontal *0.91. The first j >= minJ where at the start of tick j (the hit packet goes before that tick's movement)
     * eye -> the target's predicted box <= hit range is found. Fall: what built up on the server
     * (estFall) + the descent after the release.
     */
    private static Sim simulateRelease(LocalPlayer p, LivingEntity t, int minJ, boolean keepPath) {
        Vec3 pos = p.position(), vel = p.getDeltaMovement();
        double eyeH = p.getEyeHeight(), fall = estFall;
        List<Vec3> path = keepPath ? new ArrayList<>() : null;
        if (keepPath) path.add(pos);
        for (int j = 1; j <= 24; j++) {
            Vec3 np = pos.add(vel);
            if (np.y < pos.y) fall += pos.y - np.y;
            pos = np;
            vel = new Vec3(vel.x * 0.91, (vel.y - 0.08) * 0.98, vel.z * 0.91);
            if (keepPath) path.add(pos);
            AABB tb = boxAt(t, predict(t, j));
            if (j >= minJ && inAttackRange(p, findMaceSlot(p), tb)) return new Sim(true, j, fall, path);
            if (pos.y + eyeH < tb.minY - 1) break;
        }
        return new Sim(false, 0, fall, path);
    }

    /** Height of the ground under the feet (up to 64 blocks). */
    private static double groundY(Minecraft mc, LocalPlayer p) {
        Vec3 a = p.position();
        HitResult r = mc.level.clip(new ClipContext(a, a.add(0, -64, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, p));
        return r.getType() == HitResult.Type.MISS ? a.y - 64 : r.getLocation().y;
    }

    private static void go(St s) {
        if (debug && s != st) {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            String info = p == null ? "" : String.format(Locale.ROOT, " (y=%.1f target=%s fall=%.1f gliding=%s chest=%s vy=%.2f)",
                p.getY(), target == null ? "-" : String.format(Locale.ROOT, "%.1f", boxDist(p.getEyePosition(), target.getBoundingBox())),
                p.fallDistance, p.isFallFlying(), isElytra(p.getItemBySlot(EquipmentSlot.CHEST)) ? "elytra" : "chest", p.getDeltaMovement().y);
            dbg(ST_NAMES[st.ordinal()] + " -> " + ST_NAMES[s.ordinal()] + " [" + stTicks + " tick]" + info);
        }
        if (s != St.CLIMB) hoverPhase = null;
        if (s != st) setbacks = 0;
        st = s; stTicks = 0;
    }

    /** With Debug on, writes to latest.log (and chat). */
    static void dbg(String m) {
        if (!debug) return;
        System.out.println("[Futureplus] MaceAura: " + m);
        DebugLog.write("MA", m);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendSystemMessage(FontMod.comp("[MA] " + m));
    }

    /** HUD module list info. */
    public static String hudInfo() {
        if (!enabled) return null;
        if (missingMsg != null) return "Missing: " + missingMsg;
        String s = st == St.CLIMB && hoverPhase != null ? hoverPhase : ST_NAMES[st.ordinal()];
        if (target != null) s += String.format(Locale.ROOT, " %s %dm ~%.1f\u2764", name(target), (int) Math.round(diveHeight), hudDmg);
        if (hits > 0) s += " x" + hits;
        return s;
    }

    // ------------------------------------------------------------- rotation (LocalPlayerMaceMixin)

    private static boolean wantRot, spoofing;
    private static float wantYaw, wantPitch, rotYaw, rotPitch, savedYaw, savedPitch;
    private static boolean rotInit;
    private static float sentYaw, sentPitch;

    /** Fight.Direction: does the last look ray sent to the server pass through the target's box? */
    private static boolean aimOk(Vec3 eye, LivingEntity t) {
        double yr = Math.toRadians(sentYaw), pr = Math.toRadians(sentPitch);
        Vec3 dir = new Vec3(-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr));
        AABB box = t.getBoundingBox().inflate(0.1);
        return box.contains(eye) || box.clip(eye, eye.add(dir.scale(hr() + 2))).isPresent();
    }

    private static void look(float yaw, float pitch) { wantRot = true; wantYaw = yaw; wantPitch = Mth.clamp(pitch, -90f, 90f); }

    private static void lookAt(Vec3 from, Vec3 to) {
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        look((float) Math.toDegrees(Math.atan2(-dx, dz)), (float) -Math.toDegrees(Math.atan2(dy, h)));
    }

    private static float approach(float cur, float want, float max) {
        float d = Mth.wrapDegrees(want - cur);
        return cur + Mth.clamp(d, -max, max);
    }

    private static void applyRot(LocalPlayer p) {
        if (!wantRot) {
            rotInit = false;
            if (spoofing) restore(p);                  // no rotation wanted this tick: go back to the real angle right away
            return;
        }
        if (!rotInit) { rotYaw = p.getYRot(); rotPitch = p.getXRot(); rotInit = true; }
        float sp = (float) rotSpeed;
        rotYaw = approach(rotYaw, wantYaw, ncp ? Math.min(sp, (st == St.DIVE || st == St.SMASH) ? 45f : 30f) : sp);   // YawRate: yaw slow, pitch free
        rotPitch = Mth.clamp(approach(rotPitch, wantPitch, sp), -90f, 90f);
        if (rotMode == 1) {                         // Camera: the view really turns
            if (spoofing) restore(p);
            p.setYRot(rotYaw);
            p.setXRot(rotPitch);
        } else {                                    // Packet: a fake angle for the whole world tick (player + firework)
            if (!spoofing) { savedYaw = p.getYRot(); savedPitch = p.getXRot(); spoofing = true; }
            p.setYRot(rotYaw);
            p.setXRot(rotPitch);
        }
    }

    private static void restore(LocalPlayer p) {
        spoofing = false;
        p.setYRot(savedYaw); p.setXRot(savedPitch);
        p.yRotO = savedYaw; p.xRotO = savedPitch;
        // so the hand sway doesn't slide to the fake angle
        p.yBob = p.yBobO = savedYaw; p.xBob = p.xBobO = savedPitch;
    }

    /**
     * ClientLevel.tickEntities HEAD (ClientLevelMaceMixin). In packet mode the fake angle must hold not ONLY during the player's tick
     * but through the whole entity tick: the firework on the elytra pushes in its own tick by the player's look
     * direction (FireworkRocketEntity -> getLookAngle). In the previous version the firework tick saw the camera's
     * real angle, so the boost went the wrong way; that's why packet rotation didn't work.
     */
    public static void worldTickStart() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || !enabled || rotMode != 0 || !rotInit || spoofing) return;
        savedYaw = p.getYRot(); savedPitch = p.getXRot();
        spoofing = true;
        p.setYRot(rotYaw); p.setXRot(rotPitch);
        p.yRotO = rotYaw; p.xRotO = rotPitch;
    }

    /** ClientLevel.tickEntities RETURN: put the real angle back for the camera (the mouse turns this angle). */
    public static void worldTickEnd() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (spoofing && p != null) restore(p);
        spoofing = false;
    }

    /** LocalPlayer.tick HEAD. */
    public static void preTick(LocalPlayer p) {
        wantRot = false;
        // the last look the server knows (sent on the previous tick)
        sentYaw = rotInit ? rotYaw : p.getYRot();
        sentPitch = rotInit ? rotPitch : p.getXRot();
        if (!enabled) { if (spoofing) restore(p); rotInit = false; return; }
        Minecraft mc = Minecraft.getInstance();
        if (p != mc.player || mc.gameMode == null || mc.getConnection() == null || mc.level == null) return;
        try {
            logic(mc, p);
        } catch (Throwable t) {
            System.err.println("[Futureplus] MaceAura hata: " + t);
            t.printStackTrace();
            fail(mc, "MaceAura disabled after an error (see log).");
        }
        applyRot(p);
    }

    /** Restore the user's actual jump binding after LocalPlayer.tick has sent its input packet. */
    public static void postTick() {
        Minecraft mc = Minecraft.getInstance();
        if (forwardKeyPulse) {
            if (mc.options != null) mc.options.keyUp.setDown(forwardKeyWasDown);
            forwardKeyPulse = false;
        }
        if (!jumpKeyPulse) return;
        if (mc.options != null) mc.options.keyJump.setDown(jumpKeyWasDown);
        jumpKeyPulse = false;
    }

    private static boolean forwardKeyPulse, forwardKeyWasDown;

    /** The forward key is held this player tick (vanilla air control in the air: legit input Grim expects). */
    private static void requestForwardInput() {
        if (forwardKeyPulse) return;
        Minecraft mc = Minecraft.getInstance();
        forwardKeyWasDown = mc.options.keyUp.isDown();
        mc.options.keyUp.setDown(true);
        forwardKeyPulse = true;
    }

    /**
     * Horizontal steering in free fall: looks (yaw) at the target's position as far ahead as the fall time left to hit height
     * and presses the forward key. It is pressed if we're slower than the speed needed to get there in the time left, released
     * if faster (brake). Returns: the yaw to look at.
     */
    private static float steerFall(LocalPlayer p, Vec3 v) {
        double drop = Math.max(0.5, p.getY() - target.getY() - (attackHeight + 1.5));
        double t = fallTicks(drop, v.y);
        Vec3 impact = predict(target, Math.min(PRED_TICKS, t));
        double ex = impact.x - p.getX(), ez = impact.z - p.getZ(), err = Math.hypot(ex, ez);
        float yaw = (float) Math.toDegrees(Math.atan2(-ex, ez));
        if (err > 0.6) {
            double toward = (v.x * ex + v.z * ez) / err;           // horizontal velocity component toward the target
            if (toward < err / Math.max(1.0, t)) requestForwardInput();
        }
        if (debug && stTicks % 5 == 0) dbg(String.format(Locale.ROOT, "steer: err=%.2f ticks=%.0f forward=%s", err, t, forwardKeyPulse));
        return yaw;
    }

    private static void requestJumpInput(LocalPlayer p) {
        if (!jumpKeyPulse) {
            Minecraft mc = Minecraft.getInstance();
            jumpKeyWasDown = mc.options.keyJump.isDown();
            mc.options.keyJump.setDown(true);
            jumpKeyPulse = true;
            if (debug) dbg("jump key pressed for this player tick (wasDown=" + jumpKeyWasDown + ")");
        }
        p.input.makeJump();
    }

    // ------------------------------------------------------------- main loop

    private static double hudDmg;

    private static void logic(Minecraft mc, LocalPlayer p) {
        if (!p.isAlive() || p.isSpectator() || p.getAbilities().flying || p.isPassenger() || p.isInWater()) return;
        if (p.containerMenu != p.inventoryMenu) return;           // another container is open: so clicks don't mix up

        if (fwCooldown > 0) fwCooldown--;
        if (attackCooldownLeft > 0) attackCooldownLeft--;
        if (swapCooldown > 0) swapCooldown--;
        if (pickCooldown > 0) pickCooldown--;
        stTicks++;
        tickCount++;
        // stuck watchdog
        int lim = switch (st) { case TAKEOFF -> 60; case CLIMB -> 240; case DIVE -> 70; case SMASH -> 30; case REARM -> 60; default -> Integer.MAX_VALUE; };
        if (stTicks > lim) { dbg("stalled (" + ST_NAMES[st.ordinal()] + "), resetting"); go(st == St.REARM ? St.TAKEOFF : St.REARM); }

        // --- estimate of the fall built up on the server (from the previous tick's movement)
        double dyMove = p.getY() - lastY;
        lastY = p.getY();
        if (p.onGround()) estFall = 0;
        else {
            if (dyMove < 0 && dyMove > -10) estFall -= dyMove;
            // while gliding, the server clips the fall to 1 if vertical speed is slower than -0.5 (checkFallDistanceAccumulation)
            // with FakeFly the server only glides on pulse ticks; the clipping only happens then too
            boolean serverGliding = (p.isFallFlying() && isElytra(p.getItemBySlot(EquipmentSlot.CHEST))) || FakeFly.pulsedLastTick();
            if (serverGliding && p.getDeltaMovement().y > -0.5 && estFall > 1) estFall = 1;
        }

        // --- items: ALL must be in the hotbar (elytra/chestplate may also be worn). If missing, don't turn off, wait.
        Inventory inv = p.getInventory();
        boolean wearElytra = isElytra(p.getItemBySlot(EquipmentSlot.CHEST));
        // FakeFly: the client glide is held while a chestplate is worn on the server
        boolean flying = p.isFallFlying() && (wearElytra || FakeFly.isHolding());
        // FakeFly take off: the elytra is only worn on the server in the pulse packet group (other players never see it)
        boolean fakeFlyStart = FakeFly.isEnabled() && !wearElytra;
        boolean wearChest = isChestplate(p.getItemBySlot(EquipmentSlot.CHEST));
        int mace = hotbar(inv, s -> s.is(Items.MACE));
        int elytra = wearElytra ? -2 : hotbar(inv, MaceAura::isElytra);
        int chest = wearChest ? -2 : bestChestplate(inv, 9);
        int fwHot = hotbar(inv, s -> s.is(Items.FIREWORK_ROCKET));
        List<String> miss = new ArrayList<>(4);
        if (mace == -1) miss.add("mace");
        if (autoFirework && fwHot == -1 && !p.getOffhandItem().is(Items.FIREWORK_ROCKET)) miss.add("firework");
        if (elytra == -1) miss.add("elytra");
        if (chest == -1) miss.add("chestplate");
        if (!miss.isEmpty()) {
            String m = String.join(", ", miss);
            if (!m.equals(missingMsg)) say(mc, "MaceAura is waiting; missing from hotbar: " + m);
            missingMsg = m;
            if (st != St.IDLE) go(St.IDLE);
            target = null; aimPoint = null;
            drawPred = List.of(); drawSim = List.of(); drawIntercept = null; drawDiveStart = null;
            return;
        }
        if (missingMsg != null) { say(mc, "MaceAura: items ready, engaging."); missingMsg = null; }

        // if you change the hotbar by hand NCP gives a 500 ms hit penalty: account for it
        int selNow = inv.getSelectedSlot();
        if (selNow != lastSel) { lastSel = selNow; lastSlotChange = tickCount; }
        // Silent swap OFF + NCP: the mace is really in hand for the whole loop (the old way)
        if (!silent && ncp && inv.getSelectedSlot() != mace) {
            inv.setSelectedSlot(mace);
            ((com.futureplus.client.mixin.MultiPlayerGameModeAccessor) mc.gameMode).futureplus$syncCarried();
            lastSlotChange = tickCount;
            dbg("Mace equipped");
        }
        // the offhand is for the totem: a firework from there only if it is in the offhand, else from the inventory with a silent swap
        int fw = fwHot;
        if (fw == -1 && p.getOffhandItem().is(Items.FIREWORK_ROCKET)) fw = -2;
        boolean fwFree = silent || fw == -2 || !ncp;    // fireworks don't cause a tool swap penalty
        boolean fwActive = tickCount - lastFwTick < 18;

        // --- target
        if (target != null && !valid(mc, p, target, range * 1.3)) target = null;
        boolean canRepick = st == St.IDLE || st == St.TAKEOFF || st == St.CLIMB || st == St.REARM;
        // search EVERY tick without a target (no delay); with a target look for a better one every 10 ticks
        if (canRepick && (target == null || pickCooldown == 0)) { target = pick(mc, p); pickCooldown = 10; }
        track(mc, target);
        if (target == null) {
            aimPoint = null; drawPred = List.of(); drawSim = List.of(); drawIntercept = null; drawDiveStart = null;
            if (st == St.DIVE || st == St.SMASH) go(St.REARM);
            else if (st != St.REARM) { go(St.IDLE); return; }
        } else {
            diveHeight = hoverLoop() ? (mode == 0 ? popAltitude(p, target, inv.getItem(mace)) : hoverAltitude) : mode == 0 ? autoHeight(p, target, inv.getItem(mace)) : height;
            diveHeight = Math.max(diveHeight, hr() + minFall + 4);
            hudDmg = estimateDamage(p, target, inv.getItem(mace), Math.max(estFall, needFall())) / 2.0;
            List<Vec3> pred = new ArrayList<>();
            for (int k = 0; k <= 20; k += 2) pred.add(predict(target, k));
            drawPred = pred;
        }

        Vec3 v = p.getDeltaMovement();
        double speed = v.length();
        Vec3 eye = p.getEyePosition();
        if (debug && (lastDebugSnapshot < 0 || tickCount - lastDebugSnapshot >= 20)) {
            lastDebugSnapshot = tickCount;
            dbg(String.format(Locale.ROOT,
                "snapshot: state=%s ground=%s fallFlying=%s fakeFly=%s pos=(%.2f,%.2f,%.2f) velocity=(%.3f,%.3f,%.3f) yaw=%.1f pitch=%.1f fall=%.2f chest=%s elytraSlot=%d maceSlot=%d target=%s grimCompat=%s | SERVER: pose=%s fallFlying=%s leaks=%d holding=%s",
                ST_NAMES[st.ordinal()], p.onGround(), p.isFallFlying(), FakeFly.isEnabled(), p.getX(), p.getY(), p.getZ(), v.x, v.y, v.z,
                p.getYRot(), p.getXRot(), estFall,
                isElytra(p.getItemBySlot(EquipmentSlot.CHEST)) ? "elytra" : isChestplate(p.getItemBySlot(EquipmentSlot.CHEST)) ? "chestplate" : "other",
                elytra, mace, target == null ? "none" : name(target), grimCompat,
                FakeFly.serverPose(), FakeFly.serverFallFlying(), FakeFly.leaks(), FakeFly.isHolding()));
        }

        // while gliding with FakeFly the server isn't gliding: the fall distance builds up. A pulse opened while vertical speed is slower than -0.5
        // clips the fall to 1 on the server via checkFallDistanceAccumulation (so we take no damage landing after a miss).
        if (flying && !wearElytra && estFall > 2 && v.y > -0.5 && st != St.DIVE && st != St.SMASH) FakeFly.requestPulse();

        // go on the attack the tick a target is found (falls into the state below without waiting a tick in IDLE)
        if (st == St.IDLE && target != null) go(flying ? St.CLIMB : St.TAKEOFF);

        switch (st) {
            case IDLE -> { }

            case TAKEOFF -> {
                drawSim = List.of();
                if (tickCount < takeoffPauseUntil) {
                    if (debug && stTicks % 20 == 1) dbg("takeoff paused after server correction; waiting " + (takeoffPauseUntil - tickCount) + " more ticks");
                    return;
                }
                if (flying) { fireNow(mc, p, fw); go(St.CLIMB); return; }
                if (!wearElytra && !fakeFlyStart) {
                    if (swapCooldown == 0) equip(mc, p, elytra);          // silent: hotbar swap, the item in hand doesn't change
                    if (!isElytra(p.getItemBySlot(EquipmentSlot.CHEST))) return;
                    swapCooldown = 0;                                    // keep jumping in the same tick
                }
                look(yawTo(eye, target.position()), -55f);
                if (flying) { fireNow(mc, p, fw); go(St.CLIMB); return; }
                if (p.onGround()) {
                    if (debug && stTicks % 20 == 1) dbg(String.format(Locale.ROOT,
                        "takeoff: requesting vanilla jump input (jumpWasDown=%s, vy=%.3f, chest=%s)", p.input.keyPresses.jump(), v.y,
                        isElytra(p.getItemBySlot(EquipmentSlot.CHEST)) ? "elytra" : "not-elytra"));
                    // Send the same jump input used by a physical keypress; direct jumpFromGround() moves
                    // locally without informing prediction-based servers that a jump input preceded it.
                    requestJumpInput(p);
                } else if (fakeFlyStart) {
                    // FakeFly: the server accepts START_FALL_FLYING in the air while rising too (vanilla also opens the elytra
                    // while jumping); waiting for the top of the jump lost ~5 ticks on every take off
                    fakeFlyTakeoff(fw);
                } else if (v.y < -0.04) {                         // start gliding on descent
                    startGlide(mc, p);
                }
                else if (debug && stTicks % 20 == 1) dbg(String.format(Locale.ROOT,
                    "takeoff: airborne; waiting for descent before glide (vy=%.3f, fallFlying=%s)", v.y, p.isFallFlying()));
            }

            case CLIMB -> {
                /*
                 * POSITION: fly with fireworks to the horizontal distance A blocks above the target's predicted spot from where a dive steeper than
                 * "Dive angle" is possible (point Q). If far, Q is between you and the target; if above the target,
                 * out and up. Dive on reaching Q (or if you're somewhere steeper/higher).
                 */
                drawSim = List.of();
                if (!flying) { go(St.TAKEOFF); return; }
                if (hoverLoop()) { hoverClimb(mc, p, eye, v, speed, fw, fwActive); return; }
                double T = eye.distanceTo(target.position()) / 1.7;
                Vec3 liveTargetPoint = predict(target, T);
                if (climbAnchorTarget != target.getId() || climbAnchor == null || climbAnchor.distanceTo(liveTargetPoint) > 3.5) {
                    climbAnchorTarget = target.getId();
                    climbAnchor = liveTargetPoint;
                    navPath = List.of(); navPathIndex = 0; navGoal = null;
                }
                // Freeze the approach point between meaningful target movements;
                // tick-by-tick lead changes were making the waypoint orbit the player.
                Vec3 tp = climbAnchor;
                double dx = tp.x - p.getX(), dz = tp.z - p.getZ();
                double horiz = Math.sqrt(dx * dx + dz * dz);
                double alt = p.getY() - tp.y;
                double A = diveHeight + 1 + approachHeightOffset;
                double angle = Math.toDegrees(Math.atan2(alt, Math.max(0.01, horiz)));
                double dp = autoBestAngle ? bestDiveAngle(alt, horiz, v.y)
                    : (grimCompat ? divePitch : (fastDive ? Math.max(divePitch, 65) : divePitch));
                double hd = A / Math.tan(Math.toRadians(Math.min(80, dp + 15)));
                drawIntercept = tp;

                // Point Q at dp + 15°: with a narrow upper limit (dp + 5 with Auto Best Angle) the dive didn't start even on reaching Q,
                // and it circled around the target. A steeper dive is harmless for the mace; the upper limit is only on Grim.
                boolean diveAngleReady = angle >= dp - 5 && angle <= (grimCompat ? dp + 18 : 90);
                boolean stationaryTarget = target.onGround()
                    && tVel.x * tVel.x + tVel.z * tVel.z < 0.0064
                    && tAcc.x * tAcc.x + tAcc.z * tAcc.z < 0.0025;
                // A fixed grounded target does not need a perfect approach angle. Once
                // we have enough drop height and are reasonably close horizontally,
                // dive directly instead of orbiting the waypoint while rockets add lift.
                boolean stationaryDropReady = stationaryTarget && alt >= A - 1.5
                    && horiz <= Math.max(12.0, alt * 1.1) && angle >= 20.0;
                if (alt >= A - 1.5 && (diveAngleReady || stationaryDropReady)
                    && (!coverScan || diveLineClear(mc, p, target, target.position(), p.position()))) {   // whether the slanted dive line from us to the target is open
                    if (!fwActive) fireNow(mc, p, fw);
                    lastDist = 1e9;
                    if (stationaryDropReady && !diveAngleReady) dbg("stationary target: starting direct dive without waiting for the ideal angle");
                    dbg(String.format(Locale.ROOT, "dive: altitude=%.1f horizontal=%.1f angle=%.0f° fall target=%.1f", alt, horiz, angle, needFall()));
                    go(St.DIVE);
                    return;
                }
                if (climbSideTarget != target.getId() || climbSide.lengthSqr() < 1.0e-6) {
                    climbSideTarget = target.getId();
                    climbSide = horiz > 0.5
                        ? new Vec3(-dx / horiz, 0, -dz / horiz)
                        : Vec3.directionFromRotation(0, p.getYRot()).multiply(1, 0, 1).normalize().scale(-1);
                }
                Vec3 u = climbSide;
                // If momentum has carried us past the fixed side waypoint, move
                // that waypoint outward on our current side of the target. Do not
                // yaw back toward a point we have already passed.
                Vec3 initialQ = tp.add(u.scale(hd)).add(0, A, 0);
                double toQx = initialQ.x - p.getX(), toQz = initialQ.z - p.getZ();
                double horizontalSpeed = Math.hypot(v.x, v.z);
                if (horizontalSpeed > 0.25 && toQx * v.x + toQz * v.z < -0.15
                    && Math.hypot(toQx, toQz) < Math.max(12.0, hd + 5.0)) {
                    if (horiz > 0.5) climbSide = new Vec3(-dx / horiz, 0, -dz / horiz);
                    u = climbSide;
                    hd = Math.max(hd, horiz + 5.0);
                    if (debug) dbg("positioning: passed waypoint; moving it ahead instead of turning back");
                }
                Vec3 q = tp.add(u.scale(hd)).add(0, A, 0);
                drawDiveStart = q;
                Vec3 routePoint = pathWaypoint(mc, p, q);
                if (routePoint != null) {
                    aimPoint = routePoint;
                    lookAt(eye, routePoint);
                    if (!fwActive) fireNow(mc, p, fw);
                    return;
                }
                if (navUnreachable) {
                    // No feasible local route was found at any tested height. Keep
                    // the current heading and stop adding boost instead of circling.
                    look(rotInit ? rotYaw : p.getYRot(), rotInit ? rotPitch : p.getXRot());
                    return;
                }
                aimPoint = q;
                double qx = q.x - eye.x, qy = q.y - eye.y, qz = q.z - eye.z, qh = Math.sqrt(qx * qx + qz * qz);
                float pitch = (float) -Math.toDegrees(Math.atan2(qy, Math.max(0.5, qh)));
                pitch = Mth.clamp(pitch, -72f, 12f);
                // pathfinding: if there's a block ahead (about 6 ticks ahead) go over it: climb steeply + firework
                look((float) Math.toDegrees(Math.atan2(-qx, qz)), pitch);
                boolean below = qy > 2;
                boolean awayFromWaypoint = eye.distanceTo(q) > Math.max(3.5, flySpeed * 2.0);
                double cruiseSpeed = grimCompat ? Math.min(flySpeed, 1.2) : flySpeed;
                boolean boostNeeded = speed < cruiseSpeed || (below && v.y < 0.25);
                if (stationaryTarget && alt >= A + 2.0) boostNeeded = false;
                if (grimCompat && speed > 1.5) boostNeeded = false;
                if (awayFromWaypoint && boostNeeded) firework(mc, p, fw, false);
            }

            case DIVE -> {
                boolean freeFallDive = hoverLoop() && !poweredDive() && wearChest && !p.onGround();   // FakeFly free fall
                if (!flying && !freeFallDive) { go(St.REARM); return; }
                if (hoverLoop()) { hoverDive(mc, p, eye, v, speed, chest, fw, fwActive); return; }
                // intercept point: where our speed meets the target's predicted path (3 iterations)
                double s = Math.max(1.3, speed);
                double bbh = target.getBbHeight();
                Vec3 aim = target.position().add(0, bbh * 0.75, 0);
                for (int i = 0; i < 3; i++) aim = predict(target, eye.distanceTo(aim) / s).add(0, bbh * 0.75, 0);
                drawIntercept = aim;
                drawDiveStart = null;
                aimPoint = aim;
                double ax = aim.x - eye.x, ay = aim.y - eye.y, az = aim.z - eye.z, ah = Math.sqrt(ax * ax + az * az);
                float pitch = (float) -Math.toDegrees(Math.atan2(ay, ah));
                double alt = p.getY() - target.getY();
                if (pitch < 15f && alt > 4) { dbg("target left dive angle; repositioning"); go(St.CLIMB); return; }
                // vertical speed must stay faster than -0.5 for the fall to build up on the server: at least 25° down
                // If the look was cut shallower than the angle to the target (before: Dive angle / Auto Best Angle ~63°) it passed over
                // the target and missed. Grim simulates elytra physics one-to-one, a steep look is legit input: no upper limit.
                float diveLookPitch = (float) Mth.clamp(pitch, 25.0, VERTICAL_DIVE_PITCH);
                look((float) Math.toDegrees(Math.atan2(-ax, az)), diveLookPitch);
                if (fastDive ? (!fwActive && speed < 1.0)                       // boost only if we're at a standstill
                             : (!fwActive && (fwFree || speed < 0.9))) fireNow(mc, p, fw);

                if (instant) {
                    /*
                     * INSTANT HIT: our position at the start of the tick is the one the server knows. If eye -> the target's (lag
                     * compensated) box is within hit range, this tick: chestplate + let go of the glide + mace + smash.
                     * Dive speed ~3 blocks/tick, the chord of the range sphere ~7 blocks: a straight dive stays in range for at least one tick.
                     */
                    double dNow = boxDist(eye, boxAt(target, predict(target, 0)));
                    boolean toolOk = !ncp || tickCount - lastSlotChange >= toolPenalty;
                    if (renderPath && stTicks % 2 == 0) drawSim = simulateRelease(p, target, 1, true).path();
                    if (debug && stTicks % 4 == 0) dbg(String.format(Locale.ROOT, "dive: distance=%.1f/%.1f fall=%.1f speed=%.2f",
                        dNow, hr(), estFall, speed));
                    boolean inRange = inAttackRange(p, mace, boxAt(target, predict(target, 0)));
                    if (inRange && estFall >= minFall && toolOk) {
                        strike(mc, p, mace, chest, fw, eye);
                        return;
                    }
                    double groundI = p.getY() - groundY(mc, p);
                    boolean closing = dNow <= 6.5 + speed * 1.5;          // approach before server reach
                    boolean passedI = stTicks > 3 && dNow > lastDist + 0.1 && lastDist < 10;
                    lastDist = dNow;
                    if (passedI || groundI < 1.2 || (groundI < diveAbortHeight(speed) && !closing)) {
                        dbg(passedI ? "passed target; rearming" : "too close to ground; pulling up");
                        go(St.REARM);
                    }
                    return;
                }

                // RELEASE (Instant hit off: the old ballistic release): if we switch to a chestplate now, is the target in range on the earliest hittable tick?
                int sinceSlot = tickCount - lastSlotChange;
                // FakeFly: the chestplate is already worn (chest == -2), no swap wait; before it waited 2 ticks and crashed into the ground
                int minJ = Math.max(chest == -2 ? 1 : swapTicks, ncp ? toolPenalty - sinceSlot : 0);
                Sim sim = simulateRelease(p, target, minJ, renderPath);
                if (renderPath) drawSim = sim.path();
                if (debug && stTicks % 4 == 0) dbg(String.format(Locale.ROOT, "dive: distance=%.1f hit=%s j=%d/%d fall=%.1f (required %.1f) speed=%.2f",
                    eye.distanceTo(aim), sim.hit(), sim.ticks(), minJ, sim.fall(), needFall(), speed));
                double ground = p.getY() - groundY(mc, p);
                boolean low = ground < diveAbortHeight(speed);
                // if we're near the ground and a hit is predicted, release without waiting: the smash resets the fall, no fall damage
                if (sim.hit() && (sim.ticks() <= minJ + 1 || low) && sim.fall() >= minFall && swapCooldown == 0) {
                    equip(mc, p, chest);
                    stopGlide(p);
                    swapTick = chest == -2 ? tickCount - swapTicks : tickCount;
                    hitTick = sim.ticks();
                    lastDist = 1e9;
                    dbg(String.format(Locale.ROOT, "released: hit in %d ticks, estimated fall %.1f", sim.ticks(), sim.fall()));
                    go(St.SMASH);
                    return;
                }
                // miss / ground: pull up
                double d = boxDist(eye, target.getBoundingBox());
                boolean passed = stTicks > 3 && d > lastDist + 0.1 && lastDist < 10;
                lastDist = d;
                if (passed || low) {
                    dbg(passed ? "passed target; rearming" : "too close to ground; pulling up");
                    go(St.REARM);
                }
            }

            case SMASH -> {
                if (wearElytra) {
                    if (swapCooldown == 0) { equip(mc, p, chest); swapTick = tickCount; }
                }
                if (p.isFallFlying()) stopGlide(p);
                if (hoverLoop()) { hoverSmash(mc, p, eye, v, mace, wearChest, fw); return; }
                Vec3 tNow = predict(target, 0);                       // its position on the server (lag compensation)
                AABB tb = boxAt(target, tNow);
                double dist = boxDist(eye, tb);
                Vec3 aim = tb.getCenter();
                aimPoint = aim;
                drawIntercept = aim;
                lookAt(eye, aim);
                int since = tickCount - swapTick;
                boolean ready = wearChest && since >= swapTicks && (!ncp || tickCount - lastSlotChange >= toolPenalty);
                if (debug) dbg(String.format(Locale.ROOT, "strike: distance=%.2f/%.1f fall=%.1f client=%.1f tool=%dt ready=%s",
                    dist, hr(), estFall, p.fallDistance, tickCount - lastSlotChange, ready));
                if (ready && inAttackRange(p, mace, tb) && estFall >= minFall) {
                    attack(mc, p, mace, target, eye);
                    hits++;
                    dbg(String.format(Locale.ROOT, "HIT (angle): fall=%.1f estimated damage=%.1f❤", estFall,
                        estimateDamage(p, target, p.getInventory().getItem(mace), estFall) / 2.0));
                    rearmBoostPending = grimCompat && autoFirework && fw != -1;
                    keepAirborneAfterSmash(p, v);
                    estFall = 0;
                    drawSim = List.of();
                    go(St.REARM);
                    return;
                }
                if (renderPath && since % 2 == 0) drawSim = simulateRelease(p, target, 1, true).path();
                boolean passed = since >= 2 && dist > lastDist + 0.05 && lastDist < hr() + 1.5;
                lastDist = dist;
                double ground = p.getY() - groundY(mc, p);
                boolean late = since > Math.max(hitTick, swapTicks) + 4;
                boolean danger = ground < 3 + Math.abs(v.y) * 2 && !inAttackRange(p, mace, tb);
                if (passed || late || danger || p.onGround() || p.getY() < target.getY() - 1) {
                    dbg(String.format(Locale.ROOT, "missed: distance=%.2f fall=%.1f ready=%s%s%s",
                        dist, estFall, ready, passed ? " (passed)" : "", danger ? " (zemin)" : ""));
                    go(St.REARM);
                }
            }

            case REARM -> {
                aimPoint = null;
                drawSim = List.of();
                if (fakeFlyStart && !flying) {
                    // falling with a chestplate: switch to gliding with a FakeFly pulse without wearing the elytra
                    look(p.getYRot(), hoverLoop() ? -CLIMB_PITCH : -60f);
                    if (p.onGround()) {
                        requestJumpInput(p);
                        go(target != null ? St.TAKEOFF : St.IDLE);
                    } else if (v.y < -0.04 || p.getY() - groundY(mc, p) < 4.0) fakeFlyTakeoff(fw);
                    return;
                }
                if (!wearElytra && !flying) {
                    if (swapCooldown == 0) {
                        equip(mc, p, elytra);
                        // switching from FakeFly: the client was gliding but the server wasn't -> open the glide on the server
                        if (p.isFallFlying() && isElytra(p.getItemBySlot(EquipmentSlot.CHEST))) {
                            FakeFly.release();
                            mc.getConnection().send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
                        }
                    }
                    return;
                }
                look(p.getYRot(), hoverLoop() ? -CLIMB_PITCH : -60f);
                if (flying) {
                    // Boost once after the server has accepted the post-hit glide;
                    // waiting for the next positioning cycle leaves the player low.
                    if (rearmBoostPending && tickCount - lastFwTick < 3) return;
                    if (rearmBoostPending) {
                        fireNow(mc, p, fw);
                        rearmBoostPending = false;
                    } else if (!grimCompat || v.y < 0.5 || p.getY() - groundY(mc, p) < 7.0) fireNow(mc, p, fw);
                    go(target != null ? St.CLIMB : St.IDLE);
                    return;
                }
                if (p.onGround()) {
                    // A jump from REARM must enter TAKEOFF immediately. Staying in REARM
                    // waits for the jump arc to finish before trying to glide again.
                    requestJumpInput(p);
                    go(target != null ? St.TAKEOFF : St.IDLE);
                    return;
                }
                else if (v.y < -0.04) startGlide(mc, p);      // start gliding on descent
            }
        }
    }

    // ------------------------------------------------------------- hover loop (16 block loop)

    /** APPROACHING -> ASCENDING -> (once the cooldown is over) DIVE. While the cooldown runs a small circle is drawn at the top. */
    private static void hoverClimb(Minecraft mc, LocalPlayer p, Vec3 eye, Vec3 v, double speed, int fw, boolean fwActive) {
        /*
         * If a firework counted as "active" (18 ticks) but wasn't boosting, no new one was used: a firework used with a pulse on take off
         * was wasted before the glide settled, and it crawled at 0.2 blocks/tick for 27 ticks (log). If we're still slow after 4 ticks
         * the firework isn't boosting: don't count it as active, let the branches below use a new one right away.
         */
        boolean stalled = fwActive && tickCount - lastFwTick >= 4 && speed < 0.6;
        if (stalled) { fwActive = false; fwCooldown = 0; }
        /*
         * No horizontal control on a free fall dive: the target keeps walking during the fall (a running player covers ~5.6 blocks
         * in 20 ticks). The top point isn't where the target is NOW but where it will be when the fall ends: predicted ahead by the approach time +
         * (at the current vertical speed) the time to fall to hit height.
         */
        double gapNow = p.getY() - target.getY();
        // Powered: firework dive ~Dive speed blocks/tick; Free fall: free fall time at the current vertical speed
        double fallT = poweredDive() ? Math.max(1.0, gapNow - (attackHeight + 1.5)) / diveSpeed + 3
            : fallTicks(Math.max(1.0, gapNow - (attackHeight + 1.5)), v.y);
        double toTarget = Math.hypot(target.getX() - p.getX(), target.getZ() - p.getZ());
        double approachT = toTarget > POSITION_THRESHOLD * 2 ? toTarget / Math.max(1.2, speed) : 0;
        Vec3 tAt = predict(target, Math.min(PRED_TICKS, approachT + fallT));
        Vec3 clear = clearAimPoint(mc, p, target, tAt);
        drawIntercept = tAt;
        drawDiveStart = clear;
        double hx = clear.x - p.getX(), hz = clear.z - p.getZ(), horiz = Math.hypot(hx, hz);
        double gap = p.getY() - target.getY();
        double needGap = clear.y - tAt.y;                       // >= Hover altitude (raised if covered)
        /*
         * Bomb drop: in free fall horizontal speed decays *0.91 per tick but doesn't reach zero. Released while going 1 block/tick
         * we land ~11 blocks ahead (2btr test: dives started while circling missed by 10-13 blocks).
         * Where we'll land = position + v * (1 - 0.91^T) / 0.09; the dive only starts once this point is within POSITION_THRESHOLD
         * of the target's position T ticks later.
         */
        Vec3 impact = predict(target, Math.min(PRED_TICKS, fallT));
        Vec3 landing = landingPoint(p, v, fallT);
        double dropErr = Math.hypot(landing.x - impact.x, landing.z - impact.z);

        // On a Powered dive the glide corrects the direction: it dives from a steep enough angle (cone) without going right to the top
        double cone = poweredDive() ? Math.max(POSITION_THRESHOLD * 2, Math.max(0, gap) * DIVE_CONE) : POSITION_THRESHOLD;
        /*
         * The dive line must be open from WHERE WE ARE to the target. The cone is wide (gap * 0.7, ~16 blocks): checking only the line open
         * from the top point made it dive into the wall from the edge of the cone (log: Grim pulled back every tick,
         * the dive got stuck for 70 ticks). "Through walls" is only for the hit, not for the dive path.
         * If the line is closed, entering the cone isn't enough: it goes to the top point itself, known to be open.
         */
        boolean lineClear = diveLineClear(mc, p, target, tAt, p.position());
        if (!lineClear) cone = 1.5;
        boolean releaseOk = lineClear && (poweredDive() ? horiz <= cone : dropErr <= POSITION_THRESHOLD);
        boolean coolingDown = attackCooldownLeft > Math.min(fallT, 20);
        /*
         * The cooldown overlaps the dive: the dive takes ~fallT ticks, the cooldown ends as the hit arrives. The smash bonus doesn't scale with
         * attack strength (only base damage), a few unfinished ticks don't matter. 2btr test: the dive started right when the cooldown
         * ended, the loop was 46 ticks (34 cooldown + 12 dive).
         */
        if (releaseOk && gap >= needGap - 0.5 && !coolingDown) {
            attackedThisDive = false;
            lastDist = 1e9;
            // FakeFly: the server already has a chestplate and we aren't gliding. Don't glide on the client either: the dive is a free fall,
            // the same physics the server (and Grim) expects; the fall distance builds up from the start.
            if (!poweredDive() && FakeFly.isHolding() && isChestplate(p.getItemBySlot(EquipmentSlot.CHEST))) stopGlide(p);
            dbg(String.format(Locale.ROOT, "hover dive: gap=%.1f dropErr=%.1f hSpeed=%.2f free fall=%s fallTicks=%.0f lead=(%.1f,%.1f) vy=%.2f",
                gap, dropErr, Math.hypot(v.x, v.z), !p.isFallFlying(), fallT, impact.x - target.getX(), impact.z - target.getZ(), v.y));
            go(St.DIVE);
            return;
        }

        if (horiz > cone) {
            hoverPhase = "Approaching";
            Vec3 route = pathWaypoint(mc, p, clear);
            if (route == null && navUnreachable) {
                look(rotInit ? rotYaw : p.getYRot(), rotInit ? rotPitch : p.getXRot());
                return;
            }
            Vec3 goal = route != null ? route : clear;
            aimPoint = goal;
            double qx = goal.x - eye.x, qy = goal.y - eye.y, qz = goal.z - eye.z, qh = Math.hypot(qx, qz);
            // if the route point is below (passing under an eave), cutting at 20° left the route and went into the block
            float pitch = Mth.clamp((float) -Math.toDegrees(Math.atan2(qy, Math.max(0.5, qh))), -CLIMB_PITCH, route != null ? 60f : 20f);
            look((float) Math.toDegrees(Math.atan2(-qx, qz)), pitch);
            double cruise = grimCompat ? Math.min(flySpeed, 1.2) : flySpeed;
            // no boost while nearing the top: don't overshoot above the target and come back
            boolean boost = (speed < cruise && horiz > 8) || (qy > 2 && v.y < 0.25);
            if (route != null && !fwActive) fireNow(mc, p, fw);
            else if (boost) firework(mc, p, fw, false);
            return;
        }

        aimPoint = clear;
        // even while the cooldown runs, no higher than the altitude that pops the totem (climbing = lost time, breaks the chain)
        double ceiling = clear.y;
        if (p.getY() < ceiling) {
            hoverPhase = "Ascending";
            /*
             * If the straight climb line (from where we are to the top point) touches a block (eave, tree, cave ceiling),
             * go around it with the pathfinder instead of pushing up; before, the climb did no collision check at all.
             */
            Vec3 top = new Vec3(clear.x, ceiling, clear.z);
            if (!sweepClear(mc, p, p.position(), top, 0.15)) {
                Vec3 route = pathWaypoint(mc, p, top);
                if (route != null || navUnreachable) {
                    hoverPhase = "Ascending (route)";
                    Vec3 goal = route != null ? route : top;
                    aimPoint = goal;
                    lookAt(eye, goal.add(0, Math.min(navHeight(p) / 2.0, p.getEyeHeight()), 0));
                    if (route != null && !fwActive) fireNow(mc, p, fw);
                    return;
                }
            }
            /*
             * Turning the yaw to the point while right under the top point caused spinning: the firework carries us over
             * the point, the yaw turns 180°, and the boost keeps going while turning at NCP's yaw limit (30°/tick) -> a spiral. When close
             * the yaw is fixed and the pitch is proportional to distance (steep climb); when far it tilts at most CLIMB_PITCH.
             */
            float curYaw = rotInit ? rotYaw : p.getYRot();
            float toClear = yawTo(eye, clear);
            boolean behind = Math.abs(Mth.wrapDegrees(toClear - curYaw)) > 90f;
            float yaw = horiz < 1.5 || (behind && horiz < 4.0) ? curYaw : toClear;
            float tilt = behind || horiz < 1.5 ? 0f : (float) Mth.clamp(horiz * 2.5, 0.0, 90.0 - CLIMB_PITCH);
            look(yaw, -(VERTICAL_DIVE_PITCH - tilt));
            // no new firework if the top point already overshoots the target (climbing too much lengthens the fall time)
            if (!fwActive && p.getY() + riseHeight(v.y) < ceiling) fireNow(mc, p, fw);
            return;
        }
        /*
         * Altitude is right but the release point didn't line up (or the cooldown is running): instead of circling, a straight pass toward
         * the target's spot at the end of the fall. Our landing point passes over the target along this line; it turns back after passing.
         */
        hoverPhase = "Run";
        aimPoint = impact;
        look(yawTo(eye, impact), v.y < -0.2 ? -30f : -5f);
        if (v.y < -0.4 && !fwActive) fireNow(mc, p, fw);
    }

    /** Where we'd be horizontally t ticks after releasing now (horizontal drag in the air 0.91). */
    private static Vec3 landingPoint(LocalPlayer p, Vec3 v, double t) {
        double k = (1 - Math.pow(0.91, t)) / 0.09;
        return new Vec3(p.getX() + v.x * k, p.getY(), p.getZ() + v.z * k);
    }

    /** Ticks needed to come down drop blocks in free fall (gravity 0.08, drag 0.98) (vy0 > 0: rises first). */
    private static double fallTicks(double drop, double vy0) {
        double y = 0, vy = vy0;
        for (int t = 1; t <= 120; t++) {
            y += vy;
            vy = (vy - 0.08) * 0.98;
            if (-y >= drop) return t;
        }
        return 120;
    }

    /** How much higher we'll still rise at the current vertical speed (without boost). */
    private static double riseHeight(double vy0) {
        double y = 0, vy = vy0;
        while (vy > 0) { y += vy; vy = (vy - 0.08) * 0.98; }
        return y;
    }

    /** A momentum compensated steep dive onto the target; once the gap <= DIVE_CUT_GAP the glide is cut and the chestplate goes on. */
    private static void hoverDive(Minecraft mc, LocalPlayer p, Vec3 eye, Vec3 v, double speed, int chest, int fw, boolean fwActive) {
        double s = Math.max(1.3, speed);
        AABB tb = boxAt(target, predict(target, eye.distanceTo(target.getBoundingBox().getCenter()) / s));
        Vec3 aim = momentumAim(eye, v, tb);
        aimPoint = aim;
        drawIntercept = aim;
        drawDiveStart = null;
        double ax = aim.x - eye.x, ay = aim.y - eye.y, az = aim.z - eye.z;
        float pitch = Mth.clamp((float) -Math.toDegrees(Math.atan2(ay, Math.hypot(ax, az))), -CLIMB_PITCH, VERTICAL_DIVE_PITCH);
        // FakeFly free fall: no glide, horizontal correction with the forward key (yaw toward the target's position at the end of the fall)
        float yaw = p.isFallFlying() ? (float) Math.toDegrees(Math.atan2(-ax, az)) : steerFall(p, v);
        look(yaw, pitch);
        if (renderPath && stTicks % 2 == 0) drawSim = simulateRelease(p, target, 1, true).path();

        double gap = p.getY() - target.getY();
        // if there's a block ahead (or the server pulls us back repeatedly) don't keep pushing into the wall: pull up
        boolean blockedAhead = gap > DIVE_CUT_GAP + 1 && !sweepClear(mc, p, p.position(), p.position().add(v.scale(2.0)), 0.0);
        if (blockedAhead || (setbacks >= 2 && tickCount - lastSetbackTick <= 10)) {
            dbg(String.format(Locale.ROOT, "hover dive: %s; pulling up", blockedAhead ? "dive path blocked" : "server setbacks"));
            go(St.REARM);
            return;
        }
        if (p.isFallFlying() && poweredDive()) {
            // firework dive: while looking down a firework (in the FakeFly pulse) pulls us to Dive speed in the look direction
            if (!fwActive && speed < diveSpeed && pitch > 30f) fireNow(mc, p, fw);
            // if we'd pass beside the target when we reach the cut, don't cut: glide up (no fall damage)
            double toCut = Math.max(0, gap - DIVE_CUT_GAP) / Math.max(0.5, -v.y);
            Vec3 at = predict(target, Math.min(PRED_TICKS, toCut + 2));
            double err = Math.hypot(p.getX() + v.x * toCut - at.x, p.getZ() + v.z * toCut - at.z);
            if (debug && stTicks % 3 == 0) dbg(String.format(Locale.ROOT, "powered dive: gap=%.1f speed=%.2f vy=%.2f err=%.1f", gap, speed, v.y, err));
            if (gap < 9 && err > hr() + 1.5) {
                dbg(String.format(Locale.ROOT, "powered dive: miss predicted (err=%.1f gap=%.1f); pulling up", err, gap));
                go(St.REARM);
                return;
            }
        }
        if (gap <= DIVE_CUT_GAP) {
            // cut the glide: chestplate + START_FALL_FLYING (without an elytra the server calls stopFallFlying)
            preCutVelocity = v;
            if (chest != -2) {                                  // if dived with an elytra (with FakeFly the chestplate is already worn)
                swapCooldown = 0;
                equip(mc, p, chest);
                mc.getConnection().send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            }
            stopGlide(p);
            swapTick = tickCount;
            hitTick = 0;
            lastDist = 1e9;
            dbg(String.format(Locale.ROOT, "dive cut: gap=%.1f fall=%.1f velocity=(%.2f,%.2f,%.2f)",
                gap, p.fallDistance, v.x, v.y, v.z));
            go(St.SMASH);
            return;
        }
        double ground = p.getY() - groundY(mc, p);
        double d = boxDist(eye, tb);
        boolean passed = stTicks > 3 && d > lastDist + 0.1 && lastDist < 10;
        lastDist = d;
        if (!p.isFallFlying()) {
            // if a miss is certain in free fall, get out early before crashing into the ground (fall damage): if where we'll land in the remaining fall
            // is further than range + 1 from the target's current spot and fewer than 12 blocks are left to the target, switch to gliding
            double t = fallTicks(Math.max(0.5, gap - (attackHeight + 1.5)), v.y);
            Vec3 land = landingPoint(p, v, t), at = predict(target, Math.min(PRED_TICKS, t));
            double err = Math.hypot(land.x - at.x, land.z - at.z);
            if (gap < 12 && err > hr() + 1.0) {
                dbg(String.format(Locale.ROOT, "hover dive: miss predicted (err=%.1f gap=%.1f); pulling up", err, gap));
                go(St.REARM);
                return;
            }
        }
        if (passed || ground < 2.0 || gap < 0) {
            dbg(passed ? "hover dive: passed target; rearming" : "hover dive: too low; rearming");
            go(St.REARM);
        }
    }

    /** Free fall + smash window. After the hit, back up with elytra + glide + firework. */
    private static void hoverSmash(Minecraft mc, LocalPlayer p, Vec3 eye, Vec3 v, int mace, boolean wearChest, int fw) {
        AABB tb = boxAt(target, predict(target, 0));
        Vec3 vel = v.horizontalDistanceSqr() > 0.0025 ? v : preCutVelocity;
        Vec3 aim = momentumAim(eye, vel, tb);
        aimPoint = aim;
        drawIntercept = aim;
        // the look yaw is for steering; at the hit attack() sends a separate rotation packet facing the target
        double ax = aim.x - eye.x, ay = aim.y - eye.y, az = aim.z - eye.z;
        look(steerFall(p, v), (float) -Math.toDegrees(Math.atan2(ay, Math.hypot(ax, az))));
        double dy = p.getY() - target.getY();
        double horiz = Math.hypot(target.getX() - p.getX(), target.getZ() - p.getZ());
        boolean smashWindow = !attackedThisDive
            && p.fallDistance > Math.max(SMASH_FALL_DISTANCE, minFall)
            && v.y < 0.0
            && dy >= -1.5
            && dy <= attackHeight + 3.0
            && horiz <= hr() + 2.0;
        boolean rayOk = (throughWalls || diveClear(mc, p, aim)) && inAttackRange(p, mace, tb);
        boolean toolOk = !ncp || tickCount - lastSlotChange >= toolPenalty;
        int since = tickCount - swapTick;
        if (debug && since % 2 == 0) dbg(String.format(Locale.ROOT, "smash window: %s ray=%s dy=%.2f horizontal=%.2f fall=%.1f vy=%.2f",
            smashWindow, rayOk, dy, horiz, p.fallDistance, v.y));
        if (smashWindow && rayOk && wearChest && toolOk && !p.isFallFlying()) {
            attack(mc, p, mace, target, eye);
            attackedThisDive = true;
            hits++;
            rearmBoostPending = grimCompat && autoFirework && fw != -1;
            double fellH = Math.max(estFall, p.fallDistance);              // FakeFly: the client fall is zero while gliding
            dbg(String.format(Locale.ROOT, "HIT (hover): fall=%.1f estimated damage=%.1f❤", fellH,
                estimateDamage(p, target, p.getInventory().getItem(mace), fellH) / 2.0));
            keepAirborneAfterSmash(p, v);
            if (fastEscape && !grimCompat) escape(mc, p, fw);
            estFall = 0;
            drawSim = List.of();
            go(St.REARM);
            return;
        }
        double dist = boxDist(eye, tb);
        boolean passed = since >= 2 && dist > lastDist + 0.05 && lastDist < hr() + 1.5;
        lastDist = dist;
        double floor = groundY(mc, p), ground = p.getY() - floor;
        /*
         * If we enter range on the next tick (the hit goes before that tick's movement, from the current position) don't cancel.
         * 2btr test: on a ~1.7 blocks/tick dive the range window is 1-2 ticks; all misses were at 3.00-3.12 blocks,
         * cut by "ground" when it could have hit a tick later.
         */
        Vec3 next = p.position().add(v);
        boolean hitNext = next.y > floor + 0.05
            && boxDist(next.add(0, p.getEyeHeight(), 0), boxAt(target, predict(target, 1))) <= Math.min(hr(), 3.0);
        boolean danger = ground < 2 + Math.abs(v.y) * 2 && !inAttackRange(p, mace, tb) && !hitNext;
        if (passed || danger || p.onGround() || dy < -1.5 || since > 25) {
            dbg(String.format(Locale.ROOT, "hover miss: distance=%.2f fall=%.1f%s%s", dist, p.fallDistance,
                passed ? " (passed)" : "", danger ? " (ground)" : ""));
            go(St.REARM);
        }
    }

    /**
     * Attack cooldown on the server: depends on the attack speed of the item HELD in hand (with a silent swap the mace is only in hand
     * at the moment of the packet, the old item comes back for the tick). Totem/crystal: 4.0 -> 5 ticks. Mace in hand: 0.6 -> 34 ticks;
     * hitting before it fills drops the damage and crit (test: 58 damage instead of 98 at 15 ticks).
     */
    private static int attackCooldownTicks(ItemStack mace) {
        ItemAttributeModifiers m = mace.get(DataComponents.ATTRIBUTE_MODIFIERS);
        double spd = 4.0 + (m == null ? 0 : m.compute(Attributes.ATTACK_SPEED, 0, EquipmentSlot.MAINHAND));
        return (int) Math.ceil(20.0 / Math.max(0.1, spd)) + 1;
    }

    private static int pingTicks(Minecraft mc) {
        PlayerInfo pi = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(mc.player.getUUID());
        int ms = pi != null ? pi.getLatency() : 100;
        return Mth.clamp((ms + 49) / 50, 1, 20);
    }

    /** ClientPacketListenerMixin: whether the velocity packet we get after a smash should be ignored. */
    public static boolean ignoreSelfMotion(int entityId) {
        return enabled && entityId == tpMotionId && Minecraft.getInstance().player != null
            && Minecraft.getInstance().player.getId() == entityId && tickCount <= tpMotionUntil;
    }

    /** ClientPacketListenerMixin: the server corrected our position (setback). */
    public static void onServerTeleport(ClientboundPlayerPositionPacket packet) {
        if (!enabled) return;
        setbacks = tickCount - lastSetbackTick <= 10 ? setbacks + 1 : 1;
        lastSetbackTick = tickCount;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p != null) {
            Vec3 raw = packet.change().position();
            Vec3 current = p.position();
            Set<net.minecraft.world.entity.Relative> rel = packet.relatives();
            double destX = rel.contains(net.minecraft.world.entity.Relative.X) ? current.x + raw.x : raw.x;
            double destY = rel.contains(net.minecraft.world.entity.Relative.Y) ? current.y + raw.y : raw.y;
            double destZ = rel.contains(net.minecraft.world.entity.Relative.Z) ? current.z + raw.z : raw.z;
            Vec3 delta = packet.change().deltaMovement();
            if (debug) dbg(String.format(Locale.ROOT,
                "server position packet: id=%d raw=(%.3f,%.3f,%.3f) absolute=(%.3f,%.3f,%.3f) relative=%s velocity=(%.3f,%.3f,%.3f) clientBefore=(%.3f,%.3f,%.3f) ground=%s state=%s",
                packet.id(), raw.x, raw.y, raw.z, destX, destY, destZ, rel,
                delta.x, delta.y, delta.z, current.x, current.y, current.z, p.onGround(), ST_NAMES[st.ordinal()]));
            double horizontalCorrection = Math.hypot(current.x - destX, current.z - destZ);
            if (tickCount >= takeoffPauseUntil && st == St.TAKEOFF && current.y > destY + 0.2
                && horizontalCorrection < 0.15 && delta.lengthSqr() < 1.0e-6) {
                takeoffPauseUntil = tickCount + 20;
                if (debug) dbg(String.format(Locale.ROOT,
                    "takeoff movement reset by server: clientY=%.3f serverY=%.3f; pausing takeoff for 20 ticks",
                    current.y, destY));
            }
        }
    }

    /**
     * A one tick hit combo (the server handles the packet order as is, all before the next server tick):
     *  1. chestplate: a hotbar SWAP click (chest slot <-> hotbar), the selected slot doesn't change
     *  2. START_FALL_FLYING: the elytra is no longer worn -> tryToStartFallFlying false on the server -> stopFallFlying()
     *     (MaceItem.canSmashAttack: fall > 1.5 and not gliding)
     *  3. mace: to the hand with an inventory SWAP, attack, back (invSwap)
     */
    private static void strike(Minecraft mc, LocalPlayer p, int mace, int chest, int fw, Vec3 eye) {
        swapCooldown = 0;
        equip(mc, p, chest);
        mc.getConnection().send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
        stopGlide(p);
        // if the chestplate is in the selected slot, equip put the elytra there; the mace's spot didn't change (mace != chest)
        attack(mc, p, mace, target, eye);
        hits++;
        rearmBoostPending = grimCompat && autoFirework && fw != -1;
        dbg(String.format(Locale.ROOT, "HIT: fall=%.1f estimated damage=%.1f❤", estFall, hudDmg));
        keepAirborneAfterSmash(p, p.getDeltaMovement());
        if (fastEscape && !grimCompat) escape(mc, p, fw);
        estFall = 0;
        swapTick = tickCount;
        drawSim = List.of();
        go(St.REARM);
    }

    /**
     * Fast escape (same tick as the hit, the server handles it in order):
     *  4. elytra back (swaps places with the chestplate)  5. START_FALL_FLYING: not gliding, not on the ground -> the glide starts
     *  6. firework: already gliding on the server -> the boost starts at once. Looking up (-60°) -> the firework carries us up.
     * The smash's vertical velocity packet from the server (0.01) is ignored, otherwise it spoils the firework boost for a tick.
     */
    private static void escape(Minecraft mc, LocalPlayer p, int fw) {
        if (FakeFly.isEnabled() && !isElytra(p.getItemBySlot(EquipmentSlot.CHEST))) {
            // FakeFly: elytra + glide + firework in the same pulse, the chestplate back at the end of the tick
            look(p.getYRot(), hoverLoop() ? -CLIMB_PITCH : -60f);
            lastFwTick = -100000;                            // so the dive's firework doesn't hold the escape for 3 ticks
            fakeFlyTakeoff(fw);
            tpMotionId = p.getId();
            tpMotionUntil = tickCount + 2 + pingTicks(mc) * 2;
            dbg("escape: FakeFly pulse (elytra + glide + firework, chestplate back)");
            return;
        }
        int el = find(p.getInventory(), MaceAura::isElytra);
        if (el < 0) return;
        swapCooldown = 0;
        equip(mc, p, el);
        if (!p.onGround() && p.tryToStartFallFlying())
            mc.getConnection().send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
        look(p.getYRot(), hoverLoop() ? -CLIMB_PITCH : -60f);
        if (p.isFallFlying()) { lastFwTick = -100000; fwCooldown = 0; firework(mc, p, fw, false); }
        tpMotionId = p.getId();
        tpMotionUntil = tickCount + 2 + pingTicks(mc) * 2;
        dbg("escape: elytra + firework in the same tick");
    }

    private record NavKey(int x, int y, int z) {}
    private record NavNode(NavKey key, double g, double f, NavNode parent) {}

    /*
     * PATHFINDING: A* on the block grid (1 block cells). A cell is free if it has no collision box inside; a diagonal step
     * doesn't cut corners (all cells in between must be free), so the line between two cell centers always stays inside free
     * cells and the server's box (FakeFly: 1.8 tall) doesn't touch a block. Cells by a wall are expensive: the elytra
     * swings out on turns, so the route keeps away from walls. If the target is out of range it goes to the reachable cell closest to it.
     * The old local A* only looked 12 blocks ahead: if the straight line was open for 12 blocks, a mountain/wall beyond it
     * was never seen and the route went through blocks.
     */
    private static final int NAV_RANGE_H = 64, NAV_RANGE_V = 40, NAV_MAX_NODES = 12000;
    private static final double NAV_WALL_COST = 1.5, NAV_HEURISTIC = 1.3;
    private static int navReplanAt;
    private static int setbacks, lastSetbackTick = -100;      // server pullbacks in the last 10 ticks
    private static boolean navDirect;

    /** The next route point (feet position); null: can go straight to the target, or (navUnreachable) there is no path. */
    private static Vec3 pathWaypoint(Minecraft mc, LocalPlayer p, Vec3 destination) {
        navUnreachable = false;
        Vec3 start = p.position();
        if (start.distanceTo(destination) < 2.0) { navPath = List.of(); navPathIndex = 0; navDirect = true; return null; }
        if (navTargetId != target.getId()) {
            navTargetId = target.getId(); navPath = List.of(); navPathIndex = 0; navGoal = null; navFailureUntil = 0; navReplanAt = 0;
        }
        Vec3 v = p.getDeltaMovement();
        while (navPathIndex < navPath.size()) {
            Vec3 to = navPath.get(navPathIndex).subtract(start);
            boolean reached = to.length() < 2.0;
            boolean passed = to.length() < 4.0 && to.dot(v) < 0;
            // if the point after this one can already be reached straight, don't wait for this point (less swinging at corners)
            boolean skip = navPathIndex + 1 < navPath.size() && to.length() < 6.0
                && sweepClear(mc, p, start, navPath.get(navPathIndex + 1), 0.2);
            if (!reached && !passed && !skip) break;
            navPathIndex++;
        }
        boolean goalChanged = navGoal == null || navGoal.distanceTo(destination) > 6.0;
        boolean haveRoute = navPathIndex < navPath.size();
        boolean routeBlocked = haveRoute && !sweepClear(mc, p, start, navPath.get(navPathIndex), 0.1);
        if (!goalChanged && !routeBlocked && tickCount < navReplanAt) {
            if (haveRoute) return navPath.get(navPathIndex);
            if (navDirect) return null;
            if (tickCount < navFailureUntil) { navUnreachable = true; return null; }
        }

        navGoal = destination;
        // straight line: along the whole length (before only 12 blocks), and where momentum carries us must be open too
        Vec3 drift = v.lengthSqr() > 0.16 ? start.add(v.scale(5.0)) : start;
        if (sweepClear(mc, p, start, destination, 0.25) && (drift == start || sweepClear(mc, p, start, drift, 0.1))) {
            navPath = List.of(); navPathIndex = 0; navDirect = true;
            navReplanAt = tickCount + 4;
            return null;
        }
        navDirect = false;
        navPath = findPath(mc, p, start, destination);
        navPathIndex = 0;
        navReplanAt = tickCount + 10;
        if (navPath.isEmpty()) {
            navUnreachable = true;
            navFailureUntil = tickCount + 10;
            if (debug) dbg("pathfinding: no route; holding course without boost");
            return null;
        }
        if (debug) dbg(String.format(Locale.ROOT, "pathfinding: route with %d waypoints, end %.1f blocks from goal",
            navPath.size(), navPath.get(navPath.size() - 1).distanceTo(destination)));
        return navPath.get(0);
    }

    private static List<Vec3> findPath(Minecraft mc, LocalPlayer p, Vec3 start, Vec3 goal) {
        // node = feet block; the box (1.8 on the server) is the feet block + the block above, route point feet = block bottom + 0.1
        final double feetY = 0.1;
        final int rows = navHeight(p) + feetY > 1.0 ? 2 : 1;
        int bx = Mth.floor(start.x), by = Mth.floor(start.y + feetY), bz = Mth.floor(start.z);
        int ex = Mth.clamp(Mth.floor(goal.x) - bx, -NAV_RANGE_H, NAV_RANGE_H);
        int ey = Mth.clamp(Mth.floor(goal.y + feetY) - by, -NAV_RANGE_V, NAV_RANGE_V);
        int ez = Mth.clamp(Mth.floor(goal.z) - bz, -NAV_RANGE_H, NAV_RANGE_H);
        NavKey origin = new NavKey(0, 0, 0), end = new NavKey(ex, ey, ez);
        Map<NavKey, Byte> cells = new HashMap<>();   // 0 filled, 1 free, 2 free but by a wall
        java.util.function.ToIntFunction<NavKey> cell = k -> cells.computeIfAbsent(k, c -> {
            AABB cube = new AABB(bx + c.x(), by + c.y(), bz + c.z(), bx + c.x() + 1, by + c.y() + rows, bz + c.z() + 1);
            if (!mc.level.noCollision(p, cube.deflate(0.02))) return (byte) 0;
            return mc.level.noCollision(p, cube.inflate(0.6)) ? (byte) 1 : (byte) 2;
        });

        PriorityQueue<NavNode> open = new PriorityQueue<>(Comparator.comparingDouble(NavNode::f));
        Map<NavKey, Double> best = new HashMap<>();
        Set<NavKey> closed = new HashSet<>();
        open.add(new NavNode(origin, 0, dist(origin, end) * NAV_HEURISTIC, null));
        best.put(origin, 0.0);
        NavNode found = null, closest = null;
        double closestH = Double.MAX_VALUE;
        int expanded = 0;
        while (!open.isEmpty() && expanded++ < NAV_MAX_NODES) {
            NavNode node = open.poll();
            NavKey k = node.key();
            if (!closed.add(k)) continue;
            double h = dist(k, end);
            if (h < closestH) { closestH = h; closest = node; }
            if (k.equals(end)) { found = node; break; }
            for (int ox = -1; ox <= 1; ox++) for (int oy = -1; oy <= 1; oy++) for (int oz = -1; oz <= 1; oz++) {
                if (ox == 0 && oy == 0 && oz == 0) continue;
                NavKey nk = new NavKey(k.x() + ox, k.y() + oy, k.z() + oz);
                if (Math.abs(nk.x()) > NAV_RANGE_H || Math.abs(nk.y()) > NAV_RANGE_V || Math.abs(nk.z()) > NAV_RANGE_H) continue;
                if (closed.contains(nk)) continue;
                int c = nk.equals(end) ? Math.max(1, cell.applyAsInt(nk)) : cell.applyAsInt(nk);
                if (c == 0) continue;
                // no corner cutting: all cells covered by a diagonal step must be free
                boolean ok = true;
                for (int m = 1; m < 7 && ok; m++) {
                    int mx = (m & 1) != 0 ? ox : 0, my = (m & 2) != 0 ? oy : 0, mz = (m & 4) != 0 ? oz : 0;
                    if ((mx == 0 && my == 0 && mz == 0) || (mx == ox && my == oy && mz == oz)) continue;
                    if (cell.applyAsInt(new NavKey(k.x() + mx, k.y() + my, k.z() + mz)) == 0) ok = false;
                }
                if (!ok) continue;
                double ng = node.g() + Math.sqrt(ox * ox + oy * oy + oz * oz) + (c == 2 ? NAV_WALL_COST : 0);
                if (ng >= best.getOrDefault(nk, Double.POSITIVE_INFINITY)) continue;
                best.put(nk, ng);
                open.add(new NavNode(nk, ng, ng + dist(nk, end) * NAV_HEURISTIC, node));
            }
        }
        NavNode last = found != null ? found : closest;
        if (last == null || last.parent() == null) return List.of();
        if (found == null && closestH >= dist(origin, end) - 1.0) return List.of();   // couldn't get closer at all

        List<Vec3> cellsPath = new ArrayList<>();
        for (NavNode n = last; n != null; n = n.parent())
            cellsPath.add(0, new Vec3(bx + n.key().x() + 0.5, by + n.key().y() + feetY, bz + n.key().z() + 0.5));
        if (found != null && sweepClear(mc, p, cellsPath.get(cellsPath.size() - 1), goal, 0.1)) cellsPath.add(goal);
        // string pulling: from each point jump to the furthest point that can be reached straight (with a margin, not scraping walls)
        List<Vec3> path = new ArrayList<>();
        Vec3 cursor = start;
        int i = 1;
        while (i < cellsPath.size()) {
            int far = cellsPath.size() - 1;
            while (far > i && !sweepClear(mc, p, cursor, cellsPath.get(far), 0.15)) far--;
            path.add(cellsPath.get(far));
            cursor = cellsPath.get(far);
            i = far + 1;
        }
        return path;
    }

    private static double dist(NavKey a, NavKey b) {
        int dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Collision-checks the player's full box along a short segment, including the route's diagonal edges. */
    private static boolean navClear(Minecraft mc, LocalPlayer p, Vec3 from, Vec3 to) { return sweepClear(mc, p, from, to, 0.08); }

    /** Whether the player box (grown by margin) touches a block along from -> to (feet positions); the step is smaller than the box. */
    private static boolean sweepClear(Minecraft mc, LocalPlayer p, Vec3 from, Vec3 to, double margin) {
        AABB box = navBox(p, from).inflate(margin, Math.min(margin, 0.05), margin);
        double step = Math.max(0.2, Math.min(box.getXsize(), box.getYsize()) * 0.8);
        Vec3 delta = to.subtract(from);
        int steps = Math.max(1, (int) Math.ceil(delta.length() / step));
        for (int i = 1; i <= steps; i++) {
            Vec3 at = delta.scale((double) i / steps);
            if (!mc.level.noCollision(p, box.move(at))) return false;
        }
        return true;
    }

    /** Finds the best clear ascent/side route when the current glide path is obstructed. */
    private static Vec3 avoidancePoint(Minecraft mc, LocalPlayer p, Vec3 velocity) {
        Vec3 forward = new Vec3(velocity.x, 0, velocity.z);
        if (forward.lengthSqr() < 0.04) {
            forward = Vec3.directionFromRotation(0, p.getYRot()).multiply(1, 0, 1);
        }
        if (forward.lengthSqr() < 1e-6) forward = new Vec3(0, 0, 1);
        forward = forward.normalize();
        Vec3 side = new Vec3(-forward.z, 0, forward.x);
        Vec3[] candidates = {
            forward,
            forward.scale(0.8).add(0, 0.6, 0).normalize(),
            forward.scale(0.65).add(side.scale(0.65)).add(0, 0.4, 0).normalize(),
            forward.scale(0.65).subtract(side.scale(0.65)).add(0, 0.4, 0).normalize(),
            side.scale(0.85).add(0, 0.5, 0).normalize(),
            side.scale(-0.85).add(0, 0.5, 0).normalize(),
            new Vec3(0, 1, 0)
        };
        AABB box = p.getBoundingBox();
        double widthX = Math.max(0.1, (box.maxX - box.minX) * 0.4);
        double widthZ = Math.max(0.1, (box.maxZ - box.minZ) * 0.4);
        Vec3[] offsets = {
            new Vec3(0, 0.15, 0), new Vec3(0, p.getEyeHeight(), 0),
            new Vec3(0, Math.max(0.2, p.getBbHeight() - 0.15), 0),
            new Vec3(widthX, p.getEyeHeight(), 0), new Vec3(-widthX, p.getEyeHeight(), 0),
            new Vec3(0, p.getEyeHeight(), widthZ), new Vec3(0, p.getEyeHeight(), -widthZ)
        };
        double straightClear = 8;
        for (Vec3 offset : offsets) {
            Vec3 from = p.position().add(offset);
            HitResult hit = mc.level.clip(new ClipContext(from, from.add(forward.scale(8)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
            if (hit.getType() != HitResult.Type.MISS) straightClear = Math.min(straightClear, from.distanceTo(hit.getLocation()));
        }
        if (straightClear >= 7.8) return null;
        double bestDistance = -1;
        Vec3 best = candidates[candidates.length - 1];
        for (int i = 0; i < candidates.length; i++) {
            Vec3 dir = candidates[i];
            double clear = 8;
            for (Vec3 offset : offsets) {
                Vec3 from = p.position().add(offset);
                HitResult hit = mc.level.clip(new ClipContext(from, from.add(dir.scale(8)),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
                if (hit.getType() != HitResult.Type.MISS) clear = Math.min(clear, from.distanceTo(hit.getLocation()));
            }
            double score = clear - (i == 0 ? 0 : 0.2 + dir.subtract(forward).length() * 0.35);
            if (score > bestDistance) { bestDistance = score; best = dir; }
        }
        return p.getEyePosition().add(best.scale(8));
    }

    /** Use a firework right away regardless of the cooldown (at most once every 3 ticks). */
    private static void fireNow(Minecraft mc, LocalPlayer p, int fw) {
        if (tickCount - lastFwTick < 3) return;
        fwCooldown = 0;
        firework(mc, p, fw, false);
    }

    private static void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        target = null; aimPoint = null;
        // if we're falling in the air with a chestplate, put the elytra back on (so we don't fall and die)
        try {
            if (p != null && mc.gameMode != null && !p.onGround() && p.getDeltaMovement().y < -0.5
                && !isElytra(p.getItemBySlot(EquipmentSlot.CHEST)) && p.containerMenu == p.inventoryMenu) {
                int e = find(p.getInventory(), MaceAura::isElytra);
                if (e >= 0) { equip(mc, p, e); startGlide(mc, p); }
            }
        } catch (Throwable ignored) { }
        st = St.IDLE;
    }

    private static void fail(Minecraft mc, String text) {
        if (!failed && mc.player != null) mc.player.sendSystemMessage(FontMod.comp(text));
        failed = true;
        setEnabled(false);
    }

    // ------------------------------------------------------------- eylemler

    /** Switch to gliding with a FakeFly pulse and use a firework in the same pulse (at most once every 3 ticks). */
    private static void fakeFlyTakeoff(int fw) {
        if (tickCount - lastFwTick < 3) return;
        FakeFly.requestStart();
        if (autoFirework && fw != -1) {
            FakeFly.requestRocket();
            lastFwTick = tickCount;
            fwCooldown = effectiveFireworkDelay();
        }
        dbg("FakeFly takeoff pulse requested");
    }

    /**
     * On a smash the server sets our vertical speed to 0.01 (MaceItem). Apply the same on the client right away: otherwise it kept
     * falling at the downward speed and landed, and waiting to jump and fall again lost ~half a second in a chain
     * (log, Angle dive: Strike -> Rearming -> Takeoff, jump). In the air it takes off right away with a FakeFly pulse.
     */
    private static void keepAirborneAfterSmash(LocalPlayer p, Vec3 v) {
        // FakePlayer+ only exists on our side: the server doesn't see the hit and doesn't set our speed to 0.01. Doing it here makes Grim see
        // a rise instead of a fall and pull back (log: SETBACK right after the hit, then landing + jumping)
        if (FakePlayerPlus.isFake(target)) return;
        p.setDeltaMovement(v.x, 0.01, v.z);
        p.resetFallDistance();
    }

    /** Let go of the glide (FakeFly's too if it is holding it). */
    private static void stopGlide(LocalPlayer p) {
        FakeFly.release();
        p.stopFallFlying();
    }

    /**
     * Whether the FakeFly pulse loop is on: while climbing and taking off again a chestplate stays worn on the server. Off on the dive and the hit
     * (the glide never starts on the server, so the fall distance builds up uninterrupted).
     */
    public static boolean fakeFlyCruise() {
        if (!enabled) return false;
        LocalPlayer p = Minecraft.getInstance().player;
        // Angle dive also glides on the dive: if the pulse stops the server thinks we aren't gliding, and Grim pulls back every tick
        // (log: SETBACK through the dive, sinceLastPulse 3 -> 12). Only the hover free fall has no pulse.
        return st == St.CLIMB || (st == St.DIVE && (poweredDive() || !hoverLoop()))
            || ((st == St.TAKEOFF || st == St.REARM) && p != null && p.isFallFlying());
    }

    private static void startGlide(Minecraft mc, LocalPlayer p) {
        if (p.isFallFlying() || p.onGround()) {
            if (debug) dbg(String.format(Locale.ROOT, "glide request skipped: alreadyGliding=%s onGround=%s vy=%.3f",
                p.isFallFlying(), p.onGround(), p.getDeltaMovement().y));
            return;
        }
        boolean accepted = p.tryToStartFallFlying();
        if (debug) dbg(String.format(Locale.ROOT, "glide request: clientAccepted=%s y=%.2f vy=%.3f elytra=%s",
            accepted, p.getY(), p.getDeltaMovement().y, isElytra(p.getItemBySlot(EquipmentSlot.CHEST))));
        if (accepted)
            mc.getConnection().send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
    }

    private static void firework(Minecraft mc, LocalPlayer p, int slot, boolean force) {
        if (!autoFirework || slot == -1 || !p.isFallFlying()) return;
        // with no elytra on the chest and FakeFly not holding, the server isn't gliding: the firework only boosts on the client and gets pulled back
        if (!isElytra(p.getItemBySlot(EquipmentSlot.CHEST)) && !FakeFly.isHolding()) return;
        if (fwCooldown > 0 && !force) return;
        int fireworkDelayNow = effectiveFireworkDelay();
        if (force && fwCooldown > fireworkDelayNow / 2) return;
        if (!isElytra(p.getItemBySlot(EquipmentSlot.CHEST)) && FakeFly.isHolding()) {
            // a chestplate is worn on the server: the firework goes in the FakeFly pulse (elytra + START_FALL_FLYING + firework + chestplate)
            FakeFly.requestRocket();
            fwCooldown = fireworkDelayNow;
            lastFwTick = tickCount;
            return;
        }
        if (slot == -2) {
            mc.gameMode.useItem(p, InteractionHand.OFF_HAND);
        } else if (silent) {
            if (!invSwap(mc, p, slot, () -> mc.gameMode.useItem(p, InteractionHand.MAIN_HAND))) return;
        } else {
            withSlot(mc, p, slot, () -> mc.gameMode.useItem(p, InteractionHand.MAIN_HAND));
        }
        p.swing(slot == -2 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
        fwCooldown = fireworkDelayNow;
        lastFwTick = tickCount;
    }

    private static void attack(Minecraft mc, LocalPlayer p, int mace, LivingEntity t, Vec3 eye) {
        attackCooldownLeft = (int) Math.ceil(MIN_HIT_INTERVAL * delayMultiplier);
        if (rotMode == 0 || ncp) {
            // the momentum compensated point is always above the target box: the look ray passes through the box
            Vec3 c = momentumAim(eye, p.getDeltaMovement(), boxAt(t, predict(t, 0)));
            double dx = c.x - eye.x, dy = c.y - eye.y, dz = c.z - eye.z;
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(yaw, pitch, p.onGround(), p.horizontalCollision));
            rotYaw = yaw; rotPitch = pitch; rotInit = true;      // let the next movement packet go with the same angle too (no snapping back)
        }
        if (p.getInventory().getSelectedSlot() == mace) {          // mace already in hand
            mc.gameMode.attack(p, t);
            p.swing(InteractionHand.MAIN_HAND);
            return;
        }
        if (silent) {
            invSwap(mc, p, mace, () -> { mc.gameMode.attack(p, t); p.swing(InteractionHand.MAIN_HAND); });
            return;
        }
        withSlot(mc, p, mace, () -> {
            mc.gameMode.attack(p, t);
            p.swing(InteractionHand.MAIN_HAND);
        });
    }

    /**
     * Silent swap (inventory swap): the item at idx is brought to the held slot with an inventory click (SWAP, button = selected slot),
     * the work is done, and it is put back with the same click in the same tick. The selected hotbar slot NEVER changes:
     * no slot packet goes to the server, and NCP's tool change penalty (fight.toolchangepenalty, 500 ms) isn't triggered.
     * Since the server handles the three packets in the same tick, the item in hand is the same at the end of the tick, and the attack cooldown
     * isn't reset. The item doesn't have to be in the hotbar; it can be anywhere in the inventory. The offhand is left alone.
     */
    private static boolean invSwap(Minecraft mc, LocalPlayer p, int idx, Runnable r) {
        int sel = p.getInventory().getSelectedSlot();
        if (idx == sel) { r.run(); return true; }
        if (idx < 0 || idx > 35 || !p.containerMenu.getCarried().isEmpty()) return false;
        int id = p.inventoryMenu.containerId;
        int menu = idx < 9 ? idx + 36 : idx;
        mc.gameMode.handleContainerInput(id, menu, sel, ContainerInput.SWAP, p);
        try { r.run(); }
        finally { mc.gameMode.handleContainerInput(id, menu, sel, ContainerInput.SWAP, p); }
        return true;
    }

    /**
     * Switches to the hotbar slot and does the work. Silent: goes back to the old slot within the same tick (the hand on screen doesn't change),
     * and only the needed slot packets go to the server. With silent off it stays in the slot.
     */
    private static void withSlot(Minecraft mc, LocalPlayer p, int slot, Runnable r) {
        Inventory inv = p.getInventory();
        int prev = inv.getSelectedSlot();
        if (slot == prev) { r.run(); return; }
        lastSlotChange = tickCount;
        inv.setSelectedSlot(slot);
        r.run();                                       // attack/useItem tell the server the slot first
        if (silent) {
            inv.setSelectedSlot(prev);
            ((com.futureplus.client.mixin.MultiPlayerGameModeAccessor) mc.gameMode).futureplus$syncCarried();
        }
    }

    private static void equipClick(Minecraft mc, LocalPlayer p, int idx) {
        boolean o = ncp; ncp = false;
        try { swapCooldown = 0; equip(mc, p, idx); } finally { ncp = o; }
    }

    /** Wears the inventory item in the chest armor slot (idx: Inventory index; -2 = already worn). */
    private static void equip(Minecraft mc, LocalPlayer p, int idx) {
        if (idx < 0 || swapCooldown > 0) return;
        // Before, in NCP mode it was worn with a right click; that changed the tool held in hand, so it hit NCP's tool change
        // penalty and got the hit cancelled. An inventory click doesn't change the tool in hand.
        if (!p.containerMenu.getCarried().isEmpty()) return;
        int id = p.inventoryMenu.containerId;
        if (idx < 9 && idx != p.getInventory().getSelectedSlot()) {
            // hotbar: swap with a single click (6 = chest slot, button = hotbar slot)
            mc.gameMode.handleContainerInput(id, 6, idx, ContainerInput.SWAP, p);
        } else {
            if (idx < 9) idx += 36;                     // hotbar slots are 36..44 in the menu
            mc.gameMode.handleContainerInput(id, idx, 0, ContainerInput.PICKUP, p);
            mc.gameMode.handleContainerInput(id, 6, 0, ContainerInput.PICKUP, p);
            mc.gameMode.handleContainerInput(id, idx, 0, ContainerInput.PICKUP, p);
        }
        swapCooldown = 1;
    }

    // ------------------------------------------------------------- item helpers

    private interface StackTest { boolean test(ItemStack s); }

    private static int hotbar(Inventory inv, StackTest t) {
        int sel = inv.getSelectedSlot();
        if (t.test(inv.getItem(sel))) return sel;
        for (int i = 0; i < 9; i++) if (t.test(inv.getItem(i))) return i;
        return -1;
    }

    private static int find(Inventory inv, StackTest t) {
        int sel = inv.getSelectedSlot();
        if (t.test(inv.getItem(sel))) return sel;
        for (int i = 0; i < 36; i++) if (t.test(inv.getItem(i))) return i;
        return -1;
    }

    static boolean isElytra(ItemStack s) {
        return !s.isEmpty() && s.has(DataComponents.GLIDER) && LivingEntity.canGlideUsing(s, EquipmentSlot.CHEST)
            && !(s.isDamageableItem() && s.getMaxDamage() - s.getDamageValue() <= 2);
    }

    static boolean isChestplate(ItemStack s) {
        if (s.isEmpty() || s.has(DataComponents.GLIDER)) return false;
        Equippable e = s.get(DataComponents.EQUIPPABLE);
        return e != null && e.slot() == EquipmentSlot.CHEST && armorOf(s, EquipmentSlot.CHEST) > 0;
    }

    private static int bestChestplate(Inventory inv, int upTo) {
        int best = -1; double bv = -1;
        for (int i = 0; i < upTo; i++) {
            ItemStack s = inv.getItem(i);
            if (!isChestplate(s)) continue;
            double v = armorOf(s, EquipmentSlot.CHEST) * 10 + toughOf(s, EquipmentSlot.CHEST) + enchLevel(s, Enchantments.PROTECTION);
            if (v > bv) { bv = v; best = i; }
        }
        return best;
    }

    private static double armorOf(ItemStack s, EquipmentSlot slot) {
        ItemAttributeModifiers m = s.get(DataComponents.ATTRIBUTE_MODIFIERS);
        return m == null ? 0 : m.compute(Attributes.ARMOR, 0, slot);
    }

    private static double toughOf(ItemStack s, EquipmentSlot slot) {
        ItemAttributeModifiers m = s.get(DataComponents.ATTRIBUTE_MODIFIERS);
        return m == null ? 0 : m.compute(Attributes.ARMOR_TOUGHNESS, 0, slot);
    }

    static int enchLevel(ItemStack s, ResourceKey<Enchantment> key) {
        ItemEnchantments e = s.getEnchantments();
        if (e == null || e.isEmpty()) return 0;
        for (var en : e.entrySet()) {
            Holder<Enchantment> h = en.getKey();
            if (h.is(key)) return en.getIntValue();
        }
        return 0;
    }

    // ------------------------------------------------------------- target

    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    /** Target type filter: players / animals / monsters (armor stands etc. are none of them). */
    private static boolean wantsType(LivingEntity t) {
        if (t instanceof Player) return targetPlayers;
        if (t instanceof Animal) return targetAnimals;
        if (t instanceof Enemy) return targetMonsters;
        return false;
    }

    private static String name(LivingEntity t) {
        return t instanceof Player pl ? pl.getPlainTextName() : t.getName().getString();
    }

    private static boolean valid(Minecraft mc, LocalPlayer p, LivingEntity t, double r) {
        if (t == null || t == p || t.isRemoved() || !t.isAlive() || t.isDeadOrDying()) return false;
        if (!wantsType(t)) return false;
        if (t.isSpectator() || (t instanceof Player pl && pl.isCreative())) return false;
        if (skipFriends && t instanceof Player pl && isFriend(pl.getPlainTextName())) return false;
        if (skipInvisible && t.isInvisible()) return false;
        if (skipNaked && t instanceof Player) {
            boolean any = false;
            for (EquipmentSlot s : ARMOR) if (!t.getItemBySlot(s).isEmpty()) any = true;
            if (!any) return false;
        }
        return t.distanceTo(p) <= r;
    }

    private static LivingEntity pick(Minecraft mc, LocalPlayer p) {
        LivingEntity best = null; double bs = Double.MAX_VALUE;
        ItemStack mace = ItemStack.EMPTY;
        int ms = hotbar(p.getInventory(), s -> s.is(Items.MACE));
        if (ms >= 0) mace = p.getInventory().getItem(ms);
        for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity t) || !valid(mc, p, t, range)) continue;
            double h = mode == 0 ? autoHeight(p, t, mace) : height;
            // a covered target isn't dropped: it is a target if there's an open dive line from above or the side
            if (coverScan && findDivePoint(mc, p, t, t.position(), h, hoverLoop() && !poweredDive()) == null) continue;
            double score = t.distanceTo(p) + (mode == 0 ? h * 0.5 : 0);
            if (score < bs) { bs = score; best = t; }
        }
        return best;
    }

    private static float yawTo(Vec3 from, Vec3 to) {
        return (float) Math.toDegrees(Math.atan2(-(to.x - from.x), to.z - from.z));
    }

    private static double boxDist(Vec3 p, AABB b) {
        double x = Mth.clamp(p.x, b.minX, b.maxX), y = Mth.clamp(p.y, b.minY, b.maxY), z = Mth.clamp(p.z, b.minZ, b.maxZ);
        return p.distanceTo(new Vec3(x, y, z));
    }

    // ------------------------------------------------------------- Auto: altitude by armor

    /** The mace's fall based bonus damage (MaceItem.getAttackDamageBonus, 26.1.2). */
    static double maceBonus(double fd) {
        if (fd <= 3) return 4 * fd;
        if (fd <= 8) return 12 + 2 * (fd - 3);
        return 22 + (fd - 8);
    }

    /** Predicted hit damage to the target with an fd block fall (armor, toughness, Protection, Breach, Resistance included). */
    static double estimateDamage(LocalPlayer me, LivingEntity t, ItemStack mace, double fd) { return estimateDamage(me, t, mace, fd, 1.0); }

    /** strength: attack strength (0-1). Base damage scales by 0.2 + 0.8 * s^2, a crit needs s > 0.9; the smash bonus doesn't scale. */
    static double estimateDamage(LocalPlayer me, LivingEntity t, ItemStack mace, double fd, double strength) {
        // Base damage: the server updates item attributes on the tick; hitting with a silent swap, ATTACK_DAMAGE is that of the item held
        // at that moment (totem/crystal: +0), not the mace's. The mace bonus and Density come from the real mace.
        ItemStack held = me.getMainHandItem();
        ItemAttributeModifiers am = held.get(DataComponents.ATTRIBUTE_MODIFIERS);
        double base = 1.0 + (am == null ? 0 : am.compute(Attributes.ATTACK_DAMAGE, 0, EquipmentSlot.MAINHAND));
        MobEffectInstance str = me.getEffect(MobEffects.STRENGTH);
        if (str != null) base += 3.0 * (str.getAmplifier() + 1);
        double dmg = base * (0.2 + 0.8 * strength * strength) * (strength > 0.9 ? 1.5 : 1.0)   // while falling = crit
            + maceBonus(fd)
            + 0.5 * enchLevel(mace, Enchantments.DENSITY) * fd;

        return reduce(t, dmg, enchLevel(mace, Enchantments.BREACH));
    }

    /** Reduces damage by the target's armor, toughness, Protection and Resistance (CombatRules, 26.1.2). */
    public static double reduce(LivingEntity t, double dmg, int breach) {
        double armor = t.getArmorValue(), tough = t.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        if (armor <= 0) {                                             // if attributes didn't arrive, compute from the items
            for (EquipmentSlot s : ARMOR) { ItemStack a = t.getItemBySlot(s); armor += armorOf(a, s); tough += toughOf(a, s); }
        }
        double f = 2.0 + tough / 4.0;
        double frac = Mth.clamp(armor - dmg / f, armor * 0.2, 20.0) / 25.0;
        frac = Mth.clamp(frac - 0.15 * breach, 0, 1);
        dmg *= 1.0 - frac;

        int epf = 0;
        for (EquipmentSlot s : ARMOR) epf += enchLevel(t.getItemBySlot(s), Enchantments.PROTECTION);
        dmg *= 1.0 - Math.min(20, epf) / 25.0;

        MobEffectInstance res = t.getEffect(MobEffects.RESISTANCE);
        if (res != null) dmg *= Math.max(0, 1.0 - 0.2 * (res.getAmplifier() + 1));
        return dmg;
    }

    /** The shortest fall that kills in one hit + a dive margin; Max. height if impossible. */
    private static double autoHeight(LocalPlayer me, LivingEntity t, ItemStack mace) {
        double hp = t.getHealth() + t.getAbsorptionAmount() + 0.5;
        double offset = hr() + extraHeight;                       // we're this far above the target at the hit
        double maxFd = Math.max(minFall, maxHeight - offset);
        double fd = maxFd;
        for (double d = Math.max(1.6, minFall); d <= maxFd; d += 0.5) {
            if (estimateDamage(me, t, mace, d) >= hp) { fd = d; break; }
        }
        return Mth.clamp(fd + offset, minHeight, maxHeight);
    }

    /**
     * Hover loop: a fall that surely pops the totem + a hit margin (relative to the target's feet). The hit happens at the latest when eye -> head
     * enters range: at that moment our feet are at most height + range - eye height above. Damage with a 10% +1 margin.
     */
    private static double popAltitude(LocalPlayer me, LivingEntity t, ItemStack mace) {
        double hp = (t.getHealth() + t.getAbsorptionAmount()) * 1.1 + 1.0;
        double hitGap = t.getBbHeight() + Math.min(hr(), 3.0) - me.getEyeHeight() + extraHeight;
        double maxFd = Math.max(minFall, maxHeight - hitGap);
        double fd = maxFd;
        for (double d = Math.max(SMASH_FALL_DISTANCE + 0.1, minFall); d <= maxFd; d += 0.5) {
            if (estimateDamage(me, t, mace, d, 0.0) >= hp) { fd = d; break; }
        }
        return Mth.clamp(fd + hitGap, hr() + minFall + 4, Math.max(maxHeight, hr() + minFall + 4));
    }

    // ------------------------------------------------------------- drawing (WorldDraw.render)

    public static boolean wantsRender() { return enabled && target != null && (renderBox || renderLine || renderPath); }

    private static int color() { return themeColor ? (ClickGui.hudColor(0.5f) & 0xFFFFFF) : (renderColor & 0xFFFFFF); }

    public static void emit(Vec3 cam, float pt) {
        LivingEntity t = target;
        if (!enabled || t == null) return;
        int c = color();
        int c2 = themeColor ? (ClickGui.hudColor(0.0f) & 0xFFFFFF) : c;
        double ix = Mth.lerp(pt, t.xo, t.getX()) - t.getX();
        double iy = Mth.lerp(pt, t.yo, t.getY()) - t.getY();
        double iz = Mth.lerp(pt, t.zo, t.getZ()) - t.getZ();
        Vec3 off = new Vec3(ix - cam.x, iy - cam.y, iz - cam.z);
        if (renderBox) {
            float pulse = 0.10f + 0.06f * (float) Math.sin(System.nanoTime() / 2.0e8);
            AABB b = t.getBoundingBox().move(off);
            WorldDraw.boxFill(true, b, VisualState.argb(c, st == St.SMASH ? 0.30f : pulse));
            WorldDraw.boxOutline(true, b, 2f, VisualState.argb(c, 1f));
        }
        if (renderPath) {
            // the target's predicted path (next 20 ticks)
            List<Vec3> pr = drawPred;
            double hh = t.getBbHeight() * 0.5;
            for (int i = 1; i < pr.size(); i++) {
                float a0 = 0.85f * (1f - (i - 1) / (float) pr.size()), a1 = 0.85f * (1f - i / (float) pr.size());
                WorldDraw.line(true, pr.get(i - 1).add(0, hh, 0).subtract(cam), pr.get(i).add(0, hh, 0).subtract(cam), 1.5f,
                    VisualState.argb(c2, a0), VisualState.argb(c2, a1));
            }
            // the path followed if released (ballistic curve)
            List<Vec3> sp = drawSim;
            if (st == St.DIVE || st == St.SMASH) {
                Minecraft mc = Minecraft.getInstance();
                double eh = mc.player == null ? 1.62 : mc.player.getEyeHeight() * 0.5;
                for (int i = 1; i < sp.size(); i++)
                    WorldDraw.line(true, sp.get(i - 1).add(0, eh, 0).subtract(cam), sp.get(i).add(0, eh, 0).subtract(cam), 2f,
                        VisualState.argb(c, 0.95f), VisualState.argb(c, 0.95f));
            }
            Vec3 ic = drawIntercept;
            if (ic != null) {
                Vec3 m = ic.subtract(cam);
                double r = 0.25 + 0.08 * Math.sin(System.nanoTime() / 1.5e8);
                AABB mk = new AABB(m.x - r, m.y - r, m.z - r, m.x + r, m.y + r, m.z + r);
                WorldDraw.boxFill(true, mk, VisualState.argb(c, 0.45f));
                WorldDraw.boxOutline(true, mk, 1.5f, VisualState.argb(c, 1f));
            }
        }
        if (renderLine) {
            Vec3 q = drawDiveStart;
            if (st == St.CLIMB && q != null) {
                Vec3 qm = q.subtract(cam);
                AABB mark = new AABB(qm.x - 0.5, qm.y - 0.5, qm.z - 0.5, qm.x + 0.5, qm.y + 0.5, qm.z + 0.5);
                WorldDraw.boxOutline(true, mark, 1.5f, VisualState.argb(c, 0.9f));
                Vec3 head = new Vec3(t.getX(), t.getY() + t.getBbHeight(), t.getZ()).add(off);
                WorldDraw.line(true, qm, head, 1.2f, VisualState.argb(c, 0.7f), VisualState.argb(c, 0.1f));
            }
            if (aimPoint != null && (st == St.DIVE || st == St.SMASH)) {
                Minecraft mc = Minecraft.getInstance();
                Vec3 me = mc.player.getPosition(pt).add(0, mc.player.getEyeHeight() * 0.6, 0).subtract(cam);
                WorldDraw.line(true, me, aimPoint.subtract(cam), 1.5f, VisualState.argb(c, 0.25f), VisualState.argb(c, 0.9f));
            }
        }
    }

    // ------------------------------------------------------------- friends  (,friend add/del/list)

    private static final Set<String> friends = new LinkedHashSet<>();
    private static Path friendsFile() { return FabricLoader.getInstance().getConfigDir().resolve("futureplus-friends.txt"); }

    public static LivingEntity currentTarget() { return target; }

    /** Friend list command completion. */
    public static List<String> friendNames() { return new ArrayList<>(friends); }

    public static boolean isFriend(String name) { return name != null && friends.contains(name.toLowerCase(Locale.ROOT)); }

    /** MiddleClick: add / remove a friend; true if added. */
    static boolean toggleFriend(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        boolean added = !friends.remove(n);
        if (added) friends.add(n);
        saveFriends();
        return added;
    }

    private static void loadFriends() {
        try {
            Path f = friendsFile();
            if (!Files.exists(f)) return;
            for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                l = l.trim();
                if (!l.isEmpty() && !l.startsWith("#")) friends.add(l.toLowerCase(Locale.ROOT));
            }
        } catch (Throwable t) { System.err.println("[Futureplus] arkadas listesi okunamadi: " + t); }
    }

    private static void saveFriends() {
        try { Files.write(friendsFile(), friends, StandardCharsets.UTF_8); }
        catch (Throwable t) { System.err.println("[Futureplus] arkadas listesi yazilamadi: " + t); }
    }

    private static boolean onChat(String message) {
        String pre = PistonAura.cmdPrefix();
        if (!message.startsWith(pre + "friend")) return true;
        Minecraft mc = Minecraft.getInstance();
        String[] t = message.substring(pre.length()).trim().split("\\s+");
        String sub = t.length > 1 ? t[1].toLowerCase(Locale.ROOT) : "list";
        String name = t.length > 2 ? t[2] : null;
        switch (sub) {
            case "add" -> { if (name != null) { friends.add(name.toLowerCase(Locale.ROOT)); saveFriends(); say(mc, "Friend added: " + name); } }
            case "del", "remove" -> { if (name != null) { friends.remove(name.toLowerCase(Locale.ROOT)); saveFriends(); say(mc, "Friend removed: " + name); } }
            default -> say(mc, "Friends (" + friends.size() + "): " + String.join(", ", friends)
                + "   |  " + pre + "friend add <isim> / del <isim>");
        }
        return false;
    }

    private static void say(Minecraft mc, String s) { if (mc.player != null) mc.player.sendSystemMessage(FontMod.comp(s)); }
}
