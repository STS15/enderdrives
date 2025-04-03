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
import com.sts15.enderdrives.db.AEKeyCacheEntry;
import com.sts15.enderdrives.db.EnderDBManager;
import com.sts15.enderdrives.items.EnderDiskItem;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

import static com.sts15.enderdrives.db.EnderDBManager.dbMap;
import static com.sts15.enderdrives.db.EnderDBManager.deltaBuffer;

public class EnderDiskInventory implements StorageCell {

    private final ItemStack stack;
    private final int frequency;
    public static final ICellHandler HANDLER = new Handler();
    private static final ConcurrentMap<String, Object> diskLocks = new ConcurrentHashMap<>();

    private final int typeLimit;
    private final String scopePrefix;
    private static final boolean DEBUG_LOG = false;

    private static final ForkJoinPool SHARED_PARALLEL_POOL =
            new ForkJoinPool(Math.min(4, Runtime.getRuntime().availableProcessors()));
    private static final ThreadLocal<ByteArrayOutputStream> LOCAL_BAOS =
            ThreadLocal.withInitial(() -> new ByteArrayOutputStream(512));
    private static final ThreadLocal<DataOutputStream> LOCAL_DOS =
            ThreadLocal.withInitial(() -> new DataOutputStream(LOCAL_BAOS.get()));


    public EnderDiskInventory(ItemStack stack) {
        if (!(stack.getItem() instanceof EnderDiskItem item)) throw new IllegalArgumentException("Item is not an EnderDisk!");
        this.stack = stack;
        this.frequency = EnderDiskItem.getFrequency(stack);
        this.typeLimit = item.getTypeLimit();
        this.scopePrefix = EnderDiskItem.getSafeScopePrefix(stack);
    }

    @Override
    public CellState getStatus() {
        log("getStatus() called");
        int typesUsed = EnderDBManager.getTypeCount(scopePrefix, frequency);
        CellState state = calculateCellState(typesUsed, typeLimit);
        log("getStatus() returning state=%s (typesUsed=%d, typeLimit=%d)", state, typesUsed, typeLimit);
        return state;
    }

    public static CellState calculateCellState(int typesUsed, int typeLimit) {
        log("calculateCellState() called with typesUsed=%d, typeLimit=%d", typesUsed, typeLimit);
        if (typesUsed == 0) return CellState.EMPTY;
        if (typesUsed >= typeLimit) return CellState.FULL;
        float usagePercent = (float) typesUsed / typeLimit;
        CellState result = usagePercent >= 0.75f ? CellState.TYPES_FULL : CellState.NOT_EMPTY;
        log("calculateCellState() returning %s (usagePercent=%.2f)", result, usagePercent);
        return result;
    }

    @Override
    public double getIdleDrain() {
        log("getIdleDrain() called");
        long totalItems = Math.max(1, EnderDBManager.getTotalItemCount(scopePrefix, frequency));
        double base = 100;
        double exponent = 0.8;
        double scale = 0.015;
        double drain = base + (scale * Math.pow(totalItems, exponent));
        log("getIdleDrain() computed drain=%.3f (totalItems=%d)", drain, totalItems);
        return drain;
    }

    public static CellState getCellStateForStack(ItemStack stack) {
        log("getCellStateForStack() called");
        if (!(stack.getItem() instanceof EnderDiskItem enderDiskItem)) {
            log("getCellStateForStack() returning ABSENT (not an EnderDisk)");
            return CellState.ABSENT;
        }
        int freq = EnderDiskItem.getFrequency(stack);
        int typesUsed = EnderDBManager.getTypeCount(EnderDiskItem.getSafeScopePrefix(stack), freq);
        int typeLimit = enderDiskItem.getTypeLimit();
        CellState state = calculateCellState(typesUsed, typeLimit);
        log("getCellStateForStack() returning %s (freq=%d, typesUsed=%d, typeLimit=%d)", state, freq, typesUsed, typeLimit);
        return state;
    }

    @Override
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        log("insert() called: AEKey=%s, amount=%d, mode=%s", what, amount, mode);

        // Output-only disk? Reject insert.
        int transferMode = EnderDiskItem.getTransferMode(stack);
        if (transferMode == 2) {
            log("insert() blocked: transferMode is output-only.");
            return 0;
        }

        if (!(what instanceof AEItemKey itemKey)) {
            log("insert() blocked: AEKey is not an AEItemKey.");
            return 0;
        }

