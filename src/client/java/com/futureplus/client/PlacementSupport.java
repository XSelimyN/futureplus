package com.futureplus.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownExperienceBottle;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/** Shared block placement and visibility operations used by the retained anchor module. */
final class PlacementSupport {
    private PlacementSupport() {}
    private static Minecraft mc() { return Minecraft.getInstance(); }
    private static final Direction[] ORDER = {Direction.DOWN, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.UP};

    static boolean click(BlockPos pos, Direction dir, boolean air, InteractionHand hand) {
        Minecraft mc = mc();
        LocalPlayer p = mc.player;
        Block block = p.getItemInHand(hand).getItem() instanceof BlockItem bi ? bi.getBlock() : null;
        if (!canPlace(pos, block, false, false)) return false;
        boolean inAir = air && dir == null;
        if (dir == null) {
            if (!air) return false;
            List<Direction> f = strictFaces(pos);
            dir = f.isEmpty() ? Direction.DOWN : f.get(0);
        }
        BlockPos on = inAir ? pos : pos.relative(dir);
        boolean sneak = interactable(on);
        if (sneak) sneak(p, true);
        Vec3 v = Vec3.atCenterOf(pos).add(dir.getUnitVec3().scale(0.5));
        BlockHitResult hit = new BlockHitResult(v, inAir ? dir : dir.getOpposite(), on, false);
        CombatSupport.use(hand, hit, CombatSupport.Type.PLACE, null, true);
        p.swing(hand);
        if (sneak) sneak(p, false);
        return true;
    }

    static void sneak(LocalPlayer p, boolean on) {
        net.minecraft.world.entity.player.Input i = p.input.keyPresses;
        mc().getConnection().send(new net.minecraft.network.protocol.game.ServerboundPlayerInputPacket(new net.minecraft.world.entity.player.Input(
            i.forward(), i.backward(), i.left(), i.right(), i.jump(), on || i.shift(), i.sprint())));
    }

    static Direction placeSide(BlockPos pos, boolean strict, boolean trace) {
        Minecraft mc = mc();
        for (Direction d : ORDER) {
            BlockPos o = pos.relative(d);
            if (o.getY() >= mc.level.getMaxY()) continue;
            if (trace && !anyVisible(facePoints(o, d.getOpposite()))) continue;
            if (!CombatSupport.solid(o)) continue;
            if (strict && !strictFaces(o).contains(d.getOpposite())) continue;
            return d;
        }
        return null;
    }

    static List<Direction> strictFaces(BlockPos pos) {
        Minecraft mc = mc();
        List<Direction> l = new ArrayList<>(List.of(Direction.values()));
        for (Direction d : Direction.values()) {
            BlockPos o = pos.relative(d);
            BlockState s = mc.level.getBlockState(o);
            if (!s.canBeReplaced() && s.isCollisionShapeFullBlock(mc.level, o)) l.remove(d);
        }
        if (pos.getY() + 1 >= mc.level.getMaxY()) l.remove(Direction.UP);
        Vec3 eye = mc.player.getEyePosition(), c = Vec3.atCenterOf(pos);
        double dx = eye.x - c.x, dz = eye.z - c.z;
        if (dx > 0.5) l.remove(Direction.WEST); else if (dx < -0.5) l.remove(Direction.EAST); else { l.remove(Direction.WEST); l.remove(Direction.EAST); }
        if (dz > 0.5) l.remove(Direction.NORTH); else if (dz < -0.5) l.remove(Direction.SOUTH); else { l.remove(Direction.NORTH); l.remove(Direction.SOUTH); }
        if (eye.y < pos.getY() + (1)) l.remove(Direction.UP);
        if (eye.y > pos.getY()) l.remove(Direction.DOWN);
        return l;
    }

    static List<Vec3> facePoints(BlockPos pos, Direction face) {
        List<Vec3> out = new ArrayList<>(4);
        for (double x : new double[]{0.05, 0.95}) for (double y : new double[]{0.05, 0.95}) for (double z : new double[]{0.05, 0.95}) {
            if (face.getStepX() == 1 && x < 0.5 || face.getStepX() == -1 && x > 0.5) continue;
            if (face.getStepY() == 1 && y < 0.5 || face.getStepY() == -1 && y > 0.5) continue;
            if (face.getStepZ() == 1 && z < 0.5 || face.getStepZ() == -1 && z > 0.5) continue;
            out.add(new Vec3(pos.getX() + x, pos.getY() + y, pos.getZ() + z));
        }
        return out;
    }


    static boolean anyVisible(List<Vec3> pts) {
        for (Vec3 v : pts) if (visible(v)) return true;
        return false;
    }


    static boolean visible(Vec3 v) {
        Minecraft mc = mc();
        Vec3 eye = mc.player.getEyePosition();
        if (v.distanceTo(eye) > 30) return false;
        BlockPos at = BlockPos.containing(v);
        BlockHitResult r = mc.level.clip(new ClipContext(eye, v, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        return r.getType() == HitResult.Type.MISS || r.getLocation().equals(v) || r.getBlockPos().equals(at);
    }

    static boolean canPlace(BlockPos pos, Block block, boolean strictEntities, boolean allowCrystal) {
        Minecraft mc = mc();
        if (!mc.level.getBlockState(pos).canBeReplaced() || !mc.level.getWorldBorder().isWithinBounds(pos)) return false;
        if (pos.getY() >= mc.level.getMaxY() || pos.getY() < mc.level.getMinY()) return false;
        VoxelShape shape = block == null ? null : block.defaultBlockState().getCollisionShape(mc.level, BlockPos.ZERO);
        AABB box = shape == null || shape.isEmpty() ? new AABB(pos) : shape.bounds().move(pos);
        if (block != null && shape.isEmpty()) return true;
        for (Entity e : mc.level.getEntities((Entity) null, box, e -> !(e instanceof ThrownExperienceBottle || e instanceof ItemEntity
            || e instanceof ItemFrame || e instanceof ExperienceOrb))) {
            if (!e.getBoundingBox().intersects(box) && e != mc.player) continue;
            if (e instanceof AbstractArrow) continue;
            if (e instanceof Player) { if (e.getBoundingBox().intersects(box)) return false; }
            else if (!(e instanceof EndCrystal) || !allowCrystal) return !strictEntities;
        }
        return true;
    }

    static boolean interactable(BlockPos pos) {
        BlockState s = mc().level.getBlockState(pos);
        Block b = s.getBlock();
        return b == Blocks.RESPAWN_ANCHOR || s.hasBlockEntity() || b instanceof net.minecraft.world.level.block.BedBlock
            || b instanceof net.minecraft.world.level.block.DoorBlock || b instanceof net.minecraft.world.level.block.TrapDoorBlock
            || b instanceof net.minecraft.world.level.block.ButtonBlock || b instanceof net.minecraft.world.level.block.ComposterBlock
            || b instanceof net.minecraft.world.level.block.NoteBlock || b instanceof AnvilBlock
            || b instanceof net.minecraft.world.level.block.CraftingTableBlock || b instanceof net.minecraft.world.level.block.CakeBlock;
    }
}
