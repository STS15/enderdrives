package com.sts15.enderdrives.inventory;

import appeng.api.stacks.AEKeyType;
import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.StorageCell;
import appeng.items.contents.CellConfig;
import appeng.util.ConfigInventory;
import com.sts15.enderdrives.Constants;
import net.minecraft.world.item.ItemStack;

import java.util.Set;

public abstract class AbstractEnderDiskInventory implements StorageCell {

    private final ItemStack stack;
    private final AEKeyType aeKeyType;
    private static final boolean DEBUG_LOG = false;
    private static String enderDBManager;

    public AbstractEnderDiskInventory(ItemStack stack, AEKeyType aeKeyType, String enderDBManager) {
        this.stack = stack;
        this.aeKeyType = aeKeyType;
        this.enderDBManager = enderDBManager;
    }

    boolean passesFilter(appeng.api.stacks.AEKey key) {
        ConfigInventory configInv = CellConfig.create(Set.of(aeKeyType), stack);
        for (int i = 0; i < configInv.size(); i++) {
            appeng.api.stacks.AEKey slotKey = configInv.getKey(i);
            if (slotKey == null) continue;
            if (slotKey.equals(key)) return false;
        }
        return !configInv.keySet().isEmpty();
    }

    @Override
    public void persist() {
        // no-op (same as item implementation)
    }

    public ItemStack getContainerItem() {
        return this.stack;
    }

    public static CellState calculateCellState(int typesUsed, int typeLimit) {
        if (typesUsed == 0) return CellState.EMPTY;
        if (typesUsed >= typeLimit) return CellState.FULL;
        float usagePercent = (float) typesUsed / typeLimit;
        return usagePercent >= 0.75f ? CellState.TYPES_FULL : CellState.NOT_EMPTY;
    }

    static void log(String format, Object... args) {
        if (DEBUG_LOG) Constants.LOG.info("[" + enderDBManager + "] " + String.format(format, args));
    }

}
