package com.minecraftcombatai.novemberajax.mixin;

import com.minecraftcombatai.novemberajax.Practice;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * AJAX and MIRROR wear the diamond kit they trained with (it is what makes the damage right), but the client draws them in
 * their own colours: the armour pieces are left out of the render state for this mod's practice bots only.
 */
@Mixin(HumanoidMobRenderer.class)
abstract class BotArmourMixin {
    @Inject(method = "extractHumanoidRenderState", at = @At("TAIL"))
    private static void november_ajax$hideBotArmour(LivingEntity e, HumanoidRenderState s, float partial, ItemModelResolver items, CallbackInfo ci) {
        if (e instanceof Player p && Practice.dressed(p.getUUID())) {
            s.headEquipment = ItemStack.EMPTY;
            s.chestEquipment = ItemStack.EMPTY;
            s.legsEquipment = ItemStack.EMPTY;
            s.feetEquipment = ItemStack.EMPTY;
        }
    }
}

