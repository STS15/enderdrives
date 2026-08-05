package com.sts15.enderdrives.network;

import com.sts15.enderdrives.items.AbstractEnderDiskItem;
import com.sts15.enderdrives.items.EnderDiskItem;
import com.sts15.enderdrives.items.EnderFluidDiskItem;
import com.sts15.enderdrives.items.TapeDiskItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PacketValidation {
    private static final long REQUEST_INTERVAL_NANOS = 250_000_000L;
    private static final long GLOBAL_WINDOW_NANOS = 1_000_000_000L;
    private static final int GLOBAL_REQUEST_LIMIT = 64;
    private static final int MAX_TRACKED_REQUESTS = 8_192;
    private static final Map<UUID, RequestWindow> GLOBAL_REQUESTS = new ConcurrentHashMap<>();

    private PacketValidation() {
    }

    public static boolean allowRequest(Map<String, Long> requestTimes, ServerPlayer player, String resourceKey) {
        long now = System.nanoTime();
        if (!allowGlobalRequest(player.getUUID(), now)) return false;

        String key = player.getUUID() + "|" + resourceKey;
        Long previous = requestTimes.get(key);
        if (previous != null && now - previous < REQUEST_INTERVAL_NANOS) {
            return false;
        }
        requestTimes.put(key, now);
        if (requestTimes.size() > MAX_TRACKED_REQUESTS) {
            long cutoff = now - 60L * GLOBAL_WINDOW_NANOS;
            requestTimes.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        }
        return true;
    }

    private static boolean allowGlobalRequest(UUID playerId, long now) {
        AtomicBoolean accepted = new AtomicBoolean();
        GLOBAL_REQUESTS.compute(playerId, (id, previous) -> {
            if (previous == null || now - previous.windowStart() >= GLOBAL_WINDOW_NANOS) {
                accepted.set(true);
                return new RequestWindow(now, 1);
            }
            if (previous.count() >= GLOBAL_REQUEST_LIMIT) return previous;
            accepted.set(true);
            return new RequestWindow(previous.windowStart(), previous.count() + 1);
        });
        if (GLOBAL_REQUESTS.size() > MAX_TRACKED_REQUESTS) {
            long cutoff = now - 60L * GLOBAL_WINDOW_NANOS;
            GLOBAL_REQUESTS.entrySet().removeIf(entry -> entry.getValue().windowStart() < cutoff);
        }
        return accepted.get();
    }

    public static ItemStack findEnderDisk(
            ServerPlayer player,
            String scopePrefix,
            int frequency,
            boolean fluid
    ) {
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (matchesEnderDisk(stack, scopePrefix, frequency, fluid)) return stack;
        }
        ItemStack offhand = player.getOffhandItem();
        if (matchesEnderDisk(offhand, scopePrefix, frequency, fluid)) return offhand;

        for (var slot : player.containerMenu.slots) {
            ItemStack stack = slot.getItem();
            if (matchesEnderDisk(stack, scopePrefix, frequency, fluid)) return stack;
        }
        return ItemStack.EMPTY;
    }

    public static boolean hasTape(ServerPlayer player, UUID tapeId) {
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (matchesTape(inventory.getItem(i), tapeId)) return true;
        }
        if (matchesTape(player.getOffhandItem(), tapeId)) return true;

        for (var slot : player.containerMenu.slots) {
            if (matchesTape(slot.getItem(), tapeId)) return true;
        }
        return false;
    }

    private static boolean matchesEnderDisk(
            ItemStack stack,
            String scopePrefix,
            int frequency,
            boolean fluid
    ) {
        boolean expectedType = fluid
                ? stack.getItem() instanceof EnderFluidDiskItem
                : stack.getItem() instanceof EnderDiskItem;
        if (!expectedType || !(stack.getItem() instanceof AbstractEnderDiskItem item)) return false;
        return !item.isDisabled(stack)
                && AbstractEnderDiskItem.isScopeBound(stack)
                && AbstractEnderDiskItem.getFrequency(stack) == frequency
                && AbstractEnderDiskItem.getSafeScopePrefix(stack).equals(scopePrefix);
    }

    private static boolean matchesTape(ItemStack stack, UUID tapeId) {
        return stack.getItem() instanceof TapeDiskItem
                && !TapeDiskItem.isDisabled(stack)
                && tapeId.equals(TapeDiskItem.getTapeId(stack));
    }


    private record RequestWindow(long windowStart, int count) {}
}
