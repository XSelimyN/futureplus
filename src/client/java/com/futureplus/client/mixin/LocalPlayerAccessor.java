package com.futureplus.client.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** MaceAura TP: the position/look last sent to the server (the server's "firstGood" position at the start of the tick). */
@Mixin(LocalPlayer.class)
public interface LocalPlayerAccessor {
    @Accessor("xLast") double futureplus$xLast();
    @Accessor("yLast") double futureplus$yLast();
    @Accessor("zLast") double futureplus$zLast();
    @Accessor("yRotLast") float futureplus$yRotLast();
    @Accessor("xRotLast") float futureplus$xRotLast();
    @Accessor("yRotLast") void futureplus$setYRotLast(float v);
    @Accessor("xRotLast") void futureplus$setXRotLast(float v);
}
