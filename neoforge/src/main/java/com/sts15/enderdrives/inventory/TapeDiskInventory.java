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
import com.sts15.enderdrives.db.*;
import com.sts15.enderdrives.integration.DriveBlockEntityAccessor;
import com.sts15.enderdrives.items.EnderDiskItem;
import com.sts15.enderdrives.items.TapeDiskItem;
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
        TapeDriveCache cache = getCacheSafe(tapeId);
        if (cache == null) return CellState.EMPTY;
        int types = cache.entries.size();
        return types == 0 ? CellState.EMPTY
                : types >= typeLimit ? CellState.FULL
                : types >= (typeLimit * 0.75f) ? CellState.TYPES_FULL : CellState.NOT_EMPTY;
    }

    @Override
    public double getIdleDrain() {
        if (disabled) return 0;
        TapeDriveCache cache = getCacheSafe(tapeId);
        int totalItems = cache != null ? cache.entries.size() : 0;
        return 5.0 + Math.log10(Math.max(1, totalItems + 1)) * 0.25;
    }

    @Override
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (disabled || !(what instanceof AEItemKey itemKey)) return 0;
        if (!passesFilter(itemKey)) return 0;

        try {
            if (amount < 1 || amount > 99) {
                return 0;
            }

            ItemStack descriptorStack = itemKey.toStack(1);
            if (!isSpecialItem(descriptorStack) || !hasMeaningfulNBT(descriptorStack)) return 0;

            byte[] data = TapeDiskItem.serializeItemStackToBytes(descriptorStack);
            if (data == null || data.length == 0) {
                return 0;
            }

            synchronized (getDiskLock(tapeId)) {
                TapeDriveCache cache;
                try {
                    cache = getCacheSafe(tapeId);
                    if (cache == null) {
                        loadFromDiskAsync(tapeId);
                        return 0;
                    }
                } catch (Exception e) {
                    return 0;
                }

                TapeKey thisKey = new TapeKey(data);

                Set<TapeKey> simulatedKeys = new HashSet<>();
                try {
                    simulatedKeys.addAll(cache.entries.keySet());
                    simulatedKeys.addAll(cache.deltaBuffer.keySet());
                    simulatedKeys.add(thisKey);
                } catch (Exception e) {
                    return 0;
                }

                int simulatedTypeCount = 0;
                try {
                    for (TapeKey key : simulatedKeys) {
                        long existing = cache.entries.getOrDefault(key, StoredEntry.EMPTY).count();
                        long delta = cache.deltaBuffer.getOrDefault(key, 0L);
                        long count = existing + delta;
                        if (key.equals(thisKey)) count += amount;
                        if (count > 0) simulatedTypeCount++;
                    }
                } catch (Exception e) {
                    return 0;
                }

                if (simulatedTypeCount > typeLimit) {
                    return 0;
                }

                long currentBytes;
                try {
                    currentBytes = getTotalStoredBytes(tapeId);
                } catch (Exception e) {
                    return 0;
                }

                long extra = Math.round(data.length * amount * 0.75);
                if (currentBytes + extra > getByteLimit(tapeId)) {
                    return 0;
                }

                if (mode == Actionable.MODULATE) {
                    try {
                        saveItem(tapeId, data, itemKey, amount);
                        var server = ServerLifecycleHooks.getCurrentServer();
                        server.execute(() -> {
                            source.machine().ifPresent(host -> {
                                var node = host.getActionableNode();
                                if (node != null) {
                                    IGrid grid = node.getGrid();
                                    if (grid != null) {
                                        var drives = grid.getMachines(DriveBlockEntity.class);
                                        for (DriveBlockEntity drive : drives) {
                                            for (int i = 0; i < drive.getCellCount(); i++) {
                                                ItemStack stackInSlot = drive.getInternalInventory().getStackInSlot(i);
                                                if (!stackInSlot.isEmpty() && stackInSlot.getItem() instanceof TapeDiskItem) {
                                                    ((DriveBlockEntityAccessor) drive).enderdrives$triggerVisualUpdate();
                                                    ((DriveBlockEntityAccessor) drive).enderdrives$recalculateIdlePower();
                                                    break;
                                                }
                                            }
                                        }
                                    }
                                }
                            });
                        });
                    } catch (Exception e) {
                        return 0;
                    }
                }
            }

        } catch (Exception topLevel) {
            return 0;
        }

        return amount;
    }

    @Override
    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (disabled || !(what instanceof AEItemKey itemKey)) return 0;
        var cache = getCacheSafe(tapeId);
        if (cache == null) {
            loadFromDiskAsync(tapeId);
            return 0;
        }

        TapeKey matchKey = null;
        for (var entry : cache.entries.entrySet()) {
            StoredEntry stored = entry.getValue();
            if (stored.aeKey() != null && stored.aeKey().equals(itemKey)) {
                matchKey = entry.getKey();
                break;
            }
        }
        if (matchKey == null) {
            ItemStack s = itemKey.toStack();
            s.setCount((int) Math.min(amount, 64));
            byte[] data = TapeDiskItem.serializeItemStackToBytes(s);
            if (data == null || data.length == 0) return 0;
            matchKey = new TapeKey(data);
        }

        long available = getItemCount(tapeId, matchKey.itemBytes());
        long toExtract = Math.min(available, amount);
        if (toExtract > 0 && mode == Actionable.MODULATE) {
            saveItem(tapeId, matchKey.itemBytes(), itemKey, -toExtract);
            var server = ServerLifecycleHooks.getCurrentServer();
            server.execute(() -> {
                source.machine().ifPresent(host -> {
                    var node = host.getActionableNode();
                    if (node != null) {
                        IGrid grid = node.getGrid();
                        if (grid != null) {
                            var drives = grid.getMachines(DriveBlockEntity.class);
                            for (DriveBlockEntity drive : drives) {
                                for (int i = 0; i < drive.getCellCount(); i++) {
                                    ItemStack stackInSlot = drive.getInternalInventory().getStackInSlot(i);
                                    if (!stackInSlot.isEmpty() && stackInSlot.getItem() instanceof TapeDiskItem) {
                                        ((DriveBlockEntityAccessor) drive).enderdrives$triggerVisualUpdate();
                                        ((DriveBlockEntityAccessor) drive).enderdrives$recalculateIdlePower();
                                        break;
                                    }
                                }
                            }
                        }
                    }
                });
            });
        }
        return toExtract;
    }

    @Override
    public void getAvailableStacks(KeyCounter out) {
        if (disabled) return;
        var cache = getCacheSafe(tapeId);
        if (cache == null) {
            loadFromDiskAsync(tapeId);
            return;
        }

        Map<TapeKey, Long> merged = new HashMap<>();
        cache.entries.forEach((k, v) -> merged.put(k, v.count()));
        cache.deltaBuffer.forEach((k, v) -> merged.merge(k, v, Long::sum));

        for (var entry : merged.entrySet()) {
            long count = entry.getValue();
            if (count <= 0) continue;
            AEItemKey key = Optional.ofNullable(cache.entries.get(entry.getKey()))
                    .map(StoredEntry::aeKey)
                    .orElseGet(() -> {
                        ItemStack is = TapeDiskItem.deserializeItemStackFromBytes(entry.getKey().itemBytes());
                        return is.isEmpty() ? null : AEItemKey.of(is);
                    });
            if (key != null) out.add(key, count);
        }
    }

    @Override
    public boolean isPreferredStorageFor(AEKey what, IActionSource source) {
        if (!(what instanceof AEItemKey itemKey)) return false;
        ItemStack stack = itemKey.toStack();
        Item item = stack.getItem();
        boolean isSpecial =
                item instanceof ArmorItem ||
                        item instanceof SwordItem ||
                        item instanceof PickaxeItem ||
                        item instanceof AxeItem ||
                        item instanceof ShovelItem ||
                        item instanceof HoeItem ||
                        item instanceof BowItem ||
                        item instanceof CrossbowItem ||
                        item instanceof TridentItem ||
                        item instanceof ShearsItem ||
                        item instanceof FlintAndSteelItem ||
                        item instanceof FishingRodItem ||
                        item instanceof ShieldItem ||
                        stack.getMaxStackSize() == 1;
        return isSpecial;
    }

    private boolean isSpecialItem(ItemStack stack) {
        Item item = stack.getItem();
        return item instanceof ArmorItem ||
                item instanceof SwordItem ||
                item instanceof PickaxeItem ||
                item instanceof AxeItem ||
                item instanceof ShovelItem ||
                item instanceof HoeItem ||
                item instanceof BowItem ||
                item instanceof CrossbowItem ||
                item instanceof TridentItem ||
                item instanceof ShearsItem ||
                item instanceof FlintAndSteelItem ||
                item instanceof FishingRodItem ||
                item instanceof ShieldItem ||
                stack.getMaxStackSize() == 1;
    }

    @Override
    public void persist() {}

    @Override
    public Component getDescription() {
        return Component.literal("Tape Disk " + tapeId.toString().substring(0, 8));
    }

    private boolean hasMeaningfulNBT(ItemStack stack) {
        CompoundTag tag = (CompoundTag) stack.save(ServerLifecycleHooks.getCurrentServer().registryAccess());
        CompoundTag filtered = tag.copy();
        filtered.remove("count");
        filtered.remove("id");
        filtered.remove("damage");
        filtered.remove("repairCost");
        filtered.remove("unbreakable");
        if (filtered.contains("tag") && filtered.getCompound("tag").isEmpty()) {
            filtered.remove("tag");
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
