package com.volcanicmg.maphorizons.server;

import com.mojang.logging.LogUtils;
import com.volcanicmg.maphorizons.Bits;
import com.volcanicmg.maphorizons.Config;
import com.volcanicmg.maphorizons.net.ExploredPayload;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

/**
 * Records explored chunks and syncs each player the regions they may see. Full sync on login, team change and
 * server config reload; otherwise every interval only regions that changed for their sources. Exploration only
 * adds bits, so sending a region's whole bitset as a replacement is always correct.
 */
public final class ExploredSync {
    private static final Logger LOG = LogUtils.getLogger();
    // ~480 KB of longs; vanilla caps a clientbound custom payload at 1 MiB.
    private static final int MAX_WEIGHT_PER_PACKET = 60_000;

    /** Set by {@link FtbTeamsCompat}: players whose exploration a viewer draws from; null = no team scoping. */
    static Function<ServerPlayer, Set<UUID>> teamSources;

    private static final Map<UUID, Map<ResourceLocation, LongOpenHashSet>> CHANGED = new HashMap<>();
    private static final Set<UUID> NEEDS_FULL = new HashSet<>();
    private static volatile boolean resyncAll;
    private static int ticks;
    private static boolean failureLogged;

    private ExploredSync() {
    }

    public static void onChunkSent(ChunkWatchEvent.Sent event) {
        // Player and dimension come from the event, not the player's position (they differ mid-teleport).
        ServerPlayer p = event.getPlayer();
        ResourceLocation dim = event.getLevel().dimension().location();
        int cx = event.getPos().x, cz = event.getPos().z;
        if (ExploredData.get(p.server).mark(p.getUUID(), dim, cx, cz))
            CHANGED.computeIfAbsent(p.getUUID(), k -> new HashMap<>())
                    .computeIfAbsent(dim, k -> new LongOpenHashSet()).add(Bits.regionKey(cx, cz));
    }

    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        requestFull(event.getEntity().getUUID());
    }

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        NEEDS_FULL.remove(event.getEntity().getUUID());
    }

    static void requestFull(UUID player) {
        NEEDS_FULL.add(player);
    }

    /** Any thread: team change or config reload; every online player gets a full sync next tick. */
    public static void requestFullForAll() {
        resyncAll = true;
    }

    public static void onTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (resyncAll) {
            resyncAll = false;
            server.getPlayerList().getPlayers().forEach(p -> NEEDS_FULL.add(p.getUUID()));
        }
        boolean interval = ++ticks >= Config.SYNC_INTERVAL_SECONDS.get() * 20;
        if (NEEDS_FULL.isEmpty() && !interval) return;
        guarded(() -> {
            ExploredData data = ExploredData.get(server);
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                boolean full = NEEDS_FULL.remove(p.getUUID());
                if (full || interval && !CHANGED.isEmpty()) sync(p, data, full);
            }
            if (interval) {
                ticks = 0;
                CHANGED.clear();
            }
        });
    }

    // A bug here must never take the server tick down with it.
    private static void guarded(Runnable r) {
        try {
            r.run();
            failureLogged = false;
        } catch (RuntimeException | LinkageError e) {
            if (!failureLogged) LOG.error("MapHorizons sync failed; will keep retrying quietly", e);
            failureLogged = true;
        }
    }

    private static void sync(ServerPlayer viewer, ExploredData data, boolean full) {
        if (!viewer.connection.hasChannel(ExploredPayload.TYPE)) return;
        Set<UUID> sources = Config.TEAM_SCOPED.get() && teamSources != null ? teamSources.apply(viewer) : null;
        boolean unexplored = Config.SHOW_UNEXPLORED_TERRAIN.get();
        if (unexplored && sources == null) {
            // Full atlas: nothing to scope.
            if (full) PacketDistributor.sendToPlayer(viewer, new ExploredPayload(true, true, false, List.of(), List.of()));
            return;
        }
        Collection<UUID> from = sources == null ? data.players() : sources;
        List<ExploredPayload.Regions> mine = collect(data, from, full);
        List<ExploredPayload.Regions> anyone = unexplored ? collect(data, data.players(), full) : List.of();
        if (!full && mine.isEmpty() && anyone.isEmpty()) return;
        send(viewer, full, unexplored, mine, anyone);
    }

    /** OR of the sources' bitsets, for all their regions (full) or only regions changed this interval. */
    private static List<ExploredPayload.Regions> collect(ExploredData data, Collection<UUID> sources, boolean full) {
        Map<ResourceLocation, LongOpenHashSet> keys = new HashMap<>();
        for (UUID s : sources) {
            if (full) {
                for (ResourceLocation dim : data.dimensions()) {
                    Long2ObjectOpenHashMap<long[]> r = data.regions(s, dim);
                    if (r != null) keys.computeIfAbsent(dim, k -> new LongOpenHashSet()).addAll(r.keySet());
                }
            } else {
                Map<ResourceLocation, LongOpenHashSet> changed = CHANGED.get(s);
                if (changed != null)
                    changed.forEach((dim, set) -> keys.computeIfAbsent(dim, k -> new LongOpenHashSet()).addAll(set));
            }
        }
        List<ExploredPayload.Regions> out = new ArrayList<>();
        keys.forEach((dim, set) -> {
            long[] k = set.toLongArray();
            long[] words = new long[k.length * Bits.WORDS];
            long[] tmp = new long[Bits.WORDS];
            for (int i = 0; i < k.length; i++) {
                java.util.Arrays.fill(tmp, 0);
                for (UUID s : sources) {
                    Long2ObjectOpenHashMap<long[]> r = data.regions(s, dim);
                    if (r != null) Bits.or(tmp, r.get(k[i]));
                }
                System.arraycopy(tmp, 0, words, i * Bits.WORDS, Bits.WORDS);
            }
            out.add(new ExploredPayload.Regions(dim, k, words));
        });
        return out;
    }

    private static void send(ServerPlayer viewer, boolean full, boolean anyoneSent, List<ExploredPayload.Regions> mine,
                             List<ExploredPayload.Regions> anyone) {
        List<ExploredPayload.Regions> m = new ArrayList<>(), a = new ArrayList<>();
        int[] weight = {0};
        boolean[] first = {true};
        Runnable flush = () -> {
            PacketDistributor.sendToPlayer(viewer,
                    new ExploredPayload(first[0] && full, false, anyoneSent, List.copyOf(m), List.copyOf(a)));
            first[0] = false;
            m.clear();
            a.clear();
            weight[0] = 0;
        };
        for (int pass = 0; pass < 2; pass++) {
            List<ExploredPayload.Regions> target = pass == 0 ? m : a;
            for (ExploredPayload.Regions r : pass == 0 ? mine : anyone)
                for (ExploredPayload.Regions part : split(r, MAX_WEIGHT_PER_PACKET)) {
                    if (weight[0] > 0 && weight[0] + part.weight() > MAX_WEIGHT_PER_PACKET) flush.run();
                    target.add(part);
                    weight[0] += part.weight();
                }
        }
        if (first[0] || weight[0] > 0) flush.run();
    }

    private static List<ExploredPayload.Regions> split(ExploredPayload.Regions r, int maxWeight) {
        int per = Math.max(1, maxWeight / (Bits.WORDS + 1));
        if (r.keys().length <= per) return List.of(r);
        List<ExploredPayload.Regions> out = new ArrayList<>();
        for (int i = 0; i < r.keys().length; i += per) {
            int end = Math.min(r.keys().length, i + per);
            out.add(new ExploredPayload.Regions(r.dim(), java.util.Arrays.copyOfRange(r.keys(), i, end),
                    java.util.Arrays.copyOfRange(r.words(), i * Bits.WORDS, end * Bits.WORDS)));
        }
        return out;
    }
}
