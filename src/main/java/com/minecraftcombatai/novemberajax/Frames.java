package com.minecraftcombatai.novemberajax;

import com.minecraftcombatai.novemberajax.brain.AjaxInputs;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** One tick of raw game state for AJAX, from the body he drives ("self") and the one he fights. */
final class Frames {
    private Frames() {}

    static void fill(AjaxInputs.Frame f, LivingEntity me, boolean meCrouching, LivingEntity o) {
        f.sx = me.getX(); f.sy = me.getY(); f.sz = me.getZ();
        f.syaw = me.getYRot(); f.spitch = me.getXRot();
        f.sground = me.onGround(); f.ssprint = me.isSprinting(); f.ssneak = meCrouching;
        f.shurt = me.hurtTime; f.shp = me.getHealth(); f.shpKnown = true; f.sabs = me.getAbsorptionAmount();
        if (me instanceof Player p) {
            f.scharge = p.getAttackStrengthScale(0.5f);
            f.schargeKnown = true;
        } else {
            f.schargeKnown = false;
        }
        f.sswing = me.swinging;
        f.sSword = sword(me.getMainHandItem());
        f.sarmor = me.getArmorValue();
        f.sweapon = 7;
        f.ox = o.getX(); f.oy = o.getY(); f.oz = o.getZ();
        f.oyaw = o.getYRot(); f.opitch = o.getXRot();
        f.oground = o.onGround(); f.osprint = o.isSprinting(); f.osneak = o.isCrouching();
        f.ohurt = o.hurtTime; f.ohp = o.getHealth(); f.ohpKnown = true;
        f.oswing = o.swinging;
        f.oSword = sword(o.getMainHandItem());
        f.oarmor = o.getArmorValue();
        f.pingS = 20; f.pingO = 20; f.pingKnown = true;     // one computer: a good connection
    }

    /** A practice bot's view: itself exactly, its opponent as its client would see it (PracticeBot.LAG ticks late). */
    static void fill(AjaxInputs.Frame f, PracticeBot me, LivingEntity o) {
        fill(f, me, me.crouchingFlag(), o);
        PracticeBot.Seen v = me.seenOf(o);
        if (v == null) return;
        f.ox = v.x(); f.oy = v.y(); f.oz = v.z(); f.oyaw = v.yaw(); f.opitch = v.pitch();
        f.oground = v.ground(); f.osprint = v.sprint(); f.osneak = v.sneak(); f.oswing = v.swing();
        f.ohurt = v.hurt(); f.ohp = v.health(); f.oarmor = v.armor(); f.oSword = v.sword();
    }

    private static boolean sword(ItemStack s) {
        return s.is(Items.DIAMOND_SWORD) || s.is(Items.NETHERITE_SWORD) || s.is(Items.IRON_SWORD) || s.is(Items.STONE_SWORD)
                || s.is(Items.GOLDEN_SWORD) || s.is(Items.WOODEN_SWORD);
    }
}

