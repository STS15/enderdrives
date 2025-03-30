package com.sts15.enderdrives.network;

import com.sts15.enderdrives.network.packet.*;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.jetbrains.annotations.NotNull;

import static com.sts15.enderdrives.Constants.MOD_ID;

public class NetworkHandler {
    public static void registerPackets(@NotNull RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(MOD_ID)
                .versioned("1.0")
                .optional();

        registrar.playToServer(UpdateFrequencyPacket.TYPE, UpdateFrequencyPacket.STREAM_CODEC, UpdateFrequencyPacket::handle);
        registrar.playToClient(
                UpdateDiskTypeCountPacket.TYPE,
                UpdateDiskTypeCountPacket.STREAM_CODEC.cast(),
                UpdateDiskTypeCountPacket::handle
        );
        registrar.playToServer(RequestDiskTypeCountPacket.TYPE, RequestDiskTypeCountPacket.STREAM_CODEC, RequestDiskTypeCountPacket::handle);
    }

    public static void sendFrequencyUpdateToServer(int frequency, int scope, int transferMode) {
        PacketDistributor.sendToServer(new UpdateFrequencyPacket(frequency, scope, transferMode));
    }

    public static void requestDiskTypeCount(String scopePrefix, int frequency, int typeLimit) {
        PacketDistributor.sendToServer(new RequestDiskTypeCountPacket(scopePrefix, frequency, typeLimit));
    }

    public static void sendToClient(ServerPlayer player, UpdateDiskTypeCountPacket packet) {
        PacketDistributor.sendToPlayer(player, packet);
    }
}