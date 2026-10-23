package com.volcanicmg.maphorizons.client;

import com.mojang.logging.LogUtils;
import com.volcanicmg.maphorizons.Bits;
import com.volcanicmg.maphorizons.Config;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

/**
 * LRU of painted regions ({@code int[512*512]} in Xaero packing) plus per-region versions for Xaero's region hash.
 * All DH reads run on one daemon worker so the render thread never waits on SQLite. A version only increases on
 * recompute or permission change, never on eviction, so Xaero keeps already-baked textures.
 */
public final class RegionColours {
    private static final Logger LOG = LogUtils.getLogger();
    public static final int SIZE = 512;

    private record Key(ResourceLocation dim, long region) {
    }

    /**
     * A computed region.
     *
     * @param colours    Xaero-packed pixels, or null when there is nothing to paint
     * @param incomplete some indexed DH sections were empty (not synced or generated yet); retried later
     */
    public record Result(int[] colours, boolean incomplete) {
    }

    // DH network data may arrive without the provider listener firing; retry holes on this delay.
    private static final long RETRY_MS = 30_000;

    /** Computes a region (dim, packed region key). Set by the DH layer. */
    private static volatile BiFunction<ResourceLocation, Long, Result> computer;
    private static volatile HashClearer hashClearer;

    /** Clears Xaero's cached region hashes so changed regions re-render. Main thread only. */
    public interface HashClearer {
        void region(ResourceLocation dim, int rx, int rz);

        void all();
    }

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MapHorizons worker");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    // Negative result: nothing to paint here. Cached so Xaero's re-apply does not trigger a recompute loop.
    private static final int[] EMPTY = new int[0];
    private static final Map<Key, int[]> CACHE = new LinkedHashMap<>(64, 0.75f, true);
    private static final Map<Key, Integer> VERSIONS = new ConcurrentHashMap<>();
    private static final Set<Key> PENDING = ConcurrentHashMap.newKeySet();
    private static final Map<Key, Long> RETRY_AT = new ConcurrentHashMap<>();
    private static volatile int epoch;
    // Bumped by clearAll; a compute started before a clear must not store old-mode colours.
    private static final java.util.concurrent.atomic.AtomicInteger GENERATION = new java.util.concurrent.atomic.AtomicInteger();
    private static boolean failureLogged;

    private RegionColours() {
    }

    /** Wires the DH computer and Xaero hash clearer; only called when both mods are present. */
    public static void install(BiFunction<ResourceLocation, Long, Result> regionComputer, HashClearer clearer) {
        computer = regionComputer;
        hashClearer = clearer;
    }

    /** Runs a task on the DH worker. */
    public static void submit(Runnable task) {
        WORKER.execute(() -> {
            try {
                task.run();
            } catch (RuntimeException | LinkageError e) {
                logOnce(e);
            }
        });
    }

    /** Render thread safe: cached colours, or null after queueing a compute. */
    public static int[] get(ResourceLocation dim, int rx, int rz) {
        Key k = new Key(dim, Bits.pack(rx, rz));
        int[] hit;
        synchronized (CACHE) {
            hit = CACHE.get(k);
        }
        if (hit == null) request(k);
        else {
            Long retry = RETRY_AT.get(k);
            if (retry != null && System.currentTimeMillis() > retry) request(k);
        }
        return hit == EMPTY ? null : hit;
    }

    /**
     * DH data changed: recompute in the background (old colours stay until then) if the region is cached;
     * otherwise just bump the version so Xaero asks for it lazily when it is on screen.
     */
    public static void refresh(ResourceLocation dim, int rx, int rz) {
        Key k = new Key(dim, Bits.pack(rx, rz));
        boolean cached;
        synchronized (CACHE) {
            cached = CACHE.containsKey(k);
        }
        if (cached) request(k);
        else bump(k);
    }

    private static void request(Key k) {
        BiFunction<ResourceLocation, Long, Result> c = computer;
        if (c == null || !PENDING.add(k)) return;
        int generation = GENERATION.get();
        submit(() -> {
            try {
                Result r = c.apply(k.dim(), k.region());
                if (generation != GENERATION.get()) {
                    // Settings changed mid-compute; ask again with the new ones.
                    Minecraft.getInstance().execute(() -> bump(k));
                    return;
                }
                Diagnostics.REGIONS_COMPUTED.incrementAndGet();
                int[] colours = r.colours();
                if (r.incomplete() || colours == null) RETRY_AT.put(k, System.currentTimeMillis() + RETRY_MS);
                else RETRY_AT.remove(k);
                int[] prev;
                synchronized (CACHE) {
                    prev = CACHE.put(k, colours != null ? colours : EMPTY);
                    int max = Config.CACHE_REGIONS.get();
                    var it = CACHE.entrySet().iterator();
                    while (CACHE.size() > max && it.hasNext()) {
                        it.next();
                        it.remove();
                    }
                }
                // Only redraw on a real change; empty-to-empty or an identical retry must not wake Xaero.
                boolean was = prev != null && prev != EMPTY;
                if (colours != null ? !(was && java.util.Arrays.equals(prev, colours)) : was) bump(k);
            } finally {
                PENDING.remove(k);
            }
        });
    }

    public static int version(ResourceLocation dim, int rx, int rz) {
        return VERSIONS.getOrDefault(new Key(dim, Bits.pack(rx, rz)), 0) * 31 + epoch;
    }

    /** Main thread: a region's allowed mask changed. */
    public static void permissionsChanged(ResourceLocation dim, int rx, int rz) {
        bump(new Key(dim, Bits.pack(rx, rz)));
    }

    /** Main thread: everything may look different (full sync, login). */
    public static void permissionsChangedAll() {
        epoch++;
        clearAllHashes();
    }

    /** Main thread: settings changed, drop colours and redraw. */
    public static void clearAll() {
        GENERATION.incrementAndGet();
        synchronized (CACHE) {
            CACHE.clear();
            RETRY_AT.clear();
        }
        permissionsChangedAll();
    }

    /** Drops one dimension's colours, e.g. when DH unloads it. */
    public static void dropDimension(ResourceLocation dim) {
        synchronized (CACHE) {
            CACHE.keySet().removeIf(k -> k.dim().equals(dim));
        }
        RETRY_AT.keySet().removeIf(k -> k.dim().equals(dim));
    }

    private static void bump(Key k) {
        VERSIONS.merge(k, 1, Integer::sum);
        HashClearer h = hashClearer;
        if (h != null)
            Minecraft.getInstance().execute(() -> h.region(k.dim(), Bits.unpackX(k.region()), Bits.unpackZ(k.region())));
    }

    private static void clearAllHashes() {
        HashClearer h = hashClearer;
        if (h != null) Minecraft.getInstance().execute(h::all);
    }

    static void logOnce(Throwable e) {
        if (!failureLogged) LOG.error("MapHorizons terrain painting hit an error; continuing", e);
        failureLogged = true;
    }
}
