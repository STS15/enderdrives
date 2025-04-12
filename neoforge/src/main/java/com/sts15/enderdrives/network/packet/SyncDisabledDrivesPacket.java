package com.sts15.enderdrives.network.packet;

import com.sts15.enderdrives.client.ClientConfigCache;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import static com.sts15.enderdrives.Constants.MOD_ID;

public class SyncDisabledDrivesPacket implements CustomPacketPayload {

    public static final Type<SyncDisabledDrivesPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MOD_ID, "sync_disabled_drives"));

    private final int driveBitmask;

    public SyncDisabledDrivesPacket(int driveBitmask) {
        this.driveBitmask = driveBitmask;
    }

    public static final StreamCodec<ByteBuf, SyncDisabledDrivesPacket> STREAM_CODEC =
            ByteBufCodecs.VAR_INT.map(
                    SyncDisabledDrivesPacket::new,
                    p -> p.driveBitmask
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SyncDisabledDrivesPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> ClientConfigCache.setDriveBitmask(packet.driveBitmask));
    }
}
