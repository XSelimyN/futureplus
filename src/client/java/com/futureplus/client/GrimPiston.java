package com.futureplus.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownExperienceBottle;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * PistonAura Grim mode - ported from OpenHachimi's PistonCrystal.
 *
 * Planning follows Hachimi: every cell around the target that gives enough damage is an explosion cell (E), sorted by damage; for each E and
 * each horizontal direction d the crystal stands on obsidian next to E (E + d) and a piston pushes it into E. The piston is looked for on
 * Hachimi's grid (height 0/1, sideways 0/-1/+1, 1-3 cells away) because a crystal's hitbox is 2x2x2 and so a piston head only has to touch it.
 * An existing redstone block next to the piston is used as it is. The hit follows Hachimi's grimDesync: the aim is taken from the part of
 * the crystal's box that overlaps the box of where it spawned.
 *
 * It keeps to what Grim checks: vanilla reach, a look at what is clicked, and the piston's facing coming from that look.
 * It uses what Grim lets through: clicking through walls and placing in the air.
 *
 * Each tick looks at the world and sends what is still missing in one go: the base, the piston, the redstone, then the crystal. The redstone
 * can go first as a piston only moves on the next tick, by when the crystal stands in front of it. After the hit, a
 * piston or redstone left standing is taken out by a breaker crystal, or else by clicking the block: the mining is done by Future running
 * yapar.
 *
 * Hidden bases: GrimSwapManager, GrimRotation, GrimPlacementManager, GrimCrystalUtil, GrimDiag.
 */
final class GrimPiston {
    private GrimPiston() {}

    private static final int FACE_SAMPLES = 7;
    private static final int RETRIES = 3;
    private static final int BAD_TICKS = 100;
    private static final int PUSH_TIMEOUT_TICKS = 14;
    private static final int PUSH_TICKS = 2;
    private static final int CLEAN_AFTER_IDLE_TICKS = 20;
    private static final int BREAKER_RANGE = 3;
    private static final double BREAKER_REACHES_PISTON = 3.6;
    private static final float BLAST_PROOF = 600f;
    private static final long REASONS_EVERY_MS = 1000;
    private static final double CRYSTAL_HEIGHT = 2.0;
    private static final int MINE_RECLICK_TICKS = 60;
    private static final double MAX_MINE_DISTANCE = 6.0;
    private static final long OPENING_KEEP_MS = 5000;

    // ------------------------------------------------------------- ayarlar
    static double targetRange = 6.0, attackRange = 3.0, minDamage = 6.0, maxSelfDamage = 12.0;
    static double attackDelay = 0.1;   // seconds; between two attacks (rounded to ticks)
    static int pistonDelay = 0, fireDelay = 3;
    static boolean predictFire = false, autoBase = true, breaker = true, cleanup = true;
    static boolean pistonSearch = true, grimDesync = true;   // Hachimi: wide piston grid / spawn-box aim

    private static int delayTicks() { return (int) Math.round(attackDelay * 20); }

    static List<Setting> settings(java.util.function.BooleanSupplier visible) {
        List<Setting> s = new ArrayList<>();
        s.add(Setting.num("Target Range", 2, 8, 0.1, () -> targetRange, v -> targetRange = v).when(visible));
        s.add(Setting.num("Attack Range", 2, 6, 0.1, () -> attackRange, v -> attackRange = v).when(visible));
        s.add(Setting.num("Min Damage", 0, 36, 0.1, () -> minDamage, v -> minDamage = v).when(visible));
        s.add(Setting.num("Max Self Damage", 0, 36, 0.1, () -> maxSelfDamage, v -> maxSelfDamage = v).when(visible));
        s.add(Setting.num("Attack Delay (s)", 0, 5, 0.05, () -> attackDelay, v -> attackDelay = v).when(visible));
        s.add(Setting.num("Piston Delay", 0, 40, 1, () -> pistonDelay, v -> pistonDelay = (int) v).when(visible));
        s.add(Setting.bool("Piston Search", () -> pistonSearch, v -> pistonSearch = v).when(visible));
        s.add(Setting.bool("Grim Desync", () -> grimDesync, v -> grimDesync = v).when(visible));
        s.add(Setting.bool("Predict Fire", () -> predictFire, v -> predictFire = v).when(visible));
        s.add(Setting.num("Fire Delay", 1, 8, 1, () -> fireDelay, v -> fireDelay = (int) v).when(() -> visible.getAsBoolean() && predictFire));
        s.add(Setting.bool("Auto Base", () -> autoBase, v -> autoBase = v).when(visible));
        s.add(Setting.bool("Breaker Crystal", () -> breaker, v -> breaker = v).when(visible));
        s.add(Setting.bool("Cleanup", () -> cleanup, v -> cleanup = v).when(visible));
        return s;
    }

