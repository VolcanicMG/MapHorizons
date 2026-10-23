package com.volcanicmg.maphorizons.client;

import com.volcanicmg.maphorizons.Bits;
import com.volcanicmg.maphorizons.Config;
import com.volcanicmg.maphorizons.net.ExploredPayload;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/**
 * Which chunks this client may paint, from the server's explored sets. Payloads apply on the main thread
 * copy-on-write; Xaero's threads read the volatile {@link State}. The mask is applied at read time, so a
 * permission change only bumps region versions, colours are not recomputed.
 */
public final class ExploredClient {
    /** {@code owner}: the connection this state came from, so a new server never inherits it. */
    private record State(Object owner, boolean serverPresent, boolean unrestricted, boolean anyoneSent,
                         Map<ResourceLocation, Long2ObjectOpenHashMap<long[]>> mine,
                         Map<ResourceLocation, Long2ObjectOpenHashMap<long[]>> anyone) {
        static final State NONE = new State(null, false, false, false, Map.of(), Map.of());
    }

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    private static volatile State state = State.NONE;

    private ExploredClient() {
    }

    /** On join: whether the server has MapHorizons decides between scoped painting and {@code paintWithoutServer}. */
    public static void onLogin(Object connection, boolean serverHasMod) {
        // Singleplayer can deliver the first payload before this event; keep it, but only from this connection.
        State s = state;
        if (s.owner() != null && s.owner() != connection) s = State.NONE;
        state = new State(connection, serverHasMod || s.serverPresent(), s.unrestricted(), s.anyoneSent(), s.mine(),
                s.anyone());
        RegionColours.permissionsChangedAll();
    }

    public static void clear() {
        state = State.NONE;
        payloadLogged = false;
    }

    private static boolean payloadLogged;

    /** One line for the diagnostics summary. */
    public static String describe() {
        State s = state;
        return "server has MapHorizons=" + s.serverPresent() + ", unrestricted=" + s.unrestricted() + ", anyoneSent="
                + s.anyoneSent() + ", allowed regions=" + s.mine().values().stream().mapToInt(m -> m.size()).sum();
    }

    public static void apply(ExploredPayload p) {
        State s = state;
        Map<ResourceLocation, Long2ObjectOpenHashMap<long[]>> mine = p.full() ? new HashMap<>() : copy(s.mine());
        Map<ResourceLocation, Long2ObjectOpenHashMap<long[]>> anyone = p.full() ? new HashMap<>() : copy(s.anyone());
        put(mine, p.mine());
        put(anyone, p.anyone());
        state = new State(Minecraft.getInstance().getConnection(), true, p.unrestricted(), p.anyoneSent(), mine, anyone);
        if (!payloadLogged) {
            LOG.info("MapHorizons: explored sync received (full={}, unrestricted={}, anyoneSent={}, regions={})",
                    p.full(), p.unrestricted(), p.anyoneSent(), p.mine().stream().mapToInt(r -> r.keys().length).sum());
            payloadLogged = true;
        }
        if (p.full()) {
            RegionColours.permissionsChangedAll();
            return;
        }
        for (var list : java.util.List.of(p.mine(), p.anyone()))
            for (ExploredPayload.Regions r : list)
                for (long key : r.keys())
                    RegionColours.permissionsChanged(r.dim(), Bits.unpackX(key), Bits.unpackZ(key));
    }

    private static Map<ResourceLocation, Long2ObjectOpenHashMap<long[]>> copy(
            Map<ResourceLocation, Long2ObjectOpenHashMap<long[]>> src) {
        Map<ResourceLocation, Long2ObjectOpenHashMap<long[]>> out = new HashMap<>();
        src.forEach((k, v) -> out.put(k, new Long2ObjectOpenHashMap<>(v)));
        return out;
    }

    private static void put(Map<ResourceLocation, Long2ObjectOpenHashMap<long[]>> into,
                            java.util.List<ExploredPayload.Regions> regions) {
        for (ExploredPayload.Regions r : regions) {
            Long2ObjectOpenHashMap<long[]> m = into.computeIfAbsent(r.dim(), k -> new Long2ObjectOpenHashMap<>());
            for (int i = 0; i < r.keys().length; i++)
                m.put(r.keys()[i], java.util.Arrays.copyOfRange(r.words(), i * Bits.WORDS, (i + 1) * Bits.WORDS));
        }
    }

    /** Any thread. */
    public static boolean allowed(ResourceLocation dim, int cx, int cz) {
        State s = state;
        if (!s.serverPresent()) return Config.PAINT_WITHOUT_SERVER.get();
        if (s.unrestricted()) return true;
        long key = Bits.regionKey(cx, cz);
        if (Bits.get(region(s.mine(), dim, key), cx, cz)) return true;
        return s.anyoneSent() && !Bits.get(region(s.anyone(), dim, key), cx, cz);
    }

    /** Any thread: could any chunk of this region be allowed. */
    public static boolean anyAllowed(ResourceLocation dim, int rx, int rz) {
        State s = state;
        if (!s.serverPresent()) return Config.PAINT_WITHOUT_SERVER.get();
        if (s.unrestricted() || s.anyoneSent()) return true;
        return Bits.any(region(s.mine(), dim, Bits.pack(rx, rz)));
    }

    private static long[] region(Map<ResourceLocation, Long2ObjectOpenHashMap<long[]>> m, ResourceLocation dim,
                                 long key) {
        Long2ObjectOpenHashMap<long[]> r = m.get(dim);
        return r == null ? null : r.get(key);
    }
}
