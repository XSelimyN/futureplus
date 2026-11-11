package com.futureplus.client;

// Anchor placing and detonating module.

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * AnchorAura - places a respawn anchor, charges it with glowstone and blows it up. Doesn't work in the Nether (anchors
 * don't explode there) and turns off.
 *
 * Each tick the empty (or already anchor) cells in range are tried; the spot that does the most damage to the enemy while the damage to you
 * stays under the limit is chosen (an existing anchor is chosen right away). If there is an anchor there (once the delay + ping
 * have passed), an empty one is charged with glowstone and blown up; "Inhibit" removes the detonated anchor from the client right away.
 * Then a new anchor is placed in its spot. "Sequential" places the next one as soon as the anchor is gone on the server.
 */
public final class AnchorAura {
    private AnchorAura() {}

    static double delay = 50, range = 5, wallRange = 3.5, targetRange = 12, minDamage = 6, maxSelfDamage = 8, health = 0;
    static boolean antiSuicide = true, rotate = true, strictDirection = false, airPlace = false, liquidPlace = false,
        inhibit = true, sequential = true, render = true, fade = true, pauseMining = false, pauseEating = false;
    static int fillColor = 0xD8343A, fillAlpha = 40, outlineColor = 0xD8343A, outlineAlpha = 200;
    static double fadeTime = 0.5;