    // ------------------------------------------------------------- durum
    private static Rig rig;
    private static int waitUntil;
    private static int tries;
    private static int builtAge = -1;
    private static int lastPistonAge = Integer.MIN_VALUE / 2;
    private static int extendedSince = -1;
    private static int cooldownUntil;
    private static int lastRigAge;
    private static BlockPos breakerSpot;
    private static int breakerAge;
    private static final Map<BlockPos, Integer> bad = new HashMap<>();
    private static final Set<BlockPos> ours = new HashSet<>();
    private static final Map<String, Integer> reasons = new TreeMap<>();
    private static long lastReasons;
    private static BlockPos mineClick;
    private static int mineClickAge;
    private static final Map<BlockPos, Long> recentlyMined = new HashMap<>();
    private static final Map<Integer, BlockPos> spawned = new HashMap<>();   // crystal id -> cell it was first seen in
    static String status = "-";

    private static Minecraft mc() { return Minecraft.getInstance(); }

    static void reset() {
        rig = null;
        waitUntil = 0;
        tries = 0;
        builtAge = -1;
        cooldownUntil = 0;
        breakerSpot = null;
        bad.clear();
        ours.clear();
        reasons.clear();
        mineClick = null;
        recentlyMined.clear();
        spawned.clear();
    }

    /** The piston, redstone and crystal cell to draw; null without a rig. */
    static BlockPos[] shownRig() {
        Rig r = rig;
        return r == null ? null : new BlockPos[]{r.piston, r.redstone, r.crystal};
    }

    static Player target() { Rig r = rig; return r != null ? r.target : null; }

    private static void log(String message) {
        if (!PistonAura.debug) return;
        DebugLog.write("PistonAura/Grim", message);
        Minecraft mc = mc();
        if (mc.player != null) mc.player.sendSystemMessage(FontMod.comp("[PA/Grim] " + message));
    }

    static void tick() {
        Minecraft mc = mc();
        int age = mc.player.tickCount;
        trackCrystals();
        bad.values().removeIf(until -> until < age);
        ours.removeIf(position -> !isPiston(mc.level.getBlockState(position)) && !mc.level.getBlockState(position).is(Blocks.REDSTONE_BLOCK));

        if (GrimSwapManager.shouldDefer() || mc.player.isUsingItem() || age < cooldownUntil) return;
        if (slotOf(Items.PISTON, Items.STICKY_PISTON) < 0 || crystalHand() == null || GrimSwapManager.hotbar(Items.REDSTONE_BLOCK) < 0) {
            status = "missing item";
            countReason("missing items");
            logReasons();
            return;
        }

        if (breakerSpot != null) {
            tickBreaker(age);
            return;
        }

        if (rig != null && !rig.target.isAlive()) drop("target gone");
        if (rig != null && rig.target.position().distanceToSqr(rig.targetPos) > 2.25) drop("target moved");
        if (rig == null) {
            rig = findRig();
            if (rig == null) {
                status = "no setup";
                if (itemBlocked != null && breaker && tryBreaker(itemBlocked)) {
                    log("breaker crystal for the item at " + itemBlocked.toShortString());
                    itemBlocked = null;
                    return;
                }
                logReasons();
                if (age - lastRigAge > CLEAN_AFTER_IDLE_TICKS) cleanUp();
                return;
            }
            tries = 0;
            builtAge = -1;
            extendedSince = -1;
            log("rig target=" + rig.target.getPlainTextName() + " head=" + rig.head.toShortString() + " crystal=" + rig.crystal.toShortString() + " base=" + rig.base.toShortString()
                    + " d=" + rig.direction + " P=" + rig.piston.toShortString() + " R=" + rig.redstone.toShortString() + " damage=" + String.format("%.1f", rig.damage));
        }

        lastRigAge = age;
        step(rig, age);
    }

