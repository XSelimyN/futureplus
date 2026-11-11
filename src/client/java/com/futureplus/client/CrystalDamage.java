package com.futureplus.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Collection;
import java.util.Collections;

/**
 * Damage and raycast utilities (block raycast context / classifier): AutoCrystal's
 * explosion damage and visibility calculations. Damage isn't floored, the difficulty multiplier applies to every target,
 * low resistance blocks (< 600) don't cut the ray, the given "solid" position counts as bedrock, ignored ones count as air.
 */
final class CrystalDamage {
    private CrystalDamage() {}

    private static Minecraft mc() { return Minecraft.getInstance(); }

    enum Override { NONE, IGNORE, SOLID }

    // ------------------------------------------------------------- hasar

    static float crystal(Vec3 explosion, LivingEntity target) { return crystal(explosion, target, target.getBoundingBox()); }

    static float crystal(Vec3 explosion, LivingEntity target, AABB bounds) {
        return explosion(explosion, target, bounds, 6.0, true, Collections.emptyList(), null);
    }

    static float explosion(Vec3 explosion, LivingEntity target, AABB bounds, double power, boolean ignoreLowResistance,
                           Collection<BlockPos> ignored, BlockPos solid) {
        if (mc().player == target && mc().player.isCreative()) return 0f;
        Vec3 bottom = new Vec3(Mth.lerp(0.5, bounds.minX, bounds.maxX), bounds.minY, Mth.lerp(0.5, bounds.minZ, bounds.maxZ));
        double diameter = power * 2.0;
        double dist = bottom.distanceTo(explosion) / diameter;
        if (dist > 1.0) return 0f;
        float exposure = exposure(explosion, target, bounds, ignored, solid, ignoreLowResistance);
        double impact = (1.0 - dist) * exposure;
        float damage = (float) ((impact * impact + impact) / 2.0 * 7.0 * diameter + 1.0);
        damage = scaleForDifficulty(damage);
        damage = applyDamageReduction(target, damage);
        damage = resolveBlocking(target, explosion, damage);
        return Math.max(damage, 0f);
    }

    static float exposure(Vec3 explosion, Entity target, AABB b, Collection<BlockPos> ignored, BlockPos solid, boolean ignoreLowResistance) {
        double sx = 1.0 / ((b.maxX - b.minX) * 2.0 + 1.0);
        double sy = 1.0 / ((b.maxY - b.minY) * 2.0 + 1.0);
        double sz = 1.0 / ((b.maxZ - b.minZ) * 2.0 + 1.0);
        double ox = (1.0 - Math.floor(1.0 / sx) * sx) / 2.0;
        double oz = (1.0 - Math.floor(1.0 / sz) * sz) / 2.0;
        if (sx < 0 || sy < 0 || sz < 0) return 0f;
        CollisionContext cc = CollisionContext.of(target);
        int visible = 0, total = 0;
        for (double fx = 0; fx <= 1.0; fx += sx) {
            for (double fy = 0; fy <= 1.0; fy += sy) {
                for (double fz = 0; fz <= 1.0; fz += sz) {
                    Vec3 sample = new Vec3(Mth.lerp(fx, b.minX, b.maxX) + ox, Mth.lerp(fy, b.minY, b.maxY), Mth.lerp(fz, b.minZ, b.maxZ) + oz);
                    if (raycast(sample, explosion, cc, ignored, solid, ignoreLowResistance).getType() == HitResult.Type.MISS) visible++;
                    total++;
                }
            }
        }
        return (float) visible / total;
    }

    private static int protectionLevel(Holder<Enchantment> h, ItemStack st) { return EnchantmentHelper.getItemEnchantmentLevel(h, st); }

