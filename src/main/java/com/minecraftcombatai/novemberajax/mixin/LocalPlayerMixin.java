package com.minecraftcombatai.novemberajax.mixin;

import com.minecraftcombatai.novemberajax.CameraSmooth;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * SMOOTH CAMERA ({@link CameraSmooth}): the camera draws your view from getViewYRot/getViewXRot,
 * which vanilla answers with the current rotation for your own player. While AJAX plays and the
 * setting is on, they answer with the rotation part way through AJAX's turn instead. At a partial
 * tick of 1 (the crosshair pick the attack uses) that is exactly the turn.
 */
@Mixin(LocalPlayer.class)
abstract class LocalPlayerMixin {
    @Inject(method = "getViewYRot", at = @At("HEAD"), cancellable = true)
    private void november_ajax$smoothYaw(float a, CallbackInfoReturnable<Float> cir) {
        if (CameraSmooth.applies((LocalPlayer) (Object) this)) cir.setReturnValue(CameraSmooth.yaw(a));
    }

    @Inject(method = "getViewXRot", at = @At("HEAD"), cancellable = true)
    private void november_ajax$smoothPitch(float a, CallbackInfoReturnable<Float> cir) {
        if (CameraSmooth.applies((LocalPlayer) (Object) this)) cir.setReturnValue(CameraSmooth.pitch(a));
    }
}

