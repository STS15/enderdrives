package com.sts15.enderdrives.clientbridge;

import com.sts15.enderdrives.db.DiskTypeInfo;
import com.sts15.enderdrives.db.FluidDiskTypeInfo;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;

import java.util.List;
import java.util.UUID;

public final class ClientHooks {
    private static Handler handler = new NoOpHandler();

    private ClientHooks() {
    }

    public static void install(Handler newHandler) {
        handler = newHandler != null ? newHandler : new NoOpHandler();
    }

    public static DiskTypeInfo getItemDiskInfo(
            String scopePrefix,
            int frequency,
            int typeLimit
    ) {
        return handler.getItemDiskInfo(scopePrefix, frequency, typeLimit);
    }

    public static FluidDiskTypeInfo getFluidDiskInfo(
            String scopePrefix,
            int frequency,
            int typeLimit
    ) {
        return handler.getFluidDiskInfo(scopePrefix, frequency, typeLimit);
    }

    public static TapeInfo getTapeInfo(UUID tapeId) {
        return handler.getTapeInfo(tapeId);
    }

    public static String getOwnerDisplayName(UUID ownerId) {
        return handler.getOwnerDisplayName(ownerId);
    }

    public static void openFrequencyScreen(
            int frequency,
            Object scope,
            int transferMode,
            InteractionHand hand,
            Identifier expectedItemId,
            int expectedStackHash
    ) {
        handler.openFrequencyScreen(
                frequency,
                scope,
                transferMode,
                hand,
                expectedItemId,
                expectedStackHash
        );
    }

    public static void clearRequestCache() {
        handler.clearRequestCache();
    }

    public record TapeInfo(int typeCount, long byteCount) {
        public static final TapeInfo EMPTY = new TapeInfo(0, 0L);
    }

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

        TapeInfo getTapeInfo(UUID tapeId);

        String getOwnerDisplayName(UUID ownerId);

        void openFrequencyScreen(
                int frequency,
                Object scope,
                int transferMode,
                InteractionHand hand,
                Identifier expectedItemId,
                int expectedStackHash
        );

        void clearRequestCache();
    }

    private static final class NoOpHandler implements Handler {
        @Override
        public DiskTypeInfo getItemDiskInfo(
                String scopePrefix,
                int frequency,
                int typeLimit
        ) {
            return new DiskTypeInfo(
                    0,
                    typeLimit,
                    0L,
                    List.of()
            );
        }

        @Override
        public FluidDiskTypeInfo getFluidDiskInfo(
                String scopePrefix,
                int frequency,
                int typeLimit
        ) {
            return new FluidDiskTypeInfo(
                    0,
                    typeLimit,
                    0L,
                    List.of()
            );
        }

        @Override
        public TapeInfo getTapeInfo(UUID tapeId) {
            return TapeInfo.EMPTY;
        }

        @Override
        public String getOwnerDisplayName(UUID ownerId) {
            return ownerId != null ? ownerId.toString() : "";
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
            // Dedicated-server fallback: do nothing.
        }

        @Override
        public void clearRequestCache() {
            // Nothing to clear on a dedicated server.
        }
    }
}