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
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

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
                EnderDBManager.flushDeltaBuffer();
                int typeCount = EnderDBManager.getTypeCountInclusive(packet.scopePrefix, packet.frequency);
                long totalCount = EnderDBManager.getTotalItemCountInclusive(packet.scopePrefix, packet.frequency);
                List<ItemStack> topStacks = EnderDBManager.getTopStacks(packet.scopePrefix, packet.frequency, 5);
                NetworkHandler.sendToClient(player, new UpdateDiskTypeCountPacket(packet.scopePrefix, packet.frequency, typeCount, packet.typeLimit(), totalCount, topStacks)
                );
            }
        });
    }


    private int typeLimit() {
        return typeLimit;
    }

}
