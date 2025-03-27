package com.sts15.enderdrives.inventory;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.ICellHandler;
import appeng.api.storage.cells.StorageCell;
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
import org.jetbrains.annotations.Nullable;
import java.io.*;
import java.util.*;

public class EnderDiskInventory implements StorageCell {

    private final ItemStack stack;
    private final int frequency;
    public static final ICellHandler HANDLER = new Handler();
    private final int typeLimit;
    private final String scopePrefix;
    private static final boolean DEBUG_LOG = false;


    public EnderDiskInventory(ItemStack stack) {
        if (!(stack.getItem() instanceof EnderDiskItem item)) throw new IllegalArgumentException("Item is not an EnderDisk!");
        this.stack = stack;
        this.frequency = EnderDiskItem.getFrequency(stack);
        this.typeLimit = item.getTypeLimit();
        this.scopePrefix = EnderDiskItem.getSafeScopePrefix(stack);
    }

    @Override
    public CellState getStatus() {
        int typesUsed = EnderDBManager.getTypeCount(scopePrefix,frequency);
        return calculateCellState(typesUsed, typeLimit);
    }

    public static CellState calculateCellState(int typesUsed, int typeLimit) {
        if (typesUsed == 0) return CellState.EMPTY;
        if (typesUsed >= typeLimit) return CellState.FULL;
        float usagePercent = (float) typesUsed / typeLimit;
        return usagePercent >= 0.75f ? CellState.TYPES_FULL : CellState.NOT_EMPTY;
    }

    @Override
    public double getIdleDrain() { return 0.5; }

    public static CellState getCellStateForStack(ItemStack stack) {
        if (!(stack.getItem() instanceof EnderDiskItem enderDiskItem)) return CellState.ABSENT;
        int freq = EnderDiskItem.getFrequency(stack);
        int typesUsed = EnderDBManager.getTypeCount(EnderDiskItem.getSafeScopePrefix(stack), freq);
        int typeLimit = enderDiskItem.getTypeLimit();
        return calculateCellState(typesUsed, typeLimit);
    }

    @Override
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (!(what instanceof AEItemKey itemKey)) return 0;
        ItemStack toInsert = itemKey.toStack();
        byte[] serialized = serializeItemStackToBytes(toInsert);
        if (serialized.length == 0) return 0;
        long existing = EnderDBManager.getItemCount(scopePrefix, frequency, serialized);
        boolean isNewType = existing == 0;
        if (isNewType && EnderDBManager.getTypeCount(scopePrefix, frequency) >= typeLimit) return 0;
        if (mode == Actionable.MODULATE) EnderDBManager.saveItem(scopePrefix, frequency, serialized, amount);
        log("Insert called: freq=%d scopePrefix=%s amount=%d newType=%s mode=%s", frequency, scopePrefix, amount, isNewType, mode);
        return amount;
    }

    @Override
    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (!(what instanceof AEItemKey itemKey)) return 0;
        ItemStack toExtract = itemKey.toStack();
        byte[] serialized = serializeItemStackToBytes(toExtract);
        if (serialized.length == 0) return 0;
        long current = EnderDBManager.getItemCount(scopePrefix, frequency, serialized);
        long toExtractCount = Math.min(current, amount);
        if (toExtractCount > 0 && mode == Actionable.MODULATE) {
            EnderDBManager.saveItem(scopePrefix, frequency, serialized, -toExtractCount);
        }
        log("Extract called: freq=%d scopePrefix=%s request=%d stored=%d toExtract=%d mode=%s", frequency, scopePrefix, amount, current, toExtractCount, mode);
        return toExtractCount;
    }

    @Override
    public void persist() {}

    @Override
    public Component getDescription() {
        return Component.literal("EnderDisk @ Freq " + frequency);
    }

    public void getAvailableStacks(KeyCounter out) {
        List<AEKeyCacheEntry> entries = EnderDBManager.queryItemsByFrequency(scopePrefix, frequency);
        for (AEKeyCacheEntry entry : entries) {
            out.add(entry.aeKey(), entry.count());
        }
    }

    @Override
    public boolean isPreferredStorageFor(AEKey what, IActionSource source) {
        if (!(what instanceof AEItemKey itemKey)) return false;
        byte[] serialized = serializeItemStackToBytes(itemKey.toStack());
        if (serialized.length == 0) return false;
        long storedCount = EnderDBManager.getItemCount(scopePrefix, frequency, serialized);
        return storedCount > 0;
    }

    public static byte[] serializeItemStackToBytes(ItemStack stack) {
        try {
            HolderLookup.Provider provider = ServerLifecycleHooks.getCurrentServer().registryAccess();
            Tag genericTag = stack.save(provider);
            if (!(genericTag instanceof CompoundTag tag)) return new byte[0];
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            NbtIo.write(tag, dos);
            dos.close();
            return baos.toByteArray();
        } catch (IOException e) { return new byte[0]; }
    }

    public static ItemStack deserializeItemStackFromBytes(byte[] data) {
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            DataInputStream dis = new DataInputStream(bais);
            CompoundTag tag = NbtIo.read(dis);
            dis.close();
            HolderLookup.Provider provider = ServerLifecycleHooks.getCurrentServer().registryAccess();
            return ItemStack.parse(provider, tag).orElse(ItemStack.EMPTY);
        } catch (IOException e) { return ItemStack.EMPTY; }
    }

    private static void log(String format, Object... args) {
        if (DEBUG_LOG) System.out.printf("[EnderDiskInventory] " + format + "%n", args);
    }

    private static class Handler implements ICellHandler {
        @Override public boolean isCell(ItemStack is) { return is != null && is.getItem() instanceof EnderDiskItem; }
        @Override public @Nullable StorageCell getCellInventory(ItemStack is, @Nullable appeng.api.storage.cells.ISaveProvider host) {
            return isCell(is) ? new EnderDiskInventory(is) : null;
        }
    }
}
