package com.futureplus.client;

import com.futureplus.client.mixin.MultiPlayerGameModeAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Hidden; runs while PistonAura "Debug" is on.
 *
 * Why the server turned something down, as far as it can be told. The server gives no reason, but its answer narrows it down: every Grim
 * placement is written out with what we knew when sending it and what came back. An ack with the slot sent again is the anticheat
 * cancelling it, an ack with the block sent again is the server refusing it, and nothing at all means it never got looked at. Failures are always logged, successes
 * once after a failure (logs/futureplus-<name>.log).
 */
public final class GrimDiag {
    private GrimDiag() {}

    private static final long ANSWER_WAIT_MS = 500;
    private static final double LOOK_REACH = 8.0;

    private static final List<Attempt> attempts = new ArrayList<>();
    private static boolean failedSinceSuccess = true;

    private static Minecraft mc() { return Minecraft.getInstance(); }

    static boolean isOn() { return PistonAura.debug; }

    /** A block or crystal going out. expected is the cell that should change; for a crystal the one it stands in. */
    static void notePlace(String what, BlockHitResult hit, BlockPos expected, int sequence) {
        Minecraft mc = mc();
        if (mc.player == null || !isOn()) return;

        BlockPos clicked = hit.getBlockPos();
        AABB clickedBox = new AABB(clicked);
        Direction side = hit.getDirection();
        Vec3 eye = mc.player.getEyePosition();
        Vec3 faceCenter = Vec3.atCenterOf(clicked).add(side.getStepX() * 0.5, side.getStepY() * 0.5, side.getStepZ() * 0.5);
        boolean faceSeen = eye.subtract(faceCenter).dot(new Vec3(side.getStepX(), side.getStepY(), side.getStepZ())) > 0;
        boolean cameraOn = clickedBox.contains(eye) || clickedBox.clip(eye, eye.add(mc.player.getLookAngle().scale(LOOK_REACH))).isPresent();

        String entities = mc.level.getEntities(null, new AABB(expected)).stream()
                .map(entity -> entity.getType().toShortString()).collect(Collectors.joining(","));
        String context = "clicked=" + clicked.toShortString() + " side=" + side + " faceSeen=" + faceSeen
                + " serverLookOn=" + GrimRotation.serverLooksAt(clickedBox) + " cameraOn=" + cameraOn
                + " eyeDist=" + String.format("%.2f", eye.distanceTo(hit.getLocation()))
                + " serverSlot=" + ((MultiPlayerGameModeAccessor) mc.gameMode).futureplus$carriedIndex() + " selected=" + mc.player.getInventory().getSelectedSlot()
                + " using=" + mc.player.isUsingItem() + " inCell=[" + entities + "]";
        add(new Attempt(what, expected.immutable(), sequence, context, System.currentTimeMillis()));
    }

    /** A STOP going out, which should turn the cell to air. */
    static void noteStop(BlockPos position, String how, int startSlot, double progress) {
        Minecraft mc = mc();
        if (mc.player == null || !isOn()) return;

        String context = "how=" + how + " startSlot=" + startSlot + " serverSlot=" + ((MultiPlayerGameModeAccessor) mc.gameMode).futureplus$carriedIndex()
                + " selected=" + mc.player.getInventory().getSelectedSlot() + " progress=" + String.format("%.2f", progress)
                + " ground=" + mc.player.onGround() + " using=" + mc.player.isUsingItem();
        add(new Attempt("stop", position.immutable(), -1, context, System.currentTimeMillis()));
    }

    // ------------------------------------------------------------- packet hooks (ClientPacketListenerMixin, main thread)

    public static void onBlockAck(int sequence) {
        for (Attempt attempt : snapshot()) if (attempt.sequence == sequence) attempt.ack = true;
    }

    public static void onSlotUpdate(int containerId) {
        for (Attempt attempt : snapshot()) attempt.slotFix = containerId == 0 || attempt.slotFix;
    }

    public static void onSetback() {
        for (Attempt attempt : snapshot()) attempt.setback = true;
    }

    public static void onBlockUpdate(BlockPos pos, BlockState state) {
        boolean air = state.isAir();
        for (Attempt attempt : snapshot()) {
            if (!attempt.position.equals(pos)) continue;
            if (attempt.what.equals("stop") ? air : !attempt.what.equals("crystal") && !air) finish(attempt, true);
            else attempt.blockEcho = state.getBlock().getName().getString();
        }
    }

    public static void onCrystalAdded(BlockPos at) {
        for (Attempt attempt : snapshot())
            if (attempt.what.equals("crystal") && attempt.position.equals(at)) finish(attempt, true);
    }

    static void tick() {
        long now = System.currentTimeMillis();
        for (Attempt attempt : snapshot())
            if (now - attempt.time > ANSWER_WAIT_MS) finish(attempt, false);
    }

    private static void add(Attempt attempt) {
        synchronized (attempts) {
            attempts.add(attempt);
        }
    }

    private static List<Attempt> snapshot() {
        synchronized (attempts) {
            return new ArrayList<>(attempts);
        }
    }

    private static void finish(Attempt attempt, boolean success) {
        synchronized (attempts) {
            Iterator<Attempt> iterator = attempts.iterator();
            boolean found = false;
            while (iterator.hasNext()) {
                if (iterator.next() == attempt) {
                    iterator.remove();
                    found = true;
                }
            }
            if (!found) return;
        }

        if (success && !failedSinceSuccess) return;
        failedSinceSuccess = !success;

        String answer = success ? "OK " + (System.currentTimeMillis() - attempt.time) + "ms"
                : "REFUSED ack=" + attempt.ack + " slotFix=" + attempt.slotFix + " setback=" + attempt.setback + " blockEcho=" + attempt.blockEcho;
        DebugLog.write("GrimDiag", attempt.what + " " + attempt.position.toShortString() + " " + answer + " | " + attempt.context);
    }

    private static final class Attempt {
        final String what;
        final BlockPos position;
        final int sequence;
        final String context;
        final long time;
        volatile boolean ack;
        volatile boolean slotFix;
        volatile boolean setback;
        volatile String blockEcho = "none";

        Attempt(String what, BlockPos position, int sequence, String context, long time) {
            this.what = what;
            this.position = position;
            this.sequence = sequence;
            this.context = context;
            this.time = time;
        }
    }
}
