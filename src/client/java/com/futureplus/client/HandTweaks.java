package com.futureplus.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand Settings (Visual) - a port of Soup's HandTweaks module (one-to-one from the bytecode).
 *
 * Where Soup hooks in (ItemInHandRendererMixin):
 *  1) in renderArmWithItem after pushPose    -> HandOffsetEvent  : main/off hand position + angle
 *  2) the renderItem wrapper                  -> HandScaleEvent   : main/off hand size
 *  3) the swingArm call (3rd occurrence, plain item branch) -> HandAnimationEvent: swing style (cancels vanilla)
 *  4) LivingEntity.getCurrentSwingDuration    -> SwingDurationEvent: swing speed
 * None of them change anything sent to the server; purely visual.
 */
public final class HandTweaks {
    private HandTweaks() {}

    public static final String[] SWING_TYPES = {"Vanilla", "Swipe", "Down", "Smooth", "Power", "Feast", "Custom"};

    private static boolean enabled = VisualState.load("HandTweaks");

    // Soup defaults
    static boolean swingOn = false;
    static int swingType = 0;
    static double swingSpeed = 1.0;                       // 0.5 .. 2
    // Custom: absolute start position + fixed angles + angles added during the swing
    static double cTx = 0.56, cTy = -0.32, cTz = -0.72;
    static double cRx = 0, cRy = 0, cRz = 0;
    static double cSx = -60, cSy = 0, cSz = 0;

