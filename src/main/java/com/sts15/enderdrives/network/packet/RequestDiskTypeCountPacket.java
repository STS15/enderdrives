package com.sts15.enderdrives.network.packet;

import com.sts15.enderdrives.Constants;
import com.sts15.enderdrives.db.EnderDBManager;
import com.sts15.enderdrives.network.NetworkHandler;
import com.sts15.enderdrives.network.PacketValidation;
import com.sts15.enderdrives.items.EnderDiskItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RequestDiskTypeCountPacket implements CustomPacketPayload {
    private static final Map<String, Long> REQUEST_TIMES = new ConcurrentHashMap<>();
    public static final Type<RequestDiskTypeCountPacket> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "request_disk_type_count"));

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
                if (!PacketValidation.allowRequest(
                        REQUEST_TIMES, player, packet.scopePrefix + "|" + packet.frequency)) return;
                ItemStack disk = PacketValidation.findEnderDisk(player, packet.scopePrefix, packet.frequency, false);
                if (!(disk.getItem() instanceof EnderDiskItem item)) return;

                int typeCount = EnderDBManager.getTypeCountInclusive(packet.scopePrefix, packet.frequency);
                long totalCount = EnderDBManager.getTotalItemCountInclusive(packet.scopePrefix, packet.frequency);
                List<ItemStack> topStacks = EnderDBManager.getTopStacks(packet.scopePrefix, packet.frequency, 5);
                NetworkHandler.sendToClient(player, new UpdateDiskTypeCountPacket(packet.scopePrefix, packet.frequency, typeCount, item.getTypeLimit(), totalCount, topStacks)
                );
            }
        });
    }

}
