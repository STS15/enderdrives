package com.sts15.enderdrives.network.packet;

import com.sts15.enderdrives.client.ClientConfigCache;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import static com.sts15.enderdrives.Constants.MOD_ID;

public record SyncConfigPacket(int freqMin, int freqMax) implements CustomPacketPayload {

    public static final Type<SyncConfigPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MOD_ID, "sync_config"));

    public static final StreamCodec<FriendlyByteBuf, SyncConfigPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SyncConfigPacket::freqMin,
            ByteBufCodecs.VAR_INT, SyncConfigPacket::freqMax,
            SyncConfigPacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SyncConfigPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ClientConfigCache.update(packet.freqMin, packet.freqMax);
        });
    }
}
