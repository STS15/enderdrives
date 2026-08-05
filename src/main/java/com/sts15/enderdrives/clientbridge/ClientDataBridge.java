package com.sts15.enderdrives.clientbridge;

import com.sts15.enderdrives.db.DiskTypeInfo;
import com.sts15.enderdrives.db.FluidDiskTypeInfo;

import java.util.List;
import java.util.UUID;

public final class ClientDataBridge {
    private ClientDataBridge() {}

    public interface Handler {
        DiskTypeInfo getItemDiskInfo(
                String scopePrefix,
                int frequency,
                int typeLimit
        );

        FluidDiskTypeInfo getFluidDiskInfo(
                String scopePrefix,
                int frequency,
                int typeLimit
        );

        TapeInfo getTapeInfo(UUID id);
    }

    public record TapeInfo(int typeCount, long byteCount) {}

    private static Handler handler = new Handler() {
        @Override
        public DiskTypeInfo getItemDiskInfo(
                String scopePrefix,
                int frequency,
                int typeLimit
        ) {
            return new DiskTypeInfo(0, typeLimit, 0L, List.of());
        }

        @Override
        public FluidDiskTypeInfo getFluidDiskInfo(
                String scopePrefix,
                int frequency,
                int typeLimit
        ) {
            return new FluidDiskTypeInfo(0, typeLimit, 0L, List.of());
        }

        @Override
        public TapeInfo getTapeInfo(UUID id) {
            return new TapeInfo(0, 0L);
        }
    };

    public static Handler get() {
        return handler;
    }

    public static void set(Handler newHandler) {
        handler = newHandler;
    }
}