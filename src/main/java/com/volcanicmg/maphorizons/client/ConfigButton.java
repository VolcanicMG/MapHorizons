package com.volcanicmg.maphorizons.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** Adds a "MapHorizons…" button to another mod's settings screen, opening our config with that screen as parent. */
final class ConfigButton {
    private static final int W = 90, H = 20, MARGIN = 4;

    private ConfigButton() {
    }

    /** Places the button in the first screen corner not covered by an existing widget; skips it if all are taken. */
    static void add(ScreenEvent.Init.Post event, ModContainer container) {
        Screen parent = event.getScreen();
        int right = parent.width - W - MARGIN, bottom = parent.height - H - MARGIN;
        int[][] corners = {{right, bottom}, {right, MARGIN}, {MARGIN, bottom}, {MARGIN, MARGIN}};
        for (int[] c : corners) {
            if (overlaps(event, c[0], c[1])) continue;
            event.addListener(Button.builder(Component.translatable("maphorizons.config.button"), b -> open(container, parent))
                    .bounds(c[0], c[1], W, H).build());
            return;
        }
    }

    private static boolean overlaps(ScreenEvent.Init.Post event, int x, int y) {
        for (GuiEventListener l : event.getListenersList())
            if (l instanceof AbstractWidget w && x < w.getX() + w.getWidth() && w.getX() < x + W
                    && y < w.getY() + w.getHeight() && w.getY() < y + H) return true;
        return false;
    }

    private static void open(ModContainer container, Screen parent) {
        container.getCustomExtension(IConfigScreenFactory.class)
                .ifPresent(f -> Minecraft.getInstance().setScreen(f.createScreen(container, parent)));
    }
}