    static float applyDamageReduction(LivingEntity target, float damage) {
        boolean assumeBest = AutoCrystal.isActive() && AutoCrystal.assumeBestArmor;
        DamageSource src = mc().level.damageSources().explosion((Explosion) null);
        float armor = target.getArmorValue();
        float toughness = (float) target.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        damage = CombatRules.getDamageAfterAbsorb(target, damage, src, armor, toughness);
        if (target.hasEffect(MobEffects.RESISTANCE) || assumeBest) {
            MobEffectInstance res = target.getEffect(MobEffects.RESISTANCE);
            float amp = res != null ? res.getAmplifier() : 1f;
            damage = damage * (25f - (amp + 1f) * 5f) / 25f;
        }
        int prot = 0;
        if (target instanceof Player player) {
            int units = 1;
            try {
                var reg = mc().level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
                Holder<Enchantment> blast = reg.getOrThrow(Enchantments.BLAST_PROTECTION), protection = reg.getOrThrow(Enchantments.PROTECTION);
                for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD}) {
                    ItemStack st = player.getItemBySlot(slot);
                    if (st.isEmpty()) continue;
                    if (st.is(ItemTags.HEAD_ARMOR)) units--;
                    prot += protectionLevel(blast, st) * 2 + protectionLevel(protection, st);
                    units++;
                }
            } catch (Throwable ignored) { }
            if (assumeBest) prot = (int) (units / 4f * 20f);
        }
        if (prot > 20) prot = 20;
        damage *= 1f - prot / 25f;
        return damage < 0 ? 0 : damage;
    }

    private static float resolveBlocking(LivingEntity target, Vec3 explosion, float damage) {
        if (!target.isUsingItem()) return damage;
        BlocksAttacks blocks = target.getUseItem().get(DataComponents.BLOCKS_ATTACKS);
        if (blocks == null) return damage;
        DamageSource src = mc().level.damageSources().explosion((Explosion) null);
        Vec3 view = target.calculateViewVector(0f, target.getYHeadRot());
        Vec3 d = explosion.subtract(target.position());
        Vec3 dir = new Vec3(d.x, 0, d.z).normalize();
        return blocks.resolveBlockedDamage(src, damage, Math.acos(dir.dot(view)));
    }

    static float scaleForDifficulty(float damage) {
        return switch (mc().level.getDifficulty()) {
            case PEACEFUL -> 0f;
            case EASY -> Math.min(damage / 2f + 1f, damage);
            case HARD -> damage * 1.5f;
            default -> damage;
        };
    }

    static float health(LivingEntity e) { return e.getHealth() + e.getAbsorptionAmount(); }

    // ------------------------------------------------------------- ray (RaycastUtils.raycast)

    /** Block ray against collision shapes; the ignored position is air, the "solid" position bedrock, low resistance ones (optionally) air. */
    static BlockHitResult raycast(Vec3 start, Vec3 end, CollisionContext cc, Collection<BlockPos> ignored, BlockPos solid, boolean ignoreLowResistance) {
        BlockGetter level = mc().level;
        return BlockGetter.traverseBlocks(start, end, cc, (ctx, pos) -> {
            BlockState state = level.getBlockState(pos);
            Override o = classify(pos, state, ignored, solid, ignoreLowResistance);
            if (o == Override.IGNORE) state = Blocks.AIR.defaultBlockState();
            else if (o == Override.SOLID) state = Blocks.BEDROCK.defaultBlockState();
            VoxelShape shape = ClipContext.Block.COLLIDER.get(state, level, pos, ctx);
            return level.clipWithInteractionOverride(start, end, pos, shape, state);
        }, ctx -> {
            Vec3 diff = start.subtract(end);
            return BlockHitResult.miss(start, Direction.getApproximateNearest(diff.x, diff.y, diff.z), BlockPos.containing(end));
        });
    }

    /** BlockRaycastClassifier.firstNonNone(ignorePositions, solidPosition, IGNORE_LOW_RESISTANCE / NO_OVERRIDE). */
    private static Override classify(BlockPos pos, BlockState state, Collection<BlockPos> ignored, BlockPos solid, boolean ignoreLowResistance) {
        if (!ignored.isEmpty() && ignored.contains(pos)) return Override.IGNORE;
        if (solid != null && pos.equals(solid)) return Override.SOLID;
        if (ignoreLowResistance && state.getBlock().getExplosionResistance() < 600f) return Override.IGNORE;
        return Override.NONE;
    }

    // ------------------------------------------------------------- visibility (WorldUtils.canSee)

    static boolean canSee(Vec3 origin, Vec3 target, BlockPos targetBlock, Direction face) {
        if (target.distanceTo(origin) > 128.0) return false;
        BlockHitResult hit = raycast(origin, target, CollisionContext.of(mc().player), Collections.emptyList(), null, false);
        if (hit.getType() == HitResult.Type.MISS) return true;
        if (hit.getLocation().equals(target)) return true;
        if (!hit.getBlockPos().equals(targetBlock)) return false;
        return hit.getDirection() == face || face == null;
    }

    static boolean canSee(Vec3 origin, Vec3 target) { return canSee(origin, target, BlockPos.containing(target), null); }

    static boolean canSeeAny(Vec3 origin, Iterable<Vec3> points) {
        for (Vec3 p : points) if (canSee(origin, p)) return true;
        return false;
    }
}
