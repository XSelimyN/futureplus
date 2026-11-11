package com.futureplus.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;

/** Shared combat calculations and packet rotation for the retained modules. */
public final class CombatSupport {
    private CombatSupport() {}
    enum Type { INTERACT, PLACE, MINE, ATTACK, USE, OTHER }
    private static Minecraft mc() { return Minecraft.getInstance(); }

    private record Rot(Vec3 eye, float yaw, float pitch) {}
    private static final List<Rot> history = new ArrayList<>();
    private static float sentYaw, sentPitch;
    private static float[] want, hold;
    private static Type wantType = Type.OTHER;
    private static boolean wantForce, spoofing;
    private static int wantPri = Integer.MAX_VALUE, rotateMode = 1;
    private static long holdUntil;
    private static float realYaw, realPitch;
    private static double yawSpeed = 90, pitchSpeed = 45, yawJitter = 1, pitchJitter = 1, useTime = 0.1;
    private static final boolean[] rotOn = {true, true, true, true};
    private static final double[] rotTimes = {0.1,0.1,0.1,0.1};
    private static final int[] rotCheck = {0,0,0,0}, rotMemory = {1,1,1,1};
    private static final double[] rotYawAngle = {90,90,90,90}, rotPitchAngle = {45,45,45,45};
    private static boolean swingInteract=true, swingPlace=true, swingAttack=true, swingUse=true;
    private static int swingInteractState=1, swingPlaceState=1, swingAttackState=1, swingUseState=1;

