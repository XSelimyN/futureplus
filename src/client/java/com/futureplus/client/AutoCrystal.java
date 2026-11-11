package com.futureplus.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Port of Lure Client's AutoCrystalModule (AutoCrystalTargeting, CrystalTargetDamage, CrystalLethality, CrystalDamagePolicy,
 * CrystalPlacementValidator, CrystalPlacementResult/Kind, CrystalInteractionManager.attackEntity included).
 *
 * Not in the module list; runs with ForceSuicide while Suicide is on (target = you). Lure's order is kept:
 *  before the movement packet (CombatSupport.beforeSend): targets / eye / NoArmor, placement calculation, break, place, rotation request.
 *  when a crystal spawns (ClientboundAddEntityPacket): Instant break + Sequential next crystal + Factor attacks.
 *  ClientboundPlayerCombatKillPacket or leaving the server: ForceSuicide turns off.
 *
 * Modules that Lure has but futureplus doesn't (SpeedMine, Aura, AutoExp, Offhand, Swing, AntiCheat) are assumed off/default:
 * mining ignore never kicks in, MovementSync off, StrictDirection = NCP, 2b2t off.
 * Lure runs the placement calculation on a 4-thread pool; here it runs on the main thread every tick.
 */
public final class AutoCrystal {
    private AutoCrystal() {}

    public static final String[] SWAP = {"None", "Normal", "Silent"};
    public static final String[] SILENT = {"Normal", "Alternative"};
    public static final String[] INSTANT = {"Always", "Strict", "None"};
    public static final String[] SEQUENTIAL = {"None", "Strict", "Strong"};
    public static final String[] WEAKNESS = {"None", "Normal", "Silent"};

    // ------------------------------------------------------------- settings (Lure defaults)

    static boolean place = true;
    static double placeDelay = 0, placeRange = 6, placeWallRange = 6;
    static double minPlaceDamage = 6, maxPlaceSelfDamage = 12, placeDamageRatio = 1;
    static final double MIN_PLACE_LETHAL = 20.1;   // MinDamage at the top: "Lethal" (the target's health)
    static int sequential = 1;
    static boolean requireUpperAir = false, strictDirection = false, lowerHitBox = false;
    static boolean mineIgnore = true, alwaysIgnoreMining = false;
    static double miningIgnoreThreshold = 1;
    static boolean breakOn = true;
    static double breakDelay = 0, attackFactor = 1, breakRange = 6, breakWallRange = 6;
    static double minBreakDamage = 3, maxBreakSelfDamage = 8;
    static boolean antiSuicide = true;
    static int instant = 0;
    static boolean await = true;
    static double ticksExisted = 2;
    static int weakness = 0;
    static boolean basePlace = false, basePlaceAir = false;
    static double basePlaceDelay = 500;
    static boolean rotate = false, rotatePlace = true, rotateBreak = true;
    static boolean facePlace = false, facePlaceShieldBreaker = false, damageSync = false;
    static double facePlaceHealth = 4, facePlaceArmorThreshold = 10;
    static boolean prediction = true;
    static double predictionTicks = 4;
    static boolean render = true, fade = true, multiFade = false;
    static double fadeTime = 1.0;
    static boolean pauseMining = false, pauseEating = false, pauseMending = false;
    static double targetRange = 12, crystalRange = 8;
    static boolean antiFriendKill = false, antiQuiver = false, ignoreNakeds = false, assumeBestArmor = false, bosses = false;
    static boolean forceSuicide = false, forceSuicideNoArmor = false;
    static boolean assumeInvincibility = false, assumeSelfInvincibility = true;
    static double invincibilityTimeout = 0;
    static int autoSwap = 2, silent = 0;   // lure: AutoSwap None (doesn't place without a crystal in hand); Silent for Suicide
    static boolean swapBack = true, strictTiming = false, multipoint = false;
    static double swapDelay = 0, swapPenalty = 0;

    private static final int FILL = 0x43BD99FF, OUTLINE = 0xC9BD99FF;

    // ------------------------------------------------------------- debug

    static boolean debug = false;
    private static final Map<String, String> lastDbg = new HashMap<>();
    private static long lastSummary;
    /** Last placement scan: scanned, obsidian/bedrock, passed validation, enough damage, result. */
    private static int scanned, bases, valid, damaged;

    /** To the file and chat; not written again until the message for the same key changes (no spam every tick). */
    private static void dbg(String key, String msg) {
        if (!debug) return;
        if (msg.equals(lastDbg.put(key, msg))) return;
        out(key + ": " + msg);
    }

    private static void out(String m) {
        DebugLog.write("AutoCrystal", m);
        LocalPlayer p = mc().player;
        if (p != null) p.sendSystemMessage(FontMod.comp("[AC] " + m));
    }

    /** An overall status every 2 s. */
    private static void summary() {
        if (!debug) return;
        long now = System.currentTimeMillis();
        if (now - lastSummary < 2000) return;
        lastSummary = now;
        LocalPlayer me = mc().player;
        AABB real = me.getBoundingBox(), pred = targetBounds(me);
        Placement pl = placementData;
        out(String.format(java.util.Locale.ROOT,
            "state: force=%s targets=%d target=%s hp=%.1f pl=%s dmg=%.1f scan=%d base=%d valid=%d dmgOk=%d predOff=%.2f slot=%d crystalSlot=%d hold=%s swap=%s rot=%s crystalsNear=%d",
            forceSuicide, targets.size(), target == null ? "null" : (target == me ? "SELF" : target.getPlainTextName()),
            CrystalDamage.health(me), pl == null ? "null" : pl.base.toShortString(), pl == null ? 0 : pl.damage,
            scanned, bases, valid, damaged, pred.getCenter().distanceTo(real.getCenter()),
            me.getInventory().getSelectedSlot(), findHotbarSlot(st -> st.is(Items.END_CRYSTAL)), me.isHolding(Items.END_CRYSTAL),
            SWAP[autoSwap], rotate, mc().level.getEntitiesOfClass(EndCrystal.class, me.getBoundingBox().inflate(8)).size()));
    }

    // ------------------------------------------------------------- durum

    private static boolean enabled;
    /** Called when Suicide is turned off / on death. */
    static Runnable onSuicideOff = () -> { };

    private enum Kind { NONE, RAYTRACE, OBSIDIAN, NON_PLAYER }
    private enum Lethality {
        NONE(false), INTERSECTION(false), POP(true), KILL(true);
        final boolean lethal;
        Lethality(boolean l) { lethal = l; }
        static Lethality of(LivingEntity t, double dmg) {
            boolean lethal = dmg >= CrystalDamage.health(t);
            boolean totem = t.getOffhandItem().is(Items.TOTEM_OF_UNDYING) || t.getMainHandItem().is(Items.TOTEM_OF_UNDYING);
            return lethal ? (totem ? POP : KILL) : NONE;
        }
    }
    private record Placement(BlockPos base, LivingEntity target, double damage, Kind kind) {
        Placement better(Placement o) {
            if (o == null) return this;
            if (kind.ordinal() < o.kind.ordinal()) return this;
            if (kind.ordinal() > o.kind.ordinal()) return o;
            return o.damage > damage ? o : this;
        }
    }
    private record TargetDamage(LivingEntity target, double damage, Lethality lethality) {}
    private enum Policy { PLACE, HIT }

