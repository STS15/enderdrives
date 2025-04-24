package com.sts15.enderdrives.network;

import com.sts15.enderdrives.network.packet.*;
import com.sts15.enderdrives.screen.FrequencyScope;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

import static com.sts15.enderdrives.Constants.MOD_ID;

public class NetworkHandler {
    public static void registerPackets(@NotNull RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(MOD_ID)
                .versioned("1.0")
                .optional();

        registrar.playToClient(SyncConfigPacket.TYPE, SyncConfigPacket.STREAM_CODEC, SyncConfigPacket::handle);
        registrar.playToClient(SyncDisabledDrivesPacket.TYPE, SyncDisabledDrivesPacket.STREAM_CODEC, SyncDisabledDrivesPacket::handle);
        registrar.playToClient(UpdateDiskTypeCountPacket.TYPE, UpdateDiskTypeCountPacket.STREAM_CODEC.cast(), UpdateDiskTypeCountPacket::handle);
        registrar.playToServer(RequestDiskTypeCountPacket.TYPE, RequestDiskTypeCountPacket.STREAM_CODEC, RequestDiskTypeCountPacket::handle);
        registrar.playToServer(UpdateFrequencyPacket.TYPE, UpdateFrequencyPacket.STREAM_CODEC, UpdateFrequencyPacket::handle);
        registrar.playToServer(RequestTapeTypeCountPacket.TYPE, RequestTapeTypeCountPacket.STREAM_CODEC, RequestTapeTypeCountPacket::handle);
        registrar.playToClient(UpdateTapeTypeCountPacket.TYPE, UpdateTapeTypeCountPacket.STREAM_CODEC.cast(), UpdateTapeTypeCountPacket::handle);

    }

    public static void sendFrequencyUpdateToServer(int frequency, FrequencyScope scope, int transferMode) {
        PacketDistributor.sendToServer(new UpdateFrequencyPacket(frequency, scope, transferMode));
    }

    public static void requestDiskTypeCount(String scopePrefix, int frequency, int typeLimit) {
        PacketDistributor.sendToServer(new RequestDiskTypeCountPacket(scopePrefix, frequency, typeLimit));
    }

    public static void sendToClient(ServerPlayer player, UpdateDiskTypeCountPacket packet) {
        PacketDistributor.sendToPlayer(player, packet);
    }

    public static void sendToClient(ServerPlayer player, CustomPacketPayload packet) {
        PacketDistributor.sendToPlayer(player, packet);
    }

    public static void sendToServer(UUID id) {
        PacketDistributor.sendToServer(new RequestTapeTypeCountPacket(id.getMostSignificantBits(), id.getLeastSignificantBits()));
    }

}
