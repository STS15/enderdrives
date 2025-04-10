package com.sts15.enderdrives.client;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ClientTapeCache {
    private static final Map<UUID, CachedTapeInfo> cache = new ConcurrentHashMap<>();

    public static void put(UUID id, int typeCount, long byteCount) {
        cache.put(id, new CachedTapeInfo(typeCount, byteCount));
    }

    public static int getTypeCount(UUID id) {
        return cache.getOrDefault(id, CachedTapeInfo.EMPTY).typeCount;
    }

    public static long getByteCount(UUID id) {
        return cache.getOrDefault(id, CachedTapeInfo.EMPTY).byteCount;
    }

    private record CachedTapeInfo(int typeCount, long byteCount) {
        public static final CachedTapeInfo EMPTY = new CachedTapeInfo(0, 0);
    }
}
