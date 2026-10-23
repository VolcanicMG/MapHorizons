package com.volcanicmg.maphorizons.server;

import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.sql.dto.FullDataSourceV2DTO;
import com.seibel.distanthorizons.core.world.AbstractDhWorld;
import com.volcanicmg.maphorizons.Bits;
import com.volcanicmg.maphorizons.Config;
import com.volcanicmg.maphorizons.net.SectionDataPayload;
import com.volcanicmg.maphorizons.net.SectionRequestPayload;
import com.volcanicmg.maphorizons.share.ShareMath;
import com.volcanicmg.maphorizons.share.TokenBucket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

/**
 * Serves DH leaf sections from the server's own DH database to clients that may see them (their scope explored
 * at least one of the section's 16 chunks). All DH server internals live here; only loaded with DH installed.
 * Requests are validated on the server thread, reads and encoding run on one daemon worker, sends go back
 * through the server thread, rate-limited per player and globally.
 */
public final class ShareServer {
    private static final Logger LOG = LogUtils.getLogger();
    private static final byte LEAF = 6;
    private static final int MAX_QUEUE = 256;
    private static final int BATCH = 8;

    private static final class Queue {
        final ArrayDeque<Long> positions = new ArrayDeque<>();
        ResourceLocation dim;
        TokenBucket bucket;
        boolean inFlight;
    }

    private record Read(long pos, byte[] data) {
    }

