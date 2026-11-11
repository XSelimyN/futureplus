package com.futureplus.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * PistonAura v13 (Fabric 26.1.2, Mojang mappings).
 *
 * Push into a block: if the cell above the target's head is filled, that block counts as air; the crystal (an entity) is pushed
 *   into the block by the piston. No mining needed.
 *
 * v13: (1) The redstone goes down ONLY after the crystal shows on the client and the piston is confirmed facing right/not extended —
 *      if the crystal is refused, the piston doesn't push for nothing (the old "diagonal piston pushes without a crystal, then mines it" bug).
 *      (2) Predictive detonation: "Detonation delay" ticks after the redstone is sent, the crystal is hit without waiting for the push to be confirmed
 *      (packets are handled in order, so the push is done on the server). (3) Chain: since the explosion breaks the piston and the head,
 *      the next cycle's piston + crystal are sent right away without waiting for the server's answer (only by clicking a
 *      blast resistant block — obsidian etc.). If it doesn't hold, it falls back to the old (confirmed) path automatically.
 *
 * LINE:  T (the target's leg / head / above-head cell) <- C (crystal, obsidian B under it) <- P (piston) <- R (redstone)
 *   - Piston STRAIGHT first (behind C). Otherwise FROM THE SIDE (diagonal behind C): since an end crystal's box is 2x2,
 *     a head reaching beside C also pushes the crystal into T.
 *   - Redstone next to the piston: back, sides, top. Cells touching the crystal's 2x2x2 box are DROPPED (once the crystal
 *     is placed no block can go there — the server refuses). The piston's front face (the head cell) is never picked.
 *
 * ORDER (always): piston -> crystal -> redstone. All sent in the SAME tick (the server handles them in order).
 *
 * CONFIRMATION BASED LOOP:
 *   SEND -> WAIT: after sending, nothing is sent again for that line; it waits until the server's answer (block states)
 *     arrives. (Before, when the answer was late the same part was placed again and again, or it switched to another line and
 *     placed a new piston -> "10 pistons".)
 *   FIRE: PISTON_HEAD in the head cell (push done) or the piston's EXTENDED property for 2 ticks. The crystal's position is never
 *     updated on the client; that it was pushed can only be told from the piston.
 *   TIMEOUT: what didn't hold is diagnosed (no piston / wrong facing / no redstone / no power / no crystal);
 *     ONLY that part's position is blacklisted, and it goes on by placing the missing part elsewhere.
 *   CLEANUP: our leftover piston/redstone is mined at real speed if it would ruin the new line.
 *
 * RENDER: piston white, redstone red, crystal purple; each box follows the world —
 *   it grows in when the block/crystal is placed, shrinks and fades when broken/exploded, and a new box opens when placed again.
 *
 * The module never turns itself off. No position spoofing / instant break; a single rotation packet for the piston's facing.
 * In the hotbar: piston, end crystal, redstone block, obsidian, pickaxe.
 */
public final class PistonAura {
    private PistonAura() {}

    // ------------------------------------------------------------- ayarlar
    static double targetRange = 6.0, placeReach = 5.0, attackReach = 4.5, minExposure = 0.3;
    static int confirmExtra = 4, fireDelay = 3;
    static boolean breach = true, pickaxe = true, render = true, debug = false, predictFire = true, chain = true, intoBlock = true, instant = true, breaker = true;
    static boolean grim = false;   // when on, everything is handed to GrimPiston

