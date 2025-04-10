package com.sts15.enderdrives.network.packet;

import com.sts15.enderdrives.db.TapeDBManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.UUID;
import static com.sts15.enderdrives.Constants.MOD_ID;

public record RequestTapeTypeCountPacket(long mostSigBits, long leastSigBits) implements CustomPacketPayload {

    public static final Type<RequestTapeTypeCountPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MOD_ID, "request_tape_type_count"));

    public static final StreamCodec<FriendlyByteBuf, RequestTapeTypeCountPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_LONG, RequestTapeTypeCountPacket::mostSigBits,
                    ByteBufCodecs.VAR_LONG, RequestTapeTypeCountPacket::leastSigBits,
                    RequestTapeTypeCountPacket::new
            );

    public static void handle(RequestTapeTypeCountPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            UUID id = new UUID(packet.mostSigBits(), packet.leastSigBits());
            int typeCount = TapeDBManager.getTypeCount(id);
            long byteCount = TapeDBManager.getTotalStoredBytes(id);
            PacketDistributor.sendToPlayer((ServerPlayer) context.player(), new UpdateTapeTypeCountPacket(id, typeCount, byteCount));
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
