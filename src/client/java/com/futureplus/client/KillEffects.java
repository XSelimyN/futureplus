package com.futureplus.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.RandomSource;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * KillEffects (ClickGui > Visual > KillEffects).
 *
 * Spawns vanilla lightning where a player we killed died (drawn on the client only, nothing goes to the server).
 * The geometry is a one-to-one copy of vanilla's LightningBoltRenderer; color and duration are ours:
 *  Global  : the client theme's main color (Client > Theme)
 *  Custom  : a color picked from the HSV color wheel
 *  Rainbow : a rainbow gradient flowing along the bolt
 *  Vanilla : the vanilla lightning color
 *
 * "Our kill": the death must come from our hit (an outgoing ServerboundAttackPacket, other clients' auras included; an ID predict
 * hit counts when the crystal spawns) or from a crystal/anchor/bed we blew up
 * within KILL_MS + ping at the latest. Someone we hit who later dies on their own (fall, fire, someone else) doesn't count.
 * A death is caught by the death event (LivingEntity.handleEntityEvent 3) or the player's health hitting 0.
 * Target types: Players / Animals / Monsters (animals and monsters for trying it in singleplayer).
 * Kill sound: sounds under assets/futureplus/sounds/kill (at most 5 s) ("kill.<name>" in sounds.json); previewed when picked in the GUI.
 */
public final class KillEffects {
    private KillEffects() {}

    public static final String[] MODES = {"Global", "Custom", "Rainbow", "Vanilla"};
    public static final String[] SOUNDS = {"None", "MLG", "Double kill", "Boom headshot", "Bonk", "Boing", "Gong",
        "Achievement", "Winning", "Geometry Dash", "Undertale"};
    private static final String[] SOUND_IDS = {null, "mlg", "double_kill", "headshot", "bonk", "boing", "gong",
        "achievement", "winning", "geodash", "undertale"};

    private static boolean enabled = VisualState.load("KillEffects");
    static int mode = 0;
    static int color = 0xD8343A;
    static double length = 1.0;      // saniye (0.1 - 5)
    static double count = 1;         // number of bolts striking at once (1 - 10)
    static boolean throughWalls = true;   // visible through walls (no depth test)
    static int killSound = 3;        // Boom headshot
    static double killVolume = 100;  // %
    static boolean players = true, animals = false, monsters = false;

    private static final long KILL_MS = 1_000;     // how long after a hit the death may come (+ ping)
    private static final double CRYSTAL_R = 12, ANCHOR_R = 10, BED_R = 10;
    private static final Map<Integer, Long> predicted = new HashMap<>();  // hit on an id not on the client -> time
    private static final Map<Integer, Long> credited = new HashMap<>();   // entity id -> time of our last hit
    private static final Map<Integer, Long> fired = new HashMap<>();      // don't spawn again for the same death