    private static final List<LivingEntity> targets = new ArrayList<>();
    private static Placement placementData;
    private static Vec3 rotationTarget;
    private static boolean placementGate;
    private static final Timer rotationTimer = new Timer(), placeTimer = new Timer(), breakTimer = new Timer(),
        swapDelayTimer = new Timer(), swapPenaltyTimer = new Timer(), sequentialTimer = new Timer(),
        attackRetryTimer = new Timer(), basePlaceTimer = new Timer(), setbackTimer = new Timer();
    private static final LinkedList<Long> placementTimes = new LinkedList<>();
    private static final List<Integer> predictedAttackIds = new ArrayList<>();
    private static final Set<BlockPos> ignoredBlocks = new HashSet<>();
    private static final Set<Integer> serverDead = new HashSet<>();
    private static final Map<UUID, Long> joinTimes = new HashMap<>();
    private static BlockPos lastPlacedBase, lastAttackedBase, lastForcedBase, previousPlacement;
    private static boolean spawnFollowupQueued, breakRotationRequested, obsidianDropNearTarget, quiverThreatActive;
    private static float selectedDamage;
    private static int lastAttackedCrystalId = -1, attackRetries, ignoredSlotChanges, restoreSlot = -1, observedSlot = -1;
    private static int interactionLastAttackedId = -1;     // CrystalInteractionManager.lastAttackedEntityId
    private static boolean skip;
    static LivingEntity target, previousTarget;
    private static Vec3 eye;
    private static final List<long[]> boxes = new ArrayList<>();   // {x, y, z, time} render boxes

    /** Lure ElapsedTimer: -1 at the start (counts as already elapsed). */
    private static final class Timer {
        long last = -1;
        void reset() { last = System.currentTimeMillis(); }
        void clear() { last = -1; }
        boolean passed(long ms) { return System.currentTimeMillis() - last >= ms; }
    }

    private static Minecraft mc() { return Minecraft.getInstance(); }

    static boolean isActive() { return enabled; }

    static void setEnabled(boolean v) {
        if (v == enabled) return;
        enabled = v;
        if (v) onEnable(); else onDisable();
    }

    private static void onEnable() {
        lastDbg.clear();
        lastSummary = 0;
        updateEye();
        lastAttackedCrystalId = -1;
        previousTarget = target;
        target = null;
        lastPlacedBase = null;
        placementData = null;
        swapDelayTimer.reset();
        basePlaceTimer.clear();
        restoreSlot = -1;
        observedSlot = mc().player != null ? mc().player.getInventory().getSelectedSlot() : -1;
    }

    private static void onDisable() {
        restoreSelectedSlot();
        boxes.clear();
    }

    private static void setForceSuicide(boolean v) {
        if (forceSuicide == v) return;
        forceSuicide = v;
        if (!v) onSuicideOff.run();
    }

    private static boolean worldUnavailable() { return mc().player == null || mc().level == null || mc().getConnection() == null; }

    // ------------------------------------------------------------- tick (CombatSupport.beforeSend)

    static void tick() {
        Minecraft mc = mc();
        if (worldUnavailable()) {                                    // MinecraftDisconnectEvent
            if (forceSuicide) setForceSuicide(false);
            targets.clear(); serverDead.clear(); joinTimes.clear(); MotionPredictor.clear();
            return;
        }
        if (!enabled) return;
        interactionLastAttackedId = -1;                             // LocalPlayerTickEvent (CrystalInteractionManager)
        trackSlotChange(mc.player.getInventory().getSelectedSlot());
        trackJoins();
        MotionPredictor.update();

        // LocalPlayerSendPositionEvent (Pre)
        refreshTargets();
        updateEye();
        ignoredBlocks.clear();                                      // no mining module: no ignored blocks
        spawnFollowupQueued = false;
        quiverThreatActive = quiverThreat();
        if (forceSuicide && forceSuicideNoArmor) throwUnboundArmor();
        if (obsidianDropNearTarget) obsidianDropNearTarget = false;
        else {
            LivingEntity prox = proximityTarget();
            if (prox != null && obsidianDropNear(prox)) obsidianDropNearTarget = true;
        }

        // MinecraftRunTickEvent: placement calculation
        clearInvalidTarget();
        Placement calc = calculatePlacement(new ArrayList<>(targets));
        if (calc == null) { placementData = null; selectedDamage = 0; }
        else { placementData = calc; selectedDamage = (float) calc.damage; target = calc.target; }
        for (Integer id : predictedAttackIds) {
            if (mc.level.getEntity(id) instanceof EndCrystal) { /* lure: client-attacked mark (drawing only) */ }
        }
        predictedAttackIds.removeIf(id -> mc.level.getEntity(id) != null);
        summary();

        // RotationRequestEvent
        onPlayerUpdate();
    }

    private static void onPlayerUpdate() {
        Minecraft mc = mc();
        breakRotationRequested = false;
        lastAttackedBase = null;
        Entity last = mc.level.getEntity(lastAttackedCrystalId);
        if (last == null || !last.isAlive()) attackRetries = 0;
        if (mc.player.tickCount % 4 == 0) {
            long now = System.currentTimeMillis();
            while (!placementTimes.isEmpty() && now - placementTimes.getFirst() > 1000) placementTimes.remove();
        }
        String pr = pauseReason();
        dbg("pause", pr == null ? "no" : pr);
        if (pr != null) return;
        runBreakCycle();
        if (placeTimer.passed((long) placeDelay)) runPlacementCycle();
        Vec3 rot = rotationTarget;
        // the rotation itself is done by CombatSupport.look (the place/break gate); like in Lure, the plan drops without a rotation for 400 ms
        if (rotate && rot != null && rotationTimer.passed(400)) { placementData = null; rotationTarget = null; }
    }

    private static void setRotationTarget(Vec3 point) {
        if (point == null) return;
        rotationTimer.reset();
        rotationTarget = point;
    }

    // ------------------------------------------------------------- targeting (AutoCrystalTargeting)

