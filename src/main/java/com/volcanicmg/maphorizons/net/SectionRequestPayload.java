package com.volcanicmg.maphorizons.net;

import com.volcanicmg.maphorizons.MapHorizons;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client to server: DH leaf sections (section detail 6, {@code DhSectionPos}-encoded) the client wants.
 *
 * @param positions at most {@link #MAX} per request
 */
public record SectionRequestPayload(ResourceLocation dim, long[] positions) implements CustomPacketPayload {
    public static final int MAX = 32;
    public static final Type<SectionRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MapHorizons.MODID, "section_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SectionRequestPayload> CODEC =
            CustomPacketPayload.codec(SectionRequestPayload::write, SectionRequestPayload::read);

    private void write(FriendlyByteBuf buf) {
        buf.writeResourceLocation(dim);
        buf.writeLongArray(positions);
    }

    private static SectionRequestPayload read(FriendlyByteBuf buf) {
        ResourceLocation dim = buf.readResourceLocation();
        long[] positions = buf.readLongArray(null, MAX);
        return new SectionRequestPayload(dim, positions);
    }

    @Override
    public Type<SectionRequestPayload> type() {
        return TYPE;
    }
}