    private static final Map<UUID, Queue> QUEUES = new HashMap<>();
    private static TokenBucket global;
    private static volatile boolean failed;
    private static volatile boolean bucketsStale;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MapHorizons share");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private ShareServer() {
    }

    /** DH network protocol, so mismatched DH versions never exchange sections; 0 = sharing unavailable. */
    public static int protocol() {
        if (failed) return 0;
        try {
            return DhApi.getNetworkProtocolVersion();
        } catch (RuntimeException | LinkageError e) {
            fail(e);
            return 0;
        }
    }

    private static void fail(Throwable e) {
        if (!failed) LOG.error("MapHorizons: terrain sharing disabled, Distant Horizons access failed", e);
        failed = true;
    }

    /** Any thread: server config reloaded; rebuild the rate limiters with the new rates. */
    public static void resetBuckets() {
        bucketsStale = true;
    }

    /** Server thread. Invalid or over-quota requests are answered "missing" or dropped. */
    public static void onRequest(ServerPlayer player, SectionRequestPayload req) {
        if (failed || !Config.SHARE_TERRAIN.get()) return;
        Queue q = QUEUES.computeIfAbsent(player.getUUID(), k -> new Queue());
        if (!req.dim().equals(q.dim)) {
            // One dimension at a time: the client only fetches for the one it is in.
            q.positions.clear();
            q.dim = req.dim();
        }
        if (q.positions.size() + req.positions().length > MAX_QUEUE) return;
        ExploredData data = ExploredData.get(player.server);
        Set<UUID> sources = ExploredSync.sourcesFor(player);
        List<SectionDataPayload.Section> refused = new ArrayList<>();
        for (long pos : req.positions()) {
            if (DhSectionPos.getDetailLevel(pos) == LEAF
                    && explored(data, sources == null ? data.players() : sources, req.dim(), pos)) q.positions.add(pos);
            else refused.add(new SectionDataPayload.Section(pos, new byte[0]));
        }
        if (!refused.isEmpty()) PacketDistributor.sendToPlayer(player, new SectionDataPayload(req.dim(), refused));
    }

    /** Same rule the client applies: some source explored at least one chunk of the 64x64 section. */
    static boolean explored(ExploredData data, Iterable<UUID> sources, ResourceLocation dim, long pos) {
        int tx = DhSectionPos.getX(pos), tz = DhSectionPos.getZ(pos);
        long key = Bits.tileRegionKey(tx, tz);
        for (UUID s : sources) {
            var regions = data.regions(s, dim);
            if (regions != null && Bits.anyInTile(regions.get(key), tx, tz)) return true;
        }
        return false;
    }

    /** Server thread, every tick: start the next batch for each player whose rate allows it. */
    public static void tick(MinecraftServer server) {
        if (failed || QUEUES.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (bucketsStale) {
            bucketsStale = false;
            global = null;
            QUEUES.values().forEach(q -> q.bucket = null);
        }
        if (global == null) global = new TokenBucket(Config.SHARE_GLOBAL_KBPS.get() * 1024L, now);
        for (Iterator<Map.Entry<UUID, Queue>> it = QUEUES.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Queue> e = it.next();
            Queue q = e.getValue();
            if (server.getPlayerList().getPlayer(e.getKey()) == null) {
                it.remove();
                continue;
            }
            if (q.inFlight || q.positions.isEmpty()) continue;
            if (q.bucket == null) q.bucket = new TokenBucket(Config.SHARE_KBPS.get() * 1024L, now);
            if (!q.bucket.ready(now) || !global.ready(now)) continue;
            long[] batch = new long[Math.min(BATCH, q.positions.size())];
            for (int i = 0; i < batch.length; i++) batch[i] = q.positions.poll();
            q.inFlight = true;
            ResourceLocation dim = q.dim;
            UUID id = e.getKey();
            WORKER.execute(() -> {
                List<Read> reads = read(dim, batch);
                server.execute(() -> deliver(server, id, dim, reads));
            });
        }
    }

    // Worker: stored, already compressed DTOs, re-encoded in DH's network format.
    private static List<Read> read(ResourceLocation dim, long[] positions) {
        List<Read> out = new ArrayList<>(positions.length);
        try {
            IDhLevel level = levelFor(dim);
            for (long pos : positions) {
                byte[] data = new byte[0];
                if (level != null && level.getFullDataProvider() != null) {
                    try (FullDataSourceV2DTO dto = level.getFullDataProvider().repo.getByKey(pos)) {
                        if (dto != null) {
                            ByteBuf buf = Unpooled.buffer();
                            dto.encode(buf);
                            data = new byte[buf.readableBytes()];
                            buf.readBytes(data);
                            buf.release();
                            // A section too big for one packet would disconnect the client: report it missing.
                            data = ShareMath.fitOrMissing(data, SectionDataPayload.MAX_BYTES);
                        }
                    }
                }
                out.add(new Read(pos, data));
            }
        } catch (LinkageError e) {
            fail(e);
        } catch (RuntimeException e) {
            LOG.debug("MapHorizons: reading a shared section failed", e);
        }
        return out;
    }

    private static IDhLevel levelFor(ResourceLocation dim) {
        AbstractDhWorld world = SharedApi.getAbstractDhWorld();
        if (world == null) return null;
        for (IDhLevel l : world.getAllLoadedLevels())
            if (l.getLevelWrapper() != null && l.getLevelWrapper().getWrappedMcObject() instanceof ServerLevel sl
                    && sl.dimension().location().equals(dim)) return l;
        return null;
    }

    // Server thread: split under the packet cap, send, charge the rate limiters.
    private static void deliver(MinecraftServer server, UUID id, ResourceLocation dim, List<Read> reads) {
        Queue q = QUEUES.get(id);
        if (q != null) q.inFlight = false;
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        if (player == null || reads.isEmpty() || !player.connection.hasChannel(SectionDataPayload.TYPE)) return;
        int[] sizes = new int[reads.size()];
        long total = 0;
        for (int i = 0; i < sizes.length; i++) total += sizes[i] = reads.get(i).data().length + 16;
        int start = 0;
        for (int end : ShareMath.splitBySize(sizes, SectionDataPayload.MAX_BYTES)) {
            List<SectionDataPayload.Section> part = new ArrayList<>(end - start);
            for (int i = start; i < end; i++) part.add(new SectionDataPayload.Section(reads.get(i).pos(), reads.get(i).data()));
            PacketDistributor.sendToPlayer(player, new SectionDataPayload(dim, part));
            start = end;
        }
        long now = System.currentTimeMillis();
        if (q != null && q.bucket != null) q.bucket.charge(total, now);
        if (global != null) global.charge(total, now);
    }
}
