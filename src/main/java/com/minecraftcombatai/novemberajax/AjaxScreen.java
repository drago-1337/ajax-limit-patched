package com.minecraftcombatai.novemberajax;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The AJAX panel. AJAX himself stands on the right on a turning ray backdrop (the renders of the character from the intro);
 * the left is the wordmark, the pages and big click-only cards.
 *
 *   PLAY      let AJAX play from your eyes (against MIRROR or the dummy), spar him, or watch him fight MIRROR
 *   ARENAS    the five practice arenas (a temporary world, deleted when you leave)
 *   SETTINGS  HUD, smooth view, monsters, keys
 *   ABOUT     who he is, what is in the jar, the rules
 */
final class AjaxScreen extends Screen {
    private enum Page { PLAY, ARENAS, SETTINGS, ABOUT }

    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean in(double mx, double my) {
            return mx >= x && my >= y && mx < x + w && my < y + h;
        }
    }

    private final Screen parent;
    private Page page = Page.PLAY;
    private final List<Hit> hits = new ArrayList<>();
    private final long opened = System.currentTimeMillis();

    AjaxScreen(Screen parent) {
        super(Component.literal("November AJAX"));
        this.parent = parent;
    }

    /** Opens on a page by name (PLAY, ARENAS, SETTINGS, ABOUT): the dev director's UI tour. */
    AjaxScreen(Screen parent, String page) {
        this(parent);
        this.page = Page.valueOf(page);
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (e.button() == 0)
            for (Hit h : List.copyOf(hits))
                if (h.in(e.x(), e.y())) {
                    h.action().run();
                    return true;
                }
        return super.mouseClicked(e, doubleClick);
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (e.key() == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(e);
    }

    // ------------------------------------------------------------------ drawing ----

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
        hits.clear();
        long ms = System.currentTimeMillis();
        float t = (ms - opened) / 1000f;
        int w = width, h = height;
        g.fillGradient(0, 0, w, h, Theme.INK2, Theme.INK);
        // the ray backdrop behind him, turning slowly
        int cx = (int) (w * 0.74), cy = (int) (h * 0.46), rs = (int) (Math.max(w, h) * 1.25);
        g.pose().pushMatrix();
        g.pose().translate(cx, cy);
        g.pose().rotate(t * 0.05f);
        Theme.image(g, Theme.RAYS, -rs / 2, -rs / 2, rs, rs, Theme.alpha(page == Page.ABOUT ? Theme.CORAL : Theme.TEAL_L, 46));
        g.pose().popMatrix();
        g.fillGradient(0, (int) (h * 0.78), w, h, Theme.alpha(Theme.INK, 0), Theme.alpha(Theme.INK, 230));
        // him (and MIRROR on the watch card hover), with a slow idle bob
        float intro = Math.min(1f, t / 0.45f);
        int bob = Math.round((float) Math.sin(t * 1.6) * 2f);
        int ah = (int) (h * 0.94);
        Theme.Tex pose = switch (page) {
            case PLAY -> Theme.GUARD;
            case ARENAS -> Theme.WAVE;
            case SETTINGS -> Theme.SHOULDER;
            case ABOUT -> Theme.HERO;
        };
        int aw = Math.round((float) pose.w() * ah / pose.h());
        int ax = (int) (w * 0.74) - aw / 2 + Math.round((1 - intro) * 40);
        Theme.image(g, pose, ax, h - ah + 6 + bob, aw, ah, Theme.alpha(0xFFFFFF, (int) (255 * intro)));

        // left column
        int lx = 18, colW = Math.min(250, (int) (w * 0.5) - 26);
        int lw = Math.min(colW, 200), lh = Math.round(lw * (float) Theme.LOGO.h() / Theme.LOGO.w());
        Theme.image(g, Theme.LOGO, lx, 12, lw, lh, 0xFFFFFFFF);
        int y = 12 + lh + 6;
        g.text(font, "Open-weight LT4 sword AI. It lives in this jar.", lx, y, Theme.CREAM, true);
        y += 11;
        g.text(font, "A practice partner. Raw robot aim. For authorized testing only.", lx, y, Theme.FAINT, false);
        y += 16;
        // tabs
        int tx = lx;
        for (Page p : Page.values()) {
            String label = p.name();
            int tw = font.width(label) + 14;
            boolean on = p == page, hot = !on && new Hit(tx, y, tw, 14, null).in(mx, my);
            Theme.panel(g, tx, y, tw, 14, on ? Theme.TEAL : Theme.alpha(Theme.INK2, 0xE0), on ? Theme.BRASS : hot ? Theme.TEAL_L : Theme.TEAL_D);
            g.text(font, label, tx + 7, y + 3, on ? Theme.CREAM : hot ? Theme.CREAM : Theme.DIM, false);
            Page target = p;
            hits.add(new Hit(tx, y, tw, 14, () -> page = target));
            tx += tw + 4;
        }
        y += 22;
        switch (page) {
            case PLAY -> play(g, lx, y, colW, mx, my, ms);
            case ARENAS -> arenas(g, lx, y, colW, mx, my);
            case SETTINGS -> settings(g, lx, y, colW, mx, my);
            case ABOUT -> about(g, lx, y, colW);
        }
        // footer
        AjaxMod m = AjaxMod.get();
        String foot = "build " + m.buildTag().substring(0, 8) + "   ·   " + AjaxMod.keyName(m.playKey) + " play   " + AjaxMod.keyName(m.menuKey)
                + " panel";
        g.text(font, foot, lx, h - 12, Theme.FAINT, false);
        // the MinecraftCombatAI watermark (top right) and the way to the R1 models (bottom right), on every page
        Theme.watermark(g, w - Theme.watermarkWidth() - 10, 10, 235);
        support(g, w, h, mx, my, ms);
        if (!Practice.error.isEmpty()) g.text(font, Practice.error, lx, h - 24, Theme.AMBER, false);
        else if (System.currentTimeMillis() < m.noticeUntil()) g.text(font, m.notice, lx, h - 24, Theme.AMBER, false);
    }

    /**
     * The support card: MinecraftCombatAI's R1 models are the stronger, feature-packed line; buying one is how people support
     * new models like AJAX. Opens the page through Minecraft's own "open this link?" confirmation.
     */
    private void support(GuiGraphicsExtractor g, int w, int h, int mx, int my, long ms) {
        int cw = Math.min(232, (int) (w * 0.4)), ch = 44;
        int x = w - cw - 10, y = h - ch - 10;
        boolean hot = new Hit(x, y, cw, ch, null).in(mx, my);
        float pulse = 0.5f + 0.5f * (float) Math.sin(ms / 420.0);
        Theme.card(g, x, y, cw, ch, Theme.alpha(Theme.mix(0xFF2A1410, Theme.CORAL, hot ? 0.35f : 0.18f), 0xF2), Theme.alpha(Theme.INK, 0xF2),
                true, Theme.mix(Theme.CORAL, Theme.AMBER, hot ? 1f : pulse * 0.5f));
        Theme.image(g, Theme.MARK, x + 6, y + 6, 14, 14, 0xFFFFFFFF);
        Theme.text(g, "WANT EVEN STRONGER AI?", x + 25, y + 6, 1f, Theme.CORAL, true);
        g.text(font, font.plainSubstrByWidth("R1 models: more powerful, more features.", cw - 30), x + 25, y + 17, Theme.CREAM, false);
        String go = (hot ? "> " : "") + "Support us: " + Theme.SUPPORT_SHOWN;
        g.text(font, font.plainSubstrByWidth(go, cw - 30), x + 25, y + 30, hot ? Theme.AMBER : Theme.BRASS, false);
        hits.add(new Hit(x, y, cw, ch, () -> net.minecraft.client.gui.screens.ConfirmLinkScreen.confirmLinkNow(this, Theme.SUPPORT_URL)));
    }

    private int bigCard(GuiGraphicsExtractor g, int x, int y, int w, String title, String line, String chip, int accent, int mx, int my,
                        boolean enabled, Runnable action) {
        int h = 34;
        boolean hot = enabled && new Hit(x, y, w, h, null).in(mx, my);
        int top = enabled ? Theme.alpha(Theme.mix(Theme.TEAL_D, accent, hot ? 0.28f : 0.12f), 0xF0) : Theme.alpha(Theme.INK2, 0xD0);
        Theme.card(g, x, y, w, h, top, Theme.alpha(Theme.INK, 0xF0), hot, accent);
        g.fill(x + 1, y + 2, x + 4, y + h - 1, enabled ? accent : Theme.FAINT);
        Theme.text(g, title, x + 9, y + 6, 1.25f, enabled ? Theme.CREAM : Theme.FAINT, true);
        g.text(font, line, x + 9, y + 21, enabled ? Theme.DIM : Theme.FAINT, false);
        if (chip != null) {
            int cw = font.width(chip) + 8;
            Theme.panel(g, x + w - cw - 5, y + 5, cw, 11, Theme.alpha(Theme.INK, 0xF0), accent);
            g.text(font, chip, x + w - cw - 1, y + 7, accent, false);
        }
        if (enabled) hits.add(new Hit(x, y, w, h, action));
        return y + h + 5;
    }

    private void play(GuiGraphicsExtractor g, int x, int y, int w, int mx, int my, long ms) {
        Minecraft mc = minecraft;
        AjaxMod m = AjaxMod.get();
        boolean inWorld = mc.player != null;
        boolean allowed = inWorld && Gate.allowed(mc);
        boolean host = inWorld && Gate.hostsBots(mc);          // the practice bots need a world on this computer
        boolean arena = PracticeWorld.active;

        // where we are, and what that means for the cards below
        Gate.Where where = Gate.where(mc);
        String note = switch (where) {
            case SINGLEPLAYER -> where.label + ": every practice mode is here.";
            case LAN -> where.label + ": every practice mode is here.";
            case REMOTE -> where.label + ": the bots need a world on this computer, so AJAX fights monsters here.";
        };
        if (!inWorld) note = "Open a world first (or a practice arena).";
        else if (!allowed) note = Gate.refusal(mc);
        g.text(font, font.plainSubstrByWidth(note, w), x, y, inWorld && !allowed ? Theme.AMBER : Theme.FAINT, false);
        y += 15;

        y = bigCard(g, x, y, w, "LET AJAX PLAY", host ? "From your eyes, against MIRROR." : "From your eyes, against the monsters here.",
                AjaxMod.keyName(m.playKey), Theme.CORAL, mx, my, allowed, () -> {
                    if (host) Practice.request(mc, Practice.Mode.RIVAL);
                    if (!m.running()) m.toggle(mc);
                    onClose();
                });
        y = bigCard(g, x, y, w, "SPAR AJAX", host ? "You against him. Rounds reset on a knockout." : "Needs a world on this computer.",
                null, Theme.TEAL_L, mx, my, allowed && host, () -> {
                    if (m.running()) m.stop(mc, "");
                    Practice.request(mc, Practice.Mode.SPAR);
                    onClose();
                });
        y = bigCard(g, x, y, w, "WATCH: AJAX vs MIRROR", arena ? "Two AJAX brains, one arena. You float above." : "Needs a practice arena: see ARENAS.",
                null, Theme.VIOLET, mx, my, allowed && host && arena, () -> {
                    if (m.running()) m.stop(mc, "");
                    Practice.request(mc, Practice.Mode.WATCH);
                    onClose();
                });
        y = bigCard(g, x, y, w, "PRACTICE DUMMY", host ? "AJAX plays for you against a dummy." : "Needs a world on this computer.",
                null, Theme.BRASS, mx, my, allowed && host, () -> {
                    Practice.request(mc, Practice.Mode.DUMMY);
                    if (!m.running()) m.toggle(mc);
                    onClose();
                });
        if (!inWorld) {
            g.text(font, "Open a world first (or ARENAS).", x, y + 2, Theme.AMBER, false);
            return;
        }
        if (!allowed) {
            g.text(font, font.plainSubstrByWidth(Gate.refusal(mc), w), x, y + 2, Theme.AMBER, false);
            return;
        }
        if (Practice.mode != Practice.Mode.NONE || m.running()) {
            int bw = 70;
            boolean hot = new Hit(x, y, bw, 16, null).in(mx, my);
            Theme.card(g, x, y, bw, 16, Theme.alpha(0xFF3A1A16, 0xF0), Theme.alpha(Theme.INK, 0xF0), hot, Theme.CORAL);
            g.text(font, "STOP ALL", x + 14, y + 4, Theme.CORAL, false);
            hits.add(new Hit(x, y, bw, 16, () -> {
                m.stop(mc, "");
                Practice.request(mc, Practice.Mode.NONE);
            }));
            g.text(font, "you " + Practice.youKOs + " - " + Practice.botKOs + "   round " + Practice.round, x + bw + 8, y + 4, Theme.DIM, false);
        }
    }

    private void arenas(GuiGraphicsExtractor g, int x, int y, int w, int mx, int my) {
        for (Arenas a : Arenas.values()) {
            int h = 26;
            boolean hot = new Hit(x, y, w, h, null).in(mx, my), here = PracticeWorld.active && PracticeWorld.current == a;
            Theme.card(g, x, y, w, h, Theme.alpha(Theme.mix(Theme.INK2, a.colour, hot ? 0.3f : 0.14f), 0xF0), Theme.alpha(Theme.INK, 0xF0), hot,
                    a.colour);
            g.fillGradient(x + 1, y + 2, x + 5, y + h - 1, a.colour, a.accent);
            Theme.text(g, a.title, x + 10, y + 4, 1f, Theme.CREAM, true);
            g.text(font, font.plainSubstrByWidth(a.tagline, w - 16), x + 10, y + 15, Theme.DIM, false);
            if (here) g.text(font, "YOU ARE HERE", x + w - font.width("YOU ARE HERE") - 6, y + 4, a.accent, false);
            hits.add(new Hit(x, y, w, h, () -> {
                PracticeWorld.enter(minecraft, a);
                onClose();
            }));
            y += h + 4;
        }
        Theme.wrap(g, "A temporary world with all five arenas. It is deleted when you leave, so nothing piles up in your saves. "
                + "Opening one leaves the world or the server you are in: the arenas are a world on this computer.", x, y + 2, w,
                Theme.FAINT);
    }

    private void settings(GuiGraphicsExtractor g, int x, int y, int w, int mx, int my) {
        AjaxMod m = AjaxMod.get();
        y = toggle(g, x, y, w, "HUD box", m.hudVisible, mx, my, () -> m.setHud(!m.hudVisible));
        y = toggle(g, x, y, w, "Smooth view while he plays (drawing only)", m.config.smoothCamera, mx, my, () -> {
            m.config.smoothCamera = !m.config.smoothCamera;
            m.config.save();
        });
        y = toggle(g, x, y, w, "Fight monsters when no bot is near", m.config.mobs, mx, my, () -> {
            m.config.mobs = !m.config.mobs;
            m.config.save();
        });
        y = toggle(g, x, y, w, "Play in LAN worlds and on servers too", m.config.multiplayer, mx, my, () -> {
            m.config.multiplayer = !m.config.multiplayer;
            m.config.save();
            if (!m.config.multiplayer) m.stop(minecraft, "AJAX is set to singleplayer only.");
        });
        y += 6;
        g.text(font, "Keys (change them in Options > Controls > November AJAX):", x, y, Theme.DIM, false);
        y += 12;
        g.text(font, AjaxMod.keyName(m.playKey) + "  let AJAX play / stop", x + 6, y, Theme.CREAM, false);
        g.text(font, AjaxMod.keyName(m.menuKey) + "  this panel", x + 6, y + 10, Theme.CREAM, false);
        y += 26;
        Theme.wrap(g, "Smooth view only changes what your screen draws between ticks. The turn the game uses is always AJAX's raw turn.",
                x, y, w, Theme.FAINT);
        y += 22;
        Theme.wrap(g, "With the LAN and servers setting on, he also plays in a LAN world this computer hosts and on remote servers. "
                + "He still only fights this mod's practice bots and monsters: For authorized testing only, and he never swings at one.",
                x, y, w, Theme.FAINT);
    }

    private int toggle(GuiGraphicsExtractor g, int x, int y, int w, String label, boolean on, int mx, int my, Runnable flip) {
        boolean hot = new Hit(x, y, w, 18, null).in(mx, my);
        Theme.card(g, x, y, w, 18, Theme.alpha(Theme.INK2, 0xE8), Theme.alpha(Theme.INK, 0xE8), hot, Theme.TEAL_L);
        int kx = x + w - 26;
        Theme.panel(g, kx, y + 4, 20, 10, on ? Theme.TEAL : Theme.alpha(Theme.FAINT, 0x80), on ? Theme.TEAL_L : Theme.FAINT);
        g.fill(on ? kx + 11 : kx + 2, y + 6, on ? kx + 18 : kx + 9, y + 12, on ? Theme.CREAM : Theme.DIM);
        g.text(font, label, x + 6, y + 5, Theme.CREAM, false);
        hits.add(new Hit(x, y, w, 18, flip));
        return y + 22;
    }

    private void about(GuiGraphicsExtractor g, int x, int y, int w) {
        y = Theme.wrap(g, "November AJAX, Unit 11. A giant from Kilnhollow, the first fighter the makers ever gave away: open weights, "
                + "free to anyone.", x, y, w, Theme.CREAM) + 4;
        y = Theme.wrap(g, "In his chest is an hourglass that holds exactly two seconds of sand. That is all he remembers of a fight, "
                + "and all he needs.", x, y, w, Theme.DIM) + 4;
        y = Theme.wrap(g, "He never pretends to be human: his aim is a robot's aim, and he only ever fights this mod's own practice bots "
                + "or hostile monsters. For authorized testing only: in a LAN world or on a server he will not even swing at one.", x, y, w, Theme.DIM) + 8;
        y = Theme.wrap(g, "AJAX is free. MinecraftCombatAI's R1 models are the stronger line: buying one supports us and keeps new "
                + "models like AJAX coming.", x, y, w, Theme.BRASS) + 8;
        String[][] facts = {{"Rank", "LT4"}, {"Memory", "2 s (40 ticks)"}, {"Brain", "small MLP, ~210k weights, in this jar"},
                {"Beats", "Volt"}, {"Still loses to", "Volt 2"}, {"Arrived", "before GTA 6"}};
        for (String[] f : facts) {
            g.text(font, f[0], x, y, Theme.BRASS, false);
            g.text(font, f[1], x + 80, y, Theme.CREAM, false);
            y += 11;
        }
    }
}

