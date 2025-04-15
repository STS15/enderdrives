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
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import java.io.*;
import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

import static com.sts15.enderdrives.db.EnderDBManager.*;

public class EnderDiskInventory implements StorageCell {

    private static final Logger LOGGER = LogManager.getLogger("EnderDrives");
    private final ItemStack stack;
    private final int frequency;
    public static final ICellHandler HANDLER = new Handler();
    private final int typeLimit;
    private final String scopePrefix;
    private final boolean disabled;
    private static final ForkJoinPool SHARED_PARALLEL_POOL =
            new ForkJoinPool(Math.min(4, Runtime.getRuntime().availableProcessors()));
    private static final ThreadLocal<ByteArrayOutputStream> LOCAL_BAOS =
            ThreadLocal.withInitial(() -> new ByteArrayOutputStream(512));
    private static final ThreadLocal<DataOutputStream> LOCAL_DOS =
            ThreadLocal.withInitial(() -> new DataOutputStream(LOCAL_BAOS.get()));

    public EnderDiskInventory(ItemStack stack) {
        if (!(stack.getItem() instanceof EnderDiskItem item)) {
            throw new IllegalArgumentException("Item is not an EnderDisk!");
        }
        this.stack = stack;
        this.frequency = EnderDiskItem.getFrequency(stack);
        this.typeLimit = item.getTypeLimit();
        this.scopePrefix = EnderDiskItem.getSafeScopePrefix(stack);
        this.disabled = item.isDisabled(stack);
    }

    @Override
    public CellState getStatus() {
        if (disabled) return CellState.FULL;
        int typesUsed = EnderDBManager.getTypeCount(scopePrefix, frequency);
        CellState state = calculateCellState(typesUsed, typeLimit);
        return state;
    }

    public static CellState calculateCellState(int typesUsed, int typeLimit) {
        if (typesUsed == 0) return CellState.EMPTY;
        if (typesUsed >= typeLimit) return CellState.FULL;
        float usagePercent = (float) typesUsed / typeLimit;
        return usagePercent >= 0.75f ? CellState.TYPES_FULL : CellState.NOT_EMPTY;
    }

    @Override
    public double getIdleDrain() {
        if (disabled) return 0.0;
        long totalItems = Math.max(1, EnderDBManager.getTotalItemCount(scopePrefix, frequency));
        double base = 100;
        double exponent = 0.8;
        double scale = 0.015;
        return base + (scale * Math.pow(totalItems, exponent));
    }

    public static CellState getCellStateForStack(ItemStack stack) {
        if (!(stack.getItem() instanceof EnderDiskItem enderDiskItem)) {
            return CellState.ABSENT;
        }
        int freq = EnderDiskItem.getFrequency(stack);
        int typesUsed = EnderDBManager.getTypeCount(EnderDiskItem.getSafeScopePrefix(stack), freq);
        int typeLimit = enderDiskItem.getTypeLimit();
        return calculateCellState(typesUsed, typeLimit);
    }

