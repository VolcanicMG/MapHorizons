package com.volcanicmg.maphorizons.client.xaero;

import com.mojang.logging.LogUtils;
import com.volcanicmg.maphorizons.client.TerrainMath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.slf4j.Logger;
import xaero.lib.client.level.block.BlockTextureColorUtils;
import xaero.lib.platform.ClientOnlyServices;

/**
 * Block colours the way Xaero's World Map computes them: its cached top-face texture colour
 * ({@link BlockTextureColorUtils#getBlockTextureColor}) times the biome tint for the block's Xaero tint index.
 * Xaero's full pixel routine ({@code MapBlock.getPixelColour}) needs its tile graph, so only these colour
 * building blocks are used. Xaero's caches are not thread-safe, so lookups run on the main thread, batched,
 * and are memoised here. Any failure switches the session to DH colours.
 */
public final class XaeroColours {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Map<Key, Integer> CACHE = new ConcurrentHashMap<>();
    private static volatile boolean failed;

    /** A block state in a biome; {@code biome} may be null (no tint). */
    public record Key(BlockState state, Holder<Biome> biome) {
    }

    private XaeroColours() {
    }

    public static boolean failed() {
        return failed;
    }

    /** Drops memoised colours: logout (biome holders belong to that connection), resource reload, mode change. */
    public static void clear() {
        CACHE.clear();
    }

    /** New session: give Xaero colours another chance. */
    public static void resetFailure() {
        failed = false;
    }

    /**
     * Worker thread: colours for each key as 0xRRGGBB. Unknown keys are computed in one main-thread hop.
     *
     * @return colours in key order, or null if the main thread did not answer in time (try again later)
     */
    public static int[] resolve(List<Key> keys) {
        if (failed) return null;
        List<Key> missing = new ArrayList<>();
        for (Key k : keys) if (!CACHE.containsKey(k)) missing.add(k);
        if (!missing.isEmpty()) {
            try {
                boolean done = Minecraft.getInstance().submit(() -> {
                    // Disconnected since the request: nothing to colour against, retry later.
                    if (Minecraft.getInstance().level == null) return false;
                    for (Key k : missing) CACHE.put(k, computeOrFallback(k));
                    return true;
                }).get(10, TimeUnit.SECONDS);
                if (!done) return null;
            } catch (TimeoutException | InterruptedException e) {
                return null;
            } catch (ExecutionException e) {
                fail(e.getCause());
                return null;
            }
        }
        int[] out = new int[keys.size()];
        for (int i = 0; i < out.length; i++) out[i] = CACHE.getOrDefault(keys.get(i), 0x7F7F7F);
        return out;
    }

    private static void fail(Throwable e) {
        if (!failed) LOG.warn("MapHorizons: Xaero colour lookup failed; using Distant Horizons colours this session", e);
        failed = true;
    }

    // One bad block (e.g. a modded colour provider that throws) gets its vanilla map colour; only a missing
    // Xaero API (LinkageError) ends Xaero colours for the session.
    private static int computeOrFallback(Key k) {
        try {
            return compute(k);
        } catch (RuntimeException e) {
            LOG.debug("MapHorizons: Xaero colour failed for {}", k.state(), e);
            return k.state().getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
        }
    }

    // Main thread only.
    private static int compute(Key k) {
        Minecraft mc = Minecraft.getInstance();
        BlockTextureColorUtils utils = ClientOnlyServices.PLATFORM.getBlockUtils().getTextureColorUtils();
        int rgb = utils.getBlockTextureColor(k.state(), true, mc.level, BuiltInRegistries.BLOCK, BlockPos.ZERO) & 0xFFFFFF;
        int tintIndex = utils.getBlockTintIndex(k.state());
        if (tintIndex >= 0 && k.biome() != null) {
            int tint = mc.getBlockColors().getColor(k.state(), new BiomeTint(k.state(), k.biome().value()),
                    BlockPos.ZERO, tintIndex);
            if (tint != -1) rgb = TerrainMath.tint(rgb, tint);
        }
        return rgb;
    }

    /** A one-block world in a fixed biome, so vanilla tint providers give that biome's grass/foliage/water. */
    private record BiomeTint(BlockState state, Biome biome) implements BlockAndTintGetter {
        @Override
        public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            return resolver.getColor(biome, pos.getX(), pos.getZ());
        }

        @Override
        public float getShade(Direction direction, boolean shade) {
            return 1f;
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return Minecraft.getInstance().level == null ? null : Minecraft.getInstance().level.getLightEngine();
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return state;
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return state.getFluidState();
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinBuildHeight() {
            return -64;
        }
    }
}
