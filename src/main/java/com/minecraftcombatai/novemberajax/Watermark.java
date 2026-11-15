package com.minecraftcombatai.novemberajax;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The MinecraftCombatAI watermark in the game itself: bottom right, whenever AJAX is playing, a practice mode is on or you are
 * in the practice arenas, so every recording of AJAX carries it. Shown even with the HUD box hidden; F1 (hide GUI) hides it.
 */
final class Watermark {
    private Watermark() {}

    static void draw(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        AjaxMod m = AjaxMod.get();
        if (m == null || mc.player == null || mc.options.hideGui || mc.screen instanceof AjaxScreen) return;
        if (!m.running() && Practice.mode == Practice.Mode.NONE && !PracticeWorld.active) return;
        int w = Theme.watermarkWidth();
        int x = g.guiWidth() - w - 6, y = g.guiHeight() - 14;
        g.fill(x - 4, y - 3, x + w + 4, y + 12, Theme.alpha(Theme.INK, 110));
        Theme.watermark(g, x, y, 215);
    }
}

