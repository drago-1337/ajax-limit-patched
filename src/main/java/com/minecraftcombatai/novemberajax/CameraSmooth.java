package com.minecraftcombatai.novemberajax;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * SMOOTH CAMERA: while AJAX plays for you, the view glides between its turns instead of jumping.
 *
 * The model turns the player once a tick (20 times a second, {@link Driver#apply}), and vanilla
 * draws your own view at exactly that rotation every frame, so at 60+ FPS the view steps 20 times
 * a second: laggy and twitchy to watch. This only changes what is drawn: each frame shows the
 * rotation part way from the tick's start to where the model turned, by the frame's partial tick,
 * the way every other entity is drawn. The rotation the game uses (the crosshair the attack picks
 * at the start of the tick, what is sent to the server, what the model sees) is the model's turn,
 * untouched. It reaches the drawn view one tick later, at most.
 *
 * Client thread only.
 */
public final class CameraSmooth {
    private static LocalPlayer player;
    private static float fromYaw, fromPitch, toYaw, toPitch;

    private CameraSmooth() {}

    /** Each tick AJAX plays, after its turn: the rotation before it and the one it turned to. */
    static void tick(LocalPlayer p, float yawBefore, float pitchBefore) {
        player = p;
        fromYaw = yawBefore;
        fromPitch = pitchBefore;
        toYaw = p.getYRot();
        toPitch = p.getXRot();
    }

    /** AJAX stopped (or the setting is off): the view is vanilla's again. */
    static void off() {
        player = null;
    }

    /**
     * Whether the drawn view of this player is smoothed now: only the turn AJAX made this tick, so a
     * teleport, a respawn or anything else that sets the rotation shows at once.
     */
    public static boolean applies(LocalPlayer p) {
        return p == player && !p.isPassenger() && p.getYRot() == toYaw && p.getXRot() == toPitch;
    }

    public static float yaw(float partial) {
        return Mth.lerp(partial, fromYaw, toYaw);
    }

    public static float pitch(float partial) {
        return Mth.lerp(partial, fromPitch, toPitch);
    }
}