    private static void step(Rig r, int age) {
        Minecraft mc = mc();
        BlockState pistonState = mc.level.getBlockState(r.piston);
        boolean pistonRight = isPiston(pistonState) && pistonState.getValue(BlockStateProperties.FACING) == r.direction.getOpposite();
        boolean headThere = mc.level.getBlockState(r.front).is(Blocks.PISTON_HEAD);
        boolean extended = (pistonRight && pistonState.getValue(BlockStateProperties.EXTENDED)) || headThere;
        EndCrystal crystal = findCrystal(r);
        boolean redstoneThere = mc.level.getBlockState(r.redstone).is(Blocks.REDSTONE_BLOCK);

        // A piston takes two ticks to go out; a crystal hit before that blows up where it stood.
        if (extended) {
            if (extendedSince < 0) extendedSince = age;
        } else {
            extendedSince = -1;
        }
        boolean pushDone = headThere || (extendedSince >= 0 && age - extendedSince >= PUSH_TICKS);
        boolean pushed = pushDone || (crystal != null && moved(crystal, r));
        boolean predicted = predictFire && redstoneThere && builtAge >= 0 && age - builtAge >= fireDelay;
        if (crystal != null && (pushed || predicted)) {
            hit(r, crystal, pushed ? "pushed" : "predicted");
            return;
        }
        if (crystal != null && extended) return;
        if (extended && crystal == null) {
            if (age < waitUntil) return;
            drop("piston went out with no crystal");
            ours.add(r.piston);
            mark(r.crystal);
            return;
        }
        if (isPiston(pistonState) && !pistonRight) {
            log("piston faces " + pistonState.getValue(BlockStateProperties.FACING) + ", wanted " + r.direction.getOpposite());
            ours.add(r.piston);
            drop("wrong facing");
            mark(r.piston);
            return;
        }

        boolean needsBase = !isCrystalBase(r.base);
        boolean needsPiston = !pistonRight;
        boolean needsRedstone = !redstoneThere;
        boolean needsCrystal = crystal == null;
        if (!needsBase && !needsPiston && !needsRedstone && !needsCrystal) {
            status = "pushing";
            if (builtAge >= 0 && age - builtAge > PUSH_TIMEOUT_TICKS + pingTicks()) {
                log("piston never went out");
                mark(r.piston);
                hit(r, crystal, "not pushed");
            }
            return;
        }

        if (age < waitUntil) { status = "waiting"; return; }
        if (tries >= RETRIES) {
            drop("still missing after " + tries + " tries: " + (needsBase ? "base " : "") + (needsPiston ? "piston " : "") + (needsRedstone ? "redstone " : "") + (needsCrystal ? "crystal" : ""));
            mark(r.crystal);
            return;
        }

        // A standing crystal's box covers the piston's cell; nothing goes there until it is gone.
        if (needsPiston && crystal != null) {
            hit(r, crystal, "crystal in the way");
            return;
        }

        if (needsPiston && age - lastPistonAge < pistonDelay) return;

        tries++;
        status = "setting up";
        Set<BlockPos> solid = new HashSet<>();
        if (needsBase) {
            if (!placeBlock("base", r.base, null, solid, slotOf(Items.OBSIDIAN, Items.OBSIDIAN), null)) {
                drop("base could not be placed");
                mark(r.base);
                return;
            }
            solid.add(r.base);
        }
        if (needsPiston) {
            Predicate<float[]> facing = look -> lookDirection(look) == r.direction;
            if (!placeBlock("piston", r.piston, r.crystal, solid, slotOf(Items.PISTON, Items.STICKY_PISTON), facing)) {
                drop("no look gives the piston its facing");
                mark(r.piston);
                return;
            }
            lastPistonAge = age;
            ours.add(r.piston);
            solid.add(r.piston);
        }
        if (needsRedstone) {
            if (!placeBlock("redstone", r.redstone, r.crystal, solid, GrimSwapManager.hotbar(Items.REDSTONE_BLOCK), null)) {
                drop("redstone could not be placed");
                mark(r.redstone);
                return;
            }
            ours.add(r.redstone);
        }
        if (needsCrystal) placeCrystal(r.base, r.crystal);

        builtAge = age;
        waitUntil = age + 2 * pingTicks() + 2;
    }

    private static boolean placeBlock(String what, BlockPos pos, BlockPos avoid, Set<BlockPos> solid, int slot, Predicate<float[]> lookOk) {
        if (slot < 0) return false;

        Click click = blockClick(pos, avoid, solid, lookOk);
        if (click == null) return false;

        return send(what, pos, click, slot);
    }

    private static void placeCrystal(BlockPos base, BlockPos cell) {
        Click click = crystalClick(base);
        if (click == null) return;

        send("crystal", cell, click, crystalHand() == InteractionHand.OFF_HAND ? -2 : GrimSwapManager.hotbar(Items.END_CRYSTAL));
    }

