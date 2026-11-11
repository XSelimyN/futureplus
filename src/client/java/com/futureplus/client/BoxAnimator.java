package com.futureplus.client;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Open / close animation for world boxes. Each frame the owner calls show() for every box it
 * wants to show and then render(): a new box grows in with a small overshoot, and a box no longer
 * shown shrinks and fades away. If a box goes and comes back (a block broken and placed again) the old one
 * fades while the new one opens from scratch; every placement plays its own animation.
 */
final class BoxAnimator {
    private static final long MAX_STEP_MS = 100;

    private static final class Entry {
        AABB box;
        int fill, line;
        boolean shown, wasPresent;
        float value;
        long last = System.nanoTime();

        Entry(float initial) { value = initial; }

        float update(boolean target, float durationMs) {
            long now = System.nanoTime();
            float dt = Math.min((now - last) / 1_000_000f, MAX_STEP_MS);
            last = now;
            float goal = target ? 1f : 0f, step = dt / durationMs;
            value = goal > value ? Math.min(goal, value + step) : Math.max(goal, value - step);
            return value;
        }
    }

    private final float inMs, outMs;
    private final Map<Object, Entry> entries = new LinkedHashMap<>();
    private final List<Entry> ghosts = new ArrayList<>();

    BoxAnimator() { this(320f, 260f); }

    BoxAnimator(float inMs, float outMs) { this.inMs = inMs; this.outMs = outMs; }

    /**
     * Tracks the box under key; present=false makes the box close while its owner still knows it (the block broke),
     * and a new box opens once it turns true again. Colors are ARGB.
     */
    void show(Object key, AABB box, int fill, int line, boolean present) {
        Entry e = entries.get(key);
        if (e == null) {
            if (!present) return;
            e = new Entry(0f);
            entries.put(key, e);
        } else if (present && !e.wasPresent && e.value > 0f) {
            // came back while the old one is still fading: let it finish on its own, start the new one from zero
            Entry ghost = new Entry(e.value);
            ghost.box = e.box; ghost.fill = e.fill; ghost.line = e.line;
            ghosts.add(ghost);
            e = new Entry(0f);
            entries.put(key, e);
        }
        if (present) { e.box = box; e.fill = fill; e.line = line; }
        e.shown = present;
        e.wasPresent = present;
    }

    /** Draws every box (camera relative), forgets boxes that have finished fading. */
    void render(Vec3 cam) {
        Iterator<Entry> it = entries.values().iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            if (draw(e, cam)) it.remove();
            e.shown = false;                                   // one not shown again before the next render fades out
        }
        ghosts.removeIf(e -> draw(e, cam));
    }

    void clear() { entries.clear(); ghosts.clear(); }

    boolean isEmpty() { return entries.isEmpty() && ghosts.isEmpty(); }

    /** True once it has fully faded. */
    private boolean draw(Entry e, Vec3 cam) {
        if (!e.shown) e.wasPresent = false;
        float progress = e.update(e.shown, e.shown ? inMs : outMs);
        if (!e.shown && progress <= 0f) return true;
        if (e.box == null) return false;
        float scale = e.shown ? easeOutBack(progress) : easeInOut(progress);
        float alpha = Math.min(1f, progress);
        AABB b = scaled(e.box, scale).move(-cam.x, -cam.y, -cam.z);
        WorldDraw.boxFill(true, b, alpha(e.fill, alpha));
        WorldDraw.boxOutline(true, b, 2f, alpha(e.line, alpha));
        return false;
    }

    private static int alpha(int argb, float k) {
        int a = (int) (((argb >>> 24) & 0xFF) * k);
        return (a << 24) | (argb & 0xFFFFFF);
    }

    static AABB scaled(AABB box, float scale) {
        Vec3 c = box.getCenter();
        double hx = (box.maxX - box.minX) / 2.0 * scale, hy = (box.maxY - box.minY) / 2.0 * scale, hz = (box.maxZ - box.minZ) / 2.0 * scale;
        return new AABB(c.x - hx, c.y - hy, c.z - hz, c.x + hx, c.y + hy, c.z + hz);
    }

    static float easeInOut(float t) {
        return t < 0.5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3) / 2f;
    }

    static float easeOutBack(float t) {
        float c1 = 1.70158f, c3 = c1 + 1f;
        return 1f + c3 * (float) Math.pow(t - 1f, 3) + c1 * (float) Math.pow(t - 1f, 2);
    }
}
