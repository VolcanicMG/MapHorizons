package com.volcanicmg.maphorizons.client.dh;

import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelUnloadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.file.fullDatafile.IDataSourceUpdateListenerFunc;
import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.sql.dto.FullDataSourceV2DTO;
import com.seibel.distanthorizons.core.util.objects.DataCorruptedException;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import com.seibel.distanthorizons.core.world.AbstractDhWorld;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.volcanicmg.maphorizons.Bits;
import com.volcanicmg.maphorizons.Config;
import com.volcanicmg.maphorizons.client.Diagnostics;
import com.volcanicmg.maphorizons.client.ExploredClient;
import com.volcanicmg.maphorizons.client.RegionColours;
import com.volcanicmg.maphorizons.client.TerrainMath;
import com.volcanicmg.maphorizons.client.xaero.XaeroColours;
import java.util.ArrayList;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.awt.Color;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import org.slf4j.Logger;

/**
 * Every Distant Horizons internal this mod touches (DH 3.3.x), in one class. Reads the client's LOD database
 * through {@link FullDataSourceProviderV2}: on multiplayer that is the store of LODs received from the server.
 * Any linkage or runtime failure disables DH painting instead of crashing.
 */
public final class DhSource {
    private static final Logger LOG = LogUtils.getLogger();
    private static final byte LEAF = 6;
    private static final int TILES = RegionColours.SIZE / 64;
    // Columns below FEATURES come from DH's rough surface pass (SURFACE_THEN_CHUNKS), stored as blocky squares.
    private static final byte FULL_STEP = EDhApiWorldGenerationStep.FEATURES.value;
    // Blur radius covers DH's largest coarse squares (16 columns).
    private static final int COARSE_BLUR = 8;

    /**
     * One attached DH level. {@code colours} is the client level wrapper (DH's colour lookup only works there);
     * {@code provider} is the LOD store: the client level's remote store in multiplayer, the integrated
     * server's store in singleplayer. {@code ceilingY} is relative to {@code minY}, like DH data-point Y.
     */
    private record Attached(ClientLevel mcLevel, IDhLevel level, IClientLevelWrapper colours,
                            FullDataSourceProviderV2 provider, IDataSourceUpdateListenerFunc<FullDataSourceV2> listener,
                            boolean ceiling, int minY, int ceilingY) {
    }

    private record Dirty(ResourceLocation dim, long pos) {
    }

    private static final Map<ResourceLocation, Attached> LEVELS = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, Long2LongOpenHashMap> INDEX = new ConcurrentHashMap<>();
    private static final Queue<Dirty> DIRTY = new ConcurrentLinkedQueue<>();
    private static volatile boolean disabled;
    private static volatile boolean readFailureLogged;
    private static volatile boolean colourFailureLogged;
    private static volatile boolean insertFailureLogged;
    private static final RegionColours.Result NOTHING = new RegionColours.Result(null, false);
    // Ticks spent failing to attach the current level, to log one "not found" line instead of spam.
    private static int attachTries;
    private static long summaryAt;
    private static final int ATTACH_LOG_AFTER = 200;

    private DhSource() {
    }

    /** Binds the DH unload event. Attaching is polled in {@link #tick}, so event order does not matter. */
    public static void init() {
        try {
            DhApi.events.bind(DhApiLevelUnloadEvent.class, new DhApiLevelUnloadEvent() {
                @Override
                public void onLevelUnload(DhApiEventParam<EventParam> e) {
                    detachWrapper(e.value.levelWrapper);
                }
            });
        } catch (RuntimeException | LinkageError e) {
            disable(e);
        }
    }

    private static void debug(String msg, Object... args) {
        if (Config.DEBUG_LOGGING.get()) LOG.info("[MapHorizons] " + msg, args);
    }

    public static boolean isDisabled() {
        return disabled;
    }

    private static void disable(Throwable e) {
        if (!disabled) LOG.error("MapHorizons: Distant Horizons access failed, terrain painting is off", e);
        disabled = true;
    }