    static double mx = 0, my = 0, mz = 0, mPitch = 0, mYaw = 0, mRoll = 0, mScale = 0.7;
    static double ox = 0, oy = 0, oz = 0, oPitch = 0, oYaw = 0, oRoll = 0, oScale = 0.7;

    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean v) { enabled = v; VisualState.save("HandTweaks", v); }

    public static List<Setting> getSettings() {
        List<Setting> s = new ArrayList<>();
        s.add(Setting.bool("Swing animation", () -> swingOn, v -> swingOn = v));
        s.add(Setting.choice("Swing style", SWING_TYPES, () -> swingType, v -> swingType = v).when(() -> swingOn));
        s.add(Setting.num("Swing speed", 0.5, 2, 0.05, () -> swingSpeed, v -> swingSpeed = v).when(() -> swingOn));
        s.add(Setting.num("Custom offset X", -2, 2, 0.01, () -> cTx, v -> cTx = v).when(() -> swingOn && swingType == 6));
        s.add(Setting.num("Custom offset Y", -2, 2, 0.01, () -> cTy, v -> cTy = v).when(() -> swingOn && swingType == 6));
        s.add(Setting.num("Custom offset Z", -2, 2, 0.01, () -> cTz, v -> cTz = v).when(() -> swingOn && swingType == 6));
        s.add(Setting.num("Custom angle X", -180, 180, 1, () -> cRx, v -> cRx = v).when(() -> swingOn && swingType == 6));
        s.add(Setting.num("Custom angle Y", -180, 180, 1, () -> cRy, v -> cRy = v).when(() -> swingOn && swingType == 6));
        s.add(Setting.num("Custom angle Z", -180, 180, 1, () -> cRz, v -> cRz = v).when(() -> swingOn && swingType == 6));
        s.add(Setting.num("Custom swing X", -180, 180, 1, () -> cSx, v -> cSx = v).when(() -> swingOn && swingType == 6));
        s.add(Setting.num("Custom swing Y", -180, 180, 1, () -> cSy, v -> cSy = v).when(() -> swingOn && swingType == 6));
        s.add(Setting.num("Custom swing Z", -180, 180, 1, () -> cSz, v -> cSz = v).when(() -> swingOn && swingType == 6));
        s.add(Setting.num("Main hand X", -1, 1, 0.01, () -> mx, v -> mx = v));
        s.add(Setting.num("Main hand Y", -1, 1, 0.01, () -> my, v -> my = v));
        s.add(Setting.num("Main hand Z", -2.5, 2.5, 0.01, () -> mz, v -> mz = v));
        s.add(Setting.num("Main hand pitch", -180, 180, 1, () -> mPitch, v -> mPitch = v));
        s.add(Setting.num("Main hand yaw", -180, 180, 1, () -> mYaw, v -> mYaw = v));
        s.add(Setting.num("Main hand roll", -180, 180, 1, () -> mRoll, v -> mRoll = v));
        s.add(Setting.num("Main hand scale", 0.1, 2.0, 0.01, () -> mScale, v -> mScale = v));
        s.add(Setting.num("Offhand X", -1, 1, 0.01, () -> ox, v -> ox = v));
        s.add(Setting.num("Offhand Y", -1, 1, 0.01, () -> oy, v -> oy = v));
        s.add(Setting.num("Offhand Z", -2.5, 2.5, 0.01, () -> oz, v -> oz = v));
        s.add(Setting.num("Offhand pitch", -180, 180, 1, () -> oPitch, v -> oPitch = v));
        s.add(Setting.num("Offhand yaw", -180, 180, 1, () -> oYaw, v -> oYaw = v));
        s.add(Setting.num("Offhand roll", -180, 180, 1, () -> oRoll, v -> oRoll = v));
        s.add(Setting.num("Offhand scale", 0.1, 2.0, 0.01, () -> oScale, v -> oScale = v));
        return s;
    }

    // ------------------------------------------------------------- HandOffsetEvent

    /** Right after pushPose. Soup: leaves it alone with a crossbow in the main hand; NO left/right mirroring. */
    public static void applyHandOffset(PoseStack pose, boolean mainHand, ItemStack stack) {
        if (!enabled) return;
        if (mainHand && stack.getItem() instanceof CrossbowItem) return;
        double x = mainHand ? mx : ox, y = mainHand ? my : oy, z = mainHand ? mz : oz;
        double p = mainHand ? mPitch : oPitch, yw = mainHand ? mYaw : oYaw, r = mainHand ? mRoll : oRoll;
        pose.translate(x, y, z);
        if (p != 0) pose.mulPose(Axis.XP.rotationDegrees((float) p));
        if (yw != 0) pose.mulPose(Axis.YP.rotationDegrees((float) yw));
        if (r != 0) pose.mulPose(Axis.ZP.rotationDegrees((float) r));
    }

    // ------------------------------------------------------------- HandScaleEvent

    /** Returning 1.0 means no change. */
    public static float handScale(boolean mainHand) {
        if (!enabled) return 1f;
        return (float) (mainHand ? mScale : oScale);
    }

    // ------------------------------------------------------------- SwingDurationEvent

    /** null = vanilla. Soup: speed * (6 | 6-(1+haste) | 6+(1+fatigue)*2). */
    public static Integer swingDuration(LocalPlayer p) {
        if (!enabled || !swingOn || p == null) return null;
        float anim = (float) swingSpeed;
        if (MobEffectUtil.hasDigSpeed(p)) {
            anim = anim * (6 - (1 + MobEffectUtil.getDigSpeedAmplification(p)));
        } else if (p.hasEffect(MobEffects.MINING_FATIGUE)) {
            MobEffectInstance e = p.getEffect(MobEffects.MINING_FATIGUE);
            anim = anim * (6 + (1 + e.getAmplifier()) * 2);
        } else {
            anim = anim * 6f;
        }
        return (int) anim;
    }

    // ------------------------------------------------------------- HandAnimationEvent

    /**
     * Replaces the swingArm call. Before it is called, vanilla's "applyItemArmTransform" offset has been undone,
     * so the pose here is at the start of the scene and the style starts from an ABSOLUTE position (as Soup does).
     * @return true = vanilla swingArm is skipped
     */
    public static boolean animate(PoseStack p, boolean mainHand, boolean rightMain, float progress) {
        if (!enabled || !swingOn || !mainHand || swingType == 0) return false;
        int i = rightMain ? 1 : -1;
        float f = (float) Math.sin(progress * progress * Math.PI);                 // v5
        float f1 = (float) Math.sin(Math.sqrt(progress) * Math.PI);                // v6
        float f2 = (float) (Math.sin(progress * Math.PI) * 0.5);                   // v7
        switch (swingType) {
            case 1 -> { // Swipe
                p.translate(0.56f * i, -0.32f, -0.72f);
                p.mulPose(Axis.YP.rotationDegrees(60 * i));
                p.mulPose(Axis.ZP.rotationDegrees(-60 * i));
                p.mulPose(Axis.YP.rotationDegrees(f1 * f * -5f));
                p.mulPose(Axis.XP.rotationDegrees(f1 * f * -120f));
                p.mulPose(Axis.XP.rotationDegrees(-60f));
            }
            case 2 -> { // Down
                p.translate(i * 0.56f, -0.32f, -0.72f);
                p.mulPose(Axis.YP.rotationDegrees(76 * i));
                p.mulPose(Axis.YP.rotationDegrees(f1 * -5f));
                p.mulPose(Axis.XN.rotationDegrees(f1 * -100f));
                p.mulPose(Axis.XP.rotationDegrees(f1 * -155f));
                p.mulPose(Axis.XP.rotationDegrees(-100f));
            }
            case 3 -> { // Smooth
                p.translate(i * 0.56f, -0.42f, -0.72f);
                p.mulPose(Axis.YP.rotationDegrees(i * (45f + f * -20f)));
                p.mulPose(Axis.ZP.rotationDegrees(i * f1 * -20f));
                p.mulPose(Axis.XP.rotationDegrees(f1 * -80f));
                p.mulPose(Axis.YP.rotationDegrees(i * -45f));
                p.translate(0.0, -0.1, 0.0);
            }
            case 4 -> { // Power
                p.translate(i * 0.56f, -0.32f, -0.72f);
                p.translate(((-f2 * f2) * f) * i, 0f, 0f);
                p.mulPose(Axis.YP.rotationDegrees(61 * i));
                p.mulPose(Axis.ZP.rotationDegrees(f1));
                p.mulPose(Axis.YP.rotationDegrees(f1 * f * -5f));
                p.mulPose(Axis.XP.rotationDegrees(f1 * f * -30f));
                p.mulPose(Axis.XP.rotationDegrees(-60f));
                p.mulPose(Axis.XP.rotationDegrees(f2 * -60f));
            }
            case 5 -> { // Feast
                p.translate(i * 0.56f, -0.32f, -0.72f);
                p.mulPose(Axis.YP.rotationDegrees(30 * i));
                p.mulPose(Axis.YP.rotationDegrees(f1 * 75f * i));
                p.mulPose(Axis.XP.rotationDegrees(f1 * -45f));
                p.mulPose(Axis.YP.rotationDegrees(30 * i));
                p.mulPose(Axis.XP.rotationDegrees(-80f));
                p.mulPose(Axis.YP.rotationDegrees(35 * i));
            }
            case 6 -> { // Custom
                p.translate(i * (float) cTx, (float) cTy, (float) cTz);
                if (cRx != 0) p.mulPose(Axis.XP.rotationDegrees((float) cRx));
                if (cRy != 0) p.mulPose(Axis.YP.rotationDegrees((float) cRy * i));
                if (cRz != 0) p.mulPose(Axis.ZP.rotationDegrees((float) cRz * i));
                if (cSx != 0) p.mulPose(Axis.XP.rotationDegrees(f1 * (float) cSx));
                if (cSy != 0) p.mulPose(Axis.YP.rotationDegrees(f1 * (float) cSy * i));
                if (cSz != 0) p.mulPose(Axis.ZP.rotationDegrees(f1 * (float) cSz * i));
            }
            default -> { return false; }
        }
        return true;
    }
}
