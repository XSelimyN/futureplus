package com.futureplus.client;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * FakePlayer+: a fake player that only exists on YOUR client (the server doesn't know it). For trying out features like MaceAura and the visual settings
 * on your own. Copies your armor (with enchantments) and moves if you want.
 *
 * Hits on it are NOT sent to the server (the client attack hook cancels them); damage is computed here with vanilla
 * formulas (mace smash included), health drops, totems pop, it respawns on death.
 * On a mace smash your fall is reset and your vertical speed stops, as the server does.
 *
 * Use it in singleplayer or with gradlew runClient; it works on servers too, but only you see it.
 */
public final class FakePlayerPlus {
    private FakePlayerPlus() {}

    public static final String[] MOVES = {"Idle", "Circle", "Back and forth", "Jump", "Flee", "Fly"};

    private static boolean enabled = false;
    static boolean copyArmor = true, totem = true, respawn = true;
    static int move = 0;
    static double speed = 0.25, radius = 4, health = 20;

    private static RemotePlayer fake;
    private static Vec3 base;
    private static double phase;
    private static ClientLevel spawnedIn;
    private static final int FAKE_ID = -133742;

    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean v) {
        if (v == enabled) return;
        enabled = v;
        if (v) spawn(); else despawn();
    }

    public static List<Setting> getSettings() {
        List<Setting> s = new ArrayList<>();
        s.add(Setting.bool("Copy my armor", () -> copyArmor, v -> { copyArmor = v; respawnNow(); }));
        s.add(Setting.bool("Totem", () -> totem, v -> { totem = v; respawnNow(); }));
        s.add(Setting.num("Health", 1, 20, 1, () -> health, v -> health = v));
        s.add(Setting.choice("Movement", MOVES, () -> move, v -> move = v));
        s.add(Setting.num("Speed", 0.05, 2.0, 0.05, () -> speed, v -> speed = v).when(() -> move != 0));
        s.add(Setting.num("Radius", 1, 16, 0.5, () -> radius, v -> radius = v).when(() -> move != 0));
        s.add(Setting.bool("Respawn on death", () -> respawn, v -> respawn = v));
        return s;
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(FakePlayerPlus::tick);
    }

    public static boolean isFake(Entity e) { return e != null && e == fake; }

    // ------------------------------------------------------------- create / remove

    private static void respawnNow() { if (enabled) { despawn(); spawn(); } }

    private static void spawn() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer me = mc.player;
        if (me == null || mc.level == null) return;
        despawn();
        RemotePlayer f = new RemotePlayer(mc.level, new GameProfile(UUID.nameUUIDFromBytes("futureplus-fake".getBytes()), "FakePlayer+"));
        f.setId(FAKE_ID);
        f.snapTo(me.getX(), me.getY(), me.getZ(), me.getYRot(), me.getXRot());
        f.setOldPosAndRot();
        f.setYHeadRot(me.getYHeadRot());
        f.setYBodyRot(me.getYRot());
        if (copyArmor) {
            for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                ItemStack a = me.getItemBySlot(s);
                if (MaceAura.isElytra(a)) {                     // wear the best chestplate instead of the elytra
                    ItemStack best = ItemStack.EMPTY;
                    for (int i = 0; i < 36; i++) if (MaceAura.isChestplate(me.getInventory().getItem(i))) { best = me.getInventory().getItem(i); break; }
                    a = best;
                }
                f.setItemSlot(s, a.copy());
            }
            f.setItemSlot(EquipmentSlot.MAINHAND, me.getMainHandItem().copy());
        }
        if (totem) f.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
        f.setHealth((float) health);
        mc.level.addEntity(f);
        fake = f;
        spawnedIn = mc.level;
        base = f.position();
        phase = 0;
    }

    private static void despawn() {
        Minecraft mc = Minecraft.getInstance();
        if (fake != null && mc.level != null && spawnedIn == mc.level) {
            mc.level.removeEntity(fake.getId(), Entity.RemovalReason.DISCARDED);
        }
        fake = null;
        spawnedIn = null;
    }

    // ------------------------------------------------------------- tick

    private static void tick(Minecraft mc) {
        if (!enabled) return;
        if (mc.level == null || mc.player == null) { fake = null; spawnedIn = null; return; }
        if (fake == null || spawnedIn != mc.level || fake.isRemoved()) { spawn(); if (fake == null) return; }

        phase += speed;
        double r = radius;
        Vec3 p = switch (move) {
            case 1 -> base.add(Math.cos(phase / r) * r, 0, Math.sin(phase / r) * r);                 // daire
            case 2 -> base.add(Math.sin(phase / r) * r, 0, 0);                                       // ileri-geri
            case 3 -> base.add(0, Math.abs(Math.sin(phase * 0.6)) * 1.25, 0);                         // jump
            case 4 -> {                                                                                // run from you
                Vec3 away = fake.position().subtract(mc.player.position()).multiply(1, 0, 1);
                double len = away.length();
                Vec3 step = len < 1e-3 ? Vec3.ZERO : away.scale(speed / len);
                Vec3 n = fake.position().add(step);
                if (n.distanceTo(base) > r * 3) n = fake.position();                                  // don't wander too far
                yield new Vec3(n.x, base.y, n.z);
            }
            case 5 -> base.add(Math.cos(phase / (r * 2.5)) * r * 2.5,                                // fly: like an elytra
                         10 + Math.sin(phase * 0.045) * 4, Math.sin(phase / (r * 2.5)) * r * 2.5);
            default -> base;
        };
        Vec3 d = p.subtract(fake.position());
        fake.setPos(p.x, p.y, p.z);
        if (d.horizontalDistanceSqr() > 1e-6) {
            float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
            fake.setYRot(yaw); fake.setYBodyRot(yaw); fake.setYHeadRot(yaw);
        }
    }

    // ------------------------------------------------------------- damage (MultiPlayerGameModeMixin)

    /** When gameMode.attack(fake) is called: no packet goes out, we apply the damage here. */
    public static void onAttacked(LocalPlayer me) {
        Minecraft mc = Minecraft.getInstance();
        RemotePlayer f = fake;
        if (f == null || mc.level == null) return;
        if (f.invulnerableTime > 10) return;                       // vanilla: 10 ticks of invulnerability between hits

        ItemStack hand = me.getMainHandItem();
        float scale = me.getAttackStrengthScale(0.5f);
        boolean smash = hand.is(Items.MACE) && MaceItem.canSmashAttack(me);
        boolean crit = scale > 0.9f && me.fallDistance > 0 && !me.onGround() && !me.isInWater() && !me.isPassenger();

        double baseDmg;
        if (hand.is(Items.MACE)) baseDmg = 6.0;                      // the selected slot is the mace during a silent swap, the attribute is still the old item's
        else baseDmg = me.getAttributeValue(Attributes.ATTACK_DAMAGE);
        double dmg = baseDmg * (0.2 + scale * scale * 0.8);
        if (crit) dmg *= 1.5;
        double fd = me.fallDistance;
        if (smash) {
            dmg += MaceAura.maceBonus(fd) + 0.5 * MaceAura.enchLevel(hand, Enchantments.DENSITY) * fd;
        }
        int breach = hand.is(Items.MACE) ? MaceAura.enchLevel(hand, Enchantments.BREACH) : 0;
        double after = MaceAura.reduce(f, dmg, breach);

        // apply (absorption first)
        float abs = f.getAbsorptionAmount();
        float left = (float) after;
        float useAbs = Math.min(abs, left);
        f.setAbsorptionAmount(abs - useAbs);
        left -= useAbs;
        float hp = f.getHealth() - left;
        f.invulnerableTime = 20;
        f.animateHurt(me.getYRot());
        f.hurtTime = f.hurtDuration = 10;
        mc.level.playLocalSound(f.getX(), f.getY(), f.getZ(), crit ? SoundEvents.PLAYER_ATTACK_CRIT : SoundEvents.PLAYER_HURT,
            SoundSource.PLAYERS, 1f, 1f, false);
        if (crit) mc.particleEngine.createTrackingEmitter(f, ParticleTypes.CRIT);

        if (hp <= 0) {
            if (totem && f.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) {
                
                hp = 1f;
                f.setAbsorptionAmount(8f);                           // totem: 1 can + 4 kalp emilim (yenilenir, sonsuz totem)
                mc.particleEngine.createTrackingEmitter(f, ParticleTypes.TOTEM_OF_UNDYING, 30);
                mc.level.playLocalSound(f.getX(), f.getY(), f.getZ(), SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1f, 1f, false);
            } else {
                KillEffects.onFakeKilled(f);
                hp = respawn ? (float) health : 0f;
                if (!respawn) { f.setHealth(0f); despawn(); enabled = false; }
            }
        }
        if (fake != null) f.setHealth(Math.max(0f, hp));

        if (smash) {                                                 // what the server does after a smash: fall to zero, vertical speed stops
            me.resetFallDistance();
            Vec3 v = me.getDeltaMovement();
            me.setDeltaMovement(v.x, 0.01, v.z);
            mc.level.playLocalSound(f.getX(), f.getY(), f.getZ(), SoundEvents.MACE_SMASH_GROUND_HEAVY, SoundSource.PLAYERS, 1f, 1f, false);
        }
        me.resetAttackStrengthTicker();

                                 // Hit info on the HUD
    }
}