    /** Main thread, every client tick: attach the current level if needed and fold DH updates into the index. */
    public static void tick() {
        if (disabled) return;
        try {
            if (summaryAt != 0 && System.currentTimeMillis() > summaryAt) {
                summaryAt = 0;
                logSummary();
            }
            ClientLevel mc = Minecraft.getInstance().level;
            if (mc != null) {
                Attached a = LEVELS.get(mc.dimension().location());
                if (a == null || a.mcLevel() != mc) tryAttach(mc);
            }
            Map<ResourceLocation, Long2LongOpenHashMap> changed = new HashMap<>();
            Map<ResourceLocation, LongOpenHashSet> regions = new HashMap<>();
            for (Dirty d; (d = DIRTY.poll()) != null; ) {
                Long2LongOpenHashMap idx = changed.computeIfAbsent(d.dim(),
                        k -> new Long2LongOpenHashMap(INDEX.getOrDefault(k, new Long2LongOpenHashMap())));
                int tx = DhSectionPos.getX(d.pos()), tz = DhSectionPos.getZ(d.pos());
                long key = Bits.tileRegionKey(tx, tz);
                idx.put(key, idx.get(key) | Bits.tileBit(tx, tz));
                regions.computeIfAbsent(d.dim(), k -> new LongOpenHashSet()).add(key);
            }
            INDEX.putAll(changed);
            // One refresh per region per tick, however many sections DH streamed in.
            regions.forEach((dim, keys) -> keys.forEach((long key) ->
                    RegionColours.refresh(dim, Bits.unpackX(key), Bits.unpackZ(key))));
            RegionColours.flushDeferred();
        } catch (RuntimeException | LinkageError e) {
            disable(e);
        }
    }

    /**
     * Finds DH's level for the current client level, matched by the dimension of the level's own wrapper
     * (multiplayer: the client level; singleplayer: the integrated server level). Not by
     * {@link IDhClientLevel#getClientLevelWrapper()}: in singleplayer every {@code DhClientServerLevel} returns
     * the current client wrapper, so the Nether or End would match the overworld. Colours use that client
     * wrapper, the only one whose colour lookup works.
     */
    private static void tryAttach(ClientLevel mc) {
        ResourceLocation dim = mc.dimension().location();
        AbstractDhWorld world = SharedApi.getAbstractDhWorld();
        IDhClientLevel found = null;
        StringBuilder seen = new StringBuilder();
        if (world != null)
            for (IDhLevel l : world.getAllLoadedLevels()) {
                seen.append(l.getClass().getSimpleName()).append(' ');
                if (l instanceof IDhClientLevel cl && l.getLevelWrapper() != null
                        && l.getLevelWrapper().getWrappedMcObject() instanceof Level own && own.dimension() == mc.dimension()
                        && cl.getClientLevelWrapper() != null
                        && cl.getClientLevelWrapper().getWrappedMcObject() instanceof ClientLevel cw
                        && cw.dimension() == mc.dimension() && l.getFullDataProvider() != null) {
                    found = cl;
                    break;
                }
            }
        if (found == null) {
            if (++attachTries == ATTACH_LOG_AFTER)
                LOG.info("MapHorizons: no Distant Horizons level for {} yet (DH world {}, loaded levels: [{}]); still trying",
                        dim, world == null ? "not ready" : world.getClass().getSimpleName(), seen.toString().trim());
            return;
        }
        attachTries = 0;
        FullDataSourceProviderV2 provider = found.getFullDataProvider();
        DimensionType type = mc.dimensionType();
        // DH worker threads: only record the section here.
        IDataSourceUpdateListenerFunc<FullDataSourceV2> listener = src -> {
            long pos = src.getPos();
            if (DhSectionPos.getDetailLevel(pos) == LEAF) DIRTY.add(new Dirty(dim, pos));
        };
        Attached a = new Attached(mc, found, found.getClientLevelWrapper(), provider, listener, type.hasCeiling(),
                type.minY(), type.logicalHeight() - 1);
        Attached old = LEVELS.put(dim, a);
        if (old != null) old.provider().removeDataSourceUpdateListener(old.listener());
        provider.addDataSourceUpdateListener(listener);
        Diagnostics.reset();
        summaryAt = System.currentTimeMillis() + 15_000;
        LOG.info("MapHorizons: attached {} to Distant Horizons {} (ceiling={}, minY={}, logicalHeight={})", dim,
                found.getClass().getSimpleName(), type.hasCeiling(), type.minY(), type.logicalHeight());
        RegionColours.submit(() -> buildIndex(dim, a));
    }

