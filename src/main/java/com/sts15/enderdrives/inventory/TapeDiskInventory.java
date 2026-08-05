// Updated TapeDiskInventory to support lazy-loading DB logic
package com.sts15.enderdrives.inventory;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.*;
import appeng.api.stacks.AEKey;
import appeng.api.storage.cells.*;
import appeng.blockentity.storage.DriveBlockEntity;
import appeng.items.contents.CellConfig;
import appeng.util.ConfigInventory;
import com.sts15.enderdrives.db.StoredEntry;
import com.sts15.enderdrives.db.TapeKey;
import com.sts15.enderdrives.integration.DriveBlockEntityAccessor;
import com.sts15.enderdrives.items.TapeDiskItem;
import com.sts15.enderdrives.util.StackCodecHelper;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.sts15.enderdrives.db.TapeDBManager.*;

public class TapeDiskInventory implements StorageCell {

    private static final Logger LOGGER = LogManager.getLogger("EnderDrives");
    public static final ICellHandler HANDLER = new Handler();
    private final boolean disabled;
    private final ItemStack stack;
    private final UUID tapeId;
    private final int typeLimit;

    public TapeDiskInventory(ItemStack stack) {
        if (!(stack.getItem() instanceof TapeDiskItem item)) throw new IllegalArgumentException("Not a TapeDisk.");
        this.stack = stack;
        this.tapeId = TapeDiskItem.getOrCreateTapeId(stack);
        this.typeLimit = item.getTypeLimit(stack);
        this.disabled = item.isDisabled(stack);
    }

    @Override
    public CellState getStatus() {
        if (disabled) return CellState.FULL;
        TapeDriveCache cache = getOrLoadCache();
        if (cache == null) return CellState.EMPTY;
        if (!isOperational(tapeId)) return CellState.FULL;
        int types = getTypeCount(tapeId);
        long bytes = getTotalStoredBytes(tapeId);
        if (types == 0 && bytes == 0) return CellState.EMPTY;
        if (bytes >= getByteLimit(tapeId)) return CellState.FULL;
        if (types >= typeLimit) return CellState.TYPES_FULL;
        return CellState.NOT_EMPTY;
    }

    @Override
    public double getIdleDrain() {
        if (disabled) return 0;
        TapeDriveCache cache = getOrLoadCache();
        int totalItems = cache != null ? getTypeCount(tapeId) : 0;
        return 5.0 + Math.log10(Math.max(1, totalItems + 1)) * 0.25;
    }

    @Override
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (disabled || !isOperational(tapeId) || !(what instanceof AEItemKey itemKey)) return 0;
        if (!passesFilter(itemKey)) return 0;

