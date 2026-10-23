package com.volcanicmg.maphorizons.net;

import com.volcanicmg.maphorizons.MapHorizons;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: requested DH leaf sections in DH's own network format ({@code FullDataSourceV2DTO.encode}).
 * An empty {@code data} means the server has no data for that section.
 */
public record SectionDataPayload(ResourceLocation dim, List<Section> sections) implements CustomPacketPayload {
    /** Keeps each packet well under the 1 MiB clientbound custom payload cap. */
    public static final int MAX_BYTES = 480 * 1024;

    public record Section(long pos, byte[] data) {
    }

    public static final Type<SectionDataPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MapHorizons.MODID, "section_data"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SectionDataPayload> CODEC =
            CustomPacketPayload.codec(SectionDataPayload::write, SectionDataPayload::read);

    private void write(FriendlyByteBuf buf) {
        buf.writeResourceLocation(dim);
        buf.writeVarInt(sections.size());
        for (Section s : sections) {
            buf.writeLong(s.pos());
            buf.writeByteArray(s.data());
        }
    }

    private static SectionDataPayload read(FriendlyByteBuf buf) {
        ResourceLocation dim = buf.readResourceLocation();
        int n = buf.readVarInt();
        if (n > SectionRequestPayload.MAX * 2) throw new IllegalArgumentException("too many sections");
        List<Section> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(new Section(buf.readLong(), buf.readByteArray(MAX_BYTES)));
        return new SectionDataPayload(dim, out);
    }

    @Override
    public Type<SectionDataPayload> type() {
        return TYPE;
    }
}