    static boolean ready() { Minecraft m=mc(); return m.player!=null && m.level!=null && m.gameMode!=null && m.getConnection()!=null; }
    static boolean friend(Player p) { return p != null && MaceAura.isFriend(p.getPlainTextName()); }
    private static boolean shouldRotate(Type t) { return rotateMode != 0 && (t.ordinal() >= 4 || rotOn[t.ordinal()]); }
    private static double rotTime(Type t) { return t.ordinal() < 4 ? rotTimes[t.ordinal()] : t==Type.USE ? useTime : 1; }
    static boolean look(AABB box, Vec3 point, int priority, Type type) {
        if (!shouldRotate(type)) return true;
        Vec3 eye=mc().player.getEyePosition(); float yaw=yawTo(eye,point), pitch=Mth.clamp(pitchTo(eye,point),-90f,90f);
        if (rotationCheck(box,type)) { hold=new float[]{sentYaw,sentPitch}; holdUntil=System.currentTimeMillis()+(long)(rotTime(type)*1000); return true; }
        request(yaw,pitch,priority,type,false); return false;
    }
    private static void request(float yaw,float pitch,int priority,Type type,boolean force) {
        if(priority>wantPri)return; wantPri=priority; want=new float[]{yaw,pitch}; wantType=type; wantForce=force;
        if(rotateMode==2&&!force){LocalPlayer p=mc().player;p.setYRot(yaw);p.setXRot(pitch);p.setYHeadRot(yaw);}
    }
    private static boolean rotationCheck(AABB box,Type type) {
        int i=Math.min(type.ordinal(),3); Vec3 eye=mc().player.getEyePosition(); if(box.contains(eye))return true;
        if(history.isEmpty())return check(rotCheck[i],new Rot(eye,sentYaw,sentPitch),box,i);
        for(int r=0;r<rotMemory[i]&&r<history.size();r++)if(check(rotCheck[i],history.get(r),box,i))return true;return false;
    }
    private static boolean check(int mode,Rot rot,AABB box,int i) {
        Vec3 dir=Vec3.directionFromRotation(rot.pitch,rot.yaw);
        if(mode==2)return Math.abs(Mth.wrapDegrees(rot.yaw-yawTo(rot.eye,box.getCenter())))<=rotYawAngle[i]&&Math.abs(rot.pitch-pitchTo(rot.eye,box.getCenter()))<=rotPitchAngle[i];
        if(mode==1)return box.clip(rot.eye,rot.eye.add(dir.scale(7))).isPresent();
        double range=rot.eye.distanceTo(box.getCenter())+3; Vec3 end=rot.eye.add(dir.scale(range));
        for(float k=0;k<1;k+=0.01f)if(box.contains(Mth.lerp(k,rot.eye.x,end.x),Mth.lerp(k,rot.eye.y,end.y),Mth.lerp(k,rot.eye.z,end.z)))return true;return false;
    }
    static float yawTo(Vec3 from,Vec3 to){return (float)(Math.toDegrees(Math.atan2(to.z-from.z,to.x-from.x))-90.0);}
    static float pitchTo(Vec3 from,Vec3 to){double dx=to.x-from.x,dz=to.z-from.z;return (float)-Math.toDegrees(Math.atan2(to.y-from.y,Math.sqrt(dx*dx+dz*dz)));}
    private static void pushHistory(Rot r){history.add(0,r);if(history.size()>20)history.remove(history.size()-1);}
    public static void beforeSend(LocalPlayer p){if(p==null)return;AnchorAura.tick(mc());AutoCrystal.tick();spoofing=false;float[] target=want;Type type=wantType;boolean force=wantForce;
        if(target==null&&hold!=null&&System.currentTimeMillis()<holdUntil){target=hold;type=Type.OTHER;force=false;}
        if(target!=null&&(rotateMode==1||force||rotateMode==2&&type==Type.OTHER)){realYaw=p.getYRot();realPitch=p.getXRot();float[] next=step(target,type);p.setYRot(next[0]);p.setXRot(next[1]);spoofing=true;}
        want=null;wantPri=Integer.MAX_VALUE;wantForce=false;wantType=Type.OTHER;}
    private static float[] step(float[] target,Type type){boolean free=type==Type.OTHER||type==Type.USE;double ys=free?1000:yawSpeed,ps=free?1000:pitchSpeed;
        float y=sentYaw+(float)Mth.clamp(Mth.wrapDegrees(target[0]-sentYaw),-ys,ys);float x=sentPitch+(float)Mth.clamp(target[1]-sentPitch,-ps,ps);return new float[]{y,Mth.clamp(x,-90f,90f)};}
    public static void afterSend(LocalPlayer p){if(p==null)return;sentYaw=p.getYRot();sentPitch=p.getXRot();pushHistory(new Rot(p.getEyePosition(),sentYaw,sentPitch));if(spoofing){p.setYRot(realYaw);p.setXRot(realPitch);spoofing=false;}}
    static int hotbar(Predicate<ItemStack> filter){var inv=mc().player.getInventory();for(int i=0;i<9;i++)if(filter.test(inv.getItem(i)))return i;return -1;}
    static InteractionHand handWith(Predicate<ItemStack> filter){LocalPlayer p=mc().player;if(filter.test(p.getMainHandItem()))return InteractionHand.MAIN_HAND;if(filter.test(p.getOffhandItem()))return InteractionHand.OFF_HAND;return null;}
    static void select(int slot){if(slot<0||slot>8)return;mc().player.getInventory().setSelectedSlot(slot);((com.futureplus.client.mixin.MultiPlayerGameModeAccessor)mc().gameMode).futureplus$syncCarried();}
    static void serverSwing(Type t,boolean pre,InteractionHand hand){boolean on;int mode;switch(t){case INTERACT->{on=swingInteract;mode=swingInteractState;}case PLACE->{on=swingPlace;mode=swingPlaceState;}case ATTACK->{on=swingAttack;mode=swingAttackState;}case USE->{on=swingUse;mode=swingUseState;}default->{return;}}
        if(on&&(mode==0)==pre&&mc().getConnection()!=null)mc().getConnection().send(new net.minecraft.network.protocol.game.ServerboundSwingPacket(hand));}
    static void use(InteractionHand hand,BlockHitResult hit,Type type,Object ignored,boolean packet){serverSwing(type,true,hand);
        if(packet)((com.futureplus.client.mixin.MultiPlayerGameModeAccessor)mc().gameMode).futureplus$predict(mc().level,seq->new net.minecraft.network.protocol.game.ServerboundUseItemOnPacket(hand,hit,seq));
        else mc().gameMode.useItemOn(mc().player,hand,hit);serverSwing(type,false,hand);}
    static boolean solid(BlockPos pos){BlockState s=mc().level.getBlockState(pos);return !s.isAir()&&!s.getCollisionShape(mc().level,pos).isEmpty();}
    static double damage(LivingEntity e,AABB box,Vec3 center,double power,BlockPos ignore,boolean ignoreTerrain){return damage(e,box,center,power,ignore,ignoreTerrain,-1);}
    static double damage(LivingEntity e,AABB box,Vec3 center,double power,BlockPos ignore,boolean ignoreTerrain,int protOverride){
        double q=power*2;double dist=new Vec3((box.minX+box.maxX)/2,box.minY,(box.minZ+box.maxZ)/2).distanceTo(center)/q;if(dist>1)return 0;
        double ab=(1-dist)*exposure(center,box,ignore,ignoreTerrain);double dmg=(int)((ab*ab+ab)*3.5*q+1);if(dmg<=0)return 0;
        if(e instanceof Player){Difficulty d=mc().level.getDifficulty();if(d==Difficulty.PEACEFUL)return 0;if(d==Difficulty.EASY)dmg=Math.min(dmg/2+1,dmg);else if(d==Difficulty.HARD)dmg*=1.5;}
        double armor=e.getArmorValue(), tough=2+e.getAttributeValue(Attributes.ARMOR_TOUGHNESS)/4;dmg*=1-Mth.clamp(armor-dmg/tough,armor*0.2,20)/25;
        MobEffectInstance res=e.getEffect(MobEffects.RESISTANCE);if(res!=null)dmg=Math.max(dmg*(25-(res.getAmplifier()+1)*5)/25,0);
        int prot=protOverride>=0?protOverride:protection(e);if(prot>0)dmg*=1-Mth.clamp(prot,0,20)/25.0;return dmg;}
    private static int protection(LivingEntity e){try{var reg=mc().level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);Holder<Enchantment> p=reg.getOrThrow(Enchantments.PROTECTION),b=reg.getOrThrow(Enchantments.BLAST_PROTECTION);int n=0;
        for(EquipmentSlot slot:new EquipmentSlot[]{EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET}){ItemStack st=e.getItemBySlot(slot);if(!st.isEmpty())n+=EnchantmentHelper.getItemEnchantmentLevel(p,st)+2*EnchantmentHelper.getItemEnchantmentLevel(b,st);}return n;}catch(Throwable t){return 0;}}
    private static double exposure(Vec3 center,AABB box,BlockPos ignore,boolean ignoreTerrain){double xs=1.0/((box.maxX-box.minX)*2+1),ys=1.0/((box.maxY-box.minY)*2+1),zs=1.0/((box.maxZ-box.minZ)*2+1);double xo=(1-Math.floor(1/xs)*xs)/2,zo=(1-Math.floor(1/zs)*zs)/2;int hit=0,count=0;
        for(double x=0;x<=1;x+=xs)for(double y=0;y<=1;y+=ys)for(double z=0;z<=1;z+=zs){Vec3 from=new Vec3(Mth.lerp(x,box.minX,box.maxX)+xo,Mth.lerp(y,box.minY,box.maxY),Mth.lerp(z,box.minZ,box.maxZ)+zo);count++;if(!blocked(from,center,ignore,ignoreTerrain))hit++;}return count==0?0:(double)hit/count;}
    private static boolean blocked(Vec3 from,Vec3 to,BlockPos ignore,boolean ignoreTerrain){BlockGetter level=mc().level;Boolean result=BlockGetter.traverseBlocks(from,to,null,(ctx,pos)->{if(pos.equals(ignore))return null;BlockState state=level.getBlockState(pos);if(state.isAir()||ignoreTerrain&&state.getBlock().getExplosionResistance()<200)return null;VoxelShape shape=state.getCollisionShape(level,pos);return shape.clip(from,to,pos)!=null?Boolean.TRUE:null;},ctx->Boolean.FALSE);return result!=null&&result;}
}