        if (!passesFilter(itemKey)) {
            log("insert() blocked: item does not pass filter.");
            return 0;
        }

        // Serialize the ItemStack
        ItemStack toInsert = itemKey.toStack();
        byte[] serialized = serializeItemStackToBytes(toInsert);
        if (serialized.length == 0) {
            log("insert() blocked: failed to serialize stack.");
            return 0;
        }

        synchronized (getDiskLock()) {
            com.sts15.enderdrives.db.AEKey key = new com.sts15.enderdrives.db.AEKey(scopePrefix, frequency, serialized);

            boolean inDb = dbMap.containsKey(key);
            boolean inBuffer = deltaBuffer.containsKey(key);
            boolean isNewType = !(inDb || inBuffer);

            if (isNewType) {
                int committedTypes = EnderDBManager.getTypeCount(scopePrefix, frequency);
                Set<com.sts15.enderdrives.db.AEKey> simulatedKeys = new HashSet<>(dbMap.keySet());
                deltaBuffer.keySet().forEach(k -> {
                    if (k.scope().equals(scopePrefix) && k.freq() == frequency) {
                        simulatedKeys.add(k);
                    }
                });

                simulatedKeys.add(key);

                int simulatedTypeCount = 0;
                for (com.sts15.enderdrives.db.AEKey k : simulatedKeys) {
                    if (k.scope().equals(scopePrefix) && k.freq() == frequency) {
                        simulatedTypeCount++;
                    }
                }

                if (simulatedTypeCount > typeLimit) {
                    log("insert() blocked: would exceed typeLimit. committed=%d, simulated=%d, limit=%d",
                            committedTypes, simulatedTypeCount, typeLimit);
                    return 0;
                }
            }

            if (mode == Actionable.MODULATE) {
                EnderDBManager.saveItem(scopePrefix, frequency, serialized, amount);
                log("insert() successful: amount=%d, typeKnown=%s", amount, !isNewType);
            } else {
                log("insert() simulated OK: would insert amount=%d", amount);
            }

            return amount;
        }
    }

    @Override
    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        synchronized (getDiskLock()) {
            log("extract() called: AEKey=%s, amount=%d, mode=%s", what, amount, mode);
            int transferMode = EnderDiskItem.getTransferMode(stack);
            if (transferMode == 1) {
                log("extract() returning 0 because transferMode is input-only");
                return 0;
            }
            if (!(what instanceof AEItemKey itemKey)) {
                log("extract() returning 0 because AEKey is not an AEItemKey");
                return 0;
            }
            ItemStack toExtract = itemKey.toStack();
            byte[] serialized = serializeItemStackToBytes(toExtract);
            if (serialized.length == 0) {
                log("extract() returning 0 because serialization returned empty array");
                return 0;
            }
            long current = EnderDBManager.getItemCount(scopePrefix, frequency, serialized);
            long toExtractCount = Math.min(current, amount);
            if (toExtractCount > 0 && mode == Actionable.MODULATE) {
                EnderDBManager.saveItem(scopePrefix, frequency, serialized, -toExtractCount);
                //EnderDBManager.flushDeltaBuffer();  <-- This makes it an atomic extract, except costly ops/sec
            }
            log("extract() completed: request=%d, stored=%d, toExtract=%d, mode=%s", amount, current, toExtractCount, mode);
            return toExtractCount;
        }
    }

    private boolean passesFilter(AEKey key) {
        log("passesFilter() called for key=%s", key);
        ConfigInventory configInv = CellConfig.create(Set.of(AEKeyType.items()), stack);
        for (int i = 0; i < configInv.size(); i++) {
            AEKey slotKey = configInv.getKey(i);
            if (slotKey == null) continue;
            if (slotKey.equals(key)) {
                log("passesFilter() returning true (match found)");
                return true;
            }
        }
        boolean hasAnyFilter = !configInv.keySet().isEmpty();
        boolean result = !hasAnyFilter;
        log("passesFilter() returning %s (hasAnyFilter=%s)", result, hasAnyFilter);
        return result;
    }

    @Override
    public void persist() {
        log("persist() called - no action defined");
    }

    @Override
    public Component getDescription() {
        log("getDescription() called");
        Component desc = Component.literal("EnderDisk @ Freq " + frequency);
        log("getDescription() returning: %s", desc.getString());
        return desc;
    }

    @Override
    public void getAvailableStacks(KeyCounter out) {
        synchronized (getDiskLock()) {
            log("getAvailableStacks() called");

            List<AEKeyCacheEntry> entries = EnderDBManager.queryItemsByFrequency(scopePrefix, frequency);
            if (entries.isEmpty()) return;

            try {
                List<KeyCounter> partials = SHARED_PARALLEL_POOL.submit(() ->
                        entries.parallelStream()
                                .collect(Collectors.groupingByConcurrent(
                                        entry -> Thread.currentThread().getId(),
                                        Collectors.collectingAndThen(Collectors.toList(), group -> {
                                            KeyCounter kc = new KeyCounter();
                                            for (AEKeyCacheEntry entry : group) {
                                                kc.add(entry.aeKey(), entry.count());
                                            }
                                            return kc;
                                        })
                                ))
                                .values()
                                .stream()
                                .toList()
                ).get();

                for (KeyCounter partial : partials) {
                    out.addAll(partial);
                }

                log("getAvailableStacks() merged %d partial counters", partials.size());

            } catch (Exception e) {
                e.printStackTrace();
            }

        }
    }

    @Override
    public boolean isPreferredStorageFor(AEKey what, IActionSource source) {
        log("isPreferredStorageFor() called for AEKey=%s", what);
        if (!(what instanceof AEItemKey itemKey)) {
            log("isPreferredStorageFor() returning false (not an AEItemKey)");
            return false;
        }
        byte[] serialized = serializeItemStackToBytes(itemKey.toStack());
        if (serialized.length == 0) {
            log("isPreferredStorageFor() returning false (serialization empty)");
            return false;
        }
        long storedCount = EnderDBManager.getItemCount(scopePrefix, frequency, serialized);
        boolean result = storedCount > 0;
        log("isPreferredStorageFor() returning %s (storedCount=%d)", result, storedCount);
        return result;
    }

    public static byte[] serializeItemStackToBytes(ItemStack stack) {
        log("serializeItemStackToBytes() called");
        try {
            HolderLookup.Provider provider = ServerLifecycleHooks.getCurrentServer().registryAccess();
            Tag genericTag = stack.save(provider);
            if (!(genericTag instanceof CompoundTag tag)) {
                log("serializeItemStackToBytes() returning empty array (tag not CompoundTag)");
                return new byte[0];
            }
            ByteArrayOutputStream baos = LOCAL_BAOS.get();
            baos.reset();
            DataOutputStream dos = LOCAL_DOS.get();
            dos.flush();
            NbtIo.write(tag, dos);
            dos.close();
            return baos.toByteArray();
        } catch (IOException e) {
            e.printStackTrace();
            return new byte[0];
        }
    }

    public static ItemStack deserializeItemStackFromBytes(byte[] data) {
        log("deserializeItemStackFromBytes() called with %d bytes", data.length);
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            DataInputStream dis = new DataInputStream(bais);
            CompoundTag tag = NbtIo.read(dis);
            dis.close();
            HolderLookup.Provider provider = ServerLifecycleHooks.getCurrentServer().registryAccess();
            try {
                ItemStack stack = ItemStack.parse(provider, tag).orElse(ItemStack.EMPTY);
                log("deserializeItemStackFromBytes() parsed stack: %s", stack);
                return stack;
            } catch (Exception parseEx) {
                System.err.println("[EnderDB] Failed to parse ItemStack from NBT. Possibly unknown registry key: " + parseEx.getMessage());
                return ItemStack.EMPTY;
            }
        } catch (IOException e) {
            e.printStackTrace();
            return ItemStack.EMPTY;
        }
    }

    private static class Handler implements ICellHandler {
        @Override
        public boolean isCell(ItemStack is) {
            log("Handler.isCell() called for ItemStack=%s", is);
            return is != null && is.getItem() instanceof EnderDiskItem;
        }
        @Override
        public @Nullable StorageCell getCellInventory(ItemStack is, @Nullable appeng.api.storage.cells.ISaveProvider host) {
            log("Handler.getCellInventory() called for ItemStack=%s", is);
            return isCell(is) ? new EnderDiskInventory(is) : null;
        }
    }

    private static void log(String format, Object... args) {
        if (DEBUG_LOG) {
            System.out.printf("[EnderDiskInventory] " + format + "%n", args);
        }
    }

    private Object getDiskLock() {
        return diskLocks.computeIfAbsent(scopePrefix + ":" + frequency, k -> new Object());
    }
}