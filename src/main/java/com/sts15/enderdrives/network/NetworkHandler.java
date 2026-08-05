package com.sts15.enderdrives.network;

import com.sts15.enderdrives.network.packet.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.jetbrains.annotations.NotNull;
import static com.sts15.enderdrives.Constants.MOD_ID;

public class NetworkHandler {
    public static void registerPackets(@NotNull RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(MOD_ID)
                .versioned("2.0");

        registrar.playToClient(SyncConfigPacket.TYPE, SyncConfigPacket.STREAM_CODEC, SyncConfigPacket::handle);
        registrar.playToClient(SyncDisabledDrivesPacket.TYPE, SyncDisabledDrivesPacket.STREAM_CODEC, SyncDisabledDrivesPacket::handle);
        registrar.playToClient(UpdateDiskTypeCountPacket.TYPE, UpdateDiskTypeCountPacket.STREAM_CODEC.cast(), UpdateDiskTypeCountPacket::handle);
        registrar.playToServer(RequestDiskTypeCountPacket.TYPE, RequestDiskTypeCountPacket.STREAM_CODEC, RequestDiskTypeCountPacket::handle);
        registrar.playToServer(RequestTapeTypeCountPacket.TYPE, RequestTapeTypeCountPacket.STREAM_CODEC, RequestTapeTypeCountPacket::handle);
        registrar.playToClient(UpdateTapeTypeCountPacket.TYPE, UpdateTapeTypeCountPacket.STREAM_CODEC.cast(), UpdateTapeTypeCountPacket::handle);
        registrar.playToServer(UpdateFrequencyPacket.TYPE, UpdateFrequencyPacket.STREAM_CODEC, UpdateFrequencyPacket::handle);
        registrar.playToServer(RequestFluidDiskTypeCountPacket.TYPE, RequestFluidDiskTypeCountPacket.STREAM_CODEC, RequestFluidDiskTypeCountPacket::handle);
        registrar.playToClient(UpdateFluidDiskTypeCountPacket.TYPE, UpdateFluidDiskTypeCountPacket.STREAM_CODEC.cast(), UpdateFluidDiskTypeCountPacket::handle);
    }

    public static void sendToClient(ServerPlayer player, UpdateDiskTypeCountPacket packet) {
        PacketDistributor.sendToPlayer(player, packet);
    }

    public static void sendToClient(ServerPlayer player, UpdateFluidDiskTypeCountPacket packet) {
        PacketDistributor.sendToPlayer(player, packet);
    }

    public static void sendToClient(ServerPlayer player, CustomPacketPayload packet) {
        PacketDistributor.sendToPlayer(player, packet);
    }

}
