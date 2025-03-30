package com.sts15.enderdrives.db;

import net.minecraft.world.item.ItemStack;
import java.util.*;

public class ClientDiskCache {
    private static final Map<String, DiskTypeInfo> DISK_CACHE = new HashMap<>();

    public static void update(String key, int typeCount, int typeLimit, long totalItemCount, List<ItemStack> topStacks) {
        DISK_CACHE.put(key, new DiskTypeInfo(typeCount, typeLimit, totalItemCount, topStacks));
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
        return !DISK_CACHE.containsKey(key);
    }
}
