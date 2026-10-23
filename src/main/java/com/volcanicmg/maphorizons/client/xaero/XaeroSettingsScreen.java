package com.volcanicmg.maphorizons.client.xaero;

import net.minecraft.client.gui.screens.Screen;
import xaero.map.gui.GuiWorldMapSettings;

/** Recognises Xaero's World Map settings screen. Only loaded when Xaero's World Map is installed. */
public final class XaeroSettingsScreen {
    private XaeroSettingsScreen() {
    }

    public static boolean is(Screen screen) {
        return screen instanceof GuiWorldMapSettings;
    }
}
