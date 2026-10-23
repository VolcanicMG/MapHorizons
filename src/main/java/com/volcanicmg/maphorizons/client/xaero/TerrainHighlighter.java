package com.volcanicmg.maphorizons.client.xaero;

import com.mojang.logging.LogUtils;
import com.volcanicmg.maphorizons.Config;
import com.volcanicmg.maphorizons.client.Diagnostics;
import com.volcanicmg.maphorizons.client.ExploredClient;
import com.volcanicmg.maphorizons.client.RegionColours;
import com.volcanicmg.maphorizons.client.ShareFetch;
import com.volcanicmg.maphorizons.Bits;
import com.volcanicmg.maphorizons.client.dh.DhSource;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import xaero.map.highlight.ChunkHighlighter;
import xaero.map.region.texture.LeafRegionTexture;

/**
 * Paints DH terrain into undiscovered chunks of Xaero's World Map as an opaque highlight. Built with
 * {@code super(true)} so Xaero creates textures for highlight-only regions. Real Xaero terrain always wins:
 * chunks Xaero has discovered return null. Errors disable it instead of crashing Xaero's threads.
 */
public final class TerrainHighlighter extends ChunkHighlighter {
    public static final TerrainHighlighter INSTANCE = new TerrainHighlighter();
    private static final Logger LOG = LogUtils.getLogger();
    private static final int UNDISCOVERED = 32767;
    private static volatile boolean broken;

    private TerrainHighlighter() {
        super(true);
    }

    private static boolean on() {
        return !broken && !DhSource.isDisabled() && Config.CLIENT_SPEC.isLoaded() && Config.ENABLED.get();
    }

    private static void fail(Throwable e) {
        if (!broken) LOG.error("MapHorizons highlighter failed; terrain painting is off until restart", e);
        broken = true;
    }

    @Override
    public int calculateRegionHash(ResourceKey<Level> dim, int rx, int rz) {
        return on() ? RegionColours.version(dim.location(), rx, rz) : 0;
    }

    @Override
    public boolean regionHasHighlights(ResourceKey<Level> dim, int rx, int rz) {
        try {
            ResourceLocation d = dim.location();
            if (!on()) return false;
            Diagnostics.REGION_QUERIES.incrementAndGet();
            boolean sharing = ShareFetch.enabled();
            // On screen: missing sections here may be fetched from the server.
            if (sharing) ShareFetch.note(d, rx, rz);
            if (!DhSource.hasRegion(d, rx, rz)) {
                Diagnostics.REGION_NO_DH_DATA.incrementAndGet();
                // Keep the region alive while shared sections may still arrive.
                return sharing && Bits.any(ExploredClient.fetchableRegion(d, rx, rz));
            }
            if (!ExploredClient.anyAllowed(d, rx, rz)) {
                Diagnostics.REGION_NOT_ALLOWED.incrementAndGet();
                return false;
            }
            return true;
        } catch (RuntimeException | LinkageError e) {
            fail(e);
            return false;
        }
    }

    @Override
    public boolean chunkIsHighlit(ResourceKey<Level> dim, int cx, int cz) {
        try {
            ResourceLocation d = dim.location();
            return on() && DhSource.hasChunk(d, cx, cz) && ExploredClient.allowed(d, cx, cz);
        } catch (RuntimeException | LinkageError e) {
            fail(e);
            return false;
        }
    }

    @Override
    public int[] getChunkHighlitColor(ResourceKey<Level> dim, int cx, int cz) {
        try {
            ResourceLocation d = dim.location();
            Diagnostics.CHUNK_COLOUR_CALLS.incrementAndGet();
            if (!chunkIsHighlit(dim, cx, cz)) return null;
            LeafRegionTexture tex = LeafTextures.CURRENT.get();
            if (tex != null) {
                if (tex.getHeight((cx & 3) << 4, (cz & 3) << 4) != UNDISCOVERED) return null;
                // Overworld-style dims: surface layer only (player cave mode shows caves, not our surface).
                // Ceiling dims (Nether): Xaero's default view is a cave layer, and we paint the floor, so any layer.
                if (!DhSource.isCeiling(d) && tex.getTileChunk().getInRegion().getCaveLayer() != Integer.MAX_VALUE)
                    return null;
            }
            int[] region = RegionColours.get(d, cx >> 5, cz >> 5);
            if (region == null) return null;
            int x0 = (cx & 31) << 4, z0 = (cz & 31) << 4;
            for (int z = 0; z < 16; z++)
                System.arraycopy(region, (z0 + z) * RegionColours.SIZE + x0, resultStore, z << 4, 16);
            return resultStore;
        } catch (RuntimeException | LinkageError e) {
            fail(e);
            return null;
        }
    }

    @Override
    protected int[] getColors(ResourceKey<Level> dim, int cx, int cz) {
        return null;
    }

    @Override
    public Component getChunkHighlightSubtleTooltip(ResourceKey<Level> dim, int cx, int cz) {
        return null;
    }

    @Override
    public Component getChunkHighlightBluntTooltip(ResourceKey<Level> dim, int cx, int cz) {
        return null;
    }

    @Override
    public Component getBlockHighlightSubtleTooltip(ResourceKey<Level> dim, int x, int z) {
        return null;
    }

    @Override
    public Component getBlockHighlightBluntTooltip(ResourceKey<Level> dim, int x, int z) {
        return null;
    }

    @Override
    public void addMinimapBlockHighlightTooltips(List<Component> list, ResourceKey<Level> dim, int x, int z, int w) {
    }
}
