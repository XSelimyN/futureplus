package com.futureplus.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Blocker (the user's design). At foot level, 8 obsidian OUTSIDE Future's surround (4 sides, 2 blocks):
 *
 *            G            G = Futureplus (1 block, foot level)
 *          G S G          S = Future surround (2 blocks)
 *        G S O S G        O = player
 *          G S G
 *            G
 *
 * Once the corners (±1,±1) and the two beyond (±2,0)/(0,±2) are filled, no crystal can be placed at foot level beside the surround blocks;
 * "place a crystal beside the target and break surround" attempts come to nothing.
 *
 * Placement order (no cell is skipped):
 *   1. normal  : a real neighbour face in range is clicked (tried first; on the second pass newly placed ones count as faces too)
 *   2. base    : with no face, the 1-2 block gap under the cell is filled bottom up, then the cell is placed
 *   3. airplace: if the base doesn't work either, the center of the cell (air) is clicked; a vanilla server accepts it
 * While empty cells remain the "Delay" isn't applied, and the next tick tries again.
 *
 * For a normal placement a rotation packet looking at that point goes out before the click (BlockPlace.Direction), and the next tick
 * turns back to the real look. The same cell isn't sent again until the server answers; blocks per tick are limited (FastPlace).
 *
 * AntiCEV: the 3x3 layer one above the head (feet +2) is covered; when a CEV breaker's obsidian over the head is broken, a new one
 * is placed right away, and crystals placed on top of the layer (feet +3) are broken. Order for NCP/Grim is corner -> side -> middle:
 * since the eye stays under this layer, clicking the top face of the lower layers would be "clicking from behind the face" (Grim
 * PositionPlace, NCP Direction), so no base is used. Only the corners are air placed; sides are placed with a real click on the corners'
 * eye-facing side faces, and the middle on the sides'.
 */
public final class Blocker {
    private Blocker() {}

    static int perTick = 2, cooldownSet = 0;
    static double reach = 4.5;
    static boolean base = true, airplace = true;
    static boolean surroundToo = false, antiCev = false, rotate = true, onlyStill = true, breakCrystals = true, corners = true, outer = true, strictVisible = false, jumpOff = true;

    private static boolean enabled = false;
    private static KeyMapping key;
    private static int tickNo, cooldown;
    private static boolean rotSpoofed;
    private static double groundY = Double.NaN;
    private static final Map<BlockPos, Integer> pending = new HashMap<>();
    private static final Item[] BLOCKS = {Items.OBSIDIAN, Items.CRYING_OBSIDIAN, Items.ENDER_CHEST};

    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean v) {
        enabled = v; pending.clear(); cooldown = 0; groundY = Double.NaN;
        restoreRot(Minecraft.getInstance());
    }
    public static KeyMapping getKeyMapping() { return key; }

    public static List<Setting> getSettings() {
        List<Setting> s = new ArrayList<>();
        s.add(Setting.bool("Corners", () -> corners, v -> corners = v));
        s.add(Setting.bool("Outer ring", () -> outer, v -> outer = v));
        s.add(Setting.bool("Place floor", () -> base, v -> base = v));
        s.add(Setting.bool("Air place", () -> airplace, v -> airplace = v));
        s.add(Setting.bool("Place surround too", () -> surroundToo, v -> surroundToo = v));
        s.add(Setting.bool("AntiCEV", () -> antiCev, v -> antiCev = v));
        s.add(Setting.bool("Rotation", () -> rotate, v -> rotate = v));
        s.add(Setting.bool("Visible faces only", () -> strictVisible, v -> strictVisible = v));
        s.add(Setting.bool("Disable on jump", () -> jumpOff, v -> jumpOff = v));
        s.add(Setting.bool("Only while stationary", () -> onlyStill, v -> onlyStill = v));
        s.add(Setting.bool("Break crystals", () -> breakCrystals, v -> breakCrystals = v));
        s.add(Setting.num("Blocks per tick", 1, 8, 1, () -> perTick, v -> perTick = (int) v));
        s.add(Setting.num("Cooldown (ticks)", 0, 10, 1, () -> cooldownSet, v -> cooldownSet = (int) v));
        s.add(Setting.num("Range", 3, 6, 0.1, () -> reach, v -> reach = v));
        return s;
    }

    public static void init() {
        KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("futureplus", "blocker"));
        key = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.futureplus.blocker",
            InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), cat));
        ClientTickEvents.END_CLIENT_TICK.register(Blocker::tick);
    }

    // ------------------------------------------------------------- tick

    private static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.gameMode == null || mc.getConnection() == null) {
            pending.clear(); rotSpoofed = false; return;
        }
        while (key.consumeClick()) setEnabled(!enabled);
        if (!enabled) return;
        tickNo++;

        // TURNS OFF AT ONCE ON A JUMP: the jump key, upward velocity or rising off the ground. Sending block/rotation packets while in the air
        // caused lagbacks on the server. Walking and going down don't turn the module off.
        LocalPlayer me = mc.player;
        if (me.onGround()) groundY = me.getY();
        boolean rising = !me.onGround() && (me.getDeltaMovement().y > 0.05 || me.getY() > groundY + 0.3);
        if (jumpOff && (mc.options.keyJump.isDown() || rising)) {
            setEnabled(false);
            me.sendSystemMessage(FontMod.comp("Blocker: disabled because you jumped."));
            return;
        }
        restoreRot(mc);                                            // turn back from the previous tick's placement look
        pending.entrySet().removeIf(e -> e.getValue() < tickNo || !isEmpty(mc, e.getKey()));
        if (cooldown > 0) { cooldown--; return; }

        LocalPlayer p = mc.player;
        if (!p.onGround()) return;                                 // no placement at all in the air (falling too)
        if (onlyStill) {
            Vec3 v = p.getDeltaMovement();
            if (v.x * v.x + v.z * v.z > 0.01) return;
        }

        int slot = -1;
        for (Item it : BLOCKS) { slot = slot(p, it); if (slot >= 0) break; }
        if (slot < 0) return;

        BlockPos feet = BlockPos.containing(p.getX(), p.getY() + 0.2, p.getZ());
        List<BlockPos> cells = new ArrayList<>();
        if (surroundToo) {                                         // orange: 4 sides, feet + head
            for (Direction d : Direction.Plane.HORIZONTAL) cells.add(feet.relative(d));
            for (Direction d : Direction.Plane.HORIZONTAL) cells.add(feet.relative(d).above());
        }
        if (corners) for (Direction d : Direction.Plane.HORIZONTAL) cells.add(feet.relative(d).relative(d.getClockWise()));
        if (outer) for (Direction d : Direction.Plane.HORIZONTAL) cells.add(feet.relative(d, 2));
        BlockPos top = feet.above(2);                              // one above the head
        if (antiCev) {                                             // order matters: corner, side, middle (see the class note)
            for (Direction d : Direction.Plane.HORIZONTAL) cells.add(top.relative(d).relative(d.getClockWise()));
            for (Direction d : Direction.Plane.HORIZONTAL) cells.add(top.relative(d));
            cells.add(top);
        }

        int placed = 0;
        // AntiCEV: a crystal placed on top of the 3x3 (the one CEV would blow up) is broken
        if (antiCev && breakCrystals) {
            for (EndCrystal c : mc.level.getEntitiesOfClass(EndCrystal.class, new AABB(top.above()).inflate(1, 0, 1), Entity::isAlive)) {
                if (placed >= perTick) break;
                if (p.getEyePosition().distanceTo(c.position()) > reach) continue;
                mc.gameMode.attack(p, c);
                p.swing(InteractionHand.MAIN_HAND);
                placed++;
            }
        }
        int expire = tickNo + 2 * pingTicks(mc) + 3;
        // two passes: first the ones with a face to hold on to; placed ones (pending) become faces for the next, the server handles them in order
        for (int pass = 0; pass < 2 && placed < perTick; pass++) {
            for (BlockPos q : cells) {
                if (placed >= perTick) break;
                if (pending.containsKey(q) || !isEmpty(mc, q)) continue;
                EndCrystal c = crystalIn(mc, q);
                if (c != null) {
                    if (breakCrystals && p.getEyePosition().distanceTo(c.position()) <= reach) {
                        mc.gameMode.attack(p, c);
                        p.swing(InteractionHand.MAIN_HAND);
                        placed++;
                    }
                    continue;
                }
                if (blockedByEntity(mc, q)) continue;
                // 1) normal: click a real neighbour face
                BlockHitResult hit = support(mc, q);
                // 2) base: put support under it (from at most 2 blocks below), then on top (not in the AntiCEV layer)
                boolean cev = q.getY() == top.getY();
                if (hit == null && base && !cev && pass == 1) {
                    int b = placeBase(mc, p, slot, q, expire, perTick - placed);
                    if (b > 0) { placed += b; hit = placed < perTick ? support(mc, q) : null; if (hit == null) continue; }
                    else if (b == 0) continue;                          // base on the way, budget used up
                }
                // 3) airplace: with no face at all, click the cell itself (air)
                if (hit == null && airplace && pass == 1) hit = airHit(mc, q);
                if (hit == null) continue;
                if (rotate) lookAtPoint(mc, hit.getLocation());
                place(mc, p, slot, hit);
                pending.put(q, expire);
                placed++;
            }
        }
        // don't wait while empty cells remain: try again right on the next tick
        if (placed > 0) for (BlockPos q : cells) if (!pending.containsKey(q) && isEmpty(mc, q) && crystalIn(mc, q) == null) return;
        if (placed > 0) cooldown = cooldownSet;
    }

    // ------------------------------------------------------------- placement (no airplace)

    /** A visible, in-range, really solid neighbour face (our placements on the way count as solid too). Null otherwise. */
    private static BlockHitResult support(Minecraft mc, BlockPos q) {
        Vec3 eye = mc.player.getEyePosition();
        BlockHitResult best = null;
        double bd = Double.MAX_VALUE;
        for (Direction d : Direction.values()) {
            BlockPos n = q.relative(d);
            BlockState s = mc.level.getBlockState(n);
            boolean solid = pending.containsKey(n) || !s.isAir() && !s.canBeReplaced() && !s.liquid();
            if (!solid) continue;
            Direction face = d.getOpposite();
            Vec3 pt = Vec3.atCenterOf(n).add(face.getUnitVec3().scale(0.5));
            double dist = eye.distanceTo(pt);
            if (dist > reach) continue;
            // the face must face the eye (clicking from behind is refused on NCP)
            Vec3 toEye = eye.subtract(pt);
            if (toEye.dot(face.getUnitVec3()) <= 0) continue;
            // visibility: a 2 block surround hides the outer ground from the eye; a visible face is preferred,
            // with "Visible faces only" off a face that faces the eye but isn't visible is used too
            boolean visible = true;
            if (!pending.containsKey(n)) {
                BlockHitResult ray = mc.level.clip(new ClipContext(eye, pt, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
                visible = ray.getType() == HitResult.Type.MISS || ray.getBlockPos().equals(n);
            }
            if (!visible && strictVisible) continue;
            double score = dist + (visible ? 0 : 50);
            if (score < bd) { bd = score; best = new BlockHitResult(pt, face, n, false); }
        }
        return best;
    }

    /**
     * Base: fills the gap (1-2 blocks) under q bottom up; the lowest one needs a real face.
     * Returns: blocks placed (>0), 0 = base possible but no budget, -1 = base not possible (go to airplace).
     */
    private static int placeBase(Minecraft mc, LocalPlayer p, int slot, BlockPos q, int expire, int budget) {
        BlockPos feet = BlockPos.containing(p.getX(), p.getY() + 0.2, p.getZ());
        List<BlockPos> chain = new ArrayList<>();
        BlockHitResult first = null;
        for (int k = 1; k <= 2; k++) {
            BlockPos b = q.below(k);
            if (pending.containsKey(b)) return 0;                       // base already on the way, waiting for the answer
            if (!isEmpty(mc, b) || b.equals(feet.below()) || blockedByEntity(mc, b) || crystalIn(mc, b) != null) return -1;
            chain.add(0, b);
            first = support(mc, b);
            if (first != null) break;
        }
        if (first == null) return -1;
        if (budget <= 0) return 0;
        int n = 0;
        for (BlockPos b : chain) {
            if (n >= budget) break;
            BlockHitResult h = b.equals(chain.get(0)) ? first : support(mc, b);
            if (h == null) break;
            if (rotate) lookAtPoint(mc, h.getLocation());
            place(mc, p, slot, h);
            pending.put(b, expire);
            n++;
        }
        return n == 0 ? -1 : n;
    }

    /** Airplace: click the center of the empty cell. Air is "replaceable", so the server puts the block right there. */
    private static BlockHitResult airHit(Minecraft mc, BlockPos q) {
        Vec3 c = Vec3.atCenterOf(q);
        Vec3 eye = mc.player.getEyePosition();
        if (eye.distanceTo(c) > reach) return null;
        Vec3 d = eye.subtract(c);
        Direction face = Direction.getApproximateNearest(d.x, d.y, d.z);   // eye-facing face
        return new BlockHitResult(c, face, q, false);
    }

    private static void place(Minecraft mc, LocalPlayer p, int slot, BlockHitResult hit) {
        var inv = p.getInventory();
        int prev = inv.getSelectedSlot();
        if (slot != prev) { inv.setSelectedSlot(slot); mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot)); }
        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
        p.swing(InteractionHand.MAIN_HAND);
        if (slot != prev) { inv.setSelectedSlot(prev); mc.getConnection().send(new ServerboundSetCarriedItemPacket(prev)); }
    }

    private static void lookAtPoint(Minecraft mc, Vec3 pt) {
        Vec3 eye = mc.player.getEyePosition();
        double dx = pt.x - eye.x, dy = pt.y - eye.y, dz = pt.z - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(yaw, pitch, mc.player.onGround(), mc.player.horizontalCollision));
        rotSpoofed = true;
    }

    private static void restoreRot(Minecraft mc) {
        if (!rotSpoofed || mc.player == null || mc.getConnection() == null) return;
        rotSpoofed = false;
        mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(mc.player.getYRot(), mc.player.getXRot(),
            mc.player.onGround(), mc.player.horizontalCollision));
    }

    // ------------------------------------------------------------- helpers

    private static boolean isEmpty(Minecraft mc, BlockPos q) {
        BlockState s = mc.level.getBlockState(q);
        return (s.isAir() || s.canBeReplaced()) && !s.liquid();
    }

    private static EndCrystal crystalIn(Minecraft mc, BlockPos q) {
        for (EndCrystal c : mc.level.getEntitiesOfClass(EndCrystal.class, new AABB(q), Entity::isAlive)) return c;
        return null;
    }

    private static boolean blockedByEntity(Minecraft mc, BlockPos q) {
        return !mc.level.getEntities((Entity) null, new AABB(q), e -> e.isAlive() && e.blocksBuilding).isEmpty();
    }

    private static int slot(LocalPlayer p, Item item) {
        for (int i = 0; i < 9; i++) if (p.getInventory().getItem(i).is(item)) return i;
        return -1;
    }

    private static int pingTicks(Minecraft mc) {
        PlayerInfo pi = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        int ms = pi != null ? pi.getLatency() : 100;
        return Math.max(1, Math.min(20, (ms + 49) / 50));
    }
}