    /** One look on each side of the packet: when a look follows right away, Grim judges the placement by that next look. */
    private static boolean send(String what, BlockPos expected, Click click, int slot) {
        Minecraft mc = mc();
        InteractionHand hand = slot == -2 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        Runnable packet = () -> {
            GrimRotation.silentLook(click.yaw, click.pitch);
            ((com.futureplus.client.mixin.MultiPlayerGameModeAccessor) mc.gameMode).futureplus$predict(mc.level, seq -> {
                if (PistonAura.debug) GrimDiag.notePlace(what, click.hit, expected, seq);
                return new ServerboundUseItemOnPacket(hand, click.hit, seq);
            });
            mc.player.swing(hand);
            GrimRotation.silentLook(click.yaw, click.pitch);
        };

        if (slot == -2) {
            packet.run();
            return true;
        }
        return GrimSwapManager.withSwap(slot, GrimSwapManager.PLACEMENT, packet);
    }

    private static void hit(Rig r, EndCrystal crystal, String why) {
        Minecraft mc = mc();
        if (distanceSq(mc.player.getEyePosition(), aimBox(crystal)) > square(attackRange)) {
            drop("crystal out of hit range (" + why + ")");
            return;
        }

        log("hit (" + why + ") crystal at " + String.format("%.2f %.2f %.2f", crystal.getX(), crystal.getY(), crystal.getZ()) + " head=" + r.head.toShortString()
                + " ticksSinceBuilt=" + (builtAge < 0 ? -1 : mc.player.tickCount - builtAge) + " ticksSinceExtended=" + (extendedSince < 0 ? -1 : mc.player.tickCount - extendedSince));
        attack(crystal);
        PistonAura.noteFire();
        status = "detonate";
        rig = null;
        cooldownUntil = mc.player.tickCount + delayTicks();
    }

    /** Hachimi's grimDesync: the box where the crystal first showed up; its overlap with the real box is what gets looked at. */
    private static AABB aimBox(EndCrystal crystal) {
        AABB real = crystal.getBoundingBox();
        if (!grimDesync) return real;

        BlockPos first = spawned.get(crystal.getId());
        if (first == null) return real;

        AABB spawn = crystalBox(new Vec3(first.getX() + 0.5, first.getY(), first.getZ() + 0.5));
        return real.intersects(spawn) ? real.intersect(spawn) : real;
    }

    private static void trackCrystals() {
        Minecraft mc = mc();
        Set<Integer> alive = new HashSet<>();
        for (EndCrystal crystal : mc.level.getEntitiesOfClass(EndCrystal.class, mc.player.getBoundingBox().inflate(24), Entity::isAlive)) {
            alive.add(crystal.getId());
            spawned.putIfAbsent(crystal.getId(), crystal.blockPosition());
        }
        spawned.keySet().retainAll(alive);
    }

    private static void attack(EndCrystal crystal) {
        Minecraft mc = mc();
        Vec3 eye = mc.player.getEyePosition();
        float[] look = GrimRotation.getRotationsTo(eye, GrimPlacementManager.lookPoint(eye, aimBox(crystal)));
        GrimRotation.silentLook(look[0], look[1]);
        mc.gameMode.attack(mc.player, crystal);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    private static void drop(String why) {
        if (rig != null) log("rig dropped: " + why);
        rig = null;
    }

    private static void mark(BlockPos position) {
        bad.put(position.immutable(), mc().player.tickCount + BAD_TICKS);
    }

    /** Pistons and redstone we left behind: a powered piston would ruin the next rig, and they fill up the area. */
    private static void cleanUp() {
        if (!cleanup || ours.isEmpty()) return;

        BlockPos target = null;
        double best = Double.MAX_VALUE;
        for (BlockPos position : ours) {
            double distance = mc().player.getEyePosition().distanceToSqr(Vec3.atCenterOf(position));
            if (distance < best) {
                best = distance;
                target = position;
            }
        }
        if (target == null) return;

        if (breaker && tryBreaker(target)) return;
        if (requestBreak(target)) log("mining leftover " + target.toShortString());
    }

    /** A second crystal next to what is left: its blast takes all of it out at once, which is faster than mining. */
    private static boolean tryBreaker(BlockPos target) {
        Minecraft mc = mc();
        Vec3 targetCenter = Vec3.atCenterOf(target);
        Vec3 eye = mc.player.getEyePosition();
        BlockPos spot = null;
        double best = Double.MAX_VALUE;
        for (int x = -BREAKER_RANGE; x <= BREAKER_RANGE; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -BREAKER_RANGE; z <= BREAKER_RANGE; z++) {
                    BlockPos cell = target.offset(x, y, z);
                    if (cell.equals(target) || !isCrystalBase(cell.below()) || !mc.level.getBlockState(cell).isAir() || !mc.level.getBlockState(cell.above()).isAir()) continue;

                    AABB space = new AABB(cell.getX(), cell.getY(), cell.getZ(), cell.getX() + 1, cell.getY() + CRYSTAL_HEIGHT, cell.getZ() + 1);
                    if (!mc.level.getEntities((Entity) null, space).isEmpty()) continue;

                    Vec3 center = Vec3.atBottomCenterOf(cell).add(0, 0.05, 0);
                    double distance = center.distanceTo(targetCenter);
                    if (distance > BREAKER_REACHES_PISTON || distance >= best || blastBlocked(center, targetCenter, target)) continue;
                    if (crystalClick(cell.below()) == null || distanceSq(eye, crystalBox(new Vec3(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5))) > square(attackRange)) continue;

                    best = distance;
                    spot = cell.immutable();
                }
            }
        }
        if (spot == null) return false;