    private record Bolt(Vec3 pos, long seed, long start) {}
    private static final List<Bolt> bolts = new ArrayList<>();

    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean v) { enabled = v; VisualState.save("KillEffects", v); if (!v) bolts.clear(); }

    public static List<Setting> getSettings() {
        return List.of(
            Setting.choice("Color mode", MODES, () -> mode, v -> mode = v),
            Setting.color("Custom color", () -> color, v -> color = v).when(() -> mode == 1),
            Setting.num("Kill effect length", 0.1, 5.0, 0.1, () -> length, v -> length = v),
            Setting.num("Bolt count", 1, 10, 1, () -> count, v -> count = v),
            Setting.bool("Through walls", () -> throughWalls, v -> throughWalls = v),
            Setting.choice("Kill sound", SOUNDS, () -> killSound, v -> { boolean ch = v != killSound; killSound = v; if (ch) preview(); }),
            Setting.num("Kill sound volume", 0, 100, 1, () -> killVolume, v -> killVolume = v).when(() -> killSound != 0),
            Setting.bool("Players", () -> players, v -> players = v),
            Setting.bool("Animals", () -> animals, v -> animals = v),
            Setting.bool("Monsters", () -> monsters, v -> monsters = v));
    }

    static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(KillEffects::tick);
    }

    // ------------------------------------------------------------- kill tracking

    /**
     * Every outgoing packet (ConnectionSendMixin, Connection.send: other clients' auras may send the packet straight from there
     * too). It can come from any thread: the time is taken right away, the world is read on the main thread.
     */
    public static void onOutgoing(Packet<?> packet) {
        if (!enabled) return;
        if (!(packet instanceof ServerboundAttackPacket) && !(packet instanceof ServerboundUseItemOnPacket)) return;
        long time = System.currentTimeMillis();
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) handleOutgoing(packet, time);
        else mc.execute(() -> handleOutgoing(packet, time));
    }

    private static void handleOutgoing(Packet<?> packet, long time) {
        if (packet instanceof ServerboundAttackPacket p) onAttack(p.entityId(), time);
        else if (packet instanceof ServerboundUseItemOnPacket p) onUseOn(p.getHand(), p.getHitResult(), time);
    }

    /** Those around a hit living entity or a broken crystal count as having taken our hit. */
    private static void onAttack(int id, long time) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Entity e = mc.level.getEntity(id);
        if (e instanceof EndCrystal) creditArea(e.position(), CRYSTAL_R, time);
        else if (e instanceof LivingEntity le) { if (counts(le)) credited.put(id, time); }
        else if (e == null) predicted.put(id, time);             // ID predict: the crystal hasn't spawned on the client yet
    }

    /** A crystal spawned (ClientPacketListenerMixin): if it is an id hit earlier (predict), it is the broken crystal. */
    public static void onAddEntity(ClientboundAddEntityPacket packet) {
        if (!enabled || predicted.isEmpty()) return;
        Long t = predicted.remove(packet.getId());
        if (t == null || System.currentTimeMillis() - t > windowMs() || packet.getType() != EntityType.END_CRYSTAL) return;
        creditArea(new Vec3(packet.getX(), packet.getY(), packet.getZ()), CRYSTAL_R, t);
    }

    /** Block click: those around, if we are blowing up a charged respawn anchor or (in the Nether/End) a bed. */
    private static void onUseOn(InteractionHand hand, BlockHitResult hit, long time) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        BlockState st = mc.level.getBlockState(hit.getBlockPos());
        if (st.getBlock() instanceof BedBlock) {
            if (mc.level.dimension() != Level.OVERWORLD) creditArea(hit.getBlockPos().getCenter(), BED_R, time);
            return;
        }
        if (!(st.getBlock() instanceof RespawnAnchorBlock)) return;
        int charge = st.getValue(RespawnAnchorBlock.CHARGE);
        boolean charging = mc.player.getItemInHand(hand).is(Items.GLOWSTONE) && charge < RespawnAnchorBlock.MAX_CHARGES;
        if (charge > 0 && !charging) creditArea(hit.getBlockPos().getCenter(), ANCHOR_R, time);
    }

    private static void creditArea(Vec3 c, double r, long time) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        for (LivingEntity le : mc.level.getEntitiesOfClass(LivingEntity.class, new AABB(c, c).inflate(r), KillEffects::counts)) {
            Long old = credited.get(le.getId());
            if (old == null || old < time) credited.put(le.getId(), time);
        }
    }

    /** Whether it is of the selected target types (excluding ourselves). */
    private static boolean counts(LivingEntity e) {
        if (e instanceof LocalPlayer) return false;
        if (e instanceof Player) return players;
        if (e instanceof Animal) return animals;
        if (e instanceof Enemy) return monsters;
        return false;
    }

    /** Death event (entity event 3). */
    public static void onDeath(Entity e) {
        if (enabled && e instanceof LivingEntity le && counts(le) && isOurs(le)) fire(le);
    }

    /** When FakePlayer+ dies (even if it respawns). */
    static void onFakeKilled(Player p) { strike(p.position()); }

    /** Whether our last hit is close enough for the death to have come from it. */
    private static boolean isOurs(LivingEntity e) {
        Long t = credited.get(e.getId());
        return t != null && System.currentTimeMillis() - t <= windowMs();
    }

    private static long windowMs() {
        Minecraft mc = Minecraft.getInstance();
        int ping = 0;
        try {
            PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(mc.player.getUUID());
            if (info != null) ping = Math.min(1000, info.getLatency());
        } catch (Throwable ignored) { }
        return KILL_MS + ping;
    }

    private static void fire(LivingEntity p) {
        long now = System.currentTimeMillis();
        Long last = fired.get(p.getId());
        if (last != null && now - last < 3_000) return;
        fired.put(p.getId(), now);
        credited.remove(p.getId());
        strike(p.position());
    }

    private static void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null) { credited.clear(); fired.clear(); bolts.clear(); return; }
        if (!enabled) return;
        long now = System.currentTimeMillis();
        long win = windowMs();
        credited.values().removeIf(t -> now - t > win);
        predicted.values().removeIf(t -> now - t > win);
        fired.values().removeIf(t -> now - t > 3_000);
        // if no death event comes: a player whose health hits 0
        for (Player p : mc.level.players()) {
            if (counts(p) && p.isDeadOrDying() && isOurs(p)) fire(p);
        }
    }

    private static void strike(Vec3 pos) {
        if (!enabled) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        RandomSource r = RandomSource.create();
        long now = System.currentTimeMillis();
        for (int i = 0; i < (int) count; i++) bolts.add(new Bolt(pos, r.nextLong(), now));   // each a different shape, bases at the same point
        playKillSound();
    }

    /** The kill sound plays like a UI sound, regardless of distance. */
    private static void playKillSound() {
        if (killSound <= 0 || killSound >= SOUND_IDS.length || killVolume <= 0) return;
        SoundEvent ev = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("futureplus", "kill." + SOUND_IDS[killSound]));
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(ev, 1f, (float) (killVolume / 100.0)));
    }

    /** Preview when a sound is picked in the GUI (not while the settings file loads). */
    private static void preview() {
        if (Minecraft.getInstance().screen instanceof ClickGui) playKillSound();
    }

    // ------------------------------------------------------------- rendering (WorldDraw)

    static boolean wantsRender() { return enabled && !bolts.isEmpty(); }

    /** Writes all bolts to b (relative to the camera); returns the number of quads written. */
    static int emit(BufferBuilder b, Vec3 cam) {
        long now = System.currentTimeMillis();
        long life = Math.round(length * 1000);
        int quads = 0;
        for (Iterator<Bolt> it = bolts.iterator(); it.hasNext(); ) {
            Bolt bolt = it.next();
            long age = now - bolt.start;
            if (age >= life) { it.remove(); continue; }
            // fades softly over the last 35%
            float k = age / (float) life;
            float alpha = k < 0.65f ? 1f : 1f - (k - 0.65f) / 0.35f;
            quads += emitBolt(b, bolt, (float) (bolt.pos.x - cam.x), (float) (bolt.pos.y - cam.y), (float) (bolt.pos.z - cam.z),
                alpha, (now % 3_600_000L) / 1000f);   // float precision: epoch seconds break the hue calculation
        }
        return quads;
    }

    /** Vanilla LightningBoltRenderer.submit geometry; the color is picked by height and time. */
    private static int emitBolt(BufferBuilder buf, Bolt bolt, float ox, float oy, float oz, float alpha, float time) {
        float[] xOffs = new float[8], zOffs = new float[8];
        float xOff = 0, zOff = 0;
        RandomSource random = RandomSource.create(bolt.seed);
        for (int h = 7; h >= 0; h--) {
            xOffs[h] = xOff;
            zOffs[h] = zOff;
            xOff += random.nextInt(11) - 5;
            zOff += random.nextInt(11) - 5;
        }
        int quads = 0;
        for (int r = 0; r < 4; r++) {
            RandomSource rnd = RandomSource.create(bolt.seed);
            for (int p = 0; p < 3; p++) {
                int hs = p > 0 ? 7 - p : 7;
                int ht = p > 0 ? hs - 2 : 0;
                float xo0 = xOffs[hs] - xOff, zo0 = zOffs[hs] - zOff;
                for (int h = hs; h >= ht; h--) {
                    float xo1 = xo0, zo1 = zo0;
                    if (p == 0) { xo0 += rnd.nextInt(11) - 5; zo0 += rnd.nextInt(11) - 5; }
                    else { xo0 += rnd.nextInt(31) - 15; zo0 += rnd.nextInt(31) - 15; }
                    float rr1 = 0.1f + r * 0.2f, rr2 = 0.1f + r * 0.2f;
                    if (p == 0) { rr1 *= h * 0.1f + 1f; rr2 *= (h - 1f) * 0.1f + 1f; }
                    int c0 = colorAt(h, time, alpha), c1 = colorAt(h + 1, time, alpha);
                    float[] s = {ox, oy, oz, xo0, zo0, xo1, zo1, rr1, rr2};
                    quad(buf, s, h, c0, c1, false, false, true, false);
                    quad(buf, s, h, c0, c1, true, false, true, true);
                    quad(buf, s, h, c0, c1, true, true, false, true);
                    quad(buf, s, h, c0, c1, false, true, false, false);
                    quads += 4;
                }
            }
        }
        return quads;
    }

    private static void quad(BufferBuilder b, float[] s, int h, int c0, int c1,
                             boolean px1, boolean pz1, boolean px2, boolean pz2) {
        float ox = s[0], oy = s[1], oz = s[2], xo0 = s[3], zo0 = s[4], xo1 = s[5], zo1 = s[6], rr1 = s[7], rr2 = s[8];
        b.addVertex(ox + xo0 + (px1 ? rr2 : -rr2), oy + h * 16, oz + zo0 + (pz1 ? rr2 : -rr2)).setColor(c0);
        b.addVertex(ox + xo1 + (px1 ? rr1 : -rr1), oy + (h + 1) * 16, oz + zo1 + (pz1 ? rr1 : -rr1)).setColor(c1);
        b.addVertex(ox + xo1 + (px2 ? rr1 : -rr1), oy + (h + 1) * 16, oz + zo1 + (pz2 ? rr1 : -rr1)).setColor(c1);
        b.addVertex(ox + xo0 + (px2 ? rr2 : -rr2), oy + h * 16, oz + zo0 + (pz2 ? rr2 : -rr2)).setColor(c0);
    }

    /** ARGB vertex color for height step h (0..8). Vanilla: (0.45, 0.45, 0.5), alpha 0.3, additive blending. */
    private static int colorAt(int h, float time, float alpha) {
        int a = Math.round(0.3f * 255 * alpha);
        if (mode == 3) return (a << 24) | (115 << 16) | (115 << 8) | 128;
        int rgb = switch (mode) {
            case 0 -> ClickGui.themeColor(0f);
            case 1 -> color;
            default -> ColorPicker.fromHsv(h / 8f * 0.85f - time * 0.35f, 1f, 1f);   // the rainbow flows upward
        };
        // stay close to vanilla brightness under additive blending: brightest channel ~0.8, a bit of a white core
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, bl = rgb & 255;
        float max = Math.max(1, Math.max(r, Math.max(g, bl)));
        float sc = 0.8f / max;
        int rr = clamp(r * sc + 0.08f), gg = clamp(g * sc + 0.08f), bb = clamp(bl * sc + 0.08f);
        return (a << 24) | (rr << 16) | (gg << 8) | bb;
    }

    private static int clamp(float v) { return Math.max(0, Math.min(255, Math.round(v * 255))); }
}
