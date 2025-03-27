package com.sts15.enderdrives.db;

import net.minecraft.world.item.ItemStack;
import java.util.*;

public class ClientDiskCache {
    private static final Map<String, DiskTypeInfo> DISK_CACHE = new HashMap<>();

    public static void update(String key, int typeCount, int typeLimit) {
        DiskTypeInfo info = new DiskTypeInfo(typeCount, typeLimit, List.of());
        DISK_CACHE.put(key, info);
    }

    public static void updateTopStacks(String key, List<ItemStack> topStacks) {
        DiskTypeInfo current = DISK_CACHE.getOrDefault(key, new DiskTypeInfo(0, 0, List.of()));
        DISK_CACHE.put(key, new DiskTypeInfo(current.typeCount(), current.typeLimit(), topStacks));
    }

    public static List<ItemStack> getTopStacks(String scopePrefix, int frequency) {
        String key = scopePrefix + "|" + frequency;
        DiskTypeInfo info = DISK_CACHE.get(key);
        return info != null ? info.topStacks() : List.of();
    }

    public static boolean isEmpty(String scopePrefix, int frequency) {
        List<ItemStack> topStacks = getTopStacks(scopePrefix, frequency);
        return topStacks.isEmpty();
    }

    public static boolean shouldRequest(String key) {
        return !DISK_CACHE.containsKey(key);
    }

    public static DiskTypeInfo get(String key) {
        return DISK_CACHE.getOrDefault(key, new DiskTypeInfo(0, 0, List.of()));
    }
}
