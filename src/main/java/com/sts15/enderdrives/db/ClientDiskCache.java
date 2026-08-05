package com.sts15.enderdrives.db;

import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ClientDiskCache {
    private static final long REQUEST_INTERVAL_MS = 1_000L;
    private static final Map<String, DiskTypeInfo> DISK_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_REQUEST = new ConcurrentHashMap<>();

    public static void update(String key, int typeCount, int typeLimit, long totalItemCount, List<ItemStack> topStacks) {
        DISK_CACHE.put(key, new DiskTypeInfo(
                typeCount, typeLimit, totalItemCount, List.copyOf(topStacks)));
    }

    public static DiskTypeInfo get(String key) {
        return DISK_CACHE.getOrDefault(key, new DiskTypeInfo(0, 0, 0L, List.of()));
    }

    public static List<ItemStack> getTopStacks(String scopePrefix, int frequency) {
        return get(scopePrefix + "|" + frequency).topStacks();
    }

    public static boolean isEmpty(String scopePrefix, int frequency) {
        return getTopStacks(scopePrefix, frequency).isEmpty();
    }

    public static boolean shouldRequest(String key) {
        long now = System.currentTimeMillis();
        return LAST_REQUEST.compute(key, (ignored, lastRequest) ->
                lastRequest == null || now - lastRequest >= REQUEST_INTERVAL_MS ? now : lastRequest) == now;
    }

    public static void clear() {
        DISK_CACHE.clear();
        LAST_REQUEST.clear();
    }
}
