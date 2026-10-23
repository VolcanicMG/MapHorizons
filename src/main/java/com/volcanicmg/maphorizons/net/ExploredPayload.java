package com.volcanicmg.maphorizons.net;

import com.volcanicmg.maphorizons.Bits;
import com.volcanicmg.maphorizons.MapHorizons;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client explored-chunk sync.
 *
 * @param full         client drops what it knows before applying
 * @param unrestricted full atlas: paint everything, sets are empty
 * @param anyoneSent   {@code anyone} carries "explored by anyone" (showUnexploredTerrain with team scoping)
 * @param mine         regions the player may see, as replacements
 * @param anyone       regions explored by anyone, as replacements
 */
public record ExploredPayload(boolean full, boolean unrestricted, boolean anyoneSent, List<Regions> mine,
                              List<Regions> anyone) implements CustomPacketPayload {

    /** Region bitsets for one dimension; {@code words.length == keys.length * 16}. */
    public record Regions(ResourceLocation dim, long[] keys, long[] words) {
        public int weight() {
            return keys.length * (Bits.WORDS + 1);
        }
    }

    public static final Type<ExploredPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MapHorizons.MODID, "explored"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ExploredPayload> CODEC =
            CustomPacketPayload.codec(ExploredPayload::write, ExploredPayload::read);

    private void write(FriendlyByteBuf buf) {
        buf.writeBoolean(full);
        buf.writeBoolean(unrestricted);
        buf.writeBoolean(anyoneSent);
        writeList(buf, mine);
        writeList(buf, anyone);
    }

    private static void writeList(FriendlyByteBuf buf, List<Regions> list) {
        buf.writeVarInt(list.size());
        for (Regions r : list) {
            buf.writeResourceLocation(r.dim());
            buf.writeLongArray(r.keys());
            buf.writeLongArray(r.words());
        }
    }

    private static ExploredPayload read(FriendlyByteBuf buf) {
        return new ExploredPayload(buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), readList(buf), readList(buf));
    }

    private static List<Regions> readList(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<Regions> out = new ArrayList<>(Math.min(n, 64));
        for (int i = 0; i < n; i++) {
            ResourceLocation dim = buf.readResourceLocation();
            long[] keys = buf.readLongArray();
            long[] words = buf.readLongArray();
            if (words.length != keys.length * Bits.WORDS) throw new IllegalArgumentException("bad explored payload");
            out.add(new Regions(dim, keys, words));
        }
        return out;
    }

    @Override
    public Type<ExploredPayload> type() {
        return TYPE;
    }
}
