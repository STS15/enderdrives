package com.sts15.enderdrives.inventory;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.ICellHandler;
import appeng.api.storage.cells.StorageCell;
import appeng.items.contents.CellConfig;
import appeng.util.ConfigInventory;
import com.sts15.enderdrives.db.StoredEntry;
import com.sts15.enderdrives.db.TapeDBManager;
import com.sts15.enderdrives.db.TapeKey;
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

import static com.sts15.enderdrives.db.TapeDBManager.getByteLimit;

public class TapeDiskInventory implements StorageCell {

    private static final Logger LOGGER = LogManager.getLogger("EnderDrives");
    private static final boolean debug_log = false;
    public static final ICellHandler HANDLER = new Handler();
    private final boolean disabled;
    private final ItemStack stack;
    private final UUID tapeId;
    private final int typeLimit;

    public TapeDiskInventory(ItemStack stack) {
        long start = System.currentTimeMillis();
        if (!(stack.getItem() instanceof TapeDiskItem item))
            throw new IllegalArgumentException("Not a TapeDisk.");
        this.stack = stack;
        this.tapeId = TapeDiskItem.getOrCreateTapeId(stack);
        this.typeLimit = item.getTypeLimit(stack);
        this.disabled = item.isDisabled(stack);
        log("TapeDiskInventory created for tape %s in %d ms", tapeId, System.currentTimeMillis() - start);
    }

    @Override
    public CellState getStatus() {
        long start = System.currentTimeMillis();
        int types = TapeDBManager.getTypeCount(tapeId);
        CellState state = types == 0 ? CellState.EMPTY
                : types >= typeLimit ? CellState.FULL
                : types >= (typeLimit * 0.75f) ? CellState.TYPES_FULL : CellState.NOT_EMPTY;
        log("getStatus for tape %s returned %s in %d ms", tapeId, state, System.currentTimeMillis() - start);
        return state;
    }

    @Override
    public double getIdleDrain() {
        if (disabled) return 0;
        long start = System.currentTimeMillis();
        long totalItems = Math.max(1, TapeDBManager.getTypeCount(tapeId));
        double drain = 5.0 + Math.log10(totalItems + 1) * 0.25;
        log("getIdleDrain for tape %s completed in %d ms", tapeId, System.currentTimeMillis() - start);
        return drain;
    }

