package com.volcanicmg.maphorizons.client;

import com.volcanicmg.maphorizons.Config;
import com.volcanicmg.maphorizons.MapHorizons;
import com.volcanicmg.maphorizons.client.dh.DhSource;
import com.volcanicmg.maphorizons.client.xaero.XaeroColours;
import com.volcanicmg.maphorizons.client.xaero.XaeroHashes;
import com.volcanicmg.maphorizons.client.xaero.XaeroSettingsScreen;
import com.volcanicmg.maphorizons.net.ExploredPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/** Client entrypoint: client config, login/logout state and, with DH + Xaero World Map, the terrain pipeline. */
@Mod(value = MapHorizons.MODID, dist = Dist.CLIENT)
public final class MapHorizonsClient {
    public MapHorizonsClient(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, Config.CLIENT_SPEC);
        // Mods list > MapHorizons > Config; shows server settings too when they are editable (singleplayer).
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingIn.class,
                e -> ExploredClient.onLogin(e.getPlayer().connection, e.getPlayer().connection.hasChannel(ExploredPayload.TYPE)));
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, e -> ExploredClient.clear());
        if (ModList.get().isLoaded("xaeroworldmap"))
            NeoForge.EVENT_BUS.addListener(ScreenEvent.Init.Post.class, e -> {
                if (XaeroSettingsScreen.is(e.getScreen())) ConfigButton.add(e, container);
            });
        if (!ModList.get().isLoaded("xaeroworldmap") || !ModList.get().isLoaded("distanthorizons")) return;
        RegionColours.install(DhSource::computeRegion, new XaeroHashes());
        DhSource.init();
        // Reloading fires on the config watcher thread.
        modBus.addListener(ModConfigEvent.Reloading.class, e -> {
            if (e.getConfig().getSpec() == Config.CLIENT_SPEC) Minecraft.getInstance().execute(() -> {
                XaeroColours.clear();
                RegionColours.clearAll();
            });
        });
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> DhSource.tick());
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, e -> {
            DhSource.reset();
            // Biome holders in the colour cache belong to that connection's registries.
            XaeroColours.clear();
        });
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingIn.class, e -> XaeroColours.resetFailure());
        // Resource packs change texture colours.
        modBus.addListener(RegisterClientReloadListenersEvent.class, e -> e.registerReloadListener(
                (ResourceManagerReloadListener) rm -> {
                    XaeroColours.clear();
                    RegionColours.clearAll();
                }));
    }
}
