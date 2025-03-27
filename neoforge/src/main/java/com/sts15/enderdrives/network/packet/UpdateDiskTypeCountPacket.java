package com.sts15.enderdrives.network.packet;

import com.sts15.enderdrives.Constants;
import com.sts15.enderdrives.db.ClientDiskCache;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public class UpdateDiskTypeCountPacket implements CustomPacketPayload {
    public static final Type<UpdateDiskTypeCountPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "update_disk_type_count"));

    private final String scopePrefix;
    private final int frequency;
    private final int typeCount;
    private final int typeLimit;

    public UpdateDiskTypeCountPacket(String scopePrefix, int frequency, int typeCount, int typeLimit) {
        this.scopePrefix = scopePrefix;
        this.frequency = frequency;
        this.typeCount = typeCount;
        this.typeLimit = typeLimit;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static final StreamCodec<FriendlyByteBuf, UpdateDiskTypeCountPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, p -> p.scopePrefix,
            ByteBufCodecs.VAR_INT,     p -> p.frequency,
            ByteBufCodecs.VAR_INT,     p -> p.typeCount,
            ByteBufCodecs.VAR_INT,     p -> p.typeLimit,
            UpdateDiskTypeCountPacket::new
    );

    public static void handle(UpdateDiskTypeCountPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            String key = packet.scopePrefix + "|" + packet.frequency;
            ClientDiskCache.update(key, packet.typeCount, packet.typeLimit);
        });
    }

}
