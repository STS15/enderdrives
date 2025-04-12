package com.sts15.enderdrives.network.packet;

import com.sts15.enderdrives.client.ClientTapeCache;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.UUID;
import static com.sts15.enderdrives.Constants.MOD_ID;

public record UpdateTapeTypeCountPacket(UUID tapeId, int typeCount, long byteCount) implements CustomPacketPayload {

    public static final Type<UpdateTapeTypeCountPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MOD_ID, "update_tape_type_count"));

    public static final StreamCodec<FriendlyByteBuf, UpdateTapeTypeCountPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, packet -> packet.tapeId().toString(),
            ByteBufCodecs.VAR_INT, UpdateTapeTypeCountPacket::typeCount,
            ByteBufCodecs.VAR_LONG, UpdateTapeTypeCountPacket::byteCount,
            (uuidStr, typeCount, byteCount) -> new UpdateTapeTypeCountPacket(UUID.fromString(uuidStr), typeCount, byteCount)
    );


    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(UpdateTapeTypeCountPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ClientTapeCache.put(packet.tapeId(), packet.typeCount(), packet.byteCount());
        });
    }
}
