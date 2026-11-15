package com.minecraftcombatai.novemberajax;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;

/**
 * Where AJAX is, and what the mod can do there.
 *
 * <p>AJAX is a practice partner, never a cheat: he only ever fights this mod's own practice bots or
 * hostile monsters, For authorized testing only, and he never swings at one either (the mixin in
 * {@code mixin/MinecraftMixin} drops a click that would land on a player).
 *
 * <p>There are three places, and they differ in what the mod can do in them:
 *
 * <ul>
 *   <li>{@link Where#SINGLEPLAYER} — a world on this computer (the integrated server, not published
 *       to LAN). Everything works: the practice bots are server players in this computer's own
 *       integrated server.</li>
 *   <li>{@link Where#LAN} — a world this computer hosts and has opened to LAN (the integrated
 *       server, published). Everything works too: the bots are still this computer's own, they live
 *       in its integrated server and they only ever fight the player who asked for them.</li>
 *   <li>{@link Where#REMOTE} — someone else's server, or someone else's LAN world: this computer has
 *       only a client connection, so the practice bots cannot be spawned (they are server players).
 *       AJAX can still play for you against what is already there: this mod's bots, if that server
 *       runs the mod as well, or hostile monsters. He is for testing in authorized environments only.</li>
 * </ul>
 *
 * <p>The one rule that never changes: AJAX is for testing in authorized environments only, wherever he is.
 */
final class Gate {
    private Gate() {}

    /** The three places AJAX can be. */
    enum Where {
        SINGLEPLAYER("Singleplayer world"),
        LAN("LAN world (this computer hosts it)"),
        REMOTE("Remote server");

        final String label;

        Where(String label) {
            this.label = label;
        }
    }

    /** The integrated server of this computer, if there is one: a singleplayer world or a LAN world we host. */
    static IntegratedServer host(Minecraft mc) {
        IntegratedServer s = mc.getSingleplayerServer();
        return s != null && mc.hasSingleplayerServer() ? s : null;
    }

    /** Where the player is right now. */
    static Where where(Minecraft mc) {
        IntegratedServer s = host(mc);
        if (s == null) return Where.REMOTE;
        return s.isPublished() ? Where.LAN : Where.SINGLEPLAYER;
    }

    /** True when the practice bots can be spawned: they are server players, so the world must be hosted here. */
    static boolean hostsBots(Minecraft mc) {
        return host(mc) != null;
    }

    static boolean allowed(Minecraft mc) {
        return mc.player != null && mc.level != null;
    }

    /** Why he may not play here, or an empty string when he may. */
    static String refusal(Minecraft mc) {
        return mc.player == null ? "Open a world first." : "";
    }
}