    private static boolean enabled = false;
    private static KeyMapping key;

    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean v) {
        enabled = v;
        spot = null; target = null;
    }
    public static KeyMapping getKeyMapping() { return key; }

    public static List<Setting> getSettings() {
        List<Setting> s = new ArrayList<>();
        s.add(Setting.num("Delay", 0, 500, 5, () -> delay, v -> delay = v));
        s.add(Setting.num("Range", 1, 6, 0.1, () -> range, v -> range = v));
        s.add(Setting.num("Wall range", 0, 6, 0.1, () -> wallRange, v -> wallRange = v));
        s.add(Setting.num("Target range", 1, 20, 0.5, () -> targetRange, v -> targetRange = v));
        s.add(Setting.num("Min. damage", 0, 36, 0.5, () -> minDamage, v -> minDamage = v));
        s.add(Setting.num("Max. self damage", 0, 36, 0.5, () -> maxSelfDamage, v -> maxSelfDamage = v));
        s.add(Setting.bool("Suicide protection", () -> antiSuicide, v -> antiSuicide = v));
        s.add(Setting.bool("Rotation", () -> rotate, v -> rotate = v));
        s.add(Setting.bool("Strict direction", () -> strictDirection, v -> strictDirection = v));
        s.add(Setting.bool("Air place", () -> airPlace, v -> airPlace = v));
        s.add(Setting.bool("Place in liquids", () -> liquidPlace, v -> liquidPlace = v));
        s.add(Setting.bool("Inhibit", () -> inhibit, v -> inhibit = v));
        s.add(Setting.bool("Sequential", () -> sequential, v -> sequential = v));
        s.add(Setting.bool("Pause while mining", () -> pauseMining, v -> pauseMining = v));
        s.add(Setting.bool("Pause while eating", () -> pauseEating, v -> pauseEating = v));
        s.add(Setting.num("Pause health", 0, 36, 0.5, () -> health, v -> health = v));
        s.add(Setting.bool("Render", () -> render, v -> render = v));
        s.add(Setting.color("Fill color", () -> fillColor, v -> fillColor = v).when(() -> render));
        s.add(Setting.num("Fill opacity", 0, 255, 1, () -> fillAlpha, v -> fillAlpha = (int) v).when(() -> render));
        s.add(Setting.color("Line color", () -> outlineColor, v -> outlineColor = v).when(() -> render));
        s.add(Setting.num("Line opacity", 0, 255, 1, () -> outlineAlpha, v -> outlineAlpha = (int) v).when(() -> render));
        s.add(Setting.bool("Fade", () -> fade, v -> fade = v).when(() -> render));
        s.add(Setting.num("Fade duration", 0.1, 2, 0.05, () -> fadeTime, v -> fadeTime = v).when(() -> render && fade));
        return s;
    }

    public static void init() {
        KeyMapping.Category category = KeyMapping.Category.register(net.minecraft.resources.Identifier.fromNamespaceAndPath("futureplus", "anchoraura"));
        key = net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.futureplus.anchoraura", InputConstants.Type.KEYSYM, InputConstants.KEY_Z, category));
        // tick: CombatSupport.beforeSend (before the movement packet)
    }

    private static int ping() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null || mc.player == null) return 0;
        var info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        return info == null ? 0 : info.getLatency();
    }

    /** HUD: target and damage. */
    public static String hudInfo() {
        if (!enabled || target == null) return null;
        return String.format(java.util.Locale.ROOT, "%s, %.1f", target.getPlainTextName(), damage);
    }

    // ------------------------------------------------------------- durum

    private record Spot(BlockPos pos, Direction dir, Player target, double damage) {}

    private static Spot spot;
    private static Player target;
    /** Shader "Targets": the current target. */
    static Player target() { return enabled ? target : null; }
    /** AutoCrystal (ForceSuicide): the crystal waits if an anchor would hit harder. -1 without a plan. */
    static double plannedDamage() { return enabled && spot != null ? damage : -1; }
    private static double damage;
    private static long lastCharge;
    private static BlockPos markPos;
    private static long markTime;

    // ------------------------------------------------------------- tick

    static void tick(Minecraft mc) {
        while (key.consumeClick()) setEnabled(!enabled);
        if (!enabled || !CombatSupport.ready()) return;
        if (mc.level.dimension() == Level.NETHER) {
            mc.gui.getChat().addClientSystemMessage(Component.literal("AnchorAura does not work in the Nether!"));
            setEnabled(false);
            return;
        }
        update(mc);
        if (paused(mc) || spot == null || hand(mc) == null) return;
        BlockPos pos = spot.pos;
        BlockState st = mc.level.getBlockState(pos);
        int prev = mc.player.getInventory().getSelectedSlot();
        if (st.is(Blocks.RESPAWN_ANCHOR)) {
            Direction face = clickFace(pos);
            if (face == null) return;
            if (System.currentTimeMillis() - lastCharge < delay + ping()) return;
            if (st.getValue(BlockStateProperties.RESPAWN_ANCHOR_CHARGES) == 0) charge(mc, pos, face, prev);
            if (rotate && !CombatSupport.look(new AABB(pos), Vec3.atCenterOf(pos), 3, CombatSupport.Type.INTERACT)) return;
            if (mc.player.getMainHandItem().is(Items.GLOWSTONE)) return;      // clicking with glowstone charges it, doesn't detonate
            click(pos, face, Vec3.atCenterOf(pos), InteractionHand.OFF_HAND);
            if (inhibit) mc.level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        }
        placeAnchor(mc, pos, spot.dir, hand(mc));
    }

    /** Anchor placement step. */
    private static void update(Minecraft mc) {
        if (spot != null && stale(spot.pos)) spot = null;
        if (spot == null) {
            Spot s = find(mc);
            spot = s;
            if (s != null) { target = s.target; damage = s.damage; }
        }
        if (target != null && invalid(target)) { target = null; damage = 0; }
    }

    /** Anchor placement step. */
    private static void charge(Minecraft mc, BlockPos pos, Direction face, int prev) {
        Vec3 pt = Vec3.atCenterOf(pos).add(face.getUnitVec3().scale(0.5));
        if (rotate && !CombatSupport.look(new AABB(pos), pt, 3, CombatSupport.Type.INTERACT)) return;
        InteractionHand h = CombatSupport.handWith(s -> s.is(Items.GLOWSTONE));
        if (h != null) click(pos, face, pt, InteractionHand.MAIN_HAND);
        else {
            int slot = CombatSupport.hotbar(s -> s.is(Items.GLOWSTONE));
            if (slot != -1) {
                CombatSupport.select(slot);
                click(pos, face, pt, InteractionHand.MAIN_HAND);
                CombatSupport.select(prev);
            }
        }
        lastCharge = System.currentTimeMillis();
    }

    /** Anchor placement step. */
    private static void placeAnchor(Minecraft mc, BlockPos pos, Direction dir, InteractionHand hand) {
        if (isAnchor(pos)) return;
        int prev = mc.player.getInventory().getSelectedSlot();
        int slot = CombatSupport.hotbar(s -> s.is(Items.RESPAWN_ANCHOR));
        if (slot == -1 && hand == InteractionHand.MAIN_HAND) return;
        Vec3 aim = Vec3.atCenterOf(pos).add(dir.getUnitVec3().scale(0.5));
        if (rotate && !CombatSupport.look(new AABB(pos), aim, 3, CombatSupport.Type.PLACE)) return;
        boolean swap = hand == InteractionHand.MAIN_HAND && !mc.player.getMainHandItem().is(Items.RESPAWN_ANCHOR);
        if (swap) CombatSupport.select(slot);
        if (PlacementSupport.click(pos, PlacementSupport.placeSide(pos, strictDirection, false), airPlace, hand) && render) {
            markPos = pos; markTime = System.currentTimeMillis();
        }
        if (swap) CombatSupport.select(prev);
    }

    /** Anchor placement step. */
    private static void click(BlockPos pos, Direction face, Vec3 pt, InteractionHand swing) {
        Minecraft mc = Minecraft.getInstance();
        CombatSupport.use(InteractionHand.MAIN_HAND, new BlockHitResult(pt, face, pos, false), CombatSupport.Type.INTERACT, null, true);
        mc.getConnection().send(new ServerboundSwingPacket(swing));
    }

    /** Anchor placement step. */
    private static Direction clickFace(BlockPos pos) {
        List<Direction> l = PlacementSupport.strictFaces(pos);
        Direction d = strictDirection ? null : Direction.DOWN;
        if (!l.isEmpty()) d = l.get(0);
        return d;
    }

    /** Pick the glowstone hand if there is no anchor in hand. */
    private static InteractionHand hand(Minecraft mc) { return InteractionHand.MAIN_HAND; }

    // ------------------------------------------------------------- spot selection

    private static Spot find(Minecraft mc) {
        LocalPlayer me = mc.player;
        Spot best = null;
        double r = range;
        for (double x = -Math.floor(r); x < Math.ceil(r); x++)
            for (double y = -Math.floor(r); y < Math.ceil(r); y++)
                for (double z = -Math.floor(r); z < Math.ceil(r); z++) {
                    BlockPos p = BlockPos.containing(me.getX() + x, me.getEyeY() + y, me.getZ() + z);
                    boolean anchor = isAnchor(p);
                    if (!(PlacementSupport.canPlace(p, Blocks.RESPAWN_ANCHOR, true, false) || anchor)) continue;
                    if (p.getY() < mc.level.getMinY() || p.getY() > mc.level.getMaxY()) continue;
                    boolean vis = PlacementSupport.anyVisible(points(p));
                    double dist = me.getEyePosition().distanceTo(Vec3.atCenterOf(p));
                    if (!(vis || dist <= wallRange) || dist > range) continue;
                    Direction dir = PlacementSupport.placeSide(p, strictDirection, wallRange <= 0);
                    Spot hit = bestTarget(mc, p);
                    if (dir == null && airPlace || anchor) dir = Direction.DOWN;
                    if (dir == null && !mc.level.getFluidState(p).isEmpty() && liquidPlace) dir = Direction.DOWN;
                    if (dir == null || hit == null || rejected(p, hit)) continue;
                    Spot s = new Spot(p.immutable(), dir, hit.target, hit.damage);
                    if (anchor) return s;
                    if (best == null || s.damage > best.damage) best = s;
                }
        return best;
    }

    private static List<Vec3> points(BlockPos p) {
        List<Vec3> out = new ArrayList<>(8);
        for (double x : new double[]{0.05, 0.95}) for (double y : new double[]{0.05, 0.95}) for (double z : new double[]{0.05, 0.95})
            out.add(new Vec3(p.getX() + x, p.getY() + y, p.getZ() + z));
        return out;
    }

    private static double dmg(Player p, BlockPos pos) {
        return CombatSupport.damage(p, p.getBoundingBox(), Vec3.atCenterOf(pos), 5, isAnchor(pos) ? pos : null, true);
    }

    /** Target selection. */
    private static Spot bestTarget(Minecraft mc, BlockPos pos) {
        Spot best = null;
        for (Player p : mc.level.players()) {
            if (invalid(p)) continue;
            double d = dmg(p, pos);
            if (minDamage <= d && d >= 0.5 && (best == null || d > best.damage)) best = new Spot(pos, null, p, d);
        }
        return best;
    }

    /** Anchor placement step. */
    private static boolean rejected(BlockPos pos, Spot hit) {
        if (hit == null) return true;
        LocalPlayer me = Minecraft.getInstance().player;
        double self = CombatSupport.damage(me, me.getBoundingBox(), Vec3.atCenterOf(pos), 5, isAnchor(pos) ? pos : null, true);
        boolean lethal = hit.damage >= hp(hit.target);
        double d = dmg(hit.target, pos);
        boolean unsafe = !lethal && self >= maxSelfDamage || self >= hp(me) && antiSuicide;
        if (isAnchor(pos) && !unsafe) return false;
        if (minDamage > d || d < 0.5) return true;
        return unsafe;
    }

    /** Anchor placement step. */
    private static boolean stale(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        double r = !PlacementSupport.anyVisible(points(pos)) ? wallRange : range;
        if (rejected(pos, bestTarget(mc, pos))) return true;
        if (mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) > r) return true;
        if (mc.level.getBlockState(pos).isAir() && PlacementSupport.placeSide(pos, strictDirection, wallRange <= 0) == null && !airPlace) return true;
        return !(PlacementSupport.canPlace(pos, Blocks.RESPAWN_ANCHOR, true, false) || isAnchor(pos));
    }

    private static boolean isAnchor(BlockPos p) { return Minecraft.getInstance().level.getBlockState(p).is(Blocks.RESPAWN_ANCHOR); }

    private static double hp(Player p) { return p.getHealth() + p.getAbsorptionAmount(); }

    private static boolean invalid(Player p) {
        Minecraft mc = Minecraft.getInstance();
        return p == mc.player || !p.isAlive() || p.isDeadOrDying() || p.isSpectator() || CombatSupport.friend(p)
            || p.distanceTo(mc.player) > targetRange || p.getAbilities().instabuild;
    }

    /** Anchor placement step. */
    private static boolean paused(Minecraft mc) {
        if (mc.player.isSleeping()) return true;
        if (mc.player.isUsingItem() && pauseEating) return true;
        if (mc.gameMode.isDestroying() && pauseMining) return true;
        return hp(mc.player) < health;
    }

    /** ClientPacketListenerMixin: a block changed ("Sequential": place the next anchor as soon as one is gone). */
    public static void onBlockUpdate(BlockPos pos, BlockState state) {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled || !sequential || spot == null || mc.player == null || target == null) return;
        if (state.isAir() && pos.equals(spot.pos)) placeAnchor(mc, spot.pos, spot.dir, hand(mc));
    }

    // ------------------------------------------------------------- rendering

    static void onFrame() { }

    static boolean wantsRender() { return enabled && render && markPos != null; }

    static void emit(Vec3 cam) {
        long age = System.currentTimeMillis() - markTime;
        float a = age < 450 ? 1f : fade ? 1f - Math.min(1f, (age - 450) / (float) (fadeTime * 1000)) : 0f;
        if (a <= 0) return;
        AABB b = new AABB(markPos).move(-cam.x, -cam.y, -cam.z);
        if (fillAlpha * a > 1) WorldDraw.boxFill(true, b, ((int) (fillAlpha * a) << 24) | fillColor);
        if (outlineAlpha * a > 1) WorldDraw.boxOutline(true, b, 1f, ((int) (outlineAlpha * a) << 24) | outlineColor);
    }
}
