package com.volcanicmg.maphorizons.client;

import com.volcanicmg.maphorizons.Bits;
import com.volcanicmg.maphorizons.Config;
import com.volcanicmg.maphorizons.client.dh.DhSource;
import com.volcanicmg.maphorizons.net.SectionDataPayload;
import com.volcanicmg.maphorizons.net.SectionRequestPayload;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Pulls DH leaf sections the server shares (sections this client may see but its own DH has no data for).
 * Regions Xaero looks at are noted from the highlighter; each tick (main thread) their missing sections are
 * queued nearest first and requested in batches. Received sections go into the client's DH store via DH's
 * own ingest path, which the listener then indexes and repaints. Never in singleplayer.
 */
public final class ShareFetch {
    private static final int MAX_IN_FLIGHT = 64;
    private static final long REQUEST_TIMEOUT_MS = 60_000;
    private static final long RESCAN_MS = 30_000;
    private static final int TILES = 8;

    private record Noted(ResourceLocation dim, long region) {
    }

    private static final Set<Noted> NOTED = ConcurrentHashMap.newKeySet();
    // Main thread only below.
    private static final Set<Noted> SCANNED = new java.util.HashSet<>();
    private static final LongOpenHashSet PENDING = new LongOpenHashSet();
    private static final Long2LongOpenHashMap REQUESTED = new Long2LongOpenHashMap();
    private static final LongOpenHashSet MISSING = new LongOpenHashSet();
    // Delivered this session: never fetched again, even if DH has not indexed it yet (or never does).
    private static final LongOpenHashSet DELIVERED = new LongOpenHashSet();
    private static final int MAX_PENDING = 4096;
    // PENDING sorted nearest first; rebuilt only when scans add sections.
    private static final LongArrayList ORDER = new LongArrayList();
    private static boolean orderDirty;
    private static ResourceLocation dim;
    private static long nextRescan;

    private ShareFetch() {
    }

    /** Any thread: sharing is on with a matching DH protocol, in multiplayer. */
    public static boolean enabled() {
        int p = ExploredClient.shareProtocol();
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener c = mc.getConnection();
        return p != 0 && p == DhSource.protocol() && !mc.hasSingleplayerServer() && c != null
                && c.hasChannel(SectionRequestPayload.TYPE);
    }

    /** Any thread (Xaero's): a region is on screen; its missing sections may be fetched. */
    public static void note(ResourceLocation d, int rx, int rz) {
        NOTED.add(new Noted(d, Bits.pack(rx, rz)));
    }

    /** Disconnect or dimension change. */
    public static void reset() {
        NOTED.clear();
        SCANNED.clear();
        PENDING.clear();
        REQUESTED.clear();
        MISSING.clear();
        DELIVERED.clear();
        ORDER.clear();
        orderDirty = false;
        dim = null;
    }

    /** Main thread, every client tick. */
    public static void tick() {
        ResourceLocation current = DhSource.attachedDimension();
        if (current == null || !enabled()) return;
        if (!current.equals(dim)) {
            reset();
            dim = current;
        }
        long now = System.currentTimeMillis();
        if (now > nextRescan) {
            // Exploration grows and requests expire; look at the same regions again.
            nextRescan = now + RESCAN_MS;
            NOTED.clear();
            SCANNED.clear();
            REQUESTED.long2LongEntrySet().removeIf(e -> now - e.getLongValue() > REQUEST_TIMEOUT_MS);
        }
        for (Noted n : NOTED) if (n.dim().equals(dim) && SCANNED.add(n)) scan(n);
        send();
    }

    private static void scan(Noted n) {
        int rx = Bits.unpackX(n.region()), rz = Bits.unpackZ(n.region());
        long[] words = ExploredClient.fetchableRegion(dim, rx, rz);
        var player = Minecraft.getInstance().player;
        if (!Bits.any(words) || player == null) return;
        double max = (double) Config.FETCH_MAX_DISTANCE.get() * Config.FETCH_MAX_DISTANCE.get();
        for (int t = 0; t < TILES * TILES; t++) {
            int tx = rx * TILES + t % TILES, tz = rz * TILES + t / TILES;
            if (!Bits.anyInTile(words, tx, tz) || DhSource.hasTile(dim, tx, tz)) continue;
            long pos = DhSource.leafPos(tx, tz);
            if (REQUESTED.containsKey(pos) || MISSING.contains(pos) || DELIVERED.contains(pos)) continue;
            if (dist(pos, player.getX(), player.getZ()) > max) continue;
            if (PENDING.add(pos)) orderDirty = true;
        }
    }

    private static void send() {
        var player = Minecraft.getInstance().player;
        if (player == null || PENDING.isEmpty() || REQUESTED.size() >= MAX_IN_FLIGHT) return;
        if (orderDirty) {
            double px = player.getX(), pz = player.getZ();
            ORDER.clear();
            ORDER.addAll(PENDING);
            ORDER.sort((a, b) -> Double.compare(dist(a, px, pz), dist(b, px, pz)));
            // Keep the nearest; the rest are found again on the next rescan.
            while (ORDER.size() > MAX_PENDING) PENDING.remove(ORDER.removeLong(ORDER.size() - 1));
            orderDirty = false;
        }
        long now = System.currentTimeMillis();
        int i = 0;
        while (i < ORDER.size() && REQUESTED.size() < MAX_IN_FLIGHT) {
            int n = Math.min(SectionRequestPayload.MAX, Math.min(ORDER.size() - i, MAX_IN_FLIGHT - REQUESTED.size()));
            long[] batch = new long[n];
            for (int j = 0; j < n; j++) {
                batch[j] = ORDER.getLong(i + j);
                PENDING.remove(batch[j]);
                REQUESTED.put(batch[j], now);
            }
            PacketDistributor.sendToServer(new SectionRequestPayload(dim, batch));
            i += n;
        }
        ORDER.removeElements(0, i);
    }

    private static double dist(long pos, double px, double pz) {
        double dx = DhSource.leafCentreX(pos) - px, dz = DhSource.leafCentreZ(pos) - pz;
        return dx * dx + dz * dz;
    }

    /** Main thread (payload handler): store received sections on the worker, remember missing ones. */
    public static void onData(SectionDataPayload p) {
        if (!p.dim().equals(dim)) return;
        for (SectionDataPayload.Section s : p.sections()) {
            REQUESTED.remove(s.pos());
            if (s.data().length == 0) MISSING.add(s.pos());
            else if (DELIVERED.add(s.pos())) RegionColours.submit(() -> DhSource.insert(p.dim(), s.data()));
        }
    }
}
