package com.minecraftcombatai.novemberajax;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** AJAX's colours and the few drawing helpers the panel, HUD and button share. Sage-teal, cream, brass, coral, amber. */
final class Theme {
    static final int INK = 0xFF0A1416, INK2 = 0xFF10252A, PANEL = 0xEE10221F, TEAL = 0xFF1F7A6E, TEAL_D = 0xFF0F3F39,
            TEAL_L = 0xFF4FB9A6, CREAM = 0xFFF0E4C6, BRASS = 0xFFD6A446, CORAL = 0xFFF4664C, AMBER = 0xFFFFB347,
            VIOLET = 0xFF9A6BFF, DIM = 0xFF93ABA6, FAINT = 0xFF5E7874, WHITE = 0xFFFFFFFF;

    private Theme() {}

    record Tex(Identifier id, int w, int h) {}

    static final Tex LOGO = tex("logo", 680, 359), RAYS = tex("rays", 512, 512), HERO = tex("hero", 284, 720),
            GUARD = tex("guard", 420, 720), WAVE = tex("wave", 547, 720), SHOULDER = tex("shoulder", 400, 720),
            RIVAL = tex("rival", 531, 720), PORTRAIT = tex("portrait", 292, 256),
            MARK = tex("mca_mark", 16, 16);

    static final String BRAND = "MinecraftCombatAI", SUPPORT_URL = "https://minecraftcombatai.com/r1", SUPPORT_SHOWN = "minecraftcombatai.com/r1";

    private static Tex tex(String name, int w, int h) {
        return new Tex(Identifier.fromNamespaceAndPath("november_ajax", "textures/gui/" + name + ".png"), w, h);
    }

    /** The whole texture drawn at (x, y), w x h, tinted. */
    static void image(GuiGraphicsExtractor g, Tex t, int x, int y, int w, int h, int tint) {
        g.blit(RenderPipelines.GUI_TEXTURED, t.id(), x, y, 0f, 0f, w, h, t.w(), t.h(), t.w(), t.h(), tint);
    }

    /** The texture at height h, keeping its aspect; returns the width used. */
    static int imageH(GuiGraphicsExtractor g, Tex t, int x, int y, int h, int tint) {
        int w = Math.round((float) t.w() * h / t.h());
        image(g, t, x, y, w, h, tint);
        return w;
    }

    static int alpha(int argb, int a) {
        return (argb & 0x00FFFFFF) | (Math.max(0, Math.min(255, a)) << 24);
    }

    static int mix(int a, int b, float t) {
        int r = 0;
        for (int s = 0; s <= 24; s += 8) {
            int ca = (a >>> s) & 0xFF, cb = (b >>> s) & 0xFF;
            r |= Math.round(ca + (cb - ca) * t) << s;
        }
        return r;
    }

    /** A soft-cornered panel: 1 px edge, the corners cut by a pixel. */
    static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h, int fill, int edge) {
        g.fill(x + 1, y, x + w - 1, y + h, edge);
        g.fill(x, y + 1, x + w, y + h - 1, edge);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
    }

    /** A card: gradient face, brass hairline on top, a coral glow when hot. */
    static void card(GuiGraphicsExtractor g, int x, int y, int w, int h, int top, int bottom, boolean hot, int accent) {
        if (hot) for (int i = 1; i <= 3; i++) g.outline(x - i, y - i, w + 2 * i, h + 2 * i, alpha(accent, 90 - 25 * i));
        panel(g, x, y, w, h, top, hot ? accent : alpha(BRASS, 150));
        g.fillGradient(x + 1, y + 1, x + w - 1, y + h - 1, top, bottom);
        g.fill(x + 2, y + 1, x + w - 2, y + 2, alpha(hot ? accent : BRASS, hot ? 230 : 170));
    }

    /** Text at a scale (vanilla font), optionally with a shadow. */
    static void text(GuiGraphicsExtractor g, String s, int x, int y, float scale, int col, boolean shadow) {
        Font f = Minecraft.getInstance().font;
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        g.text(f, s, 0, 0, col, shadow);
        g.pose().popMatrix();
    }

    static int width(String s, float scale) {
        return Math.round(Minecraft.getInstance().font.width(s) * scale);
    }

    /** Word-wrapped text; returns the y after the last line. */
    static int wrap(GuiGraphicsExtractor g, String s, int x, int y, int w, int col) {
        Font f = Minecraft.getInstance().font;
        for (var line : f.split(net.minecraft.network.chat.Component.literal(s), w)) {
            g.text(f, line, x, y, col, false);
            y += 10;
        }
        return y;
    }

    /**
     * The MinecraftCombatAI watermark, the same everywhere: the site's pixel mark and the name. Returns its width.
     * {@code alpha} 0..255 fades both.
     */
    static int watermark(GuiGraphicsExtractor g, int x, int y, int alpha) {
        int w = 9 + 3 + Minecraft.getInstance().font.width(BRAND);
        image(g, MARK, x, y, 9, 9, alpha(WHITE, alpha));
        g.text(Minecraft.getInstance().font, BRAND, x + 12, y + 1, alpha(CREAM, alpha), true);
        return w;
    }

    static int watermarkWidth() {
        return 12 + Minecraft.getInstance().font.width(BRAND);
    }

    /** A rounded pill (key hints, tabs). */
    static void pill(GuiGraphicsExtractor g, int x, int y, int w, int h, int fill, int edge) {
        panel(g, x, y, w, h, fill, edge);
        g.fill(x, y + 2, x + 1, y + h - 2, alpha(edge, 0));
    }
}

