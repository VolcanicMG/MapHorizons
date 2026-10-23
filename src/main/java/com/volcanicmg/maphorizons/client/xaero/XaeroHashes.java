package com.volcanicmg.maphorizons.client.xaero;

import com.volcanicmg.maphorizons.client.RegionColours;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.world.MapDimension;
import xaero.map.world.MapWorld;

/** Drops Xaero World Map's cached region highlight hashes so changed regions re-render. Main thread. */
public final class XaeroHashes implements RegionColours.HashClearer {
    private static MapWorld world() {
        WorldMapSession session = WorldMapSession.getCurrentSession();
        MapProcessor processor = session == null ? null : session.getMapProcessor();
        return processor == null ? null : processor.getMapWorld();
    }

    @Override
    public void region(ResourceLocation dim, int rx, int rz) {
        MapWorld world = world();
        MapDimension d = world == null ? null : world.getDimension(ResourceKey.create(Registries.DIMENSION, dim));
        if (d != null && d.getHighlightHandler() != null) d.getHighlightHandler().clearCachedHash(rx, rz);
    }

    @Override
    public void all() {
        MapWorld world = world();
        if (world != null) world.clearAllCachedHighlightHashes();
    }
}
