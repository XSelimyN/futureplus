package com.futureplus.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Movement prediction (prediction manager + player motion predictor + motion history): players' boxes over the next 20 ticks
 * (weighted average velocity, acceleration, friction, gravity, collision). AutoCrystal uses it for "Extrapolation".
 */
final class MotionPredictor {
    private MotionPredictor() {}

    private static final int PREDICTION_TICKS = 20;
    private static final float BASE = 0.2873f;
    private static final float SNEAK_MIN = BASE * 0.3f;
    private static final float MAX_H = BASE * 1.4f + 0.2f;

    private record Sample(double mx, double my, double mz, double ax, double az) {}

    private static final class Track {
        final Player player;
        final Deque<Sample> history = new ArrayDeque<>(10);
        final List<AABB> boxes = new ArrayList<>();
        double lastX = Double.NaN, lastY, lastZ;
        Track(Player p) { player = p; }

        double weighted(ToDoubleFunction<Sample> f) {
            if (history.isEmpty()) return 0;
            double tot = 0, w = 0; int k = 1;
            for (Sample s : history) { tot += f.applyAsDouble(s) * k; w += k++; }
            return tot / w;
        }
        double average(ToDoubleFunction<Sample> f) { return history.stream().mapToDouble(f).average().orElse(0); }

        void update() {
            Player p = player;
            if (p.getX() == lastX && p.getY() == lastY && p.getZ() == lastZ) return;
            lastX = p.getX(); lastY = p.getY(); lastZ = p.getZ();
            boxes.clear();
            double mx = p.getX() - p.xo, my = p.getY() - p.yo, mz = p.getZ() - p.zo;
            Sample prev = history.peekLast();
            double ax = prev == null ? 0 : mx - prev.mx, az = prev == null ? 0 : mz - prev.mz;
            if (history.size() >= 10) history.pollFirst();
            history.addLast(new Sample(mx, my, mz, ax, az));
            mx = weighted(Sample::mx); mz = weighted(Sample::mz);
            double limit = p.isFallFlying() ? 1.8f : MAX_H;
            double min = p.isShiftKeyDown() ? SNEAK_MIN : BASE;
            double hs = Math.hypot(mx, mz);
            if (hs > 1e-4) { double sp = Mth.clamp(hs, min, limit); mx = mx / hs * sp; mz = mz / hs * sp; }
            else { mx = 0; mz = 0; }
            if (collisionBelow(p) && my > 0) my = -0.0784f;
            AABB b = AutoCrystal.interpolationBounds(p);
            if (hs < 1e-4 && Math.abs(my) < 0.01) { boxes.addAll(Collections.nCopies(PREDICTION_TICKS, b)); return; }
            double aax = average(Sample::ax), aaz = average(Sample::az);
            for (int t = 0; t < PREDICTION_TICKS; t++) {
                boolean ground = blockCollision(p, b.move(0, -0.01, 0));
                double drag = ground ? 0.54 : 0.98;
                double aw = Math.max(0, 1.0 - t * 0.15);
                mx = (mx + aax * aw) * drag;
                mz = (mz + aaz * aw) * drag;
                double ps = Math.hypot(mx, mz);
                if (ps > limit) { mx = mx / ps * limit; mz = mz / ps * limit; }
                my = ground ? -0.0784f : (p.isFallFlying() ? my : (my - 0.08) * 0.98);
                b = move(p, b, mx, my, mz);
                boxes.add(b);
            }
        }
    }

    private static final Map<Integer, Track> tracks = new HashMap<>();
    private static long lastUpdate;

    private static Minecraft mc() { return Minecraft.getInstance(); }

    /** Every 25 ms. */
    static void update() {
        Minecraft mc = mc();
        if (mc.level == null || mc.player == null) { tracks.clear(); return; }
        long now = System.currentTimeMillis();
        if (now - lastUpdate < 25) return;
        lastUpdate = now;
        for (AbstractClientPlayer p : mc.level.players()) {
            Track t = tracks.get(p.getId());
            // on respawn the new player object gets the same id: tracking of the old (dead) object is dropped
            if (t == null || t.player != p) { t = new Track(p); tracks.put(p.getId(), t); }
            t.update();
        }
        tracks.entrySet().removeIf(en -> mc.level.getEntity(en.getKey()) != en.getValue().player);
    }

    static AABB predicted(Player p, int tick) {
        Track t = tracks.get(p.getId());
        if (t == null || t.player != p || t.boxes.isEmpty()) return p.getBoundingBox();
        return t.boxes.get(Math.min(tick, t.boxes.size() - 1));
    }

    static void clear() { tracks.clear(); }

    private static boolean collisionBelow(Player p) {
        return !mc().level.noBlockCollision(p, p.getBoundingBox().expandTowards(0, -0.1, 0));
    }

    private static boolean blockCollision(Player p, AABB b) {
        return mc().level.getBlockCollisions(p, b).iterator().hasNext();
    }

    private static AABB move(Player p, AABB b, double x, double y, double z) {
        AABB sweep = b.expandTowards(x, y, z);
        List<VoxelShape> col = new ArrayList<>(mc().level.getEntityCollisions(p, sweep));
        for (VoxelShape s : mc().level.getBlockCollisions(p, sweep)) col.add(s);
        return resolve(b, col, x, y, z);
    }

    /** Collision axis by axis, the result applied to the starting box as a total. */
    private static AABB resolve(AABB b, List<VoxelShape> col, double x, double y, double z) {
        if (col.isEmpty()) return b.move(x, y, z);
        AABB orig = b;
        if (y != 0) { y = Shapes.collide(Direction.Axis.Y, b, col, y); if (y != 0) b = b.move(0, y, 0); }
        boolean zFirst = Math.abs(x) < Math.abs(z);
        if (zFirst && z != 0) { z = Shapes.collide(Direction.Axis.Z, b, col, z); if (z != 0) b = b.move(0, 0, z); }
        if (x != 0) { x = Shapes.collide(Direction.Axis.X, b, col, x); if (!zFirst && x != 0) b = b.move(x, 0, 0); }
        if (!zFirst && z != 0) z = Shapes.collide(Direction.Axis.Z, b, col, z);
        return orig.move(x, y, z);
    }
}