        log("breaker crystal " + spot.toShortString() + " for " + target.toShortString());
        placeCrystal(spot.below(), spot);
        breakerSpot = spot;
        breakerAge = mc.player.tickCount;
        status = "breaker crystal";
        return true;
    }

    private static void tickBreaker(int age) {
        for (EndCrystal crystal : mc().level.getEntitiesOfClass(EndCrystal.class, new AABB(breakerSpot), Entity::isAlive)) {
            attack(crystal);
            breakerSpot = null;
            cooldownUntil = age + delayTicks();
            return;
        }
        if (age - breakerAge > 2 * pingTicks() + 4) {
            log("breaker crystal never came " + breakerSpot.toShortString());
            breakerSpot = null;
        }
    }

    private static boolean blastBlocked(Vec3 from, Vec3 to, BlockPos target) {
        Vec3 difference = to.subtract(from);
        int steps = (int) Math.ceil(difference.length() / 0.25);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int i = 1; i < steps; i++) {
            double fraction = (double) i / steps;
            cursor.set(Mth.floor(from.x + difference.x * fraction), Mth.floor(from.y + difference.y * fraction), Mth.floor(from.z + difference.z * fraction));
            if (cursor.equals(target)) return false;
            if (mc().level.getBlockState(cursor).getBlock().getExplosionResistance() >= BLAST_PROOF) return true;
        }
        return false;
    }

    private static EndCrystal findCrystal(Rig r) {
        AABB area = new AABB(r.crystal).minmax(new AABB(r.head)).inflate(0.5);
        for (EndCrystal crystal : mc().level.getEntitiesOfClass(EndCrystal.class, area, Entity::isAlive)) return crystal;
        return null;
    }

    /** Whether it has left the middle of its cell, which only a piston does. */
    private static boolean moved(EndCrystal crystal, Rig r) {
        Vec3 start = new Vec3(r.crystal.getX() + 0.5, r.crystal.getY(), r.crystal.getZ() + 0.5);
        return crystal.position().distanceToSqr(start) > 0.04;
    }

    // ------------------------------------------------------------- planning (OpenHachimi PistonCrystal)

    private record Cell(BlockPos pos, float damage) {}

    /** The crystal cell an item lies in; a breaker crystal clears it when no rig could be built. */
    private static BlockPos itemBlocked;

    private static Rig findRig() {
        itemBlocked = null;
        Player target = pickTarget();
        if (target == null) return no("no target");

        List<Cell> cells = explosionCells(target);
        if (cells.isEmpty()) return no("no cell with enough damage");

        for (Cell cell : cells) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                Rig rig = tryPlan(target, cell, direction);
                if (rig != null) return rig;
            }
        }
        return null;
    }

    private static Player pickTarget() {
        Minecraft mc = mc();
        Player best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Player player : mc.level.players()) {
            if (player == mc.player || !player.isAlive() || player.isCreative() || player.isSpectator() || MaceAura.isFriend(player.getPlainTextName())) continue;

            double distance = mc.player.distanceToSqr(player);
            if (distance > targetRange * targetRange || distance >= bestDistance) continue;

            best = player;
            bestDistance = distance;
        }
        return best;
    }

    /** Every cell within 2 sideways and -1..+3 vertically of the target where a crystal does enough damage, best first. */
    private static List<Cell> explosionCells(Player target) {
        Minecraft mc = mc();
        Vec3 position = target.position();
        Vec3 eye = mc.player.getEyePosition();
        List<Cell> cells = new ArrayList<>();
        for (int x = Mth.floor(position.x - 2); x <= Mth.floor(position.x + 2); x++) {
            for (int y = Mth.floor(position.y - 1); y <= Mth.floor(position.y + 3); y++) {
                for (int z = Mth.floor(position.z - 2); z <= Mth.floor(position.z + 2); z++) {
                    BlockPos cell = new BlockPos(x, y, z);
                    // Only the block right above the head may be pushed into; every other cell has to be open.
                    if (!cell.equals(target.blockPosition().above(2)) && (!isOpen(cell) || !isOpen(cell.above()))) continue;
                    Vec3 explosion = new Vec3(x + 0.5, y, z + 0.5);
                    if (distanceSq(eye, crystalBox(explosion)) > square(attackRange)) continue;

                    // Hachimi: the cell counts as air even when a block stands in it (a covered head), so the blast is worked out through it.
                    float damage = GrimCrystalUtil.calculateDamage(target, explosion, false, cell);
                    if (damage < minDamage) continue;

                    float self = GrimCrystalUtil.calculateDamage(mc.player, explosion, false, cell);
                    if (self > maxSelfDamage || self >= mc.player.getHealth() + mc.player.getAbsorptionAmount()) continue;

                    cells.add(new Cell(cell, damage));
                }
            }
        }
        cells.sort((first, second) -> Float.compare(second.damage, first.damage));
        return cells;
    }

    /**
     * explosion: the cell the crystal is pushed into. crystal: the cell next to it (on obsidian) the crystal is placed in. The piston
     * sits on Hachimi's grid and faces the explosion cell's side; its head only has to touch the crystal's 2x2x2 box.
     */
    private static Rig tryPlan(Player target, Cell cell, Direction direction) {
        Minecraft mc = mc();
        BlockPos explosion = cell.pos;
        BlockPos crystal = explosion.relative(direction);
        BlockPos base = crystal.below();
        if (bad.containsKey(crystal) || bad.containsKey(base)) return no("marked bad");
        if (!isOpen(crystal) || !isOpen(crystal.above())) return no("crystal cell not air");

        AABB space = new AABB(crystal.getX(), crystal.getY(), crystal.getZ(), crystal.getX() + 1, crystal.getY() + CRYSTAL_HEIGHT, crystal.getZ() + 1);
        List<Entity> blockers = mc.level.getEntities((Entity) null, space, entity -> !(entity instanceof EndCrystal));
        if (!blockers.isEmpty()) {
            if (blockers.stream().allMatch(entity -> entity instanceof ItemEntity)) itemBlocked = crystal.immutable();
            return no("something in the crystal cell");
        }

        if (!isCrystalBase(base)) {
            if (!autoBase || GrimSwapManager.hotbar(Items.OBSIDIAN) < 0) return no("no base and auto base can't");
            if (!canBuild(base) || !GrimPlacementManager.canPlace(base)) return no("base cell taken or an opening");
            if (blockClick(base, null, Set.of(), null) == null) return no("no click for the base");
        }
        if (crystalClick(base) == null) return no("crystal base out of reach");

        Set<BlockPos> baseSoon = Set.of(base);
        Direction facing = direction.getOpposite();
        AABB crystalBox = crystalBox(new Vec3(crystal.getX() + 0.5, crystal.getY(), crystal.getZ() + 0.5));
        int[] ups = pistonSearch ? new int[]{0, 1} : new int[]{0};
        int[] sides = pistonSearch ? new int[]{0, -1, 1} : new int[]{0};
        int[] reaches = pistonSearch ? new int[]{1, 2, 3} : new int[]{2};

        for (int up : ups) {
            for (int side : sides) {
                for (int reach : reaches) {
                    if (reach == 1 && side == 0) continue;

                    BlockPos piston = explosion.above(up).relative(direction, reach).relative(direction.getClockWise(), side);
                    if (bad.containsKey(piston) || piston.equals(crystal) || piston.equals(crystal.above())) continue;

                    BlockPos front = piston.relative(facing);
                    if (!new AABB(front).intersects(crystalBox) || !isOpen(front)) continue;

                    BlockState pistonState = mc.level.getBlockState(piston);
                    if (isPiston(pistonState)) {
                        if (pistonState.getValue(BlockStateProperties.FACING) != facing || pistonState.getValue(BlockStateProperties.EXTENDED)) continue;
                    } else {
                        if (!canBuild(piston) || !GrimPlacementManager.canPlace(piston)) continue;
                        if (blockClick(piston, crystal, baseSoon, look -> lookDirection(look) == direction) == null) continue;
                    }

                    BlockPos redstone = findRedstone(piston, front, crystal, base, explosion);
                    if (redstone == null) continue;

                    Rig rig = new Rig();
                    rig.target = target;
                    rig.targetPos = target.position();
                    rig.head = explosion;
                    rig.crystal = crystal;
                    rig.base = base;
                    rig.piston = piston;
                    rig.front = front;
                    rig.redstone = redstone;
                    rig.direction = direction;
                    rig.damage = cell.damage;
                    return rig;
                }
            }
        }
        return no("no piston spot");
    }

    /** A redstone block already next to the piston is used as it is; otherwise one is placed on any side except the one the head comes out of. */
    private static BlockPos findRedstone(BlockPos piston, BlockPos front, BlockPos crystal, BlockPos base, BlockPos explosion) {
        Minecraft mc = mc();
        for (Direction side : Direction.values()) {
            BlockPos spot = piston.relative(side);
            if (!spot.equals(front) && mc.level.getBlockState(spot).is(Blocks.REDSTONE_BLOCK)) return spot;
        }
        if (mc.level.hasNeighborSignal(piston)) return null;

        Set<BlockPos> soon = Set.of(piston, base);
        for (Direction side : Direction.values()) {
            BlockPos spot = piston.relative(side);
            if (spot.equals(front) || spot.equals(base) || spot.equals(crystal) || spot.equals(crystal.above()) || spot.equals(explosion) || spot.equals(explosion.above()) || bad.containsKey(spot)) continue;
            if (!canBuild(spot) || !GrimPlacementManager.canPlace(spot)) continue;
            if (blockClick(spot, crystal, soon, null) != null) return spot;
        }
        return null;
    }

    private static Direction lookDirection(float[] look) {
        Vec3 vector = GrimRotation.getRotationVector(look[0], look[1]);
        return Direction.getApproximateNearest(vector.x, vector.y, vector.z);
    }

    /** A neighbour's face in sight, or else a face of the cell itself (air place): walls in the way do not matter. */
    private static Click blockClick(BlockPos pos, BlockPos avoid, Set<BlockPos> solid, Predicate<float[]> lookOk) {
        Click best = null;
        for (Direction side : Direction.values()) {
            BlockPos neighbour = pos.relative(side);
            if (neighbour.equals(avoid)) continue;

            BlockState state = mc().level.getBlockState(neighbour);
            if (!solid.contains(neighbour) && (state.isAir() || state.canBeReplaced() || !state.getFluidState().isEmpty() || GrimPlacementManager.isInteractable(state.getBlock()))) continue;

            Click click = faceClick(neighbour, side.getOpposite(), lookOk);
            if (click != null && (best == null || click.distance < best.distance)) best = click;
        }
        if (best != null) return best;

        for (Direction face : Direction.values()) {
            Click click = faceClick(pos, face, lookOk);
            if (click != null && (best == null || click.distance < best.distance)) best = click;
        }
        return best;
    }

    private static Click crystalClick(BlockPos base) {
        Click best = null;
        for (Direction face : Direction.values()) {
            Click click = faceClick(base, face, null);
            if (click != null && (best == null || click.distance < best.distance)) best = click;
        }
        return best;
    }

    private static Click faceClick(BlockPos block, Direction face, Predicate<float[]> lookOk) {
        Vec3 eye = mc().player.getEyePosition();
        Vec3 normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
        Vec3 center = Vec3.atCenterOf(block).add(normal.scale(0.5));
        if (eye.subtract(center).dot(normal) <= 0) return null;

        double reach = mc().player.blockInteractionRange();
        AABB box = new AABB(block);
        Click best = null;
        for (int a = 0; a < FACE_SAMPLES; a++) {
            for (int b = 0; b < FACE_SAMPLES; b++) {
                double u = (a + 0.5) / FACE_SAMPLES - 0.5, v = (b + 0.5) / FACE_SAMPLES - 0.5;
                Vec3 point = switch (face.getAxis()) {
                    case X -> center.add(0, u, v);
                    case Y -> center.add(u, 0, v);
                    case Z -> center.add(u, v, 0);
                };
                double distance = eye.distanceTo(point);
                if (distance > reach || (best != null && distance >= best.distance)) continue;

                float[] look = GrimRotation.getRotationsTo(eye, point);
                if (lookOk != null && !lookOk.test(look)) continue;

                Vec3 end = eye.add(GrimRotation.getRotationVector(look[0], look[1]).scale(reach + 1.0));
                if (box.clip(eye, end).isPresent()) best = new Click(new BlockHitResult(point, face, block, false), look[0], look[1], distance);
            }
        }
        return best;
    }

    private static AABB crystalBox(Vec3 feet) {
        return new AABB(feet.x - 1, feet.y, feet.z - 1, feet.x + 1, feet.y + CRYSTAL_HEIGHT, feet.z + 1);
    }

    private static double distanceSq(Vec3 point, AABB box) {
        double dx = Mth.clamp(point.x, box.minX, box.maxX) - point.x;
        double dy = Mth.clamp(point.y, box.minY, box.maxY) - point.y;
        double dz = Mth.clamp(point.z, box.minZ, box.maxZ) - point.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static double square(double value) {
        return value * value;
    }

    private static boolean isCrystalBase(BlockPos position) {
        BlockState state = mc().level.getBlockState(position);
        return state.is(Blocks.OBSIDIAN) || state.is(Blocks.BEDROCK);
    }

    private static boolean isOpen(BlockPos position) {
        BlockState state = mc().level.getBlockState(position);
        return state.isAir() || state.canBeReplaced();
    }

    /** Free for a block of ours. What is being mined or just was stays open on purpose. */
    private static boolean canBuild(BlockPos position) {
        return isOpen(position) && !isOurOpening(position);
    }

    private static boolean isPiston(BlockState state) {
        return state.getBlock() instanceof PistonBaseBlock;
    }

    private static InteractionHand crystalHand() {
        if (mc().player.getOffhandItem().is(Items.END_CRYSTAL)) return InteractionHand.OFF_HAND;
        return GrimSwapManager.hotbar(Items.END_CRYSTAL) >= 0 ? InteractionHand.MAIN_HAND : null;
    }

    private static int slotOf(Item first, Item second) {
        int slot = GrimSwapManager.hotbar(first);
        return slot >= 0 ? slot : GrimSwapManager.hotbar(second);
    }

    private static int pingTicks() {
        PlayerInfo pi = mc().getConnection().getPlayerInfo(mc().player.getUUID());
        return (pi != null ? pi.getLatency() : 0) / 50;
    }

    private static Rig no(String reason) {
        countReason(reason);
        return null;
    }

    private static void countReason(String reason) {
        reasons.merge(reason, 1, Integer::sum);
    }

    /** Why nothing is happening, added up over a second; quiet when no rig is ever chosen. */
    private static void logReasons() {
        long now = System.currentTimeMillis();
        if (now - lastReasons < REASONS_EVERY_MS || reasons.isEmpty()) return;

        lastReasons = now;
        log("no rig: " + reasons);
        reasons.clear();
    }

    // ------------------------------------------------------------- mining through Future

    /**
     * Counterpart of SpeedMine.requestBreak: the block is clicked once and Future's SpeedMine, running alongside, mines it.
     * The same block is not clicked again within MINE_RECLICK_TICKS (so Future's mining does not restart).
     */
    private static boolean requestBreak(BlockPos target) {
        Minecraft mc = mc();
        int age = mc.player.tickCount;
        if (target.equals(mineClick) && age - mineClickAge < MINE_RECLICK_TICKS) return true;
        if (mc.level.getBlockState(target).isAir() || Vec3.atCenterOf(target).distanceTo(mc.player.getEyePosition()) > MAX_MINE_DISTANCE) return false;

        mc.gameMode.startDestroyBlock(target, Direction.getApproximateNearest(mc.player.getEyePosition().subtract(Vec3.atCenterOf(target))));
        mc.player.swing(InteractionHand.MAIN_HAND);
        mineClick = target.immutable();
        mineClickAge = age;
        recentlyMined.put(mineClick, System.currentTimeMillis());
        return true;
    }

    /** Counterpart of SpeedMine.isOurOpening: a cell we had Future mine, or just had it mine, stays open on purpose. */
    private static boolean isOurOpening(BlockPos position) {
        long now = System.currentTimeMillis();
        recentlyMined.values().removeIf(time -> now - time > OPENING_KEEP_MS);
        return recentlyMined.containsKey(position);
    }

    private static final class Rig {
        Player target;
        Vec3 targetPos;
        BlockPos head, crystal, base, piston, front, redstone;
        Direction direction;
        float damage;
    }

    private record Click(BlockHitResult hit, float yaw, float pitch, double distance) {}
}
