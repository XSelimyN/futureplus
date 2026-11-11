package com.futureplus.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import com.futureplus.client.mixin.MultiPlayerGameModeAccessor;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AntiPhase v2.
 *
 * Places scaffolding in every empty cell touched at foot level by the boxes of ALL (non-friend) players in range;
 * without scaffolding — if no player is in the cell — cobblestone/dirt/netherrack. Key: H.
 *
 * v2 fixes:
 *  - Everyone in range, not only the nearest player (friends are skipped).
 *  - All 1-4 cells the box touches, not only the cell of the foot center (a phasing player spills into the wall).
 *  - A sent cell isn't sent again until the server answers (about a ping): at Delay 0 it used to rain packets on the same spot
 *    every tick and hit NCP's fast place limit (and the phase got through meanwhile); that's over.
 *  - Face to click: the visible solid neighbour face closest to the eye (NCP visibility/direction checks).
 *
 * v3 fixes:
 *  - Placement goes out as an unpredicted packet (no block is placed on the client). Before, a refused placement left a ghost
 *    scaffold on the client and the module thought the cell was filled and left it; now a cell only counts as filled once the server sends the block.
 *  - A block is never placed in cells touched by our box or friends' boxes (including the next tick's movement).
 *  - Target cells come from the last position from the server, not the gliding on-screen position (doesn't lag behind with fakelag),
 *    and are also computed from the opponent's box one tick ahead based on their last movement.
 *  - Scaffolding isn't tried in a cell without support under it (the server would refuse).
 */
public final class AntiPhase {
    private static double TARGET_RANGE = 5.0;
    private static double PLACE_REACH = 5.0;
    private static int COOLDOWN = 0;
    private static int PER_TICK = 2;
    private static boolean SKIP_FRIENDS = true;
    private static boolean HEAD_TOO = false;

    private static final Item[] FALLBACK_BLOCKS = { Items.COBBLESTONE, Items.DIRT, Items.NETHERRACK };

    private static KeyMapping toggleKey;
    private static boolean enabled = false;
    private static int cooldown, tickNo;
    private static final Map<BlockPos, Integer> pending = new HashMap<>();
    /** Players' server position on the previous tick (movement direction estimate). */
    private static final Map<Integer, Vec3> lastPos = new HashMap<>();

    private AntiPhase() {}

    public static void init() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("futureplus", "antiphase"));
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.futureplus.antiphase", InputConstants.Type.KEYSYM, InputConstants.KEY_H, category));
        ClientTickEvents.END_CLIENT_TICK.register(AntiPhase::tick);
    }

    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean v) { enabled = v; pending.clear(); lastPos.clear(); cooldown = 0; }
    public static KeyMapping getKeyMapping() { return toggleKey; }

    public static List<Setting> getSettings() {
        List<Setting> s = new ArrayList<>();
        s.add(Setting.num("Target range", 2, 8, 0.5, () -> TARGET_RANGE, v -> TARGET_RANGE = v));
        s.add(Setting.num("Placement range", 2, 6, 0.5, () -> PLACE_REACH, v -> PLACE_REACH = v));
        s.add(Setting.num("Cooldown (ticks)", 0, 10, 1, () -> COOLDOWN, v -> COOLDOWN = (int) v));
        s.add(Setting.num("Blocks per tick", 1, 4, 1, () -> PER_TICK, v -> PER_TICK = (int) v));
        s.add(Setting.bool("Skip friends", () -> SKIP_FRIENDS, v -> SKIP_FRIENDS = v));
        s.add(Setting.bool("Include head block", () -> HEAD_TOO, v -> HEAD_TOO = v));
        return s;
    }

    private static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.gameMode == null || mc.getConnection() == null) { pending.clear(); lastPos.clear(); return; }
        while (toggleKey.consumeClick()) setEnabled(!enabled);   
        if (!enabled) return;
        tickNo++;
        // expired cells, or ones that look filled now (client prediction/server confirmation), leave the list;
        // if the server refuses, the block goes back to empty and is retried a ping later at the earliest (not every tick)
        // (as placement is unpredicted, the block in the world is what the server sent; no ghost blocks)
        pending.entrySet().removeIf(e -> e.getValue() < tickNo || !isEmpty(mc, e.getKey()));
        List<Player> targets = targets(mc);
        Map<Integer, Vec3> motion = new HashMap<>();
        for (Player p : mc.level.players()) {
            Vec3 now = serverPos(p), prev = lastPos.put(p.getId(), now);
            if (prev != null && prev.distanceToSqr(now) < 9) motion.put(p.getId(), now.subtract(prev));
        }
        lastPos.keySet().removeIf(id -> mc.level.getEntity(id) == null);
        if (cooldown > 0) { cooldown--; return; }

        List<AABB> safe = protectedBoxes(mc);
        int placed = 0;
        int expire = tickNo + 2 * pingTicks(mc) + 3;
        for (Player t : targets) {
            for (BlockPos cell : cells(t, motion.getOrDefault(t.getId(), Vec3.ZERO))) {
                if (placed >= PER_TICK) break;
                if (pending.containsKey(cell) || !isEmpty(mc, cell) || touches(safe, cell)) continue;
                if (fill(mc, cell, t)) { pending.put(cell, expire); placed++; }
            }
            if (placed >= PER_TICK) break;
        }
        if (placed > 0) cooldown = COOLDOWN;
    }

    private static int pingTicks(Minecraft mc) {
        PlayerInfo pi = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        int ms = pi != null ? pi.getLatency() : 100;
        return Math.max(1, Math.min(20, (ms + 49) / 50));
    }

    private static List<Player> targets(Minecraft mc) {
        List<Player> out = new ArrayList<>();
        for (Player p : mc.level.players()) {
            if (p == mc.player || !p.isAlive() || p.isSpectator()) continue;
            if (SKIP_FRIENDS && MaceAura.isFriend(p.getPlainTextName())) continue;
            if (serverPos(p).distanceTo(mc.player.position()) > TARGET_RANGE) continue;
            out.add(p);
        }
        out.sort(Comparator.comparingDouble(p -> serverPos(p).distanceToSqr(mc.player.position())));
        return out;
    }

    /**
     * The last position the server reported. The client slides remote players to it over a few ticks; after fakelag/blink
     * a burst of movement leaves the on-screen position behind, so target cells are computed from here.
     */
    private static Vec3 serverPos(Player p) {
        var in = p.getInterpolation();
        return in != null && in.hasActiveInterpolation() ? in.position() : p.position();
    }

    private static AABB boxAt(Player p, Vec3 pos) {
        return p.getBoundingBox().move(pos.subtract(p.position()));
    }

    /**
     * Cells the box touches at foot (optionally head) level: first the foot center at the server position, then
     * the other ones the box touches, then the cells it will enter one tick later with its last movement.
     */
    private static List<BlockPos> cells(Player t, Vec3 move) {
        List<BlockPos> out = new ArrayList<>();
        Vec3 pos = serverPos(t);
        out.add(BlockPos.containing(pos));
        addCells(out, boxAt(t, pos));
        if (move.horizontalDistanceSqr() > 1.0E-4) addCells(out, boxAt(t, pos.add(move.x, 0, move.z)));
        return out;
    }

    private static void addCells(List<BlockPos> out, AABB box) {
        AABB b = box.deflate(1.0E-3, 0, 1.0E-3);
        int y0 = (int) Math.floor(b.minY + 1.0E-3);
        int x0 = (int) Math.floor(b.minX), x1 = (int) Math.floor(b.maxX);
        int z0 = (int) Math.floor(b.minZ), z1 = (int) Math.floor(b.maxZ);
        int yMax = HEAD_TOO ? (int) Math.floor(b.maxY - 1.0E-3) : y0;
        for (int y = y0; y <= yMax; y++)
            for (int x = x0; x <= x1; x++)
                for (int z = z0; z <= z1; z++) {
                    BlockPos q = new BlockPos(x, y, z);
                    if (!out.contains(q)) out.add(q);
                }
    }

    /**
     * Boxes not to place blocks in: us (now and with our movement one tick ahead) and friends (both on-screen and
     * server position). With the opponent standing next to us, the cells their box touched could be ours too.
     */
    private static List<AABB> protectedBoxes(Minecraft mc) {
        List<AABB> out = new ArrayList<>();
        AABB me = mc.player.getBoundingBox().inflate(0.05);
        Vec3 v = mc.player.getDeltaMovement();
        out.add(me.expandTowards(v.x, Math.max(v.y, 0), v.z));
        for (Player p : mc.level.players()) {
            if (p == mc.player || !MaceAura.isFriend(p.getPlainTextName())) continue;
            out.add(p.getBoundingBox().inflate(0.05));
            out.add(boxAt(p, serverPos(p)).inflate(0.05));
        }
        return out;
    }

    private static boolean touches(List<AABB> boxes, BlockPos cell) {
        AABB c = new AABB(cell);
        for (AABB b : boxes) if (b.intersects(c)) return true;
        return false;
    }

    private static boolean isEmpty(Minecraft mc, BlockPos p) {
        BlockState s = mc.level.getBlockState(p);
        return (s.isAir() || s.canBeReplaced()) && !s.is(Blocks.SCAFFOLDING);
    }

    private static boolean fill(Minecraft mc, BlockPos cell, Player t) {
        int slot = findHotbar(mc, Items.SCAFFOLDING);
        // scaffolding with no support under it (solid ground or a scaffold chain within 6) is refused by the server: fall back to a normal block
        if (slot >= 0 && !Blocks.SCAFFOLDING.defaultBlockState().canSurvive(mc.level, cell)) slot = -1;
        if (slot < 0) {
            // a normal block can't be placed in a cell a player stands in (the server refuses): empty cells only
            if (cellHasPlayer(mc, cell, t)) return false;
            for (Item it : FALLBACK_BLOCKS) { slot = findHotbar(mc, it); if (slot >= 0) break; }
            if (slot < 0) return false;
        }
        BlockHitResult hit = support(mc, cell);
        if (hit == null) return false;
        clickPlace(mc, hit, slot);
        return true;
    }

    /** Whether a player is in the cell (with the on-screen or server position); the target's box one tick ahead counts too. */
    private static boolean cellHasPlayer(Minecraft mc, BlockPos cell, Player t) {
        AABB c = new AABB(cell);
        for (Player p : mc.level.players())
            if (p.isAlive() && (p.getBoundingBox().intersects(c) || boxAt(p, serverPos(p)).intersects(c))) return true;
        Vec3 last = lastPos.get(t.getId());
        return last != null && boxAt(t, serverPos(t).add(serverPos(t).subtract(last))).intersects(c);
    }

    /**
     * The solid neighbour face to click to place in the cell. Faces visible from the eye first, the one closest to the eye among them.
     * Scaffolding clicked on another scaffold goes to the top of the tower, so scaffolds don't count as support.
     */
    private static BlockHitResult support(Minecraft mc, BlockPos cell) {
        Vec3 eye = mc.player.getEyePosition();
        BlockHitResult best = null;
        double bestScore = Double.MAX_VALUE;
        for (Direction d : Direction.values()) {
            BlockPos n = cell.relative(d);
            BlockState s = mc.level.getBlockState(n);
            if (s.isAir() || s.canBeReplaced() || s.liquid() || s.is(Blocks.SCAFFOLDING)) continue;
            Direction face = d.getOpposite();
            Vec3 pt = Vec3.atCenterOf(n).add(face.getUnitVec3().scale(0.5));
            double dist = eye.distanceTo(pt);
            if (dist > PLACE_REACH) continue;
            BlockHitResult ray = mc.level.clip(new ClipContext(eye, pt, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
            boolean visible = ray.getType() == HitResult.Type.MISS || ray.getBlockPos().equals(n);
            double score = dist + (visible ? 0 : 100);
            if (score < bestScore) { bestScore = score; best = new BlockHitResult(pt, face, n, false); }
        }
        return best;
    }

    private static void clickPlace(Minecraft mc, BlockHitResult hit, int slot) {
        var inv = mc.player.getInventory();
        int prev = inv.getSelectedSlot();
        if (slot != prev) {
            inv.setSelectedSlot(slot);
            mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
        }
        // unpredicted: no block is placed on the client, so a refusal leaves no ghost scaffold (the block shows once the server confirms)
        ((MultiPlayerGameModeAccessor) mc.gameMode).futureplus$predict(mc.level,
                seq -> new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, seq));
        mc.player.swing(InteractionHand.MAIN_HAND);
        if (slot != prev) {
            inv.setSelectedSlot(prev);
            mc.getConnection().send(new ServerboundSetCarriedItemPacket(prev));
        }
    }

    private static int findHotbar(Minecraft mc, Item item) {
        for (int i = 0; i < 9; i++) if (mc.player.getInventory().getItem(i).is(item)) return i;
        return -1;
    }
}