        try {
            if (amount < 1) return 0;

            ItemStack descriptorStack = itemKey.toStack(1);
            if (!isTapeEligible(descriptorStack)) return 0;

            byte[] data = TapeDiskItem.serializeItemStackToBytes(descriptorStack);
            if (data == null || data.length == 0) {
                return 0;
            }

            synchronized (getDiskLock(tapeId)) {
                TapeDriveCache cache;
                try {
                    cache = getOrLoadCache();
                    if (cache == null) return 0;
                } catch (Exception e) {
                    return 0;
                }

                boolean isNewType = getItemCount(tapeId, data) == 0;
                if (isNewType && getTypeCount(tapeId) >= typeLimit) return 0;

                long accepted = getMaxInsertable(tapeId, data, amount);
                if (accepted <= 0) return 0;

                if (mode == Actionable.MODULATE) {
                    try {
                        accepted = insertItem(tapeId, data, itemKey, accepted, typeLimit);
                        if (accepted <= 0) return 0;
                        pingDriveForUpdate(source);
                    } catch (Exception e) {
                        return 0;
                    }
                }
                return accepted;
            }

        } catch (Exception topLevel) {
            return 0;
        }

    }

    @Override
    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (disabled || !isOperational(tapeId) || amount <= 0 || !(what instanceof AEItemKey itemKey)) return 0;
        synchronized (getDiskLock(tapeId)) {
            TapeDriveCache cache = getOrLoadCache();
            if (cache == null) return 0;

            TapeKey matchKey = null;
            Set<TapeKey> availableKeys = new HashSet<>(cache.entries.keySet());
            availableKeys.addAll(cache.deltaBuffer.keySet());
            for (TapeKey key : availableKeys) {
                long count = getItemCount(tapeId, key.itemBytes());
                if (count <= 0) continue;
                StoredEntry stored = cache.entries.get(key);
                AEItemKey storedKey = stored != null ? stored.aeKey() : null;
                if (storedKey == null) {
                    ItemStack decoded = TapeDiskItem.deserializeItemStackFromBytes(key.itemBytes());
                    storedKey = decoded.isEmpty() ? null : AEItemKey.of(decoded);
                }
                if (itemKey.equals(storedKey)) {
                    matchKey = key;
                    break;
                }
            }
            if (matchKey == null) {
                byte[] data = TapeDiskItem.serializeItemStackToBytes(itemKey.toStack(1));
                if (data == null || data.length == 0) return 0;
                matchKey = new TapeKey(data);
            }

            long toExtract = Math.min(getItemCount(tapeId, matchKey.itemBytes()), amount);
            if (toExtract <= 0 || mode == Actionable.SIMULATE) return toExtract;

            long persistedDelta = saveItem(tapeId, matchKey.itemBytes(), itemKey, -toExtract);
            long extracted = persistedDelta < 0 ? -persistedDelta : 0;
            if (extracted > 0) pingDriveForUpdate(source);
            return extracted;
        }
    }

    @Override
    public void getAvailableStacks(KeyCounter out) {
        if (disabled || !isOperational(tapeId)) return;
        TapeDriveCache cache = getOrLoadCache();
        if (cache == null) return;

        Map<TapeKey, Long> merged = new HashMap<>();
        cache.entries.forEach((k, v) -> merged.put(k, v.count()));
        cache.deltaBuffer.forEach((k, v) -> merged.merge(k, v, Long::sum));

        for (var entry : merged.entrySet()) {
            long count = entry.getValue();
            if (count <= 0) continue;
            StoredEntry stored = cache.entries.get(entry.getKey());
            AEItemKey key = stored != null ? stored.aeKey() : null;
            if (key == null) {
                ItemStack decoded = TapeDiskItem.deserializeItemStackFromBytes(entry.getKey().itemBytes());
                key = decoded.isEmpty() ? null : AEItemKey.of(decoded);
            }
            if (key != null) out.add(key, count);
        }
    }

    @Override
    public boolean isPreferredStorageFor(AEKey what, IActionSource source) {
        return false;
    }

    private boolean isTapeEligible(ItemStack stack) {
        return isSpecialItem(stack) || hasMeaningfulNBT(stack);
    }

    private boolean isSpecialItem(ItemStack stack) {
        return stack.has(DataComponents.EQUIPPABLE) ||
                stack.has(DataComponents.WEAPON) ||
                stack.has(DataComponents.TOOL) ||
                stack.getMaxStackSize() == 1;
    }

    @Override
    public void persist() {}

    @Override
    public Component getDescription() {
        return Component.literal("Tape Disk " + tapeId.toString().substring(0, 8));
    }

    private boolean hasMeaningfulNBT(ItemStack stack) {
        CompoundTag tag = StackCodecHelper.encodeItemStack(
                ServerLifecycleHooks.getCurrentServer().registryAccess(), stack);
        CompoundTag filtered = tag.copy();
        filtered.remove("count");
        filtered.remove("id");
        filtered.remove("damage");
        filtered.remove("repairCost");
        filtered.remove("unbreakable");
        if (filtered.getCompound("components").map(CompoundTag::isEmpty).orElse(false)) {
            filtered.remove("components");
        }
        return !filtered.isEmpty();
    }

    private boolean passesFilter(AEKey key) {
        ConfigInventory config = CellConfig.create(Set.of(AEKeyType.items()), stack);
        for (int i = 0; i < config.size(); i++) {
            AEKey filterKey = config.getKey(i);
            if (filterKey != null && filterKey.equals(key)) return true;
        }
        return config.keySet().isEmpty();
    }

    private static void pingDriveForUpdate(IActionSource source) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        server.execute(() -> source.machine().ifPresent(host -> {
            var node = host.getActionableNode();
            if (node == null) return;
            IGrid grid = node.getGrid();
            if (grid == null) return;

            for (DriveBlockEntity drive : grid.getMachines(DriveBlockEntity.class)) {
                for (int i = 0; i < drive.getCellCount(); i++) {
                    ItemStack stackInSlot = drive.getInternalInventory().getStackInSlot(i);
                    if (!stackInSlot.isEmpty() && stackInSlot.getItem() instanceof TapeDiskItem) {
                        ((DriveBlockEntityAccessor) drive).enderdrives$triggerVisualUpdate();
                        ((DriveBlockEntityAccessor) drive).enderdrives$recalculateIdlePower();
                        break;
                    }
                }
            }
        }));
    }

    @Nullable
    private TapeDriveCache getOrLoadCache() {
        TapeDriveCache cache = getCacheSafe(tapeId);
        if (cache == null && ServerLifecycleHooks.getCurrentServer() != null) {
            cache = getOrLoadForRead(tapeId);
        }
        return cache;
    }

    private static final ConcurrentMap<UUID, Object> DISK_LOCKS = new ConcurrentHashMap<>();
    private static Object getDiskLock(UUID id) {
        return DISK_LOCKS.computeIfAbsent(id, k -> new Object());
    }

    public static class Handler implements ICellHandler {
        @Override
        public boolean isCell(ItemStack is) {
            return is != null && is.getItem() instanceof TapeDiskItem;
        }

        @Override
        public @Nullable StorageCell getCellInventory(ItemStack is, @Nullable ISaveProvider host) {
            return isCell(is) ? new TapeDiskInventory(is) : null;
        }
    }
}
