package com.futureplus.client;

import com.futureplus.client.mixin.LocalPlayerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Silent looks for the Grim modes. Hidden base class.
 *
 * The look the server knows is LocalPlayer's yRotLast/xRotLast fields (counterpart of RotationManager.serverYaw/Pitch):
 * a silent look pulls them to itself, so the next movement packet sends the camera's look back by itself
 * (counterpart of requestLookResync).
 */
final class GrimRotation {
    private GrimRotation() {}

    private static final long SILENT_ACTIVE_MS = 500;
    private static int silentAge = -1;
    private static int silentPriority;
    private static long lastSilentMillis;

    private static Minecraft mc() { return Minecraft.getInstance(); }

    static float[] getRotationsTo(Vec3 src, Vec3 dest) {
        float yaw = (float) (Math.toDegrees(Math.atan2(dest.subtract(src).z, dest.subtract(src).x)) - 90);
        float pitch = (float) Math.toDegrees(-Math.atan2(dest.subtract(src).y, Math.hypot(dest.subtract(src).x, dest.subtract(src).z)));
        return new float[]{Mth.wrapDegrees(yaw), Mth.wrapDegrees(pitch)};
    }

    static Vec3 getRotationVector(float yaw, float pitch) {
        float vx = -Mth.sin(rad(yaw)) * Mth.cos(rad(pitch));
        float vz = Mth.cos(rad(yaw)) * Mth.cos(rad(pitch));
        float vy = -Mth.sin(rad(pitch));
        return new Vec3(vx, vy, vz);
    }

    private static float rad(float degrees) { return (float) Math.toRadians(degrees); }

    /** A full movement packet at the same position; writes the look to the server. */
    static void doSilentRotate(float[] rotation) {
        Minecraft mc = mc();
        mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(
            mc.player.getX(), mc.player.getY(), mc.player.getZ(), rotation[0], rotation[1], mc.player.onGround(), false));
        LocalPlayerAccessor acc = (LocalPlayerAccessor) mc.player;
        acc.futureplus$setYRotLast(rotation[0]);
        acc.futureplus$setXRotLast(rotation[1]);
    }

    /**
     * A look for one action (Grim modes). The small pitch change keeps it from repeating the last packet exactly; the next movement
     * packet takes the camera's look back without a packet of its own.
     */
    static void silentLook(float yaw, float pitch) {
        float jitter = (float) ((Math.random() * 2.0 - 1.0) * 0.001f);
        doSilentRotate(new float[]{yaw, Mth.clamp(pitch + jitter, -90.0f, 90.0f)});
    }

    /** One silent look per tick, the highest priority winning. False when one at least as important has gone out. */
    static boolean submitSilent(int priority, float yaw, float pitch) {
        int age = mc().player.tickCount;
        if (silentAge == age && priority <= silentPriority) return false;

        silentAge = age;
        silentPriority = priority;
        lastSilentMillis = System.currentTimeMillis();
        silentLook(yaw, pitch);
        return true;
    }

    /** A silent look went out moments ago: a teleport now must not leave the camera on it. */
    static boolean isSilentActive() {
        return System.currentTimeMillis() - lastSilentMillis < SILENT_ACTIVE_MS;
    }

    /** Whether our look on the server points at this box. */
    static boolean serverLooksAt(AABB box) {
        Minecraft mc = mc();
        LocalPlayerAccessor acc = (LocalPlayerAccessor) mc.player;
        Vec3 eye = mc.player.getEyePosition();
        if (box.contains(eye)) return true;

        Vec3 look = getRotationVector(acc.futureplus$yRotLast(), acc.futureplus$xRotLast());
        return box.clip(eye, eye.add(look.scale(8.0))).isPresent();
    }
}
