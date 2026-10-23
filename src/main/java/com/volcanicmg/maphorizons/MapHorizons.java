package com.volcanicmg.maphorizons;

import com.volcanicmg.maphorizons.client.ExploredClient;
import com.volcanicmg.maphorizons.client.ShareFetch;
import com.volcanicmg.maphorizons.net.ExploredPayload;
import com.volcanicmg.maphorizons.net.SectionDataPayload;
import com.volcanicmg.maphorizons.net.SectionRequestPayload;
import com.volcanicmg.maphorizons.server.ShareServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
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
            if (e.getConfig().getSpec() != Config.SERVER_SPEC) return;
            ExploredSync.requestFullForAll();
            if (ModList.get().isLoaded("distanthorizons")) ShareServer.resetBuckets();
        });
        NeoForge.EVENT_BUS.addListener(ExploredSync::onChunkSent);
        NeoForge.EVENT_BUS.addListener(ExploredSync::onTick);
        if (ModList.get().isLoaded("distanthorizons"))
            NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, e -> ShareServer.tick(e.getServer()));
        NeoForge.EVENT_BUS.addListener(ExploredSync::onLogout);
        if (ModList.get().isLoaded("ftbteams")) FtbTeamsCompat.init();
        else NeoForge.EVENT_BUS.addListener(ExploredSync::onLogin);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        // Optional both ways; the handler lambda keeps client classes unloaded on servers.
        event.registrar("1").optional()
                .playToClient(ExploredPayload.TYPE, ExploredPayload.CODEC, (payload, ctx) -> ExploredClient.apply(payload))
                // Both share handlers touch DH classes, so they bail out when DH is absent on that side.
                .playToServer(SectionRequestPayload.TYPE, SectionRequestPayload.CODEC, (payload, ctx) -> {
                    if (ModList.get().isLoaded("distanthorizons") && ctx.player() instanceof ServerPlayer p)
                        ShareServer.onRequest(p, payload);
                })
                .playToClient(SectionDataPayload.TYPE, SectionDataPayload.CODEC, (payload, ctx) -> {
                    if (ModList.get().isLoaded("distanthorizons") && ModList.get().isLoaded("xaeroworldmap"))
                        ShareFetch.onData(payload);
                });
    }
}