    @Override
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (disabled) return 0;
        int transferMode = EnderDiskItem.getTransferMode(stack);
        if (transferMode == 2) {
            return 0;
        }
        if (!(what instanceof AEItemKey)) return 0;
        if (!passesFilter(what)) return 0;
        if (!running || isShutdown) {
            debugLog("DB not ready for inserts.");
            return 0;
        }
        byte[] serialized = serializeItemStackToBytes(((AEItemKey) what).toStack());
        if (serialized.length == 0) return 0;
        synchronized (EnderDBManager.getDriveLock(scopePrefix, frequency)) {
            com.sts15.enderdrives.db.AEKey key = new com.sts15.enderdrives.db.AEKey(scopePrefix, frequency, serialized);

            boolean inDb = dbMap.containsKey(key);
            boolean inBuffer = deltaBuffer.containsKey(key);
            boolean isNewType = !(inDb || inBuffer);
            int committedTypes = EnderDBManager.getTypeCount(scopePrefix, frequency);
            if (isNewType && committedTypes >= typeLimit) {
                debugLog("[EnderDisk @ {}|{}] INSERT: rejected due to type limit", scopePrefix, frequency);
                return 0;
            }
            long currentStored = EnderDBManager.getItemCount(scopePrefix, frequency, serialized);
            long toInsert = Math.max(0, amount);
            if (mode == Actionable.SIMULATE) {
                debugLog("[EnderDisk @ {}|{}] INSERT: SIMULATE OK. Would accept [{}]x", scopePrefix, frequency, toInsert);
                return toInsert;
            }
            debugLog("[Insert Debug] Mode: {} | Requested: {} | Current Stored: {} | Delta Insert: {} | Source: {}", mode, amount, currentStored, toInsert, source);
            if (toInsert <= 0) {
                return 0;
            }
            EnderDBManager.saveItem(scopePrefix, frequency, serialized, toInsert);
            debugLog("[EnderDisk @ {}|{}] INSERT: SUCCESSFUL. Added [{}]x", scopePrefix, frequency, toInsert);
            return toInsert;
        }
    }

    @Override
    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (disabled) return 0;
        synchronized (EnderDBManager.getDriveLock(scopePrefix, frequency)) {
            int transferMode = EnderDiskItem.getTransferMode(stack);
            if (transferMode == 1) return 0;
            if (!(what instanceof AEItemKey)) return 0;
            ItemStack toExtract = ((AEItemKey) what).toStack();
            byte[] serialized = serializeItemStackToBytes(toExtract);
            if (serialized.length == 0) return 0;
            long current = EnderDBManager.getItemCount(scopePrefix, frequency, serialized);
            long toExtractCount = Math.min(current, amount);
            if (toExtractCount > 0 && mode == Actionable.MODULATE) {
                EnderDBManager.saveItem(scopePrefix, frequency, serialized, -toExtractCount);
            }
            return toExtractCount;
        }
    }

    private boolean passesFilter(AEKey key) {
        ConfigInventory configInv = CellConfig.create(Set.of(AEKeyType.items()), stack);
        for (int i = 0; i < configInv.size(); i++) {
            AEKey slotKey = configInv.getKey(i);
            if (slotKey == null) continue;
            if (slotKey.equals(key)) return true;
        }
        return configInv.keySet().isEmpty();
    }

    @Override
    public void persist() {
        // No action
    }

    @Override
    public Component getDescription() {
        return Component.literal("EnderDisk @ Freq " + frequency);
    }

    @Override
    public void getAvailableStacks(KeyCounter out) {
        if (disabled) return;
        synchronized (EnderDBManager.getDriveLock(scopePrefix, frequency)) {
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
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public boolean isPreferredStorageFor(AEKey what, IActionSource source) {
        if (!(what instanceof AEItemKey)) return false;
        if (disabled) return false;
        byte[] serialized = serializeItemStackToBytes(((AEItemKey) what).toStack());
        if (serialized.length == 0) return false;
        long storedCount = EnderDBManager.getItemCount(scopePrefix, frequency, serialized);
        if (storedCount > 0) return true;
        int currentTypeCount = EnderDBManager.getTypeCount(scopePrefix, frequency);
        return currentTypeCount < typeLimit;
    }

    public static byte[] serializeItemStackToBytes(ItemStack stack) {
        try {
            HolderLookup.Provider provider = ServerLifecycleHooks.getCurrentServer().registryAccess();
            Tag genericTag = stack.save(provider);
            if (!(genericTag instanceof CompoundTag tag)) {
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
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            DataInputStream dis = new DataInputStream(bais);
            CompoundTag tag = NbtIo.read(dis);
            dis.close();
            HolderLookup.Provider provider = ServerLifecycleHooks.getCurrentServer().registryAccess();
            try {
                return ItemStack.parse(provider, tag).orElse(ItemStack.EMPTY);
            } catch (Exception parseEx) {
                LOGGER.error("Failed to parse ItemStack from NBT. Possibly unknown registry key: {}", parseEx.getMessage());
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
            return is != null && is.getItem() instanceof EnderDiskItem;
        }
        @Override
        public @Nullable StorageCell getCellInventory(ItemStack is, @Nullable appeng.api.storage.cells.ISaveProvider host) {
            return isCell(is) ? new EnderDiskInventory(is) : null;
        }
    }

    private void debugLog(String message, Object... args) {
        LOGGER.info("[EnderDisk @ {}|{}] " + message, scopePrefix, frequency, args);
    }

}
