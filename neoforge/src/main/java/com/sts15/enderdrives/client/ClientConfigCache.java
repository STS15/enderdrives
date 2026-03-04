package com.sts15.enderdrives.client;

import com.sts15.enderdrives.items.ItemInit;
import net.minecraft.world.item.Item;

public final class ClientConfigCache {

    public static final int IDX_ITEM_1K        = 0;
    public static final int IDX_ITEM_4K        = 1;
    public static final int IDX_ITEM_16K       = 2;
    public static final int IDX_ITEM_64K       = 3;
    public static final int IDX_ITEM_256K      = 4;
    public static final int IDX_ITEM_CREATIVE  = 5;

    public static final int IDX_TAPE           = 6;

    public static final int IDX_FLUID_1K       = 7;
    public static final int IDX_FLUID_4K       = 8;
    public static final int IDX_FLUID_16K      = 9;
    public static final int IDX_FLUID_64K      = 10;
    public static final int IDX_FLUID_256K     = 11;
    public static final int IDX_FLUID_CREATIVE = 12;

    private static final int DEFAULT_MASK = (1 << (IDX_FLUID_CREATIVE + 1)) - 1;

    private static int driveBitmask = DEFAULT_MASK;

    public static int freqMin = 1;
    public static int freqMax = 4095;

    private ClientConfigCache() {}

    // -------------------- Config sync --------------------

    public static void update(int min, int max) {
        freqMin = min;
        freqMax = max;
    }

    /** Called by SyncDisabledDrivesPacket handler. */
    public static void setDriveBitmask(int bitmask) {
        driveBitmask = bitmask;
    }

    // -------------------- Queries (by index) --------------------

    public static boolean isDriveEnabled(int index) {
        if (index < 0 || index >= 32) return false; // guard
        return (driveBitmask & (1 << index)) != 0;
    }

    public static boolean isDriveDisabled(int index) {
        return !isDriveEnabled(index);
    }

    // -------------------- Queries (by item) --------------------

    /**
     * Convenience: ask if the given registry item is enabled.
     * Returns false if the item isn't one of our known drives.
     */
    public static boolean isDriveEnabled(Item item) {
        int idx = getDriveIndexFromItem(item);
        return idx >= 0 && isDriveEnabled(idx);
    }

    public static boolean isDriveDisabled(Item item) {
        return !isDriveEnabled(item);
    }

    /**
     * Central mapping from concrete registry items to bit indices.
     * Avoids relying on per-item helpers so the mapping is defined in one place.
     */
    public static int getDriveIndexFromItem(Item item) {
        // Item disks
        if (item == ItemInit.ENDER_DISK_1K.get())        return IDX_ITEM_1K;
        if (item == ItemInit.ENDER_DISK_4K.get())        return IDX_ITEM_4K;
        if (item == ItemInit.ENDER_DISK_16K.get())       return IDX_ITEM_16K;
        if (item == ItemInit.ENDER_DISK_64K.get())       return IDX_ITEM_64K;
        if (item == ItemInit.ENDER_DISK_256K.get())      return IDX_ITEM_256K;
        if (item == ItemInit.ENDER_DISK_creative.get())  return IDX_ITEM_CREATIVE;

        // Tape
        if (item == ItemInit.TAPE_DISK.get())            return IDX_TAPE;

        // Fluid disks
        if (item == ItemInit.ENDER_FLUID_DISK_1K.get())       return IDX_FLUID_1K;
        if (item == ItemInit.ENDER_FLUID_DISK_4K.get())       return IDX_FLUID_4K;
        if (item == ItemInit.ENDER_FLUID_DISK_16K.get())      return IDX_FLUID_16K;
        if (item == ItemInit.ENDER_FLUID_DISK_64K.get())      return IDX_FLUID_64K;
        if (item == ItemInit.ENDER_FLUID_DISK_256K.get())     return IDX_FLUID_256K;
        if (item == ItemInit.ENDER_FLUID_DISK_creative.get()) return IDX_FLUID_CREATIVE;

        return -1; // not a known drive item
    }
}