    // One line 15 s after attaching, so a blank map can be explained from latest.log.
    private static void logSummary() {
        long computed = Diagnostics.REGIONS_COMPUTED.get();
        String why = computed > 0 ? "painting is running"
                : INDEX.values().stream().allMatch(Long2LongOpenHashMap::isEmpty) ? "no index: DH lists no leaf sections"
                : Diagnostics.REGION_QUERIES.get() == 0 ? "Xaero never asked (open the world map)"
                : Diagnostics.REGION_NOT_ALLOWED.get() > 0 && Diagnostics.CHUNK_COLOUR_CALLS.get() == 0
                ? "no allowed bits: explored mask blocks every region"
                : "regions queried but no chunk colours requested";
        LOG.info("MapHorizons status after 15 s: {}. {}; {}; index regions={}", why, Diagnostics.summary(),
                ExploredClient.describe(), INDEX.values().stream().mapToInt(Long2LongOpenHashMap::size).sum());
    }

    private static void detachWrapper(IDhApiLevelWrapper w) {
        for (Map.Entry<ResourceLocation, Attached> e : LEVELS.entrySet()) {
            Attached a = e.getValue();
            // The server or client wrapper of the level we attached; anything else is not ours.
            if (a.colours() == w || a.level().getLevelWrapper() == w) detach(e.getKey(), a);
        }
    }

    private static void detach(ResourceLocation dim, Attached a) {
        if (!LEVELS.remove(dim, a)) return;
        a.provider().removeDataSourceUpdateListener(a.listener());
        INDEX.remove(dim);
        RegionColours.dropDimension(dim);
        LOG.info("MapHorizons: detached {}", dim);
    }

    /** Main thread, on disconnect: forget every attached level. */
    public static void reset() {
        try {
            LEVELS.forEach(DhSource::detach);
        } catch (RuntimeException | LinkageError e) {
            disable(e);
        }
        attachTries = 0;
    }

    /** Worker: every leaf section in the DH database becomes a tile bit. */
    private static void buildIndex(ResourceLocation dim, Attached a) {
        LongArrayList positions = a.provider().repo.getAllPositions();
        Long2LongOpenHashMap idx = new Long2LongOpenHashMap();
        for (int i = 0; i < positions.size(); i++) {
            long pos = positions.getLong(i);
            if (DhSectionPos.getDetailLevel(pos) != LEAF) continue;
            int tx = DhSectionPos.getX(pos), tz = DhSectionPos.getZ(pos);
            long key = Bits.tileRegionKey(tx, tz);
            idx.put(key, idx.get(key) | Bits.tileBit(tx, tz));
        }
        if (LEVELS.get(dim) != a) return;
        INDEX.put(dim, idx);
        LOG.info("MapHorizons: index built for {}: {} leaf sections, {} regions (provider {}, db {})", dim,
                idx.values().longStream().map(Long::bitCount).sum(), idx.size(), a.provider().getClass().getSimpleName(),
                a.provider().repo.databaseFile);
        // Drop anything (e.g. empty results) computed against a previous attach of this dimension.
        RegionColours.dropDimension(dim);
        RegionColours.permissionsChangedAll();
    }

    /** Any thread: does DH have any leaf data in this Xaero region (512x512 blocks). */
    public static boolean hasRegion(ResourceLocation dim, int rx, int rz) {
        Long2LongOpenHashMap idx = INDEX.get(dim);
        return idx != null && idx.get(Bits.pack(rx, rz)) != 0;
    }

