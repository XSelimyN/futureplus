/*
 * Derived from Homovore PlacementManager by dev.leonetic.
 * This file is licensed under AGPL-3.0.
 */
package com.futureplus.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownExperienceBottle;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Placement helpers used by GrimPiston. Hidden base class.
 */
final class GrimPlacementManager {
    private GrimPlacementManager() {}

    private static final double MAX_PLACE_DISTANCE = 5.154;

    private static Minecraft mc() { return Minecraft.getInstance(); }

    /** The 1x2 space of an empty cell, for placing a crystal. */
    static AABB crystalSpace(BlockPos air) {
        return new AABB(air.getX(), air.getY(), air.getZ(), air.getX() + 1, air.getY() + 2, air.getZ() + 1);
    }

    static boolean canPlace(BlockPos pos) {
        Minecraft mc = mc();
        if (mc.level.isOutsideBuildHeight(pos) || !mc.level.getBlockState(pos).canBeReplaced()) return false;
        if (Vec3.atCenterOf(pos).distanceTo(mc.player.getEyePosition()) > MAX_PLACE_DISTANCE) return false;
        if (mc.player.getBoundingBox().intersects(new AABB(pos))) return false;

        return mc.level.getEntities(mc.player, new AABB(pos), entity -> !(entity instanceof ItemEntity || entity instanceof ExperienceOrb
                || entity instanceof ThrownExperienceBottle || entity instanceof FireworkRocketEntity || entity instanceof EndCrystal)).isEmpty();
    }

    static boolean isInteractable(Block block) {
        return block instanceof EntityBlock || block instanceof DoorBlock || block instanceof TrapDoorBlock
                || block instanceof FenceGateBlock || block instanceof ButtonBlock || block instanceof LeverBlock
                || block instanceof BedBlock || block instanceof NoteBlock || block instanceof AnvilBlock;
    }

    /** The point of the box closest to the eye, 1/16 in from the side edges. */
    static Vec3 lookPoint(Vec3 eye, AABB box) {
        double x = Mth.clamp(eye.x, box.minX, box.maxX);
        double y = Mth.clamp(eye.y, box.minY, box.maxY);
        double z = Mth.clamp(eye.z, box.minZ, box.maxZ);
        double inset = 1.0 / 16.0;
        double epsilon = 1e-9;

        if (Math.abs(x - box.minX) < epsilon) x = Math.min(box.minX + inset, box.maxX - epsilon);
        else if (Math.abs(x - box.maxX) < epsilon) x = Math.max(box.maxX - inset, box.minX + epsilon);
        if (Math.abs(z - box.minZ) < epsilon) z = Math.min(box.minZ + inset, box.maxZ - epsilon);
        else if (Math.abs(z - box.maxZ) < epsilon) z = Math.max(box.maxZ - inset, box.minZ + epsilon);

        return new Vec3(x, y, z);
    }
}
