package com.sts15.enderdrives.network;

import com.sts15.enderdrives.network.packet.RequestDiskTypeCountPacket;
import com.sts15.enderdrives.network.packet.RequestFluidDiskTypeCountPacket;
import com.sts15.enderdrives.network.packet.RequestTapeTypeCountPacket;
import com.sts15.enderdrives.network.packet.UpdateFrequencyPacket;
import com.sts15.enderdrives.screen.FrequencyScope;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.UUID;

public final class ClientNetworkHandler {
    private ClientNetworkHandler() {}

    public static boolean canSendToServer() {
        return Minecraft.getInstance().getConnection() != null;
    }

    public static void sendFrequencyUpdateToServer(
            int frequency,
            FrequencyScope scope,
            int transferMode,
            InteractionHand hand,
            int expectedFrequency,
            FrequencyScope expectedScope,
            int expectedTransferMode,
            Identifier expectedItemId,
            int expectedStackHash
    ) {
        if (!canSendToServer()) return;
        ClientPacketDistributor.sendToServer(new UpdateFrequencyPacket(
                frequency,
                scope,
                transferMode,
                hand,
                expectedFrequency,
                expectedScope,
                expectedTransferMode,
                expectedItemId,
                expectedStackHash
        ));
    }

    public static void requestDiskTypeCount(String scopePrefix, int frequency, int typeLimit) {
        if (!canSendToServer()) return;
        ClientPacketDistributor.sendToServer(
                new RequestDiskTypeCountPacket(scopePrefix, frequency, typeLimit));
    }

    public static void requestFluidDiskTypeCount(String scopePrefix, int frequency, int typeLimit) {
        if (!canSendToServer()) return;
        ClientPacketDistributor.sendToServer(
                new RequestFluidDiskTypeCountPacket(scopePrefix, frequency, typeLimit));
    }

    public static void requestTapeTypeCount(UUID id) {
        if (!canSendToServer()) return;
        ClientPacketDistributor.sendToServer(new RequestTapeTypeCountPacket(
                id.getMostSignificantBits(), id.getLeastSignificantBits()));
    }
}
