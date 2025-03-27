package com.sts15.enderdrives.network.packet;

import com.sts15.enderdrives.Constants;
import com.sts15.enderdrives.db.EnderDBManager;
import com.sts15.enderdrives.network.NetworkHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public class RequestDiskTypeCountPacket implements CustomPacketPayload {
    public static final Type<RequestDiskTypeCountPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "request_disk_type_count"));

    private final String scopePrefix;
    private final int frequency;
    private final int typeLimit;

    public RequestDiskTypeCountPacket(String scopePrefix, int frequency, int typeLimit) {
        this.scopePrefix = scopePrefix;
        this.frequency = frequency;
        this.typeLimit = typeLimit;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static final StreamCodec<FriendlyByteBuf, RequestDiskTypeCountPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, p -> p.scopePrefix,
            ByteBufCodecs.VAR_INT, p -> p.frequency,
            ByteBufCodecs.VAR_INT, p -> p.typeLimit,
            RequestDiskTypeCountPacket::new
    );

    public static void handle(RequestDiskTypeCountPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                int count = EnderDBManager.getTypeCount(packet.scopePrefix + "[" + packet.frequency + "]");
                int limit = packet.typeLimit();
                NetworkHandler.sendToClient(player, new UpdateDiskTypeCountPacket(packet.scopePrefix, packet.frequency, count, limit));
            }
        });
    }

    private int typeLimit() {
        return typeLimit;
    }

}