    private static void refreshTargets() {
        targets.clear();
        for (Entity e : mc().level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity c) || invalidTarget(c)) continue;
            if (e instanceof Player p) targets.add(p);
            if (bosses && (c instanceof WitherBoss || c instanceof EnderDragon || c instanceof Warden)) targets.add(c);
        }
    }

    private static LivingEntity proximityTarget() {
        LivingEntity t = target == null && !invalidTarget(previousTarget) ? previousTarget : target;
        if (t == null) {
            double best = Double.MAX_VALUE;
            for (LivingEntity c : new ArrayList<>(targets)) {
                double d = mc().player.distanceTo(c);
                if (!invalidTarget(c) && !(d > best)) { best = d; t = c; }
            }
            if (t == null) return null;
            target = t;
        }
        return t;
    }

    private static boolean obsidianDropNear(LivingEntity t) {
        for (Entity e : mc().level.entitiesForRendering()) {
            if (e instanceof ItemEntity item && item.getItem().is(Items.OBSIDIAN) && item.getItem().getCount() >= 8 && e.isAlive()) {
                AABB tb = t.getBoundingBox();
                AABB box = tb.setMinY(tb.getCenter().y).inflate(0.5, 0, 0.5);
                if (e.getBoundingBox().intersects(box)) return true;
            }
        }
        return false;
    }

    static boolean invalidTarget(LivingEntity c) {
        LocalPlayer me = mc().player;
        if (c == null) return true;
        if (forceSuicide) return c != me;
        if (c instanceof Player p && (CombatSupport.friend(p) || p.isCreative())) return true;
        if (!c.isAlive() || c.isDeadOrDying()) return true;
        if (!(c.distanceTo(me) <= targetRange) || serverDead.contains(c.getId()) || me == c) return true;
        return c.isSpectator();
    }

    private static void clearInvalidTarget() { if (invalidTarget(target)) target = null; }

    // ------------------------------------------------------------- placement calculation

    private static Placement calculatePlacement(List<LivingEntity> targets) {
        float crystalRangeSel = shouldIgnoreMiningBlock() ? 999f : (float) breakRange;
        boolean ignoreMining = shouldIgnoreMiningBlock();
        Placement current = placementData;
        TargetDamage currentDamage = null;
        Placement best = null;
        Validator v = new Validator();
        v.requireUpperAir = requireUpperAir;
        v.lowerHitbox = lowerHitBox;
        v.expandMovingPlayers = !shouldIgnoreMiningBlock();          // MovementSync off
        v.ignoreBaseBlock = basePlace && canRetryBasePlacement();
        v.ignoreYoungItems = ignoreMining;
        v.predictionTicks = prediction ? (int) predictionTicks : 0;
        v.ignoredCrystalRangeSq = crystalRangeSel * crystalRangeSel;
        if (current != null && v.canPlace(current.base)) {
            currentDamage = damageForBase(current.base, isBedrockOrObsidian(current.base), targets);
        }
        if (eye == null) return null;
        double r = placeRange;
        BlockPos.MutableBlockPos scan = new BlockPos.MutableBlockPos();
        scanned = bases = valid = damaged = 0;
        for (double ox = -Math.floor(r); ox < Math.ceil(r); ox++) {
            for (double oy = -Math.floor(r); oy < Math.ceil(r); oy++) {
                for (double oz = -Math.floor(r); oz < Math.ceil(r); oz++) {
                    scan.set(eye.x + ox, eye.y + oy, eye.z + oz);
                    scanned++;
                    boolean hasBase = isBedrockOrObsidian(scan);
                    if (hasBase) bases++;
                    if (best != null && best.kind != Kind.OBSIDIAN && !hasBase) continue;
                    double dist = eye.distanceTo(scan.getCenter());
                    if (dist > placeRange || dist > crystalRangeSel) continue;
                    if (!v.canPlace(scan)) continue;
                    valid++;
                    boolean visible = true;
                    if (placeWallRange != placeRange) visible = CrystalDamage.canSeeAny(eye, sampleBlock(scan, multipoint));
                    if (!visible && dist > placeWallRange) continue;
                    if (strictDirection && strictDirections(scan).isEmpty()) continue;
                    Vec3 explosion = Vec3.upFromBottomCenterOf(scan, 1.0);
                    LocalPlayer me = mc().player;
                    double selfDamage = CrystalDamage.explosion(explosion, me, targetBounds(me), 6.0, true,
                        Collections.emptyList(), scan.immutable());
                    TargetDamage cand = damageForBase(scan, hasBase, targets);
                    if (cand == null) continue;
                    damaged++;
                    Lethality leth = Lethality.of(cand.target, cand.damage);
                    if (rejectDamage(Policy.PLACE, cand.target, selfDamage, cand.damage, leth)) continue;
                    Kind kind = Kind.NONE;
                    if (currentDamage != null && !current.base.equals(scan)) {
                        if (Math.round(currentDamage.damage) >= cand.damage) continue;
                    }
                    if (!visible && dist > placeWallRange) kind = Kind.RAYTRACE;
                    if (basePlace && !hasBase) {
                        AABB union = targetBounds(cand.target).minmax(cand.target.getBoundingBox());
                        if (union.intersects(new AABB(scan))) continue;
                        if (!canPlaceBlockAt(scan)) continue;
                        if (!basePlaceAir && PlacementSupport.placeSide(scan.immutable(), true, false) == null) continue;
                        kind = Kind.OBSIDIAN;
                    }
                    if (!(target instanceof Player)) kind = Kind.NON_PLAYER;
                    best = new Placement(scan.immutable(), cand.target, cand.damage, kind).better(best);
                }
            }
        }
        return best;
    }

    /** CrystalTargetDamage.calculateForBase. */
    private static TargetDamage damageForBase(BlockPos base, boolean hasBase, List<LivingEntity> targets) {
        TargetDamage best = null;
        LocalPlayer me = mc().player;
        for (LivingEntity c : targets) {
            if (invalidTarget(c)) continue;
            if (base.getY() > c.getY() + 1.5 && !hasBase) continue;
            AABB tb = targetBounds(c);
            double min = minimumDamage(Policy.PLACE, c);
            double dmg = explosionDamage(base, c, tb, true);
            Lethality leth = Lethality.of(c, dmg);
            boolean holdingCrystal = c.isHolding(Items.END_CRYSTAL);
            if (ignoreNakeds && !hasArmor(c) && !holdingCrystal) continue;
            Vec3 bc = new Vec3((tb.minX + tb.maxX) / 2, tb.minY, (tb.minZ + tb.maxZ) / 2);
            if (bc.distanceTo(Vec3.atBottomCenterOf(base)) > crystalRange && !leth.lethal && !obsidianDropNearTarget && !quiverThreatActive) continue;
            if (c instanceof Player p && CombatSupport.friend(p) && c != me) {
                if (antiFriendKill && dmg > CrystalDamage.health(c)) return null;
                continue;
            }
            if ((min > dmg || dmg < 0.5) && !leth.lethal) continue;
            if (best != null && !(dmg > best.damage)) continue;
            best = new TargetDamage(c, dmg, leth);
        }
        return best;
    }

    private static boolean hasArmor(LivingEntity e) {
        for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET})
            if (!e.getItemBySlot(s).isEmpty()) return true;
        return false;
    }

    // ------------------------------------------------------------- damage policy (CrystalDamagePolicy)

    private static double minimumDamage(Policy policy, LivingEntity t) {
        if (obsidianDropNearTarget || quiverThreatActive) return 0.1;
        if (facePlaceEnabled()) {
            if (CrystalDamage.health(t) <= facePlaceHealth) return 0.6;
            if (t instanceof Player p && lowestArmorDurability(p) <= facePlaceArmorThreshold) return 0.6;
            if (t.isUsingItem() && t.getUseItem().is(Items.SHIELD) && facePlaceShieldBreaker) return 0.6;
        }
        if (mc().level.getBlockState(t.blockPosition()).is(Blocks.NETHER_PORTAL)) return 0.6;
        double placeMin = minPlaceDamage, hitMin = minBreakDamage;
        if (minPlaceDamage >= MIN_PLACE_LETHAL) placeMin = CrystalDamage.health(t);
        if (shouldIgnoreMiningBlock()) { placeMin = Math.min(placeMin, alwaysIgnoreMining ? 0.6 : 5.0); hitMin = 3.0; }
        return Math.min(policy == Policy.HIT ? hitMin : placeMin, CrystalDamage.health(t));
    }

    private static double maximumSelfDamage(Policy policy) {
        if (selfInvulnerable()) return 999.0;
        return policy == Policy.HIT ? maxBreakSelfDamage : maxPlaceSelfDamage;
    }

    private static boolean rejectDamage(Policy policy, LivingEntity t, double self, double dmg, Lethality leth) {
        boolean holdingTotem = mc().player.isHolding(Items.TOTEM_OF_UNDYING);
        if (selfInvulnerable()) self = 0;
        if (forceSuicide) return false;
        if (self >= CrystalDamage.health(mc().player) && antiSuicide && (leth != Lethality.KILL || !holdingTotem)) return true;
        if (policy == Policy.HIT && leth == Lethality.INTERSECTION) return false;
        if (leth == Lethality.KILL) return false;
        if (policy == Policy.PLACE && self > 0 && dmg / self < placeDamageRatio) return true;
        if (!(dmg >= CrystalDamage.health(t))) {
            if (!(dmg >= minimumDamage(policy, t))) return true;
            if (!(self <= maximumSelfDamage(policy))) return true;
        }
        return false;
    }

    private static int lowestArmorDurability(Player p) {
        int low = 100;
        for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack st = p.getItemBySlot(s);
            if (st.isEmpty() || !st.isDamageableItem()) continue;
            float used = 1f - ((float) st.getMaxDamage() - st.getDamageValue()) / st.getMaxDamage();
            low = Math.min(low, Math.max(1, Math.min(100, 100 - (int) Math.ceil(used * 100f))));
        }
        return low;
    }

    private static boolean facePlaceEnabled() { return facePlace; }   // lure: if Aura isn't hitting the target or DamageSync is off (no Aura)

    // ------------------------------------------------------------- placement validation (CrystalPlacementValidator)

    private static final class Validator {
        boolean requireUpperAir, checkEntityCollisions = true, blockNearbyCrystals, allowFire = true, lowerHitbox,
            expandMovingPlayers, ignoreBaseBlock, ignoreYoungItems;
        int predictionTicks;
        float ignoredCrystalRangeSq = 16f;

        boolean canPlace(BlockPos base) {
            Minecraft mc = mc();
            if (!ignoreBaseBlock && !isBedrockOrObsidian(base)) return false;
            BlockPos space = base.offset(0, 1, 0);
            BlockState st = mc.level.getBlockState(space);
            Block b = st.getBlock();
            boolean clear = b instanceof AirBlock;
            if (!st.getFluidState().isEmpty()) clear = false;
            if (allowFire) { if (!clear && b != Blocks.FIRE) return false; }
            else if (!clear) return false;
            if (requireUpperAir && !(mc.level.getBlockState(base.offset(0, 2, 0)).getBlock() instanceof AirBlock)) return false;
            AABB box = new AABB(space).setMaxY(space.getY() + (lowerHitbox ? 1 : 2));
            int hits = 0;
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e == null || e.isRemoved() || !e.isAlive()) continue;
                AABB eb = e.getBoundingBox();
                if (e instanceof Player p && e != mc.player) {
                    eb = interpolationBounds(p);
                    if (expandMovingPlayers && !nextToWall(p)) eb = eb.inflate(1.0E-4, 0, 1.0E-4);
                    for (int t = 1; t <= predictionTicks; t++) {
                        AABB pb = MotionPredictor.predicted(p, t).setMaxY(eb.maxY).setMinY(eb.minY);
                        if (pb.intersects(box)) hits++;
                    }
                } else {
                    if (e instanceof EndCrystal && !blockNearbyCrystals
                        && mc.player.getEyePosition().distanceToSqr(e.position()) <= ignoredCrystalRangeSq) continue;
                    if (e instanceof ItemEntity && ignoreYoungItems && e.tickCount < 3) continue;
                }
                if (eb.intersects(box)) { hits++; break; }
            }
            return !(checkEntityCollisions && hits != 0);
        }
    }

    private static boolean nextToWall(Player p) { return !mc().level.noCollision(p.getBoundingBox().inflate(1.0E-4, 0, 1.0E-4)); }

    // ------------------------------------------------------------- place loop

    private static void runPlacementCycle() {
        Minecraft mc = mc();
        LocalPlayer me = mc.player;
        if (!shouldIgnoreMiningBlock()) placementGate = false;
        if (!place || swapPenaltyActive() || placementGate) { dbg("place", "off / swap penalty / gate"); return; }
        boolean crystalOffhand = me.getOffhandItem().getItem() == Items.END_CRYSTAL;
        boolean holding = me.isHolding(Items.END_CRYSTAL);
        boolean missingTarget = target == null;
        if (damageSync || obsidianDropNearTarget || quiverThreatActive || missingTarget) {
            if (missingTarget || selectedDamage < minPlaceDamage) {
                if ((missingTarget || selectedDamage < 5f) && !placeTimer.passed(tickDurationMillis())) { dbg("place", "low damage wait (500 ms)"); return; }
            }
        }
        Placement sel = placementData;
        if (sel == null) { dbg("place", "no placement found"); restoreSelectedSlot(); return; }
        BlockPos base = sel.base;
        if (base == null || target == null) { dbg("place", "no target"); restoreSelectedSlot(); return; }
        double min = minimumDamage(Policy.PLACE, target);
        double dmg = CrystalDamage.explosion(Vec3.upFromBottomCenterOf(base, 1.0), target, targetBounds(target), 6.0, true, ignoredBlocks, base);
        if (prediction && dmg > 2.0 && dmg < min) dmg = Math.max(dmg, explosionDamage(base, target, interpolationBounds(target), true));
        if (dmg < min) { dbg("place", String.format(java.util.Locale.ROOT, "damage %.1f < min %.1f", dmg, min)); restoreSelectedSlot(); return; }
        if (!isBedrockOrObsidian(base)) {
            dbg("place", "base not obsidian/bedrock");
            if (sel.kind == Kind.OBSIDIAN && basePlace) runForcedPlacement(base);
            return;
        }
        // counterpart of Lure AntiCheat MovementSync (NCP/Grim): first turn to the base, place once the rotation reached the server; the swap waits too
        if (rotate && rotatePlace && !facingBase(base)) { dbg("place", "waiting for rotation"); return; }
        int original = -1;
        int crystalSlot = findHotbarSlot(s -> s.is(Items.END_CRYSTAL));
        if (mc.level.getBlockState(base.above()).getBlock() == Blocks.FIRE) {
            mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, base.above(), Direction.DOWN));
            mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, base.above(), Direction.DOWN));
            dbg("place", "extinguishing fire");
            return;
        }
        boolean altSearch = false;
        boolean altSwap = silentSwap() && silent == 1;
        if (altSwap && (!skip || !me.containerMenu.getCarried().isEmpty())) { skip = true; dbg("place", "alternative swap skip tick"); return; }
        if (altSwap && crystalSlot == -1) crystalSlot = findMenuSlot(Items.END_CRYSTAL);
        if (autoSwap != 0 && !holding && !swapDelayActive() && crystalSlot != -1) {
            original = me.getInventory().getSelectedSlot();
            if (altSwap) {
                swapIntoSelectedSlot(crystalSlot);
                altSearch = true;
                placementGate = false;
            } else {
                if (autoSwap == 2) ignoreNextSlotChange();
                if (shouldRestoreSlot()) restoreSlot = original;
                selectSlot(crystalSlot);
            }
        }
        if (!holding && original == -1) {
            dbg("place", autoSwap == 0 ? "not holding crystal (Auto swap None)" : crystalSlot == -1 ? "no crystal in hotbar" : "swap delay");
            return;
        }
        placeCrystal(base, crystalOffhand, dmg);
        if (!silentSwap() && !altSearch) return;
        if (original != -1) {
            if (altSearch) swapIntoSelectedSlot(crystalSlot);
            else if (autoSwap == 2) { ignoreNextSlotChange(); selectSlot(original); }
        }
        if (strictTiming || skip) skip = !skip;
    }

    private static void placeCrystal(BlockPos base, boolean offhand, double damage) {
        if (base == null) return;
        Direction dir = selectPlacementDirection(base);
        if (dir == null) { dbg("place", "no placement direction"); return; }
        if (!base.equals(lastAttackedBase) && lastAttackedBase != null && sequential == 0) { dbg("place", "sequential none: other base attacked"); return; }
        Vec3 hit = placementHit(base, dir);
        if (rotatePlace && rotate && !breakRotationRequested) setRotationTarget(hit);
        InteractionHand hand = offhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        sendUseItemOn(hand, new BlockHitResult(hit, dir, base, false));
        mc().player.swing(hand);
        dbg("place", "sent " + base.toShortString() + " " + dir + " " + hand);
        if (debug) lastDbg.remove("place");                       // log every send
        lastPlacedBase = base;
        if (render) {
            if (!multiFade) boxes.clear();
            boxes.add(new long[]{base.getX(), base.getY(), base.getZ(), System.currentTimeMillis()});
        }
        placeTimer.reset();
    }

    /** Rotation gate: are we looking at the crystal click point (a rotation is requested if not). */
    private static boolean facingBase(BlockPos base) {
        Direction dir = selectPlacementDirection(base);
        if (dir == null) return true;                              // placeCrystal won't place anyway
        return CombatSupport.look(new AABB(base), placementHit(base, dir), 3, CombatSupport.Type.PLACE);
    }

    private static Vec3 placementHit(BlockPos base, Direction dir) {
        Vec3 hit = base.getCenter().relative(dir, 0.5);
        Vec3 corner = null; double cd = Double.MAX_VALUE;
        for (Vec3 c : faceCorners(base, dir)) {
            if (!placementHitVisible(c, base, dir)) continue;
            double d = eye.distanceTo(c);
            if (d < cd) { cd = d; corner = c; }
        }
        if (corner != null) hit = corner;
        if (placeRange == placeWallRange) {
            hit = dir.getAxis() == Direction.Axis.Y ? base.getCenter().relative(dir, 0.5) : base.getCenter().relative(dir, 0.5).add(0, 0.5, 0);
        }
        return hit;
    }

    private static Direction selectPlacementDirection(BlockPos base) {
        List<Direction> dirs = strictDirections(base);
        if (dirs.isEmpty()) dirs = Arrays.asList(Direction.values());
        return dirs.stream().filter(d -> placementFaceVisible(base, d))
            .min(Comparator.comparingDouble(d -> eye.distanceTo(base.getCenter().relative(d, 0.5)))).orElse(null);
    }

    private static boolean placementFaceVisible(BlockPos base, Direction d) {
        if (placeRange == placeWallRange) return true;
        if (eye.distanceTo(base.getCenter()) < placeRange) return true;
        return CrystalDamage.canSeeAny(mc().player.getEyePosition(), sampleFace(base, d, true));
    }

    private static boolean placementHitVisible(Vec3 point, BlockPos base, Direction d) {
        if (placeRange == placeWallRange) return true;
        if (eye.distanceTo(point) >= placeWallRange) return CrystalDamage.canSee(eye, point, base, d);
        return true;
    }

    /** BasePlace: place obsidian if there is no crystal base. */
    private static void runForcedPlacement(BlockPos base) {
        Minecraft mc = mc();
        if (!canRetryBasePlacement()) return;
        int crystalSlot = findHotbarSlot(s -> s.is(Items.END_CRYSTAL));
        if (autoSwap == 2 && silent == 1) crystalSlot = findMenuSlot(Items.END_CRYSTAL);
        if (!mc.player.getOffhandItem().is(Items.END_CRYSTAL) && crystalSlot == -1) return;
        Direction dir = PlacementSupport.placeSide(base, true, placeWallRange <= 0);
        Vec3 hit = base.getCenter();
        if (dir != null) {
            Vec3 best = null; double bd = Double.MAX_VALUE;
            for (Vec3 p : sampleFace(base, dir, multipoint)) {
                if (!placementHitVisible(p, base, dir)) continue;
                double d = mc.player.getEyePosition().distanceTo(p);
                if (d < bd) { bd = d; best = p; }
            }
            hit = best != null ? best : hit.relative(dir, 0.5);
        }
        int original = mc.player.getInventory().getSelectedSlot();
        int baseSlot = findHotbarSlot(s -> s.is(Items.OBSIDIAN));
        boolean altSwap = silentSwap() && silent == 1;
        if (altSwap && baseSlot == -1) baseSlot = findMenuSlot(Items.OBSIDIAN);
        if (baseSlot == -1) return;
        if (dir == null && !basePlaceAir) return;
        if (!canPlaceBlockAt(base)) return;
        if (baseSlot != original) { if (altSwap) swapIntoSelectedSlot(baseSlot); else selectSlot(baseSlot); }
        PlacementSupport.click(base, dir, basePlaceAir, InteractionHand.MAIN_HAND);
        basePlaceTimer.reset();
        if (rotatePlace && rotate) setRotationTarget(hit);
        lastForcedBase = base;
        if (baseSlot != original) { if (altSwap) swapIntoSelectedSlot(baseSlot); else selectSlot(original); }
    }

    private static boolean canRetryBasePlacement() {
        if (lastForcedBase != null && target != null) {
            BlockPos fb = lastForcedBase;
            Validator v = new Validator();
            v.requireUpperAir = requireUpperAir; v.lowerHitbox = lowerHitBox; v.ignoreBaseBlock = true;
            if (!v.canPlace(fb)) return true;
            boolean visible = true;
            if (placeWallRange != placeRange) visible = CrystalDamage.canSeeAny(eye, sampleBlock(fb, multipoint));
            double dist = mc().player.getEyePosition().distanceTo(fb.getCenter());
            if (dist > placeRange) return true;
            if (!visible && dist > placeWallRange) return true;
            double dmg = CrystalDamage.crystal(Vec3.upFromBottomCenterOf(fb, 1.0), target, targetBounds(target));
            if (dmg >= minimumDamage(Policy.PLACE, target)) return false;
        }
        return basePlaceTimer.passed(Math.max(tickDurationMillis(), (long) basePlaceDelay));
    }

    // ------------------------------------------------------------- break loop

    private static void runBreakCycle() {
        Minecraft mc = mc();
        if (!breakOn || !breakTimer.passed((long) breakDelay) || swapPenaltyActive() || playerInvulnerable(target)) { dbg("break", "off / delay / penalty"); return; }
        if (!sequentialTimer.passed(ping()) && instant != 2 && await) { dbg("break", "await (instant break pending)"); return; }
        Entity best = null;
        double bestDamage = -1337.0;
        int seen = 0, inRange = 0;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof EndCrystal crystal)) continue;
            seen++;
            if (!canAttackCrystal(crystal)) continue;
            inRange++;
            double dmg = skip
                ? CrystalDamage.crystal(e.position(), target, targetBounds(target))
                : explosionDamage(BlockPos.containing(e.position()).below(), target, targetBounds(target), false);
            boolean intersect = false;
            if (lastPlacedBase != null && !e.blockPosition().equals(lastPlacedBase.above())
                && e.getBoundingBox().intersects(new AABB(lastPlacedBase.above()).expandTowards(0, 1, 0)) && !placeTimer.passed(200)) intersect = true;
            double self = CrystalDamage.crystal(e.position(), mc.player, mc.player.getBoundingBox());
            Lethality leth = intersect ? Lethality.INTERSECTION : Lethality.of(target, dmg);
            if (dmg <= self && !forceSuicide && !leth.lethal && !intersect) continue;
            if (!rejectDamage(Policy.HIT, target, self, dmg, leth)) {
                if (intersect && best == null) best = e;
                if (!(dmg < minimumDamage(Policy.HIT, target)) && dmg > bestDamage) { bestDamage = dmg; best = e; }
            }
        }
        if (best != null) attackCrystal((EndCrystal) best);
        else dbg("break", seen == 0 ? "no crystals" : "no breakable crystal (seen " + seen + ", in range/age " + inRange + ")");
    }

    static boolean canAttackCrystal(EndCrystal c) {
        if (c.tickCount < ticksExisted && !shouldIgnoreMiningBlock()) return false;
        if (c.isRemoved() || target == null) return false;
        LocalPlayer me = mc().player;
        boolean visible = me.hasLineOfSight(c);
        return me.getEyePosition().distanceTo(c.position()) <= (visible ? breakRange : breakWallRange);
    }

    private static void attackCrystal(EndCrystal c) {
        if (c.getId() == lastAttackedCrystalId && attackRetryTimer.passed(500) && c.isAlive() && !c.isRemoved()) attackRetries++;
        else attackRetries = 0;
        lastAttackedCrystalId = c.getId();
        if (attackRetries >= 5) attackRetryTimer.reset();
        AABB cb = c.getBoundingBox();
        Vec3 aim = new Vec3((cb.minX + cb.maxX) / 2, cb.minY + cb.getYsize() * 0.25, (cb.minZ + cb.maxZ) / 2);
        if (rotate && rotateBreak && !CombatSupport.look(cb, aim, 2, CombatSupport.Type.ATTACK)) { dbg("break", "waiting for rotation"); return; }   // MovementSync counterpart
        if (attackEntity(c.getId(), false)) {
            dbg("break", "attacked " + c.getId());
            if (debug) lastDbg.remove("break");
            lastAttackedBase = c.blockPosition().below();
            sendFactorAttacks(c.getId());
            if (rotateBreak && rotate) {
                AABB b = c.getBoundingBox();
                setRotationTarget(new Vec3((b.minX + b.maxX) / 2, b.minY + b.getYsize() * 0.25, (b.minZ + b.maxZ) / 2));
                breakRotationRequested = true;
            }
            breakTimer.reset();
            basePlaceTimer.reset();
        }
    }

    private static void sendFactorAttacks(int id) {
        if (attackFactor <= 1) return;
        for (Entity e : mc().level.entitiesForRendering()) {
            if (e instanceof LivingEntity && e.isRemoved()) return;
            if (!e.isAlive()) return;
        }
        for (int i = 1; i < (int) attackFactor; i++) {
            int pid = id + i;                                        // MovementSync off
            mc().getConnection().send(new ServerboundAttackPacket(pid));
            mc().getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
            predictedAttackIds.add(pid);
        }
    }

    /** CrystalInteractionManager.attackEntity: attack packet + hand swing (weapon selection under Weakness). */
    private static boolean attackEntity(int id, boolean instantAttack) {
        Minecraft mc = mc();
        if (instant == 2 && instantAttack) return false;
        int prev = mc.player.getInventory().getSelectedSlot();
        Entity e = mc.level.getEntity(id);
        boolean weak = e instanceof EndCrystal && selectWeaknessWeapon();
        interactionLastAttackedId = id;
        InteractionHand hand = e instanceof EndCrystal && !instantAttack && mc.player.getOffhandItem().getItem() == Items.END_CRYSTAL
            ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        mc.getConnection().send(new ServerboundAttackPacket(id));
        if (!instantAttack) mc.player.swing(hand, false);
        mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        if (weak && weakness == 2) { ignoreNextSlotChange(); selectSlot(prev); }
        return true;
    }

    private static boolean selectWeaknessWeapon() {
        LocalPlayer me = mc().player;
        boolean tool = me.getMainHandItem().has(DataComponents.TOOL);
        if (weakness == 0 || !me.hasEffect(MobEffects.WEAKNESS) || tool) return false;
        int slot = findHotbarSlot(s -> s.has(DataComponents.WEAPON));
        if (slot == -1) return false;
        if (weakness == 2) ignoreNextSlotChange();
        selectSlot(slot);
        return true;
    }

    // ------------------------------------------------------------- crystal spawned (ClientboundAddEntityPacket)

    public static void onAddEntity(ClientboundAddEntityPacket packet) {
        if (!enabled || worldUnavailable() || swapPenaltyActive()) return;
        try {
            if (shouldPause()) return;
            onCrystalSpawn(packet);
        } catch (Throwable ignored) { }
    }

    private static void onCrystalSpawn(ClientboundAddEntityPacket packet) {
        Minecraft mc = mc();
        if (packet.getType() != EntityType.END_CRYSTAL || target == null) return;
        Vec3 spawn = new Vec3(packet.getX(), packet.getY(), packet.getZ());
        if (lastPlacedBase != null && spawn.distanceToSqr(lastPlacedBase.getCenter()) <= 9.0) placementTimes.add(System.currentTimeMillis());
        if (instant == 2 || !breakOn || playerInvulnerable(target)) { dbg("instant", "off"); return; }
        if (shouldIgnoreMiningBlock() && await) return;
        boolean visible = true;
        if (breakRange != breakWallRange) visible = CrystalDamage.canSee(eye, spawn);
        if (mc.player.getEyePosition().distanceTo(spawn) > (visible ? breakRange : breakWallRange)) { dbg("instant", "out of range"); return; }
        double dmg = CrystalDamage.crystal(spawn, target);
        double self = CrystalDamage.crystal(spawn, mc.player);
        Lethality leth = Lethality.of(target, dmg);
        if (dmg <= self && !forceSuicide && !leth.lethal) { dbg("instant", "self damage too high"); return; }
        if (dmg < minimumDamage(Policy.HIT, target)) { dbg("instant", String.format(java.util.Locale.ROOT, "damage %.1f < min", dmg)); return; }
        if (rejectDamage(Policy.HIT, target, self, dmg, leth)) { dbg("instant", "rejected by damage policy"); return; }
        BlockPos pos = BlockPos.containing(spawn);
        if (instant == 1 && pos.equals(previousPlacement)) return;
        if (rotate && rotateBreak) {
            setRotationTarget(spawn);
            AABB cb = new AABB(spawn.x - 1, spawn.y, spawn.z - 1, spawn.x + 1, spawn.y + 2, spawn.z + 1);
            if (!CombatSupport.look(cb, spawn.add(0, 0.5, 0), 2, CombatSupport.Type.ATTACK)) { dbg("instant", "waiting for rotation"); return; }   // no Instant if we aren't looking
        }
        if (attackEntity(packet.getId(), true)) {
            dbg("instant", "attacked " + packet.getId());
            if (debug) lastDbg.remove("instant");
            lastAttackedBase = pos;
            previousPlacement = pos;
            lastAttackedCrystalId = packet.getId();
            attackRetries = 0;
            basePlaceTimer.reset();
        }
        // 2b2t off: follow-up placement is always free
        if (sequential != 0 && !spawnFollowupQueued) {
            sequentialTimer.reset();
            runPlacementCycle();
            if (sequential == 1) spawnFollowupQueued = true;
        }
        sendFactorAttacks(packet.getId());
    }

    /** ClientboundEntityEventPacket 3: the target died on the server. */
    public static void onEntityEvent(Entity e, byte id) {
        if (id == 3 && target != null && e != null && e.getId() == target.getId()) serverDead.add(e.getId());
    }

    /** ClientboundPlayerCombatKillPacket: we died, ForceSuicide turns off. */
    public static void onCombatKill() { if (enabled) dbg("death", "combat kill packet, ForceSuicide off"); setForceSuicide(false); }

    /** The server corrected our position (setback). */
    public static void onSetback() { setbackTimer.reset(); }

    // ------------------------------------------------------------- duraklama

    private static boolean shouldPause() { return pauseReason() != null; }

    /** Lure shouldPause; the reason if pausing (for debug), else null. */
    private static String pauseReason() {
        Minecraft mc = mc();
        if (worldUnavailable()) return "world unavailable";
        if (invalidTarget(target)) return "no valid target";
        LocalPlayer me = mc.player;
        if (me.isSleeping()) return "sleeping";
        if (me.isUsingItem() && pauseEating) return "eating";
        if (pauseMending && me.isUsingItem() && me.getUseItem().is(Items.EXPERIENCE_BOTTLE)) return "mending";
        if (mc.gameMode.isDestroying() && pauseMining) return "mining";
        if (!forceSuicide) return null;
        double anchor = AnchorAura.plannedDamage();
        if (anchor >= 0 && placementData != null && anchor > placementData.damage) return "AnchorAura does more damage";
        return me.isDeadOrDying() ? "dead" : null;
    }

    /** No mining module (SpeedMine): no block is "about to break". */
    private static boolean shouldIgnoreMiningBlock() { return false; }

    private static boolean selfInvulnerable() {
        if (!assumeSelfInvincibility) return false;
        return mc().player.tickCount / 20f < invulnerabilityTimeout();
    }

    private static float invulnerabilityTimeout() { return assumeInvincibility ? (float) invincibilityTimeout : -1f; }

    private static boolean playerInvulnerable(LivingEntity t) {
        if (selfInvulnerable() || !(t instanceof Player p)) return false;
        Long join = joinTimes.get(p.getUUID());
        if (join == null) return false;
        return System.currentTimeMillis() - join <= invulnerabilityTimeout() * 1000f;
    }

    private static void trackJoins() {
        var conn = mc().getConnection();
        if (conn == null) return;
        Set<UUID> online = new HashSet<>();
        long now = System.currentTimeMillis();
        for (PlayerInfo info : conn.getOnlinePlayers()) { online.add(info.getProfile().id()); joinTimes.putIfAbsent(info.getProfile().id(), now); }
        joinTimes.keySet().retainAll(online);
    }

    private static boolean quiverThreat() {
        if (quiverThreatActive) return false;
        if (!antiQuiver || !(target instanceof Player) || !target.isAlive()) return false;
        for (Entity e : mc().level.entitiesForRendering()) {
            if (!(e instanceof Arrow) || e.getY() < target.getEyeY()) continue;
            Vec3 a = e.position(), te = target.getEyePosition();
            boolean h = Math.hypot(a.x - te.x, a.z - te.z) < 0.5;
            boolean v = Math.abs(target.getEyeY() - e.getEyeY()) < 4.0;
            if (h && v) return true;
        }
        return false;
    }

    // ------------------------------------------------------------- swap (InventoryUtils)

    private static boolean silentSwap() { return autoSwap == 2; }
    private static boolean swapDelayActive() { return autoSwap == 1 && !swapDelayTimer.passed((long) (50.0 * swapDelay)); }
    private static boolean swapPenaltyActive() { return !swapPenaltyTimer.passed((long) (50.0 * swapPenalty)); }
    private static boolean shouldRestoreSlot() { return autoSwap == 1 && swapBack; }
    private static void ignoreNextSlotChange() { ignoredSlotChanges++; }

    private static void restoreSelectedSlot() {
        if (worldUnavailable()) return;
        if (shouldRestoreSlot() && restoreSlot != -1) { selectSlot(restoreSlot); restoreSlot = -1; }
    }

    /** SelectedSlotChangeEvent: a slot change that isn't ours resets the swap delay/penalty timers. */
    private static void trackSlotChange(int slot) {
        if (observedSlot == -1) { observedSlot = slot; return; }
        if (slot == observedSlot) return;
        observedSlot = slot;
        if (ignoredSlotChanges > 0) ignoredSlotChanges--;
        else { swapPenaltyTimer.reset(); swapDelayTimer.reset(); }
    }

    private static void selectSlot(int slot) {
        if (slot < 0 || slot >= 9) return;
        CombatSupport.select(slot);
        if (ignoredSlotChanges > 0) { ignoredSlotChanges--; observedSlot = slot; }
        else { observedSlot = slot; swapPenaltyTimer.reset(); swapDelayTimer.reset(); }
    }

    private static int findHotbarSlot(Predicate<ItemStack> f) {
        LocalPlayer me = mc().player;
        if (f.test(me.getMainHandItem())) return me.getInventory().getSelectedSlot();
        for (int i = 0; i < 9; i++) if (f.test(me.getInventory().getItem(i))) return i;
        return -1;
    }

    private static int toMenuSlot(int slot) { return slot < 9 ? slot + 36 : slot; }

    private static int findMenuSlot(Item item) {
        LocalPlayer me = mc().player;
        if (me.getOffhandItem().is(item)) return -1;
        for (int i = 35; i >= 0; i--) if (me.getInventory().getItem(i).is(item)) return toMenuSlot(i);
        return -1;
    }

    private static void swapIntoSelectedSlot(int slot) {
        if (slot < 0) return;
        LocalPlayer me = mc().player;
        mc().gameMode.handleContainerInput(me.containerMenu.containerId, toMenuSlot(slot), me.getInventory().getSelectedSlot(), ContainerInput.SWAP, me);
    }

    /** ForceSuicide NoArmor: drop the first armor piece without Curse of Binding. */
    private static void throwUnboundArmor() {
        LocalPlayer me = mc().player;
        Holder<Enchantment> binding;
        try { binding = mc().level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.BINDING_CURSE); }
        catch (Throwable t) { return; }
        EquipmentSlot[] order = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        int[] menuSlots = {5, 6, 7, 8};
        for (int i = 0; i < order.length; i++) {
            ItemStack st = me.getItemBySlot(order[i]);
            if (st.isEmpty() || EnchantmentHelper.getItemEnchantmentLevel(binding, st) > 0) continue;
            mc().gameMode.handleContainerInput(me.containerMenu.containerId, menuSlots[i], 1, ContainerInput.THROW, me);
            break;
        }
    }

    // ------------------------------------------------------------- paket

    private static void sendUseItemOn(InteractionHand hand, BlockHitResult hit) {
        ((com.futureplus.client.mixin.MultiPlayerGameModeAccessor) mc().gameMode)
            .futureplus$predict(mc().level, seq -> new ServerboundUseItemOnPacket(hand, hit, seq));
    }

    private static long ping() {
        var conn = mc().getConnection();
        if (conn == null || mc().player == null) return 0;
        PlayerInfo info = conn.getPlayerInfo(mc().player.getUUID());
        return info == null ? 0 : info.getLatency();
    }

    private static long tickDurationMillis() { return 500L; }   // lure: 500 / server tick scale (1.0)

    // ------------------------------------------------------------- helpers

    private static void updateEye() { if (!worldUnavailable()) eye = mc().player.getEyePosition(); }

    private static boolean isBedrockOrObsidian(BlockPos p) {
        Block b = mc().level.getBlockState(p).getBlock();
        return b == Blocks.BEDROCK || b == Blocks.OBSIDIAN;
    }

    private static boolean canPlaceBlockAt(BlockPos p) {
        if (p.getY() < mc().level.getMinY() || p.getY() > mc().level.getMaxY()) return false;
        if (!mc().level.getBlockState(p).canBeReplaced()) return false;
        for (Entity e : mc().level.getEntities((Entity) null, new AABB(p), Entity::isAlive)) if (e.blocksBuilding) return false;
        return true;
    }

    static AABB targetBounds(LivingEntity t) {
        if (prediction && t instanceof Player p) return MotionPredictor.predicted(p, (int) predictionTicks);
        return interpolationBounds(t);
    }

    /** BoundingBoxUtils.getInterpolationBounds: araya giren konum hedefindeki kutu. */
    static AABB interpolationBounds(LivingEntity e) {
        AABB b = e.getBoundingBox();
        if (FakePlayerPlus.isFake(e)) return b;
        Vec3 pos = e.getInterpolation() == null ? null : e.getInterpolation().position();
        if (pos == null || pos.lengthSqr() == 0.0) return b;
        double hx = b.getXsize() / 2.0, hz = b.getZsize() / 2.0;
        return new AABB(-hx, 0, -hz, hx, b.getYsize(), hz).move(pos);
    }

    private static double explosionDamage(BlockPos base, LivingEntity t, AABB bounds, boolean ignoreBaseBlock) {
        Collection<BlockPos> ignored = ignoreBaseBlock ? ignoredBlocks : Collections.emptyList();
        return CrystalDamage.explosion(Vec3.upFromBottomCenterOf(base, 1.0), t, bounds, 6.0, true, ignored, ignoreBaseBlock ? base.immutable() : null);
    }

    /** BlockInteractionUtils.getStrictDirections (StrictDirection = NCP). */
    private static List<Direction> strictDirections(BlockPos pos) {
        Minecraft mc = mc();
        List<Direction> dirs = new ArrayList<>(List.of(Direction.DOWN, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.UP));
        for (Direction d : List.copyOf(dirs)) {
            BlockPos n = pos.relative(d);
            BlockState s = mc.level.getBlockState(n);
            if (!s.canBeReplaced() && s.isSolidRender()) dirs.remove(d);
        }
        if (pos.getY() + 1 > mc.level.getMaxY()) dirs.remove(Direction.UP);
        Vec3 e = mc.player.getEyePosition();
        double dx = e.x - pos.getCenter().x, dz = e.z - pos.getCenter().z;
        if (dx > 0.5) dirs.remove(Direction.WEST); else if (dx < -0.5) dirs.remove(Direction.EAST);
        else { dirs.remove(Direction.WEST); dirs.remove(Direction.EAST); }
        if (dz > 0.5) dirs.remove(Direction.NORTH); else if (dz < -0.5) dirs.remove(Direction.SOUTH);
        else { dirs.remove(Direction.NORTH); dirs.remove(Direction.SOUTH); }
        if (e.y < pos.getY()) dirs.remove(Direction.UP);
        if (e.y > pos.getY()) dirs.remove(Direction.DOWN);
        return dirs;
    }

    private static final Vec3[] FAST = {new Vec3(0.5, 0.5, 0.5)};
    private static final Vec3[] X8 = {
        new Vec3(0.05, 0.05, 0.05), new Vec3(0.05, 0.05, 0.95), new Vec3(0.05, 0.95, 0.05), new Vec3(0.05, 0.95, 0.95),
        new Vec3(0.95, 0.05, 0.05), new Vec3(0.95, 0.05, 0.95), new Vec3(0.95, 0.95, 0.05), new Vec3(0.95, 0.95, 0.95)};

    private static List<Vec3> sampleBlock(BlockPos pos, boolean x8) {
        List<Vec3> out = new ArrayList<>();
        for (Vec3 o : x8 ? X8 : FAST) out.add(o.add(Vec3.atLowerCornerOf(pos)));
        return out;
    }

    private static boolean oppositeFace(int step, int rounded) { return step == 1 && rounded == 0 || step == -1 && rounded == 1; }

    private static List<Vec3> sampleFace(BlockPos pos, Direction face, boolean x8) {
        List<Vec3> out = new ArrayList<>();
        for (Vec3 o : x8 ? X8 : FAST) {
            if (oppositeFace(face.getStepX(), (int) Math.round(o.x))) continue;
            if (oppositeFace(face.getStepY(), (int) Math.round(o.y))) continue;
            if (oppositeFace(face.getStepZ(), (int) Math.round(o.z))) continue;
            out.add(o.add(Vec3.atLowerCornerOf(pos)));
        }
        return out;
    }

    /** MathUtils.faceCorners. */
    private static List<Vec3> faceCorners(BlockPos p, Direction d) {
        Direction.Axis ax = d.getAxis();
        double x = p.getX(), y = p.getY(), z = p.getZ();
        double off = d.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0 : 0.0;
        if (ax == Direction.Axis.X) x += off;
        if (ax == Direction.Axis.Y) y += off;
        if (ax == Direction.Axis.Z) z += off;
        Direction.Axis a1 = ax == Direction.Axis.X ? Direction.Axis.Y : Direction.Axis.X;
        Direction.Axis a2 = ax == Direction.Axis.Z ? Direction.Axis.Y : Direction.Axis.Z;
        return List.of(corner(x, y, z, a1, a2, 0, 0), corner(x, y, z, a1, a2, 1, 0), corner(x, y, z, a1, a2, 1, 1), corner(x, y, z, a1, a2, 0, 1));
    }

    private static Vec3 corner(double x, double y, double z, Direction.Axis a1, Direction.Axis a2, int o1, int o2) {
        return new Vec3(
            x + (a1 == Direction.Axis.X ? o1 : 0) + (a2 == Direction.Axis.X ? o2 : 0),
            y + (a1 == Direction.Axis.Y ? o1 : 0) + (a2 == Direction.Axis.Y ? o2 : 0),
            z + (a1 == Direction.Axis.Z ? o1 : 0) + (a2 == Direction.Axis.Z ? o2 : 0));
    }

    // ------------------------------------------------------------- rendering (FeatureBoxRenderer)

    static boolean wantsRender() { return enabled && render && !boxes.isEmpty(); }

    static void emit(Vec3 cam) {
        long now = System.currentTimeMillis();
        long life = (long) (fadeTime * 1000);
        boxes.removeIf(b -> fade && now - b[3] > life);
        for (long[] b : boxes) {
            float a = fade ? 1f - Math.min(1f, (now - b[3]) / (float) life) : 1f;
            if (a <= 0) continue;
            AABB box = new AABB(new BlockPos((int) b[0], (int) b[1], (int) b[2])).move(-cam.x, -cam.y, -cam.z);
            WorldDraw.boxFill(true, box, (Math.round(((FILL >>> 24) & 255) * a) << 24) | (FILL & 0xFFFFFF));
            WorldDraw.boxOutline(true, box, 1f, (Math.round(((OUTLINE >>> 24) & 255) * a) << 24) | (OUTLINE & 0xFFFFFF));
        }
    }
}
