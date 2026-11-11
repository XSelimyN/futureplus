package com.futureplus.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * GrimPiston's crystal damage calculation. Hidden base class.
 */
final class GrimCrystalUtil {
    private GrimCrystalUtil() {}

    private static Minecraft mc() { return Minecraft.getInstance(); }

    static float calculateDamage(LivingEntity entity, Vec3 crystal, boolean ignoreTerrain, BlockPos ignore) {
        double ab = getExposure(crystal, entity.getBoundingBox(), ignoreTerrain, ignore);
        double w = Math.sqrt(entity.distanceToSqr(crystal)) / 12.0;
        double ac = (1.0 - w) * ab;
        float dmg = ((int) ((ac * ac + ac) / 2.0 * 7.0 * 12.0 + 1.0));
        dmg = calculateReductions(dmg, entity, mc().level.damageSources().explosion(null, null));
        return (float) Math.max(0.0, dmg);
    }

    static float calculateReductions(float damage, LivingEntity entity, DamageSource damageSource) {
        if (damageSource.scalesWithDifficulty()) {
            switch (mc().level.getDifficulty()) {
                case EASY -> damage = Math.min(damage / 2 + 1, damage);
                case HARD -> damage *= 1.5f;
                default -> { }
            }
        }

        // armor
        damage = CombatRules.getDamageAfterAbsorb(entity, damage, damageSource, entity.getArmorValue(), (float) entity.getAttributeValue(Attributes.ARMOR_TOUGHNESS));

        // resistance
        damage = resistanceReduction(entity, damage);

        float protAmount = getProtectionAmount(List.of(entity.getItemBySlot(EquipmentSlot.FEET), entity.getItemBySlot(EquipmentSlot.LEGS),
            entity.getItemBySlot(EquipmentSlot.CHEST), entity.getItemBySlot(EquipmentSlot.HEAD)));

        if (protAmount > 0) damage = CombatRules.getDamageAfterMagicAbsorb(damage, protAmount);

        return Math.max(damage, 0);
    }

    static int getProtectionAmount(Iterable<ItemStack> equipment) {
        int n = 0;
        for (ItemStack i : equipment) n += getProtectionAmount(i);
        return n;
    }

    static int getProtectionAmount(ItemStack stack) {
        var reg = mc().level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        int modifierBlast = EnchantmentHelper.getItemEnchantmentLevel(reg.getOrThrow(Enchantments.BLAST_PROTECTION), stack);
        int modifier = EnchantmentHelper.getItemEnchantmentLevel(reg.getOrThrow(Enchantments.PROTECTION), stack);
        return modifierBlast * 2 + modifier;
    }

    private static float resistanceReduction(LivingEntity player, float damage) {
        MobEffectInstance resistance = player.getEffect(MobEffects.RESISTANCE);
        if (resistance != null) {
            int lvl = resistance.getAmplifier() + 1;
            damage *= (1 - (lvl * 0.2f));
        }
        return Math.max(damage, 0);
    }

    static float getExposure(Vec3 source, Entity entity, boolean ignoreTerrain, BlockPos ignorePos) {
        return getExposure(source, entity.getBoundingBox(), ignoreTerrain, ignorePos);
    }

    static float getExposure(Vec3 source, AABB box, boolean ignoreTerrain, BlockPos ignorePos) {
        double xDiff = box.maxX - box.minX;
        double yDiff = box.maxY - box.minY;
        double zDiff = box.maxZ - box.minZ;

        double xStep = 1 / (xDiff * 2 + 1);
        double yStep = 1 / (yDiff * 2 + 1);
        double zStep = 1 / (zDiff * 2 + 1);

        if (xStep > 0 && yStep > 0 && zStep > 0) {
            int misses = 0;
            int hits = 0;

            double xOffset = (1 - Math.floor(1 / xStep) * xStep) * 0.5;
            double zOffset = (1 - Math.floor(1 / zStep) * zStep) * 0.5;

            xStep = xStep * xDiff;
            yStep = yStep * yDiff;
            zStep = zStep * zDiff;

            double startX = box.minX + xOffset;
            double startY = box.minY;
            double startZ = box.minZ + zOffset;
            double endX = box.maxX + xOffset;
            double endY = box.maxY;
            double endZ = box.maxZ + zOffset;

            for (double x = startX; x <= endX; x += xStep) {
                for (double y = startY; y <= endY; y += yStep) {
                    for (double z = startZ; z <= endZ; z += zStep) {
                        Vec3 position = new Vec3(x, y, z);
                        if (raycast(position, source, ignoreTerrain, ignorePos) == null) misses++;
                        hits++;
                    }
                }
            }

            return (float) misses / hits;
        }

        return 0f;
    }

    private static BlockHitResult raycast(Vec3 start, Vec3 end, boolean ignoreTerrain, BlockPos ignorePos) {
        return BlockGetter.traverseBlocks(start, end, null, (ctx, blockPos) -> {
            BlockState blockState = mc().level.getBlockState(blockPos);
            if (ignorePos != null && blockPos.equals(ignorePos)) return null;
            if (ignoreTerrain && blockState.getBlock().getExplosionResistance() < 600) return null;
            return blockState.getCollisionShape(mc().level, blockPos).clip(start, end, blockPos);
        }, ctx -> null);
    }
}
