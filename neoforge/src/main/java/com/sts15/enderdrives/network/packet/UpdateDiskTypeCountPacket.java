package com.sts15.enderdrives.network.packet;

import com.sts15.enderdrives.Constants;
import com.sts15.enderdrives.db.ClientDiskCache;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

public record UpdateDiskTypeCountPacket(String scopePrefix, int frequency, int typeCount, int typeLimit, long totalItemCount, List<ItemStack> topStacks) implements CustomPacketPayload {
    public static final Type<UpdateDiskTypeCountPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "update_disk_type_count"));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateDiskTypeCountPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, UpdateDiskTypeCountPacket::scopePrefix,
            ByteBufCodecs.VAR_INT, UpdateDiskTypeCountPacket::frequency,
            ByteBufCodecs.VAR_INT, UpdateDiskTypeCountPacket::typeCount,
            ByteBufCodecs.VAR_INT, UpdateDiskTypeCountPacket::typeLimit,
            ByteBufCodecs.VAR_LONG, UpdateDiskTypeCountPacket::totalItemCount,
            ItemStack.STREAM_CODEC.apply(ByteBufCodecs.list()), UpdateDiskTypeCountPacket::topStacks,
            UpdateDiskTypeCountPacket::new
    );

    public static void handle(UpdateDiskTypeCountPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            String key = packet.scopePrefix() + "|" + packet.frequency();
            ClientDiskCache.update(
                    key,
                    packet.typeCount(),
                    packet.typeLimit(),
                    packet.totalItemCount(),
                    packet.topStacks()
            );
        });
    }
}
