package com.minecraftcombatai.novemberajax;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;

/**
 * Applies the model's action to the player through vanilla's own inputs, at the START of a
 * client tick: the turn through Entity.turn (the mouse's
 * path), movement through the key mappings, and an attack as one click of the attack key, which
 * vanilla consumes this same tick after re-picking the crosshair with the new rotation.
 *
 * <p>The attack click is marked as the model's ({@link #modelClick()}). The game's own crosshair
 * pick then decides what it lands on, and the mixin in {@code mixin/MinecraftMixin} drops the click
 * when that would be a real player: in a LAN world or on a server there are other people about, and
 * AJAX never hits one of them.
 */
public final class Driver {
    /** What the model asked for this tick: held keys, an attack click, and the turn in degrees. */
    record Action(int keys, float yawDelta, float pitchDelta) {
        static final int FORWARD = 1, BACK = 2, LEFT = 4, RIGHT = 8, JUMP = 16, SPRINT = 32, SNEAK = 64, ATTACK = 128;

        boolean has(int bit) {
            return (keys & bit) != 0;
        }
    }

    private static final int MOVE_BITS = Action.FORWARD | Action.BACK | Action.LEFT | Action.RIGHT
            | Action.JUMP | Action.SPRINT | Action.SNEAK;

    /** The attack click of this tick is the model's, not the player's. */
    private static boolean armed;
    /** The game has taken an attack click since the model armed it. */
    private static boolean taken;

    private int held;                 // the key bits this driver is holding down right now
    long clicks;                      // attack clicks made

    void apply(Minecraft mc, Action a) {
        LocalPlayer me = mc.player;
        // Entity.turn multiplies by 0.15 (one mouse count); divide so the turn is verbatim.
        me.turn(a.yawDelta() / 0.15, a.pitchDelta() / 0.15);
        Options o = mc.options;
        int want = a.keys() & MOVE_BITS;
        hold(o.keyUp, want, Action.FORWARD);
        hold(o.keyDown, want, Action.BACK);
        hold(o.keyLeft, want, Action.LEFT);
        hold(o.keyRight, want, Action.RIGHT);
        hold(o.keyJump, want, Action.JUMP);
        hold(o.keySprint, want, Action.SPRINT);
        hold(o.keyShift, want, Action.SNEAK);
        held = want;
        // AJAX trained with no charge gate: every click the network asks for is a click.
        if (a.has(Action.ATTACK) && !o.keyAttack.isUnbound()) {
            armed = true;
            KeyMapping.click(InputConstants.getKey(o.keyAttack.saveString()));
            clicks++;
        }
    }

    /** True while the model's own attack click is still waiting for the game to take it. */
    public static boolean modelClick() {
        return armed;
    }

    /** The game took an attack click: it was the model's, or the player's own: either way it is handled. */
    public static void clickTaken() {
        taken = true;
    }

    /**
     * The client tick is over. If the game never took the model's click (a screen or an overlay was
     * in the way), the click is dropped here: it must not stay in the key mapping and land on
     * somebody in a later tick.
     */
    public static void sweep(Minecraft mc) {
        if (armed && !taken && mc.options != null) mc.options.keyAttack.consumeClick();
        armed = false;
        taken = false;
    }

    /** Let go of every key this driver is holding (stop, pause, a screen opened, death). */
    void release(Minecraft mc) {
        if (held != 0 && mc.options != null) {
            Options o = mc.options;
            hold(o.keyUp, 0, Action.FORWARD);
            hold(o.keyDown, 0, Action.BACK);
            hold(o.keyLeft, 0, Action.LEFT);
            hold(o.keyRight, 0, Action.RIGHT);
            hold(o.keyJump, 0, Action.JUMP);
            hold(o.keySprint, 0, Action.SPRINT);
            hold(o.keyShift, 0, Action.SNEAK);
        }
        held = 0;
    }

    private void hold(KeyMapping k, int want, int bit) {
        boolean down = (want & bit) != 0;
        if ((held & bit) == 0 && !down) return;      // never touch a key the player holds
        if (k.isDown() == down) return;
        k.setDown(down);
        // Toggle sneak / sprint: setDown(true) flips the state and setDown(false) is ignored.
        if (k.isDown() != down) k.setDown(true);
    }
}

