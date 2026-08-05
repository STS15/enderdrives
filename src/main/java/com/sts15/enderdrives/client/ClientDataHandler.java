package com.sts15.enderdrives.client;

import com.sts15.enderdrives.clientbridge.ClientDataBridge;
import com.sts15.enderdrives.db.ClientDiskCache;
import com.sts15.enderdrives.db.ClientFluidDiskCache;
import com.sts15.enderdrives.db.DiskTypeInfo;
import com.sts15.enderdrives.db.FluidDiskTypeInfo;
import com.sts15.enderdrives.network.ClientNetworkHandler;

import java.util.UUID;

public final class ClientDataHandler implements ClientDataBridge.Handler {

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
    public ClientDataBridge.TapeInfo getTapeInfo(UUID id) {
        if (ClientNetworkHandler.canSendToServer()) {
            ClientNetworkHandler.requestTapeTypeCount(id);
        }

        return new ClientDataBridge.TapeInfo(
                ClientTapeCache.getTypeCount(id),
                ClientTapeCache.getByteCount(id)
        );
    }
}