    @Override
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (disabled) return 0;
        long start = System.currentTimeMillis();
        if (!(what instanceof AEItemKey itemKey) || !passesFilter(itemKey)) return 0;
        ItemStack stackToInsert = itemKey.toStack((int) amount);
        if (!hasMeaningfulNBT(stackToInsert)) return 0;
        byte[] data = TapeDiskItem.serializeItemStackToBytes(stackToInsert);
        if (data == null || data.length == 0) return 0;
        synchronized (getDiskLock(tapeId)) {
            var cache = TapeDBManager.getCache(tapeId);
            if (cache != null) {
                final TapeKey thisKey = new TapeKey(data);
                Set<TapeKey> simulatedKeys = new HashSet<>(cache.entries.keySet());
                simulatedKeys.addAll(cache.deltaBuffer.keySet());
                simulatedKeys.add(thisKey);
                int simulatedTypeCount = 0;
                for (TapeKey key : simulatedKeys) {
                    long existing = cache.entries.getOrDefault(key, StoredEntry.EMPTY).count();
                    long delta = cache.deltaBuffer.getOrDefault(key, 0L);
                    long simulatedCount = existing + delta;
                    if (key.equals(thisKey)) {
                        simulatedCount += amount;
                    }
                    if (simulatedCount > 0) {
                        simulatedTypeCount++;
                    }
                }
                if (simulatedTypeCount > typeLimit) {
                    return 0;
                }
                long currentBytes = TapeDBManager.getTotalStoredBytes(tapeId);
                long additionalBytes = Math.round(data.length * amount * 0.75);
                long totalBytesSimulated = currentBytes + additionalBytes;
                long byteLimit = getByteLimit(tapeId);
                if (totalBytesSimulated > byteLimit) {
                    return 0;
                }
            }
            if (mode == Actionable.MODULATE) {
                TapeDBManager.saveItem(tapeId, data, itemKey, amount);
            }
        }
        log("insert for tape %s completed in %d ms", tapeId, System.currentTimeMillis() - start);
        return amount;
    }

    @Override
    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (disabled) return 0;
        long start = System.currentTimeMillis();
        if (!(what instanceof AEItemKey itemKey)) return 0;

        var cache = TapeDBManager.getCache(tapeId);
        if (cache == null) return 0;

        TapeKey matchKey = null;
        for (var entry : cache.entries.entrySet()) {
            StoredEntry stored = entry.getValue();
            if (stored.aeKey() != null && stored.aeKey().equals(itemKey)) {
                matchKey = entry.getKey();
                break;
            }
        }

        if (matchKey == null) {
            // Fallback to serialized key as last resort
            byte[] data = TapeDiskItem.serializeItemStackToBytes(itemKey.toStack((int) amount));
            if (data == null || data.length == 0) return 0;
            matchKey = new TapeKey(data);
        }

        long toExtract;
        synchronized (getDiskLock(tapeId)) {
            long available = TapeDBManager.getItemCount(tapeId, matchKey.itemBytes());
            toExtract = Math.min(available, amount);
            if (toExtract > 0 && mode == Actionable.MODULATE) {
                TapeDBManager.saveItem(tapeId, matchKey.itemBytes(), itemKey, -toExtract);
            }
        }

        log("extract for tape %s completed in %d ms", tapeId, System.currentTimeMillis() - start);
        return toExtract;
    }


    @Override
    public void getAvailableStacks(KeyCounter out) {
        if (disabled) return;
        long start = System.currentTimeMillis();
        var cache = TapeDBManager.getCache(tapeId);
        if (cache == null) {
            log("getAvailableStacks for tape %s found no cache loaded", tapeId);
            return;
        }

        Map<TapeKey, Long> combined = new HashMap<>();
        cache.entries.forEach((k, v) -> combined.put(k, v.count()));
        cache.deltaBuffer.forEach((k, v) -> combined.merge(k, v, Long::sum));

        for (var entry : combined.entrySet()) {
            long count = entry.getValue();
            if (count > 0) {
                AEItemKey key = null;
                StoredEntry stored = cache.entries.get(entry.getKey());
                if (stored != null && stored.aeKey() != null) {
                    key = stored.aeKey();
                } else {
                    ItemStack stack = TapeDiskItem.deserializeItemStackFromBytes(entry.getKey().itemBytes());
                    if (!stack.isEmpty()) key = AEItemKey.of(stack);
                }
                if (key != null) out.add(key, count);
            }
        }

        log("getAvailableStacks for tape %s completed in %d ms", tapeId, System.currentTimeMillis() - start);
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

    @Override
    public void persist() {
        log("persist called for tape %s", tapeId);
    }

    @Override
    public Component getDescription() {
        return Component.literal("Tape Disk " + tapeId.toString().substring(0, 8));
    }

    private boolean hasMeaningfulNBT(ItemStack stack) {
        CompoundTag fullTag = (CompoundTag) stack.save(ServerLifecycleHooks.getCurrentServer().registryAccess());
        CompoundTag filtered = fullTag.copy();
        filtered.remove("count");
        filtered.remove("id");
        filtered.remove("repairCost");
        filtered.remove("unbreakable");

        if (filtered.contains("tag")) {
            CompoundTag inner = filtered.getCompound("tag");
            if (inner.isEmpty()) filtered.remove("tag");
        }
        if (!filtered.isEmpty()) {
            return true;
        }

        Item item = stack.getItem();
        if (item instanceof net.minecraft.world.item.ArmorItem) return true;
        if (item instanceof net.minecraft.world.item.SwordItem) return true;
        if (item instanceof net.minecraft.world.item.PickaxeItem) return true;
        if (item instanceof net.minecraft.world.item.AxeItem) return true;
        if (item instanceof net.minecraft.world.item.ShovelItem) return true;
        if (item instanceof net.minecraft.world.item.HoeItem) return true;
        if (item instanceof net.minecraft.world.item.BowItem) return true;
        if (item instanceof net.minecraft.world.item.CrossbowItem) return true;
        if (item instanceof net.minecraft.world.item.TridentItem) return true;
        if (item instanceof net.minecraft.world.item.ShearsItem) return true;
        if (item instanceof net.minecraft.world.item.FlintAndSteelItem) return true;
        if (item instanceof net.minecraft.world.item.FishingRodItem) return true;
        if (item instanceof net.minecraft.world.item.ShieldItem) return true;
        return stack.getMaxStackSize() == 1;
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

    private static void log(String format, Object... args) {
        if (debug_log) {
            LOGGER.info(String.format(format, args));
        }
    }

    public static class Handler implements ICellHandler {
        @Override
        public boolean isCell(ItemStack is) {
            return is != null && is.getItem() instanceof TapeDiskItem;
        }

        @Override
        public @Nullable StorageCell getCellInventory(ItemStack is, @Nullable appeng.api.storage.cells.ISaveProvider host) {
            return isCell(is) ? new TapeDiskInventory(is) : null;
        }
    }
}