    /** Any thread: does DH have data for the 64x64-block tile containing this chunk. */
    public static boolean hasChunk(ResourceLocation dim, int cx, int cz) {
        Long2LongOpenHashMap idx = INDEX.get(dim);
        if (idx == null) return false;
        int tx = cx >> 2, tz = cz >> 2;
        return (idx.get(Bits.tileRegionKey(tx, tz)) & Bits.tileBit(tx, tz)) != 0;
    }

    public static boolean isCeiling(ResourceLocation dim) {
        Attached a = LEVELS.get(dim);
        return a != null && a.ceiling();
    }

    /**
     * Reads a stored section straight from the DH database. Unlike {@code provider.get}, this never sends DH's
     * sync-on-load request to the server (which rejects far sections). Null if missing.
     */
    private static FullDataSourceV2 read(Attached a, long pos) {
        try (FullDataSourceV2DTO dto = a.provider().repo.getByKey(pos)) {
            return dto == null ? null : dto.createDataSource(a.level().getLevelWrapper(), null);
        } catch (IOException | InterruptedException | DataCorruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    /** DH's network protocol on this client, compared with the server's before fetching; 0 if unknown. */
    public static int protocol() {
        try {
            return disabled ? 0 : DhApi.getNetworkProtocolVersion();
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
    }

    /** The dimension currently attached, or null. Main thread. */
    public static ResourceLocation attachedDimension() {
        ClientLevel mc = Minecraft.getInstance().level;
        if (mc == null) return null;
        Attached a = LEVELS.get(mc.dimension().location());
        return a != null && a.mcLevel() == mc ? mc.dimension().location() : null;
    }

    public static long leafPos(int tx, int tz) {
        return DhSectionPos.encode(LEAF, tx, tz);
    }

    public static double leafCentreX(long pos) {
        return DhSectionPos.getX(pos) * 64 + 32;
    }

    public static double leafCentreZ(long pos) {
        return DhSectionPos.getZ(pos) * 64 + 32;
    }

    /** Any thread: does DH have data for this leaf tile (64x64 blocks). */
    public static boolean hasTile(ResourceLocation dim, int tx, int tz) {
        Long2LongOpenHashMap idx = INDEX.get(dim);
        return idx != null && (idx.get(Bits.tileRegionKey(tx, tz)) & Bits.tileBit(tx, tz)) != 0;
    }

    /**
     * Worker: inserts a section received from the server through DH's own ingest path (merge by generation step,
     * save, update listeners). The listener then indexes and repaints it. Dropped if the level changed.
     */
    public static void insert(ResourceLocation dim, byte[] encoded) {
        Attached a = LEVELS.get(dim);
        if (disabled || a == null) return;
        try (FullDataSourceV2DTO dto = FullDataSourceV2DTO.CreateEmptyDataSourceForDecoding()) {
            dto.decode(Unpooled.wrappedBuffer(encoded));
            if (LEVELS.get(dim) != a) return;
            FullDataSourceV2 src = dto.createDataSource(a.level().getLevelWrapper(), null);
            try {
                a.level().updateDataSourcesAsync(src).whenComplete((v, e) -> src.close());
            } catch (Throwable t) {
                src.close();
                throw t;
            }
        } catch (LinkageError e) {
            disable(e);
        } catch (Exception e) {
            if (!insertFailureLogged) LOG.warn("MapHorizons: a shared Distant Horizons section could not be stored; skipping", e);
            insertFailureLogged = true;
        }
    }

    /** One DH (state, biome) pair; wrappers are interned by DH, so identity equality is fine. */
    private record ColourKey(IDhApiBlockStateWrapper state, IDhApiBiomeWrapper biome) {
    }

    /** One parsed DH column: the point to paint and, under liquid, the floor below it. */
    private record Column(DhApiTerrainDataPoint surface, DhApiTerrainDataPoint floor, boolean liquid, int height,
                          int depth) {
        static Column of(List<DhApiTerrainDataPoint> col, Attached a) {
            if (col == null || col.isEmpty()) return null;
            int size = col.size();
            int[] top = new int[size], bottom = new int[size];
            boolean[] air = new boolean[size], liquid = new boolean[size];
            for (int i = 0; i < size; i++) {
                DhApiTerrainDataPoint p = col.get(i);
                top[i] = p.topYBlockPos;
                bottom[i] = p.bottomYBlockPos;
                air[i] = p.blockStateWrapper == null || p.blockStateWrapper.isAir();
                liquid[i] = !air[i] && p.blockStateWrapper.isLiquid();
            }
            int[] order = TerrainMath.byTopDescending(top);
            int s = TerrainMath.pickSurface(order, top, bottom, air, a.ceiling(), a.ceilingY());
            if (s < 0) return null;
            int f = liquid[s] ? TerrainMath.floorBelow(order, top, air, liquid, s) : -1;
            int depth = liquid[s] ? TerrainMath.liquidDepth(order, top, bottom, air, liquid, s) : 0;
            return new Column(col.get(s), f >= 0 ? col.get(f) : null, liquid[s], top[s], depth);
        }
    }

    /** Per-region scratch: colour-key ids per pixel, filled while sections are open, coloured afterwards. */
    private static final class Canvas {
        final int n = RegionColours.SIZE;
        final int[] height = new int[n * n];
        final int[] surface = new int[n * n];
        final int[] floor = new int[n * n];
        final int[] depth = new int[n * n];
        final boolean[] liquid = new boolean[n * n];
        // DH's rough first pass (generation step below FEATURES): shaded from a smoothed height field.
        final boolean[] coarse = new boolean[n * n];
        boolean anyCoarse;
        // Heights of the row north of and the column west of the region, so edges shade like the interior.
        final int[] north = new int[n];
        final int[] west = new int[n];
        final List<ColourKey> keys = new ArrayList<>();
        final Map<ColourKey, Integer> ids = new HashMap<>();
        final List<Integer> dhColours = new ArrayList<>();

        Canvas() {
            Arrays.fill(height, Integer.MIN_VALUE);
            Arrays.fill(north, Integer.MIN_VALUE);
            Arrays.fill(west, Integer.MIN_VALUE);
        }
    }

    private static boolean xaeroColours() {
        return Config.COLOR_MODE.get() == Config.ColorMode.XAERO && !XaeroColours.failed();
    }

    /** Worker only: reads the region's leaf sections from DH (SQLite) and paints them. */
    public static RegionColours.Result computeRegion(ResourceLocation dim, long regionKey) {
        Attached a = LEVELS.get(dim);
        Long2LongOpenHashMap idx = INDEX.get(dim);
        if (disabled || a == null || idx == null) return NOTHING;
        long mask = idx.get(regionKey);
        if (mask == 0) return NOTHING;
        long started = System.nanoTime();
        boolean xaero = xaeroColours();
        int rx = Bits.unpackX(regionKey), rz = Bits.unpackZ(regionKey);
        Canvas c = new Canvas();
        int emptyTiles = 0;
        try {
            for (int t = 0; t < 64; t++) {
                if ((mask & 1L << t) == 0) continue;
                int tx = rx * TILES + (t % TILES), tz = rz * TILES + (t / TILES);
                // Fresh source per read; must be closed. Missing sections come back empty.
                try (FullDataSourceV2 src = read(a, DhSectionPos.encode(LEAF, tx, tz))) {
                    if (src == null || src.isEmpty) {
                        emptyTiles++;
                        continue;
                    }
                    paintSection(a, src, tx, tz, rx, rz, c, !xaero);
                }
            }
            readApron(a, idx, rx, rz, c);
        } catch (LinkageError e) {
            disable(e);
            return NOTHING;
        } catch (RuntimeException e) {
            // Level unloaded mid-read (dimension change, disconnect): expected, not a DH breakage.
            if (LEVELS.get(dim) != a) return NOTHING;
            if (!readFailureLogged) LOG.warn("MapHorizons: reading a Distant Horizons region failed; skipping it", e);
            readFailureLogged = true;
            return new RegionColours.Result(null, true);
        }
        int[] palette;
        if (xaero) {
            List<XaeroColours.Key> keys = new ArrayList<>(c.keys.size());
            for (ColourKey k : c.keys) keys.add(xaeroKey(k));
            palette = XaeroColours.resolve(keys);
            // Xaero failed for the session: redo this region with DH colours. Timed out: retry later.
            if (palette == null) return XaeroColours.failed() ? computeRegion(dim, regionKey) : new RegionColours.Result(null, true);
        } else {
            palette = c.dhColours.stream().mapToInt(Integer::intValue).toArray();
        }
        int[] out = shadeAndPack(c, palette, xaero);
        int painted = 0;
        int coarse = 0;
        for (int i = 0; i < c.height.length; i++) {
            if (c.height[i] != Integer.MIN_VALUE) painted++;
            if (c.coarse[i]) coarse++;
        }
        // Holes inside painted areas: "empty" = DH has the section indexed but no data in it yet.
        debug("region {} {},{}: {} sections indexed, {} empty, {} columns painted ({} coarse), {} colours ({}), {} ms",
                dim, rx, rz, Long.bitCount(mask), emptyTiles, painted, coarse, c.keys.size(), xaero ? "xaero" : "dh",
                (System.nanoTime() - started) / 1_000_000);
        return new RegionColours.Result(painted > 0 ? out : null, emptyTiles > 0);
    }

    @SuppressWarnings("unchecked")
    private static XaeroColours.Key xaeroKey(ColourKey k) {
        BlockState state = k.state().getWrappedMcObject() instanceof BlockState bs ? bs : Blocks.STONE.defaultBlockState();
        Holder<Biome> biome = k.biome() != null && k.biome().getWrappedMcObject() instanceof Holder<?> h
                ? (Holder<Biome>) h : null;
        return new XaeroColours.Key(state, biome);
    }

    private static int[] shadeAndPack(Canvas c, int[] palette, boolean xaero) {
        int n = c.n;
        double strength = Config.SHADE_STRENGTH.get();
        int[] out = new int[n * n];
        double[] smooth = c.anyCoarse ? TerrainMath.blur(c.height, n, COARSE_BLUR) : null;
        for (int z = 0; z < n; z++)
            for (int x = 0; x < n; x++) {
                int i = z * n + x;
                int h = c.height[i];
                if (h == Integer.MIN_VALUE) continue;
                int rgb = palette[c.surface[i]];
                if (c.liquid[i]) {
                    boolean lava = c.keys.get(c.surface[i]).state().getWrappedMcObject() instanceof BlockState bs
                            && bs.getFluidState().is(FluidTags.LAVA);
                    if (xaero && !lava && c.floor[i] >= 0) rgb = TerrainMath.blendWater(rgb, palette[c.floor[i]], c.depth[i]);
                    else rgb = TerrainMath.darkenLiquid(rgb, c.depth[i]);
                }
                if (c.coarse[i]) {
                    // Gentle gradient across DH's blocky first-pass squares instead of a stair grid.
                    double sN = z > 0 && c.height[i - n] != Integer.MIN_VALUE ? smooth[i - n] : smooth[i];
                    double sW = x > 0 && c.height[i - 1] != Integer.MIN_VALUE ? smooth[i - 1] : smooth[i];
                    out[i] = TerrainMath.pack(TerrainMath.shade(rgb, smooth[i], sN, sW, strength), 255);
                    continue;
                }
                int hN = z > 0 ? c.height[i - n] : c.north[x];
                int hW = x > 0 ? c.height[i - 1] : c.west[z];
                if (hN == Integer.MIN_VALUE) hN = h;
                if (hW == Integer.MIN_VALUE) hW = h;
                out[i] = TerrainMath.pack(TerrainMath.shade(rgb, h, hN, hW, strength), 255);
            }
        return out;
    }

    private static int keyId(Canvas c, Attached a, FullDataSourceV2 src, DhApiTerrainDataPoint p, int x, int z,
                             boolean dh) {
        ColourKey k = new ColourKey(p.blockStateWrapper, p.biomeWrapper);
        Integer id = c.ids.get(k);
        if (id != null) return id;
        id = c.keys.size();
        c.keys.add(k);
        c.ids.put(k, id);
        if (dh) c.dhColours.add(dhColour(a, src, p, x, z));
        return id;
    }

    private static void paintSection(Attached a, FullDataSourceV2 src, int tx, int tz, int rx, int rz, Canvas c,
                                     boolean dh) {
        int w = Math.min(64, src.getWidthInDataColumns());
        int x0 = tx * 64, z0 = tz * 64;
        ByteArrayList steps = src.columnGenerationSteps;
        for (int z = 0; z < w; z++)
            for (int x = 0; x < w; x++) {
                Column col = Column.of(src.getApiDataPointColumn(x, z), a);
                if (col == null) continue;
                int bx = x0 + x, bz = z0 + z;
                int i = (bz - rz * c.n) * c.n + (bx - rx * c.n);
                int si = FullDataSourceV2.relativePosToIndex(x, z);
                if (steps != null && si < steps.size() && steps.getByte(si) < FULL_STEP) {
                    c.coarse[i] = true;
                    c.anyCoarse = true;
                }
                c.height[i] = col.height();
                c.surface[i] = keyId(c, a, src, col.surface(), bx, bz, dh);
                c.liquid[i] = col.liquid();
                c.depth[i] = col.depth();
                c.floor[i] = col.floor() != null ? keyId(c, a, src, col.floor(), bx, bz, dh) : -1;
            }
    }

    /** Surface heights just outside the region's north and west edges, from the neighbouring sections. */
    private static void readApron(Attached a, Long2LongOpenHashMap idx, int rx, int rz, Canvas c) {
        for (int k = 0; k < TILES; k++) {
            edge(a, idx, rx * TILES + k, rz * TILES - 1, true, c.north, k * 64);
            edge(a, idx, rx * TILES - 1, rz * TILES + k, false, c.west, k * 64);
        }
    }

    private static void edge(Attached a, Long2LongOpenHashMap idx, int tx, int tz, boolean southRow, int[] into,
                             int offset) {
        if ((idx.get(Bits.tileRegionKey(tx, tz)) & Bits.tileBit(tx, tz)) == 0) return;
        try (FullDataSourceV2 src = read(a, DhSectionPos.encode(LEAF, tx, tz))) {
            if (src == null || src.isEmpty) return;
            int w = Math.min(64, src.getWidthInDataColumns());
            for (int j = 0; j < w; j++) {
                Column col = southRow ? Column.of(src.getApiDataPointColumn(j, w - 1), a)
                        : Column.of(src.getApiDataPointColumn(w - 1, j), a);
                if (col != null) into[offset + j] = col.height();
            }
        }
    }

    private static int dhColour(Attached a, FullDataSourceV2 src, DhApiTerrainDataPoint p, int x, int z) {
        IDhApiBlockStateWrapper state = p.blockStateWrapper;
        // DH Y is relative to minY and top is exclusive; the colour lookup wants the absolute block.
        DhApiResult<Color> r = a.colours().getBlockColorPreApi(state, p.biomeWrapper, x, p.topYBlockPos - 1 + a.minY(), z, src);
        if (r != null && r.success && r.payload != null) return r.payload.getRGB() & 0xFFFFFF;
        if (!colourFailureLogged)
            LOG.warn("MapHorizons: Distant Horizons block colour failed ({}); using vanilla map colours",
                    r == null ? "null result" : r.message);
        colourFailureLogged = true;
        return state != null && state.getWrappedMcObject() instanceof BlockState bs
                ? bs.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col : 0x7F7F7F;
    }
}
