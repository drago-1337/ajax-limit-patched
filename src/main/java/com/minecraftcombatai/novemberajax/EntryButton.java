package com.minecraftcombatai.novemberajax;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/** The way in without keybinds: an AJAX button in the corner of the pause menu and the title screen. */
final class EntryButton extends AbstractButton {
    static final int W = 70, H = 24;

    EntryButton(int x, int y) {
        super(x, y, W, H, Component.literal("AJAX"));
    }

    @Override
    public void onPress(InputWithModifiers input) {
        Minecraft.getInstance().setScreen(new AjaxScreen(Minecraft.getInstance().screen));
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        boolean hot = isHoveredOrFocused();
        boolean on = AjaxMod.get() != null && AjaxMod.get().running();
        int x = getX(), y = getY() - (hot ? 1 : 0);
        Theme.card(g, x, y, W, H, Theme.alpha(Theme.TEAL_D, 0xF0), Theme.alpha(Theme.INK, 0xF0), hot, Theme.CORAL);
        Theme.image(g, Theme.PORTRAIT, x + 2, y + 2, 23, 20, 0xFFFFFFFF);
        Theme.text(g, "AJAX", x + 29, y + 4, 1f, Theme.CREAM, true);
        Theme.text(g, on ? "PLAYING" : "OPEN", x + 29, y + 14, 1f, on ? Theme.CORAL : Theme.BRASS, false);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        defaultButtonNarrationText(out);
    }
}

