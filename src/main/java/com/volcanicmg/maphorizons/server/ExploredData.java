package com.volcanicmg.maphorizons.server;

import com.volcanicmg.maphorizons.Bits;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Chunks each player has been sent, per dimension, as region bitsets. Per-player storage means team changes need
 * no migration. Lives on the overworld as {@code maphorizons_explored}.
 */
public final class ExploredData extends SavedData {
    private static final String NAME = "maphorizons_explored";
    private final Map<UUID, Map<ResourceLocation, Long2ObjectOpenHashMap<long[]>>> players = new HashMap<>();

    public static ExploredData get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(new Factory<>(ExploredData::new, ExploredData::load), NAME);
    }

    /** @return true if the chunk was newly marked */
    public boolean mark(UUID player, ResourceLocation dim, int cx, int cz) {
        long[] words = players.computeIfAbsent(player, k -> new HashMap<>())
                .computeIfAbsent(dim, k -> new Long2ObjectOpenHashMap<>())
                .computeIfAbsent(Bits.regionKey(cx, cz), k -> new long[Bits.WORDS]);
        if (!Bits.set(words, cx, cz)) return false;
        setDirty();
        return true;
    }

    /** @return the player's regions in a dimension, or null */
    public Long2ObjectOpenHashMap<long[]> regions(UUID player, ResourceLocation dim) {
        Map<ResourceLocation, Long2ObjectOpenHashMap<long[]>> dims = players.get(player);
        return dims == null ? null : dims.get(dim);
    }

    public Set<UUID> players() {
        return players.keySet();
    }

    /** @return every dimension anyone explored in */
    public Set<ResourceLocation> dimensions() {
        Set<ResourceLocation> out = new java.util.HashSet<>();
        players.values().forEach(d -> out.addAll(d.keySet()));
        return out;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag all = new CompoundTag();
        players.forEach((uuid, dims) -> {
            CompoundTag p = new CompoundTag();
            dims.forEach((dim, regions) -> {
                long[] keys = new long[regions.size()];
                long[] words = new long[regions.size() * Bits.WORDS];
                int i = 0;
                for (Long2ObjectMap.Entry<long[]> e : regions.long2ObjectEntrySet()) {
                    keys[i] = e.getLongKey();
                    System.arraycopy(e.getValue(), 0, words, i++ * Bits.WORDS, Bits.WORDS);
                }
                CompoundTag d = new CompoundTag();
                d.putLongArray("k", keys);
                d.putLongArray("w", words);
                p.put(dim.toString(), d);
            });
            all.put(uuid.toString(), p);
        });
        tag.put("players", all);
        return tag;
    }

    private static ExploredData load(CompoundTag tag, HolderLookup.Provider registries) {
        ExploredData data = new ExploredData();
        CompoundTag all = tag.getCompound("players");
        for (String id : all.getAllKeys()) {
            CompoundTag p = all.getCompound(id);
            Map<ResourceLocation, Long2ObjectOpenHashMap<long[]>> dims = new HashMap<>();
            for (String dimId : p.getAllKeys()) {
                ResourceLocation dim = ResourceLocation.tryParse(dimId);
                CompoundTag d = p.getCompound(dimId);
                long[] keys = d.getLongArray("k");
                long[] words = d.getLongArray("w");
                if (dim == null || words.length != keys.length * Bits.WORDS) continue;
                Long2ObjectOpenHashMap<long[]> regions = new Long2ObjectOpenHashMap<>(keys.length);
                for (int i = 0; i < keys.length; i++)
                    regions.put(keys[i], java.util.Arrays.copyOfRange(words, i * Bits.WORDS, (i + 1) * Bits.WORDS));
                dims.put(dim, regions);
            }
            try {
                data.players.put(UUID.fromString(id), dims);
            } catch (IllegalArgumentException ignored) {
                // Skip a corrupt key rather than lose the whole file.
            }
        }
        return data;
    }
}
