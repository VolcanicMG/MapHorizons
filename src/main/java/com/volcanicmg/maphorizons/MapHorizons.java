package com.volcanicmg.maphorizons;

import com.volcanicmg.maphorizons.client.ExploredClient;
import com.volcanicmg.maphorizons.net.ExploredPayload;
import com.volcanicmg.maphorizons.server.ExploredSync;
import com.volcanicmg.maphorizons.server.FtbTeamsCompat;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Common entrypoint: server config, payload registration and the exploration tracker. */
@Mod(MapHorizons.MODID)
public final class MapHorizons {
    public static final String MODID = "maphorizons";

    public MapHorizons(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, Config.SERVER_SPEC);
        modBus.addListener(MapHorizons::registerPayloads);
        modBus.addListener(ModConfigEvent.Reloading.class, e -> {
            if (e.getConfig().getSpec() == Config.SERVER_SPEC) ExploredSync.requestFullForAll();
        });
        NeoForge.EVENT_BUS.addListener(ExploredSync::onChunkSent);
        NeoForge.EVENT_BUS.addListener(ExploredSync::onTick);
        NeoForge.EVENT_BUS.addListener(ExploredSync::onLogout);
        if (ModList.get().isLoaded("ftbteams")) FtbTeamsCompat.init();
        else NeoForge.EVENT_BUS.addListener(ExploredSync::onLogin);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        // Optional both ways; the handler lambda keeps client classes unloaded on servers.
        event.registrar("1").optional().playToClient(ExploredPayload.TYPE, ExploredPayload.CODEC,
                (payload, ctx) -> ExploredClient.apply(payload));
    }
}
