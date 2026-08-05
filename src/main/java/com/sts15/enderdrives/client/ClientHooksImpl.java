package com.sts15.enderdrives.client;

import com.sts15.enderdrives.clientbridge.ClientHooks;
import com.sts15.enderdrives.db.ClientDiskCache;
import com.sts15.enderdrives.db.ClientFluidDiskCache;
import com.sts15.enderdrives.db.DiskTypeInfo;
import com.sts15.enderdrives.db.FluidDiskTypeInfo;
import com.sts15.enderdrives.network.ClientNetworkHandler;
import com.sts15.enderdrives.screen.EnderDiskFrequencyScreen;
import com.sts15.enderdrives.screen.FrequencyScope;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientHooksImpl implements ClientHooks.Handler {
    private static final long REQUEST_INTERVAL_MS = 1_000L;

    private final Map<UUID, Long> tapeRequests = new ConcurrentHashMap<>();

    @Override
    public DiskTypeInfo getItemDiskInfo(
            String scopePrefix,
            int frequency,
            int typeLimit
    ) {
        String key = scopePrefix + "|" + frequency;

        if (ClientNetworkHandler.canSendToServer()
                && ClientDiskCache.shouldRequest(key)) {
            ClientNetworkHandler.requestDiskTypeCount(
                    scopePrefix,
                    frequency,
                    typeLimit
            );
        }

        return ClientDiskCache.get(key);
    }

    @Override
    public FluidDiskTypeInfo getFluidDiskInfo(
            String scopePrefix,
            int frequency,
            int typeLimit
    ) {
        String key = scopePrefix + "|" + frequency;

        if (ClientNetworkHandler.canSendToServer()
                && ClientFluidDiskCache.shouldRequest(key)) {
            ClientNetworkHandler.requestFluidDiskTypeCount(
                    scopePrefix,
                    frequency,
                    typeLimit
            );
        }

        return ClientFluidDiskCache.get(key);
    }

    @Override
    public ClientHooks.TapeInfo getTapeInfo(UUID tapeId) {
        if (tapeId == null) {
            return ClientHooks.TapeInfo.EMPTY;
        }

        long now = System.currentTimeMillis();
        long lastRequest = tapeRequests.getOrDefault(tapeId, 0L);

        if (now - lastRequest >= REQUEST_INTERVAL_MS
                && ClientNetworkHandler.canSendToServer()) {
            ClientNetworkHandler.requestTapeTypeCount(tapeId);
            tapeRequests.put(tapeId, now);
        }

        return new ClientHooks.TapeInfo(
                ClientTapeCache.getTypeCount(tapeId),
                ClientTapeCache.getByteCount(tapeId)
        );
    }

    @Override
    public String getOwnerDisplayName(UUID ownerId) {
        if (ownerId == null) {
            return "";
        }

        var player = Minecraft.getInstance().player;

        if (player != null && ownerId.equals(player.getUUID())) {
            return player.getName().getString();
        }

        return ownerId.toString();
    }

    @Override
    public void openFrequencyScreen(
            int frequency,
            Object scope,
            int transferMode,
            InteractionHand hand,
            Identifier expectedItemId,
            int expectedStackHash
    ) {
        if (!(scope instanceof FrequencyScope frequencyScope)) {
            return;
        }

        EnderDiskFrequencyScreen.open(
                frequency,
                frequencyScope,
                transferMode,
                hand,
                expectedItemId,
                expectedStackHash
        );
    }

    @Override
    public void clearRequestCache() {
        tapeRequests.clear();
    }
}