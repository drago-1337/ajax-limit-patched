package com.minecraftcombatai.novemberajax.mixin;

import com.minecraftcombatai.novemberajax.Practice;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The practice bots wear the skin bundled in this jar. A custom skin normally has to be signed by
 * Mojang; this client simply draws its own bots with a local texture, so it works offline. Both
 * the body (AvatarRenderer reads entity.getSkin(), which asks PlayerInfo) and the tab-list head use
 * this. Every other player is untouched.
 */
@Mixin(PlayerInfo.class)
abstract class PlayerInfoMixin {
    @Inject(method = "getSkin", at = @At("HEAD"), cancellable = true)
    private void november_ajax$practiceBotSkin(CallbackInfoReturnable<PlayerSkin> cir) {
        if (Practice.dressed(((PlayerInfo) (Object) this).getProfile().id())) cir.setReturnValue(Practice.botSkin(((PlayerInfo) (Object) this).getProfile().id()));
    }
}

