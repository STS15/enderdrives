package com.sts15.enderdrives.db;

import net.neoforged.neoforge.fluids.FluidStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight client cache for fluid EnderDrives.
 * Key format: scopePrefix + "|" + frequency (same convention as item cache).
 */
public class ClientFluidDiskCache {
    private static final Map<String, FluidDiskTypeInfo> CACHE = new HashMap<>();

    /** Insert/replace the snapshot for a (scope|freq). */
    public static void update(String key, int typeCount, int typeLimit, long totalAmount, List<FluidStack> topFluids) {
        CACHE.put(key, new FluidDiskTypeInfo(typeCount, typeLimit, totalAmount, topFluids));
    }

    /** Get snapshot; returns an empty snapshot if not present. */
    public static FluidDiskTypeInfo get(String key) {
        return CACHE.getOrDefault(key, new FluidDiskTypeInfo(0, 0, 0L, List.of()));
    }

    /** Convenience key builder. */
    private static String k(String scopePrefix, int frequency) {
        return scopePrefix + "|" + frequency;
    }

    /** Get top fluids list for UI. */
    public static List<FluidStack> getTopFluids(String scopePrefix, int frequency) {
        return get(k(scopePrefix, frequency)).topFluids();
    }

    /** Returns true if we have no cached top fluids for (scope|freq). */
    public static boolean isEmpty(String scopePrefix, int frequency) {
        return getTopFluids(scopePrefix, frequency).isEmpty();
    }

    /** Should we ask the server for data? */
    public static boolean shouldRequest(String key) {
        return !CACHE.containsKey(key);
    }
}
