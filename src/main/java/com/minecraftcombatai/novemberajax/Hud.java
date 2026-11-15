package com.minecraftcombatai.novemberajax;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** The in-game box: AJAX's portrait, what he is doing, the score. Out of the way when nothing is happening. */
final class Hud {
    private Hud() {}

    static void draw(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        AjaxMod m = AjaxMod.get();
        if (m == null || !m.hudVisible || mc.options.hideGui || mc.screen instanceof AjaxScreen || mc.player == null) return;
        boolean notice = System.currentTimeMillis() < m.noticeUntil();
        Practice.Mode mode = Practice.mode;
        if (!m.running() && !notice && mode == Practice.Mode.NONE && m.state != AjaxMod.State.ERROR) return;
        long ms = System.currentTimeMillis();
        String status, score;
        int accent = Theme.TEAL_L;
        if (m.running()) {
            accent = Theme.CORAL;
            status = m.target != null ? "PLAYING vs " + AjaxMod.describe(m.target) + "  " + clock((ms - m.fightStartMs()) / 1000)
                    : "PLAYING: looking for a fight";
        } else if (mode == Practice.Mode.WATCH) {
            status = "WATCHING: AJAX vs MIRROR";
        } else if (mode == Practice.Mode.SPAR) {
            status = "SPAR: you vs AJAX";
        } else if (mode == Practice.Mode.RIVAL) {
            status = "RIVAL: press " + AjaxMod.keyName(m.playKey) + " to let AJAX play";
        } else if (mode == Practice.Mode.DUMMY) {
            status = "DUMMY: press " + AjaxMod.keyName(m.playKey) + " to let AJAX play";
        } else {
            status = m.state == AjaxMod.State.ERROR ? "error: open the panel" : "";
        }
        if (mode == Practice.Mode.WATCH) score = "AJAX " + Practice.duelA + " - " + Practice.duelB + " MIRROR   round " + Practice.round;
        else if (mode == Practice.Mode.RIVAL) score = "AJAX " + Practice.youKOs + " - " + Practice.botKOs + " MIRROR   round " + Practice.round;
        else if (mode == Practice.Mode.SPAR) score = "you " + Practice.youKOs + " - " + Practice.botKOs + " AJAX   round " + Practice.round;
        else if (mode != Practice.Mode.NONE) score = "you " + Practice.youKOs + " - " + Practice.botKOs + " dummy   round " + Practice.round;
        else score = AjaxMod.keyName(m.playKey) + (m.running() ? " to stop" : " to start");
        int x = 6, y = 6, ph = 34;
        int w = Math.max(150, Math.max(mc.font.width(status), mc.font.width(score)) + 50);
        if (notice) w = Math.max(w, Math.min(mc.font.width(m.notice) + 12, 320));
        int h = ph + 6 + (notice ? 12 : 0);
        Theme.card(g, x, y, w, h, Theme.alpha(Theme.INK2, 0xE8), Theme.alpha(Theme.INK, 0xE8), m.running(), accent);
        Theme.image(g, Theme.PORTRAIT, x + 3, y + 3, 39, ph, 0xFFFFFFFF);
        int tx = x + 46;
        Theme.text(g, "AJAX", tx, y + 5, 1f, Theme.CREAM, true);
        if (m.running()) {
            boolean on = (ms / 400) % 2 == 0;
            g.fill(tx + 28, y + 6, tx + 33, y + 11, on ? Theme.CORAL : Theme.alpha(Theme.CORAL, 90));
            Theme.text(g, String.format("%.2f ms", m.inferMs()), tx + 37, y + 5, 1f, Theme.FAINT, false);
        }
        g.text(mc.font, status, tx, y + 16, Theme.DIM, false);
        g.text(mc.font, score, tx, y + 26, Theme.FAINT, false);
        if (notice) g.text(mc.font, m.notice, x + 5, y + ph + 6, Theme.AMBER, false);
    }

    private static String clock(long s) {
        return String.format("%d:%02d", s / 60, s % 60);
    }
}