    private static boolean enabled = false;
    private static KeyMapping toggleKey;

    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean v) {
        if (v == enabled) return;
        enabled = v;
        Minecraft mc = Minecraft.getInstance();
        softReset(mc);
        GrimPiston.reset();
        boxes.clear();
        lastFireTick = tickNo;
    }
    public static KeyMapping getKeyMapping() { return toggleKey; }
    public static Player currentTarget() {
        if (grim) return GrimPiston.target();
        Rig r = rig; return r != null ? r.target : null;
    }
    /** PistonAura crystal cell. */
    static BlockPos crystalPos() {
        if (grim) { BlockPos[] g = GrimPiston.shownRig(); return g != null ? g[2] : null; }
        Rig r = rig; return r != null ? r.C : null;
    }
    /** GrimPiston hit a crystal: the fires/s counter on the HUD. */
    static void noteFire() { fireTimes.addLast(System.currentTimeMillis()); }
    public static String cmdPrefix() { return ","; }

    public static List<Setting> getSettings() {
        List<Setting> s = new ArrayList<>();
        s.add(Setting.bool("Grim", () -> grim, v -> { grim = v; softReset(Minecraft.getInstance()); GrimPiston.reset(); }));
        s.addAll(GrimPiston.settings(() -> grim));
        s.add(Setting.num("Target range", 2, 8, 0.5, () -> targetRange, v -> targetRange = v).when(() -> !grim));
        s.add(Setting.num("Placement range", 2, 6, 0.1, () -> placeReach, v -> placeReach = v).when(() -> !grim));
        s.add(Setting.num("Attack range", 2, 6, 0.1, () -> attackReach, v -> attackReach = v).when(() -> !grim));
        s.add(Setting.num("Min. exposure", 0, 1, 0.05, () -> minExposure, v -> minExposure = v).when(() -> !grim));
        s.add(Setting.num("Confirmation padding (ticks)", 1, 12, 1, () -> confirmExtra, v -> confirmExtra = (int) v).when(() -> !grim));
        s.add(Setting.bool("Instant trigger", () -> instant, v -> instant = v).when(() -> !grim));
        s.add(Setting.bool("Predictive detonation", () -> predictFire, v -> predictFire = v).when(() -> !grim));
        s.add(Setting.num("Detonation delay (ticks)", 1, 8, 1, () -> fireDelay, v -> fireDelay = (int) v).when(() -> !grim && (predictFire || instant)));
        s.add(Setting.bool("Chain (fast cycle)", () -> chain, v -> chain = v).when(() -> !grim));
        s.add(Setting.bool("Push into block (overhead)", () -> intoBlock, v -> intoBlock = v).when(() -> !grim));
        s.add(Setting.bool("Breaker crystal", () -> breaker, v -> breaker = v).when(() -> !grim));
        s.add(Setting.bool("Breach", () -> breach, v -> breach = v).when(() -> !grim));
        s.add(Setting.bool("Break with pickaxe", () -> pickaxe, v -> pickaxe = v).when(() -> !grim));
        s.add(Setting.bool("Render", () -> render, v -> render = v));
        s.add(Setting.bool("Debug", () -> debug, v -> debug = v));
        return s;
    }

    public static void init() {
        KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("futureplus", "pistonaura"));
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.futureplus.pistonaura",
            InputConstants.Type.KEYSYM, InputConstants.KEY_R, cat));
        ClientTickEvents.END_CLIENT_TICK.register(PistonAura::tick);
        GrimSwapManager.init();
        ClientTickEvents.END_CLIENT_TICK.register(mc -> GrimDiag.tick());
        ClientSendMessageEvents.ALLOW_CHAT.register(PistonAura::onChatSend);
    }

    // ------------------------------------------------------------- durum

    enum St { IDLE, BREACH, WAIT_C, WAIT, AFTER, BREAK, CLEAN }

    private static final class Rig {
        Player target;
        BlockPos feet, T, C, B, P, H, R, S;   // S: obsidian support placed first if the cell under the piston is empty (else null)
        Direction d;            // outward from T (C = T+d); the piston faces OPPOSITE to d (toward T)
        boolean side, breachT, into;
        double score;
        boolean same(Rig o) { return o != null && P.equals(o.P) && C.equals(o.C) && R.equals(o.R) && target == o.target; }
    }

    private static St state = St.IDLE;
    private static Rig rig;
    private static int tickNo, stateAge, extendedSince = -1, lastFiredId = -1;
    private static int fireAt = -1, fireId = -1, crystalTries, oldGoneTick = -1, noChainUntil;
    private static boolean chained;
    private static int lastFireTick, refires, instantOffUntil;
    private static boolean instantRig;
    private static BlockPos breakerPos;
    private static final ArrayDeque<Integer> emptyPushes = new ArrayDeque<>();
    private static int pistonSeenTick = -1;
    private static final Map<BlockPos, Integer> pending = new HashMap<>();   // sent, waiting for the server's answer
    private static final Map<Long, Integer> bad = new HashMap<>();           // (role, position) -> don't use until this tick
    private static final Set<BlockPos> ours = new HashSet<>();               // pistons/redstone we placed
    private static final ArrayDeque<BlockPos> cleanList = new ArrayDeque<>();
    private static final ArrayDeque<Long> fireTimes = new ArrayDeque<>();
    private static BlockPos mining;
    private static int minePrevSlot = -1;
    private static boolean rotSpoofed;
    private static String status = "-";
    private static int msgCd;

    private static final int ROLE_P = 1, ROLE_R = 2, ROLE_C = 3, ROLE_T = 4;
    private static long key(int role, BlockPos p) { return p.asLong() * 8 + role; }
    private static boolean isBad(int role, BlockPos p) { Integer t = bad.get(key(role, p)); return t != null && t > tickNo; }
    private static void markBad(int role, BlockPos p, int ticks) { bad.put(key(role, p), tickNo + Math.min(ticks, 40)); }

    public static String hudInfo() {
        if (!enabled) return null;
        long now = System.currentTimeMillis();
        while (!fireTimes.isEmpty() && now - fireTimes.peekFirst() > 1000) fireTimes.pollFirst();
        Player tg = currentTarget();
        String t = tg != null ? tg.getPlainTextName() + ", " : "";
        return t + fireTimes.size() + "/s, " + (grim ? GrimPiston.status : status);
    }

    private static void dbg(String m) {
        if (!debug) return;
        System.out.println("[Futureplus] PistonAura: " + m);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendSystemMessage(FontMod.comp("[PA] " + m));
    }

    private static void warn(Minecraft mc, String m) {
        if (msgCd > 0 || mc.player == null) return;
        mc.player.sendSystemMessage(FontMod.comp(m));
        msgCd = 100;
    }

    private static void go(St s) {
        if (s != state) dbg(state + " -> " + s + (rig != null ? " (P=" + rig.P.toShortString() + " R=" + rig.R.toShortString()
            + (rig.side ? " side" : " straight") + (rig.into ? " into block" : "") + ")" : ""));
        state = s; stateAge = 0;
    }

    // ------------------------------------------------------------- tick

    private static void tick(Minecraft mc) {
        while (toggleKey.consumeClick()) setEnabled(!enabled);
        if (msgCd > 0) msgCd--;
        if (mc.player == null || mc.level == null || mc.gameMode == null || mc.getConnection() == null) {
            rig = null; state = St.IDLE; pending.clear(); mining = null; minePrevSlot = -1; rotSpoofed = false;
            return;
        }
        if (!enabled) return;
        if (grim) { GrimPiston.tick(); return; }
        tickNo++;
        stateAge++;
        restoreRot(mc);                                         // turn back from the previous tick's piston look to the real look
        pending.entrySet().removeIf(e -> e.getValue() < tickNo);
        bad.values().removeIf(t -> t < tickNo);
        ours.removeIf(q -> { BlockState s = mc.level.getBlockState(q); return !(isPiston(s) || s.is(Blocks.REDSTONE_BLOCK)) && !pending.containsKey(q); });

        // WATCHDOG: if nothing was fired for 5 s with a target in range it is stuck -> the same reset as toggling
        if (!targetNear(mc)) lastFireTick = tickNo;
        else if (tickNo - lastFireTick > 60 && state != St.BREACH && state != St.CLEAN) {
            dbg("watchdog: stalled, resetting (state " + state + ", " + status + ")");
            softReset(mc);
            lastFireTick = tickNo;
        }

        refire(mc);

        try {
            switch (state) {
                case IDLE -> idle(mc);
                case BREACH -> breachTick(mc);
                case WAIT_C -> waitCTick(mc);
                case WAIT -> waitTick(mc);
                case AFTER -> afterTick(mc);
                case BREAK -> breakTick(mc);
                case CLEAN -> cleanTick(mc);
            }
        } catch (Throwable t) {
            System.err.println("[Futureplus] PistonAura hata: " + t);
            t.printStackTrace();
            stopMine(mc);
            rig = null; go(St.IDLE);
        }
    }

    private static boolean targetNear(Minecraft mc) {
        for (Player t : mc.level.players()) {
            if (t == mc.player || !t.isAlive() || t.isSpectator() || t.isCreative()) continue;
            if (MaceAura.isFriend(t.getPlainTextName())) continue;
            if (t.distanceTo(mc.player) <= targetRange) return true;
        }
        return false;
    }

    private static void softReset(Minecraft mc) {
        stopMine(mc);
        restoreRot(mc);
        rig = null; state = St.IDLE; stateAge = 0;
        pending.clear(); bad.clear(); cleanList.clear();
        fireAt = -1; noChainUntil = 0; chained = false; lastFiredId = -1; extendedSince = -1; breakerPos = null;
    }

    private static int pingTicks(Minecraft mc) {
        PlayerInfo pi = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        int ms = pi != null ? pi.getLatency() : 100;
        return Math.max(1, (ms + 49) / 50);
    }

    // ------------------------------------------------------------- IDLE: pick a line, send in one tick

    private static void idle(Minecraft mc) {
        LocalPlayer p = mc.player;
        int sP = slot(p, Items.PISTON), sC = slot(p, Items.END_CRYSTAL), sR = slot(p, Items.REDSTONE_BLOCK), sO = slot(p, Items.OBSIDIAN);
        if (sP < 0) sP = slot(p, Items.STICKY_PISTON);
        if (sP < 0 || sC < 0 || sR < 0) {
            status = "missing item";
            warn(mc, "PistonAura: keep a piston, end crystal, and redstone block in your hotbar (obsidian recommended).");
            return;
        }

        Rig n = findRig(mc, p);
        if (n == null) {
            // if we just fired, don't mine until the explosion's result arrives (the parts will break anyway)
            BlockPos junk = tickNo - lastFireTick < 2 * pingTicks(mc) + confirmExtra ? null : nearestOurs(mc, p);
            if (junk != null) { cleanList.clear(); cleanList.add(junk); go(St.CLEAN); return; }
            status = "no setup";
            rig = null;
            return;
        }
        rig = n;

        BlockPos conflict = conflictingOurs(mc, n);
        if (conflict != null) { cleanList.clear(); cleanList.add(conflict); go(St.CLEAN); return; }

        // no piston can go down while an old unpushed crystal is at C (the crystal's box covers P): blow it up first
        EndCrystal stale = crystalAt(mc, n.C);
        if (stale != null && !isPiston(mc.level.getBlockState(n.P)) && !pending.containsKey(n.P) && !pending.containsKey(n.C)) {
            mc.gameMode.attack(p, stale);
            p.swing(InteractionHand.MAIN_HAND);
            lastFiredId = stale.getId();
            status = "stale crystal";
            return;
        }

        if (n.breachT && !isAirish(mc, n.T)) { go(St.BREACH); return; }

        chained = false;
        beginSetup();
        boolean sent = setup(mc, p, n, sP, sC, sO, false);
        status = "setting up";
        boolean pGoing = pending.containsKey(n.P) || isPiston(mc.level.getBlockState(n.P));
        boolean cGoing = pending.containsKey(n.C) || crystalAt(mc, n.C) != null;
        if (sent && state == St.IDLE && pGoing && cGoing && instantNow() && !mc.level.getBlockState(n.R).is(Blocks.REDSTONE_BLOCK)) {
            sendTrigger(mc, p, n, sR);                           // piston + crystal + redstone in the same tick
            return;
        }
        go(St.WAIT_C);
        if (!sent) waitCTick(mc);                                // everything is already in place: trigger in the same tick
    }

    private static boolean instantNow() { return instant && tickNo >= instantOffUntil; }


    /** Instant trigger: sends the redstone without waiting for the crystal to be confirmed; fires once the crystal shows + fireDelay. */
    private static void sendTrigger(Minecraft mc, LocalPlayer p, Rig r, int sR) {
        if (sR < 0 || !placeBlock(mc, p, r.R, sR, r.H)) { markBad(ROLE_R, r.R, 40); go(St.WAIT_C); return; }
        ours.add(r.R);
        fireId = -1;
        fireAt = tickNo + fireDelay;
        extendedSince = -1;
        instantRig = true;
        status = "triggered";
        go(St.WAIT);
    }

    /** If the hit crystal is still standing (packet loss etc.) hit it again — whatever state we're in. */
    private static void refire(Minecraft mc) {
        if (lastFiredId < 0 || refires >= 2) return;
        if (tickNo - lastFireTick < 2 * pingTicks(mc) + confirmExtra) return;
        Entity e = mc.level.getEntity(lastFiredId);
        if (e == null || !e.isAlive()) { refires = 2; return; }
        if (boxDist(mc.player.getEyePosition(), e.getBoundingBox()) > attackReach + 0.5) return;
        mc.gameMode.attack(mc.player, e);
        mc.player.swing(InteractionHand.MAIN_HAND);
        lastFireTick = tickNo;
        refires++;
        dbg("crystal did not explode; attacked again");
    }

    /**
     * Sends the base, piston and crystal IN ORDER (NO redstone — it comes once the crystal is confirmed).
     * force=true (chain): sends even if the client still sees the old piston/crystal; the server handles the explosion first,
     * so the spots are empty. In that case only a blast resistant block is clicked.
     */
    private static boolean setup(Minecraft mc, LocalPlayer p, Rig r, int sP, int sC, int sO, boolean force) {
        boolean sent = false;
        if (!isBase(mc, r.B)) {
            if (sO < 0 || !placeBlock(mc, p, r.B, sO, null)) { markBad(ROLE_C, r.C, 100); return true; }
            sent = true;
        }
        BlockState sp = mc.level.getBlockState(r.P);
        if (r.S != null && !force && !isPiston(sp) && placeable(mc, r.S)) {      // destek obsidyeni
            if (sO < 0 || !placeBlock(mc, p, r.S, sO, null)) { markBad(ROLE_P, r.P, 100); return true; }
            sent = true;
        }
        if (force || !isPiston(sp) && !pending.containsKey(r.P)) {
            BlockHitResult hit = force ? hardSupport(mc, r.P, r) : support(mc, r.P, r.H);
            if (hit == null) { markBad(ROLE_P, r.P, 100); return true; }
            sendRot(mc, r.d.toYRot(), 0f);                     // looking along d, the piston faces -d (toward T)
            use(mc, p, sP, hit);
            pending.put(r.P, tickNo + 2 * pingTicks(mc) + confirmExtra + 2);
            ours.add(r.P);
            sent = true;
        }
        if (force || crystalAt(mc, r.C) == null && !pending.containsKey(r.C)) {
            placeCrystal(mc, p, r, sC);
            sent = true;
        }
        return sent;
    }

    /** New setup: reset the crystal retry, chain and rotation counters. */
    private static void beginSetup() {
        crystalTries = 0;
        oldGoneTick = -1;
        pistonSeenTick = -1;
    }

    private static void placeCrystal(Minecraft mc, LocalPlayer p, Rig r, int sC) {
        use(mc, p, sC, new BlockHitResult(Vec3.atCenterOf(r.B).add(0, 0.5, 0), Direction.UP, r.B, false));
        pending.put(r.C, tickNo + 2 * pingTicks(mc) + confirmExtra + 2);
    }

    // ------------------------------------------------------------- WAIT_C: piston + crystal confirmed, then trigger

    private static void waitCTick(Minecraft mc) {
        Rig r = rig;
        LocalPlayer p = mc.player;
        if (r == null || r.target.isRemoved() || !r.target.isAlive()) { go(St.IDLE); return; }
        int timeout = 2 * pingTicks(mc) + confirmExtra + 3;

        // in a chain: the world looks as it was until the old (hit) crystal disappears on the client — wait
        if (chained && lastFiredId >= 0) {
            Entity old = mc.level.getEntity(lastFiredId);
            if (old != null && old.isAlive()) {
                if (stateAge >= timeout) { dbg("old crystal did not disappear"); lastFiredId = -1; go(St.IDLE); }
                else status = "waiting";
                return;
            }
            if (oldGoneTick < 0) { oldGoneTick = tickNo; return; }   // 1 tick of margin for block updates
        }

        BlockState sp = mc.level.getBlockState(r.P);
        boolean pistonThere = isPiston(sp);
        boolean pOk = pistonThere && sp.getValue(BlockStateProperties.FACING) == r.d.getOpposite();
        boolean ext = pOk && sp.getValue(BlockStateProperties.EXTENDED)
            || mc.level.getBlockState(r.H).is(Blocks.PISTON_HEAD);
        EndCrystal c = crystalAt(mc, r.C);

        if (pOk && !ext && c != null) { trigger(mc, p, r, c); return; }

        if (ext) {
            if (c != null) {                                         // the crystal was placed after the head: it isn't pushed, but hit it anyway
                fire(mc, c);
                return;
            }
            // the piston went out without a crystal: P was already powered (leftover redstone / other power). Turn the chain off, change P.
            boolean redLeft = mc.level.getBlockState(r.R).is(Blocks.REDSTONE_BLOCK);
            dbg("piston extended without crystal P=" + r.P.toShortString() + (chained ? " (chained)" : "") + (redLeft ? " redstone remains" : ""));
            if (chained) {
                noChainUntil = tickNo + 100;
                if (!redLeft && fireDelay < 6) { fireDelay++; dbg("detonation delay -> " + fireDelay); }   // exploded early: the piston was still moving
            }
            if (redLeft) markBad(ROLE_P, r.P, 200);                  // the spot was already powered: another P
            if (pistonThere) cleanList.add(r.P);                     // a piston breaks instantly (hardness 0.5)
            go(cleanList.isEmpty() ? St.IDLE : St.CLEAN);
            return;
        }

        // the piston showed but the crystal didn't come within 2 ticks, so it was refused: once more right away
        if (pOk && pistonSeenTick < 0) pistonSeenTick = tickNo;
        if (c == null && pOk && tickNo - pistonSeenTick >= 2 && crystalTries < 1) {
            int sC = slot(p, Items.END_CRYSTAL);
            if (sC >= 0) { crystalTries++; placeCrystal(mc, p, r, sC); dbg("retrying crystal"); }
        }

        if (stateAge < timeout) { status = "waiting for confirmation"; return; }

        if (!pistonThere) { dbg("piston was not placed " + r.P.toShortString()); markBad(ROLE_P, r.P, 200); if (chained) noChainUntil = tickNo + 200; }
        else if (!pOk) {
            dbg("piston facing the wrong way: " + sp.getValue(BlockStateProperties.FACING) + " (expected " + r.d.getOpposite() + ")");
            markBad(ROLE_P, r.P, 200);
            cleanList.add(r.P);
        } else {
            dbg("crystal was not placed " + r.C.toShortString());           // the piston stands but didn't extend -> another C is tried
            markBad(ROLE_C, r.C, 60);
        }
        go(cleanList.isEmpty() ? St.IDLE : St.CLEAN);
    }

    /** Crystal and piston confirmed: place the redstone, set up predictive fire. */
    private static void trigger(Minecraft mc, LocalPlayer p, Rig r, EndCrystal c) {
        if (!mc.level.getBlockState(r.R).is(Blocks.REDSTONE_BLOCK)) {
            int sR = slot(p, Items.REDSTONE_BLOCK);
            if (sR < 0 || !placeBlock(mc, p, r.R, sR, r.H)) { markBad(ROLE_R, r.R, 100); go(St.IDLE); return; }
            ours.add(r.R);
        }
        fireId = c.getId();
        fireAt = predictFire ? tickNo + fireDelay : -1;
        extendedSince = -1;
        instantRig = false;
        status = "triggered";
        go(St.WAIT);
    }

    private static void fire(Minecraft mc, Entity c) {
        mc.gameMode.attack(mc.player, c);
        mc.player.swing(InteractionHand.MAIN_HAND);
        lastFiredId = c.getId();
        fireAt = -1;
        refires = 0;
        fireTimes.addLast(System.currentTimeMillis());
        lastFireTick = tickNo;
        Rig r = rig;
        if (r != null) {                                          // like toggling: clear this line's 'waiting' records
            pending.remove(r.P); pending.remove(r.C); pending.remove(r.R); pending.remove(r.B);
            if (r.S != null) pending.remove(r.S);
        }
        status = "detonate";
        go(St.AFTER);
    }

    // ------------------------------------------------------------- WAIT: push and fire

    private static void waitTick(Minecraft mc) {
        Rig r = rig;
        if (r == null) { go(St.IDLE); return; }
        boolean inReach = boxDist(mc.player.getEyePosition(), crystalBox(r.T)) <= attackReach + 0.5;

        EndCrystal c = crystalNear(mc, r);
        if (fireId < 0 && c != null) fireId = c.getId();               // with the instant trigger the crystal's id is only known now
        Entity fc = fireId >= 0 ? mc.level.getEntity(fireId) : null;

        // predictive fire: fireDelay ticks after the redstone (the push is done on the server); the crystal must be visible
        if (fireAt >= 0 && tickNo >= fireAt && fc instanceof EndCrystal && fc.isAlive() && inReach) { fire(mc, fc); return; }

        BlockState sp = mc.level.getBlockState(r.P);
        boolean pistonOk = isPiston(sp) && sp.getValue(BlockStateProperties.FACING) == r.d.getOpposite();
        boolean headDone = mc.level.getBlockState(r.H).is(Blocks.PISTON_HEAD);
        boolean extProp = pistonOk && sp.getValue(BlockStateProperties.EXTENDED);
        if (extProp || headDone) { if (extendedSince < 0) extendedSince = tickNo; } else extendedSince = -1;

        boolean pushed = headDone || extendedSince >= 0 && tickNo - extendedSince >= 2;
        if (pushed && c != null && inReach && (fireAt < 0 || tickNo >= fireAt)) { fire(mc, c); return; }

        int timeout = 2 * pingTicks(mc) + confirmExtra + fireDelay;
        if (stateAge < timeout) { status = extendedSince >= 0 ? "pushing" : "waiting"; return; }

        // timeout: hit a visible crystal anyway; otherwise ban the line briefly and move to another line WITHOUT MINING
        if (c != null && inReach) { fire(mc, c); return; }
        boolean redOk = mc.level.getBlockState(r.R).is(Blocks.REDSTONE_BLOCK);
        if (c == null) {
            dbg("crystal did not appear " + r.C.toShortString() + (instantRig ? " (instant)" : ""));
            markBad(ROLE_C, r.C, 30);
            if (instantRig) {
                emptyPushes.addLast(tickNo);
                while (!emptyPushes.isEmpty() && tickNo - emptyPushes.peekFirst() > 200) emptyPushes.pollFirst();
                if (emptyPushes.size() >= 2) { instantOffUntil = tickNo + 200; emptyPushes.clear(); dbg("instant trigger disabled for 10s (confirmed path)"); }
            }
        } else if (!redOk) {
            dbg("redstone was not placed " + r.R.toShortString());
            markBad(ROLE_R, r.R, 40);
        } else {
            dbg("piston did not extend R=" + r.R.toShortString());
            markBad(ROLE_R, r.R, 40);
            markBad(ROLE_P, r.P, 40);
        }
        go(St.IDLE);
    }

    // ------------------------------------------------------------- AFTER: after the explosion (chain)

    private static void afterTick(Minecraft mc) {
        Rig r = rig;
        if (r == null) { go(St.IDLE); return; }
        LocalPlayer p = mc.player;
        if (stateAge < 1) return;

        // pushing into a solid block over the head: the explosion is inside the block, nothing breaks -> mine the redstone right away
        // (the piston retracts and stays ready, the next cycle comes back with a crystal + redstone)
        if (r.into && mc.level.getBlockState(r.T).getBlock().getExplosionResistance() >= 100f
                && mc.level.getBlockState(r.R).is(Blocks.REDSTONE_BLOCK)) {
            // BREAKER CRYSTAL: place a second crystal next to the piston and blow it up -> the piston, head and redstone break,
            // and the line is built from scratch (piston -> crystal -> redstone). Without a spot, the old way: mine the redstone.
            int sC = slot(p, Items.END_CRYSTAL);
            BlockPos q = sC >= 0 && breaker ? findBreaker(mc, p, r) : null;
            if (q != null) {
                use(mc, p, sC, new BlockHitResult(Vec3.atCenterOf(q.below()).add(0, 0.5, 0), Direction.UP, q.below(), false));
                pending.put(q, tickNo + 2 * pingTicks(mc) + confirmExtra + 2);
                breakerPos = q;
                status = "breaker crystal";
                dbg("breaker crystal " + q.toShortString());
                go(St.BREAK);
                return;
            }
            cleanList.clear();
            cleanList.add(r.R);
            go(St.CLEAN);
            return;
        }

        // CHAIN: the explosion breaks the piston + head -> rebuild the same line without waiting for an answer
        if (chain && tickNo >= noChainUntil && canChain(mc, p, r)) {
            int sP = slot(p, Items.PISTON), sC = slot(p, Items.END_CRYSTAL), sO = slot(p, Items.OBSIDIAN), sR = slot(p, Items.REDSTONE_BLOCK);
            if (sP < 0) sP = slot(p, Items.STICKY_PISTON);
            if (sP >= 0 && sC >= 0) {
                chained = true;
                beginSetup();
                setup(mc, p, r, sP, sC, sO, true);
                status = "chained";
                if (instantNow()) {                                  // redstone de hemen
                    BlockHitResult hit = support(mc, r.R, r.H);
                    if (sR >= 0 && hit != null) {
                        use(mc, p, sR, hit);
                        pending.put(r.R, tickNo + 2 * pingTicks(mc) + confirmExtra + 2);
                        ours.add(r.R);
                        fireId = -1; fireAt = tickNo + fireDelay; extendedSince = -1; instantRig = true;
                        go(St.WAIT);
                        return;
                    }
                }
                go(St.WAIT_C);
                return;
            }
        }

        // otherwise: move to the best free line without waiting for an answer (as toggling does)
        go(St.IDLE);
        idle(mc);
    }

    // ------------------------------------------------------------- BREAK: blow up the breaker crystal

    private static void breakTick(Minecraft mc) {
        Rig r = rig;
        BlockPos q = breakerPos;
        if (r == null || q == null) { go(St.IDLE); return; }
        EndCrystal c = crystalAt(mc, q);
        if (c != null) {
            if (boxDist(mc.player.getEyePosition(), c.getBoundingBox()) <= attackReach + 0.5) {
                mc.gameMode.attack(mc.player, c);
                mc.player.swing(InteractionHand.MAIN_HAND);
                lastFiredId = c.getId();
                refires = 0;
                lastFireTick = tickNo;
                pending.remove(q);
                breakerPos = null;
                status = "piston broken";
                go(St.IDLE);                                     // once the explosion result arrives the line empties, a new cycle
                return;
            }
        }
        if (stateAge < 2 * pingTicks(mc) + confirmExtra + 2) { status = "waiting for breaker"; return; }
        dbg("breaker crystal wasn't placed " + q.toShortString() + ", mining redstone");
        breakerPos = null;
        cleanList.clear();
        if (mc.level.getBlockState(r.R).is(Blocks.REDSTONE_BLOCK)) cleanList.add(r.R);
        go(cleanList.isEmpty() ? St.IDLE : St.CLEAN);
    }

    /**
     * Spot for a second crystal to break the piston (and redstone): obsidian/bedrock under it, itself empty, no entities,
     * within placement range, clear sight from the explosion center to the piston. The one closest to the piston and redstone.
     */
    private static BlockPos findBreaker(Minecraft mc, LocalPlayer p, Rig r) {
        Vec3 eye = p.getEyePosition();
        Vec3 pc = Vec3.atCenterOf(r.P), rc = Vec3.atCenterOf(r.R);
        AABB tb = r.target.getBoundingBox();
        BlockPos best = null; double bs = 1e9;
        for (int dx = -3; dx <= 3; dx++) for (int dy = -2; dy <= 2; dy++) for (int dz = -3; dz <= 3; dz++) {
            BlockPos q = r.P.offset(dx, dy, dz);
            if (q.equals(r.P) || q.equals(r.H) || q.equals(r.R) || q.equals(r.C) || q.equals(r.T) || q.equals(r.S)) continue;
            BlockPos base = q.below();
            BlockState bs0 = mc.level.getBlockState(base);
            if (!(bs0.is(Blocks.OBSIDIAN) || bs0.is(Blocks.BEDROCK))) continue;
            if (!isAirish(mc, q) || pending.containsKey(q)) continue;
            AABB col = new AABB(q.getX(), q.getY(), q.getZ(), q.getX() + 1, q.getY() + 2, q.getZ() + 1);
            if (col.intersects(tb) || entityIn(mc, col)) continue;          // old crystal / players
            if (eye.distanceTo(Vec3.atCenterOf(base).add(0, 0.5, 0)) > placeReach) continue;
            Vec3 center = Vec3.atBottomCenterOf(q).add(0, 0.05, 0);
            double dp = center.distanceTo(pc);
            if (dp > 3.6) continue;
            if (!clearTo(mc, center, pc, r)) continue;                       // the explosion has to reach the piston
            double score = dp + 0.6 * center.distanceTo(rc) + (clearTo(mc, center, rc, r) ? 0 : 2);
            if (score < bs) { bs = score; best = q; }
        }
        return best;
    }

    /** ray from a to b: no block in between other than our piston/its head/redstone. */
    private static boolean clearTo(Minecraft mc, Vec3 a, Vec3 b, Rig r) {
        BlockHitResult h = mc.level.clip(new ClipContext(a, b, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        if (h.getType() == HitResult.Type.MISS) return true;
        BlockPos hp = h.getBlockPos();
        return hp.equals(r.P) || hp.equals(r.H) || hp.equals(r.R);
    }

    /** Whether the same line can be chained: the target in place, the base solid, a blast resistant click face for the piston. */
    private static boolean canChain(Minecraft mc, LocalPlayer p, Rig r) {
        if (r.breachT || r.into || r.target.isRemoved() || !r.target.isAlive() || !r.target.blockPosition().equals(r.feet)) return false;
        if (r.target.distanceTo(p) > targetRange) return false;
        if (!isBase(mc, r.B) || isBad(ROLE_P, r.P) || isBad(ROLE_C, r.C)) return false;
        Vec3 eye = p.getEyePosition();
        if (eye.distanceTo(Vec3.atCenterOf(r.P)) > placeReach || eye.distanceTo(Vec3.atCenterOf(r.R)) > placeReach) return false;
        return hardSupport(mc, r.P, r) != null;
    }

    /** A neighbour face to click for P that won't break in the explosion (obsidian, bedrock...); excluding the line's cells. */
    private static BlockHitResult hardSupport(Minecraft mc, BlockPos pos, Rig r) {
        Vec3 eye = mc.player.getEyePosition();
        BlockHitResult best = null; double bd = 1e9;
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            if (n.equals(r.H) || n.equals(r.R) || n.equals(r.C) || n.equals(r.T)) continue;
            BlockState s = mc.level.getBlockState(n);
            if (s.isAir() || s.liquid() || s.getBlock().getExplosionResistance() < 100f) continue;
            Direction face = d.getOpposite();
            Vec3 pt = Vec3.atCenterOf(n).add(face.getUnitVec3().scale(0.5));
            double dist = eye.distanceTo(pt);
            if (dist < bd) { bd = dist; best = new BlockHitResult(pt, face, n, false); }
        }
        return best;
    }

    // ------------------------------------------------------------- BREACH / CLEAN (mining at real speed)

    private static void breachTick(Minecraft mc) {
        Rig r = rig;
        if (r == null || r.target.isRemoved() || !r.target.blockPosition().equals(r.feet)) { stopMine(mc); go(St.IDLE); return; }
        if (isAirish(mc, r.T)) { stopMine(mc); go(St.IDLE); idle(mc); return; }
        if (stateAge > 600) { stopMine(mc); markBad(ROLE_T, r.T, 200); go(St.IDLE); return; }
        status = "mining wall";
        mine(mc, r.T);
    }

    private static void cleanTick(Minecraft mc) {
        while (!cleanList.isEmpty()) {
            BlockPos q = cleanList.peekFirst();
            BlockState s = mc.level.getBlockState(q);
            boolean done = s.isAir() || s.canBeReplaced()
                || mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(q)) > placeReach + 1.5;
            if (!done) break;
            cleanList.pollFirst();
            stopMine(mc);
        }
        if (cleanList.isEmpty() || stateAge > 100) { stopMine(mc); cleanList.clear(); go(St.IDLE); return; }
        status = "cleanup";
        mine(mc, cleanList.peekFirst());
    }

    private static void mine(Minecraft mc, BlockPos pos) {
        LocalPlayer p = mc.player;
        if (mining == null || !mining.equals(pos)) {
            stopMine(mc);
            mining = pos;
            if (pickaxe) {
                int best = bestTool(p, mc.level.getBlockState(pos));
                if (best >= 0 && best != p.getInventory().getSelectedSlot()) {
                    minePrevSlot = p.getInventory().getSelectedSlot();
                    p.getInventory().setSelectedSlot(best);
                    mc.getConnection().send(new ServerboundSetCarriedItemPacket(best));
                }
            }
            mc.gameMode.startDestroyBlock(pos, faceToward(p, pos));
        } else {
            mc.gameMode.continueDestroyBlock(pos, faceToward(p, pos));
        }
        p.swing(InteractionHand.MAIN_HAND);
    }

    private static void stopMine(Minecraft mc) {
        if (mining != null && mc.gameMode != null) { try { mc.gameMode.stopDestroyBlock(); } catch (Throwable ignored) { } }
        mining = null;
        if (minePrevSlot >= 0 && mc.player != null && mc.getConnection() != null) {
            mc.player.getInventory().setSelectedSlot(minePrevSlot);
            mc.getConnection().send(new ServerboundSetCarriedItemPacket(minePrevSlot));
        }
        minePrevSlot = -1;
    }

    private static Direction faceToward(LocalPlayer p, BlockPos pos) {
        return Direction.getApproximateNearest(p.getEyePosition().subtract(Vec3.atCenterOf(pos)));
    }

    private static int bestTool(LocalPlayer p, BlockState s) {
        int best = -1; float bs = 1f;
        for (int i = 0; i < 9; i++) { float sp = p.getInventory().getItem(i).getDestroySpeed(s); if (sp > bs) { bs = sp; best = i; } }
        return best;
    }

    // ------------------------------------------------------------- hat arama

    private static Rig findRig(Minecraft mc, LocalPlayer p) {
        Rig best = null;
        for (Player t : mc.level.players()) {
            if (t == p || !t.isAlive() || t.isSpectator() || t.isCreative()) continue;
            if (MaceAura.isFriend(t.getPlainTextName())) continue;
            if (t.distanceTo(p) > targetRange) continue;
            Rig r = bestFor(mc, p, t);
            if (r != null && (best == null || r.score > best.score)) best = r;
        }
        return best;
    }

    private static Rig bestFor(Minecraft mc, LocalPlayer p, Player t) {
        AABB box = t.getBoundingBox();
        int bx = (int) Math.floor((box.minX + box.maxX) / 2.0), bz = (int) Math.floor((box.minZ + box.maxZ) / 2.0);
        int minY = (int) Math.floor(box.minY + 1.0E-3), maxY = (int) Math.floor(box.maxY - 1.0E-3);
        Rig best = null;
        for (int y = minY; y <= maxY + 1; y++) {                                  // leg, head, above head (empty)
            BlockPos T = new BlockPos(bx, y, bz);
            if (!isAirish(mc, T)) continue;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                Rig r = tryLine(mc, p, t, T, d, bx, bz, minY, maxY, false, false);
                if (r != null && (best == null || r.score > best.score)) best = r;
            }
        }
        if (intoBlock) {                                                         // above head filled: count the block as air, push into it
            BlockPos T = new BlockPos(bx, maxY + 1, bz);
            if (!isAirish(mc, T) && !isBad(ROLE_T, T)) {
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    Rig r = tryLine(mc, p, t, T, d, bx, bz, minY, maxY, false, true);
                    if (r != null && (best == null || r.score > best.score)) best = r;
                }
            }
        }
        if (best != null || !breach) return best;
        for (int y = maxY + 1; y >= minY; y--) {                                  // opening the box: a filled but breakable T
            BlockPos T = new BlockPos(bx, y, bz);
            BlockState s = mc.level.getBlockState(T);
            if (intoBlock && y == maxY + 1) continue;                             // never mine the block over the head: it gets pushed into
            if (isAirish(mc, T) || s.getDestroySpeed(mc.level, T) < 0 || isBad(ROLE_T, T)) continue;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                Rig r = tryLine(mc, p, t, T, d, bx, bz, minY, maxY, true, false);
                if (r != null && (best == null || r.score > best.score)) best = r;
            }
        }
        return best;
    }

    private static Rig tryLine(Minecraft mc, LocalPlayer p, Player t, BlockPos T, Direction d,
                               int bx, int bz, int minY, int maxY, boolean breachT, boolean into) {
        Vec3 eye = p.getEyePosition();
        if (boxDist(eye, crystalBox(T)) > attackReach) return null;          // distance to the pushed crystal's box
        BlockPos C = T.relative(d), B = C.below();
        if (body(C, bx, bz, minY, maxY) || isBad(ROLE_C, C)) return null;
        if (!isAirish(mc, C)) return null;                                     // the crystal cell must be empty
        boolean crystalHere = crystalAt(mc, C) != null;
        // no entity (player etc.) in the 1x2 column where the crystal goes
        if (!crystalHere && entityIn(mc, new AABB(C.getX(), C.getY(), C.getZ(), C.getX() + 1, C.getY() + 2, C.getZ() + 1))) return null;
        if (!(isBase(mc, B) || placeable(mc, B) && support(mc, B, null) != null)) return null;
        if (eye.distanceTo(Vec3.atCenterOf(B)) > placeReach) return null;

        Direction s1 = d.getClockWise(), s2 = d.getCounterClockWise();
        BlockPos[] pcs = {C.relative(d), C.relative(d).relative(s1), C.relative(d).relative(s2)};
        Rig best = null;
        for (int i = 0; i < pcs.length; i++) {
            BlockPos P = pcs[i], S = null;
            BlockPos H = P.relative(d.getOpposite());                             // the cell the head will enter
            if (isBad(ROLE_P, P) || body(P, bx, bz, minY, maxY) || body(H, bx, bz, minY, maxY)) continue;
            BlockState sp = mc.level.getBlockState(P);
            boolean pistonReady = isPiston(sp) && sp.getValue(BlockStateProperties.FACING) == d.getOpposite()
                && !sp.getValue(BlockStateProperties.EXTENDED);
            if (!pistonReady) {
                if (!placeable(mc, P)) continue;
                if (entityIn(mc, new AABB(P))) continue;                          // a player or an old crystal's box
                if (eye.distanceTo(Vec3.atCenterOf(P)) > placeReach) continue;
                if (support(mc, P, H) == null) {                                   // nothing under it: look for obsidian support
                    S = findSupportBlock(mc, p, P, H, C, B, T, bx, bz, minY, maxY);
                    if (S == null) continue;
                }
            }
            if (!H.equals(C) && !isAirish(mc, H)) continue;

            // redstone: back, sides, top — excluding the head cell and the crystal box
            BlockPos[] rcs = {P.relative(d), P.relative(s1), P.relative(s2), P.above()};
            BlockPos R = null;
            for (BlockPos q : rcs) {
                if (q.equals(H) || q.equals(C) || q.equals(B) || q.equals(S) || isBad(ROLE_R, q) || body(q, bx, bz, minY, maxY)) continue;
                if (new AABB(q).intersects(crystalBox(C))) continue;              // placed after the crystal: must not touch its box
                if (!placeable(mc, q) || entityIn(mc, new AABB(q))) continue;
                if (eye.distanceTo(Vec3.atCenterOf(q)) > placeReach) continue;
                R = q; break;
            }
            if (R == null) continue;
            if (!pistonReady && poweredBy(mc, P, H, R)) continue;                 // a piston extends for nothing on an already powered spot

            // when pushing into a block the crystal stands at the base of the block over the head: exposure is measured just below the base
            double exp = breachT ? 0.8 : into ? Math.max(0.6, seenPercent(mc, Vec3.atBottomCenterOf(T).add(0, -0.15, 0), t))
                : seenPercent(mc, Vec3.atBottomCenterOf(T).add(0, 0.2, 0), t);
            if (!breachT && exp < minExposure) continue;
            Rig r = new Rig();
            r.target = t; r.feet = t.blockPosition(); r.T = T; r.C = C; r.B = B; r.P = P; r.H = H; r.R = R; r.d = d;
            r.side = i > 0; r.breachT = breachT; r.into = into; r.S = S;
            double reach = Math.max(eye.distanceTo(Vec3.atCenterOf(P)), eye.distanceTo(Vec3.atCenterOf(R)));
            r.score = exp * 100 - reach * 2 - (r.side ? 6 : 0) - (breachT ? 30 : 0) - (into ? 5 : 0) - (S != null ? 4 : 0)
                + (T.getY() == minY + 1 ? 3 : 0)                                   // prefer the head cell
                + (rig != null && r.same(rig) ? 25 : 0)                            // same line: no left/right wobble
                + (rig != null && rig.target == t && rig.d == d ? 10 : 0)          // same side
                + (pistonReady ? 8 : 0) + (crystalHere ? 4 : 0);
            if (best == null || r.score > best.score) best = r;
        }
        return best;
    }

    /** If the piston has no face to click: a placeable obsidian cell next to P that is itself supported. */
    private static BlockPos findSupportBlock(Minecraft mc, LocalPlayer p, BlockPos P, BlockPos H, BlockPos C, BlockPos B, BlockPos T,
                                             int bx, int bz, int minY, int maxY) {
        if (slot(p, Items.OBSIDIAN) < 0) return null;
        Vec3 eye = p.getEyePosition();
        for (Direction dd : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos q = P.relative(dd);
            if (q.equals(H) || q.equals(C) || q.equals(B) || q.equals(T) || q.equals(C.above()) || body(q, bx, bz, minY, maxY)) continue;
            if (!placeable(mc, q) || entityIn(mc, new AABB(q))) continue;
            if (new AABB(q).intersects(crystalBox(C)) && crystalAt(mc, C) != null) continue;
            if (eye.distanceTo(Vec3.atCenterOf(q)) > placeReach) continue;
            if (support(mc, q, null) == null) continue;
            return q;
        }
        return null;
    }

    /** Whether P extends by itself once placed: power reaching P or (quasi-connectivity) the cell above P, excluding R. */
    private static boolean poweredBy(Minecraft mc, BlockPos P, BlockPos H, BlockPos R) {
        for (Direction dd : Direction.values()) {
            BlockPos n = P.relative(dd);
            if (n.equals(H) || n.equals(R)) continue;
            if (mc.level.getBlockState(n).is(Blocks.REDSTONE_BLOCK)) return true;
        }
        try {
            if (mc.level.hasNeighborSignal(P)) return true;
            BlockPos up = P.above();
            if (!up.equals(R)) for (Direction dd : Direction.values()) {
                BlockPos n = up.relative(dd);
                if (n.equals(P) || n.equals(R)) continue;
                if (mc.level.getBlockState(n).is(Blocks.REDSTONE_BLOCK)) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private static boolean adjacent(BlockPos a, BlockPos b) { return a.distManhattan(b) == 1; }

    private static double boxDist(Vec3 e, AABB b) {
        double x = Math.max(b.minX, Math.min(e.x, b.maxX)), y = Math.max(b.minY, Math.min(e.y, b.maxY)), z = Math.max(b.minZ, Math.min(e.z, b.maxZ));
        return e.distanceTo(new Vec3(x, y, z));
    }

    private static AABB crystalBox(BlockPos c) {
        return new AABB(c.getX() - 0.5, c.getY(), c.getZ() - 0.5, c.getX() + 1.5, c.getY() + 2.0, c.getZ() + 1.5);
    }

    private static boolean body(BlockPos q, int bx, int bz, int minY, int maxY) {
        return q.getX() == bx && q.getZ() == bz && q.getY() >= minY && q.getY() <= maxY;
    }

    /** An old part of ours that ruins the new line: redstone pre-powering the new piston, or a piston standing in a cell of the line. */
    private static BlockPos conflictingOurs(Minecraft mc, Rig n) {
        for (BlockPos q : ours) {
            if (q.equals(n.P) || q.equals(n.R) || pending.containsKey(q)) continue;
            BlockState s = mc.level.getBlockState(q);
            if (s.is(Blocks.REDSTONE_BLOCK) && adjacent(q, n.P) && !q.equals(n.H)) return q;
            if (isPiston(s) && (q.equals(n.C) || q.equals(n.H))) return q;
        }
        return null;
    }

    private static BlockPos nearestOurs(Minecraft mc, LocalPlayer p) {
        BlockPos best = null; double bd = 1e9;
        for (BlockPos q : ours) {
            if (pending.containsKey(q)) continue;
            double d = p.getEyePosition().distanceTo(Vec3.atCenterOf(q));
            if (d < bd && d <= placeReach + 1) { bd = d; best = q; }
        }
        return best;
    }

    // ------------------------------------------------------------- placement

    private static boolean placeBlock(Minecraft mc, LocalPlayer p, BlockPos pos, int slot, BlockPos avoid) {
        BlockHitResult hit = support(mc, pos, avoid);
        if (hit == null) return false;
        use(mc, p, slot, hit);
        pending.put(pos, tickNo + 2 * pingTicks(mc) + confirmExtra + 2);
        return true;
    }

    /** A filled neighbour face to click to place at pos (our placements on the way count as filled too). */
    private static BlockHitResult support(Minecraft mc, BlockPos pos, BlockPos avoid) {
        Vec3 eye = mc.player.getEyePosition();
        BlockHitResult best = null; double bd = 1e9;
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            if (n.equals(avoid)) continue;
            BlockState s = mc.level.getBlockState(n);
            boolean solid = pending.containsKey(n) || !s.isAir() && !s.canBeReplaced() && !s.liquid();
            if (!solid) continue;
            Direction face = d.getOpposite();
            Vec3 pt = Vec3.atCenterOf(n).add(face.getUnitVec3().scale(0.5));
            double dist = eye.distanceTo(pt);
            if (dist < bd) { bd = dist; best = new BlockHitResult(pt, face, n, false); }
        }
        return best;
    }

    private static void use(Minecraft mc, LocalPlayer p, int slot, BlockHitResult hit) {
        var inv = p.getInventory();
        int prev = inv.getSelectedSlot();
        if (slot != prev) { inv.setSelectedSlot(slot); mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot)); }
        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
        p.swing(InteractionHand.MAIN_HAND);
        if (slot != prev) { inv.setSelectedSlot(prev); mc.getConnection().send(new ServerboundSetCarriedItemPacket(prev)); }
    }

    private static void sendRot(Minecraft mc, float yaw, float pitch) {
        mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(yaw, pitch, mc.player.onGround(), mc.player.horizontalCollision));
        rotSpoofed = true;
    }

    private static void restoreRot(Minecraft mc) {
        if (!rotSpoofed || mc.player == null || mc.getConnection() == null) return;
        rotSpoofed = false;
        mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(mc.player.getYRot(), mc.player.getXRot(),
            mc.player.onGround(), mc.player.horizontalCollision));
    }

    // ------------------------------------------------------------- world helpers

    private static int slot(LocalPlayer p, Item item) {
        for (int i = 0; i < 9; i++) if (p.getInventory().getItem(i).is(item)) return i;
        return -1;
    }

    private static boolean isPiston(BlockState s) { return s.is(Blocks.PISTON) || s.is(Blocks.STICKY_PISTON); }

    private static boolean isAirish(Minecraft mc, BlockPos q) {
        BlockState s = mc.level.getBlockState(q);
        return s.isAir() || s.canBeReplaced() && s.getCollisionShape(mc.level, q).isEmpty() && !s.liquid();
    }

    private static boolean placeable(Minecraft mc, BlockPos q) {
        if (pending.containsKey(q)) return false;
        BlockState s = mc.level.getBlockState(q);
        return s.isAir() || s.canBeReplaced();
    }

    private static boolean isBase(Minecraft mc, BlockPos q) {
        if (pending.containsKey(q)) return true;
        BlockState s = mc.level.getBlockState(q);
        return s.is(Blocks.OBSIDIAN) || s.is(Blocks.BEDROCK);
    }

    /** Whether a living entity (player, crystal...) in the box blocks placing a block. */
    private static boolean entityIn(Minecraft mc, AABB box) {
        return !mc.level.getEntities((Entity) null, box, e -> e.isAlive() && e.blocksBuilding).isEmpty();
    }

    private static EndCrystal crystalAt(Minecraft mc, BlockPos c) {
        for (EndCrystal e : mc.level.getEntitiesOfClass(EndCrystal.class, new AABB(c).inflate(0.3)))
            if (e.isAlive() && e.getId() != lastFiredId) return e;
        return null;
    }

    /** A living crystal on the line (between C..T; the client always sees the crystal at C). */
    private static EndCrystal crystalNear(Minecraft mc, Rig r) {
        AABB box = new AABB(r.C).minmax(new AABB(r.T)).inflate(0.4);
        for (EndCrystal e : mc.level.getEntitiesOfClass(EndCrystal.class, box))
            if (e.isAlive() && e.getId() != lastFiredId) return e;
        return null;
    }

    private static double seenPercent(Minecraft mc, Vec3 center, Player target) {
        AABB box = target.getBoundingBox();
        int seen = 0, total = 0;
        for (double fx = 0; fx <= 1.0; fx += 0.5) for (double fy = 0; fy <= 1.0; fy += 0.45) for (double fz = 0; fz <= 1.0; fz += 0.5) {
            Vec3 v = new Vec3(box.minX + (box.maxX - box.minX) * fx, box.minY + (box.maxY - box.minY) * fy, box.minZ + (box.maxZ - box.minZ) * fz);
            if (mc.level.clip(new ClipContext(v, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, target)).getType() == HitResult.Type.MISS) seen++;
            total++;
        }
        return total == 0 ? 0 : (double) seen / total;
    }

    // ------------------------------------------------------------- render (WorldDraw)

    private static final BoxAnimator boxes = new BoxAnimator();

    public static boolean wantsRender() { return enabled && render || !boxes.isEmpty(); }

    public static void emit(Vec3 cam) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (enabled && render) {
            Rig r = rig;
            BlockPos[] g = grim ? GrimPiston.shownRig() : null;
            BlockPos P = grim ? (g != null ? g[0] : null) : r != null ? r.P : null;
            BlockPos R = grim ? (g != null ? g[1] : null) : r != null ? r.R : null;
            BlockPos C = grim ? (g != null ? g[2] : null) : r != null ? r.C : null;
            if (P != null) {
                // each box follows the world: opens when the block/crystal is placed, fades when broken/exploded;
                // a new box opens when placed again
                track("piston", P, new AABB(P), 0xFFFFFF, isPiston(mc.level.getBlockState(P)));
                track("redstone", R, new AABB(R), 0xFF2A2A, mc.level.getBlockState(R).is(Blocks.REDSTONE_BLOCK));
                EndCrystal c = crystalAt(mc, C);
                track("crystal", C, c != null ? c.getBoundingBox() : new AABB(C), 0xB45CFF, c != null);
            }
        }
        boxes.render(cam);
    }

    private static void track(String role, BlockPos pos, AABB shape, int rgb, boolean present) {
        boxes.show(role + pos.asLong(), shape, VisualState.argb(rgb, 50 / 255f), VisualState.argb(rgb, 1f), present);
    }

    // ------------------------------------------------------------- sohbet

    private static boolean onChatSend(String message) {
        String pre = cmdPrefix();
        if (!message.startsWith(pre)) return true;
        Minecraft mc = Minecraft.getInstance();
        String[] t = message.substring(pre.length()).trim().split("\\s+");
        if (t.length == 0 || t[0].isEmpty()) return false;
        String k = t[0].toLowerCase(Locale.ROOT);
        String v = t.length >= 3 && t[1].equalsIgnoreCase("set") ? t[2] : (t.length >= 2 ? t[1] : null);
        switch (k) {
            case "on" -> setEnabled(true);
            case "off" -> setEnabled(false);
            case "range" -> targetRange = num(v, targetRange, 2, 8);
            case "reach" -> placeReach = num(v, placeReach, 2, 6);
            case "attackreach" -> attackReach = num(v, attackReach, 2, 6);
            case "debug" -> debug = "on".equalsIgnoreCase(v) || "true".equalsIgnoreCase(v);
            case "grim" -> { grim = "on".equalsIgnoreCase(v) || "true".equalsIgnoreCase(v); softReset(mc); GrimPiston.reset(); }
            default -> {
                if (mc.player != null) mc.player.sendSystemMessage(FontMod.comp("PistonAura: " + pre + "on, " + pre + "off, "
                    + pre + "range set <2-8>, " + pre + "reach set <2-6>, " + pre + "attackreach set <2-6>, "
                    + pre + "debug on|off, " + pre + "grim on|off, " + pre + "friend add|del|list, " + pre + "config save|load|delete|list"));
            }
        }
        return false;
    }

    private static double num(String v, double cur, double lo, double hi) {
        try { return Math.max(lo, Math.min(hi, Double.parseDouble(v))); } catch (Exception e) { return cur; }
    }
}
