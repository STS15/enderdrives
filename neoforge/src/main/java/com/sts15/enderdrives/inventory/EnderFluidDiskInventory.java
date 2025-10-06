package com.sts15.enderdrives.inventory;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.ICellHandler;
import appeng.api.storage.cells.StorageCell;
import appeng.blockentity.storage.DriveBlockEntity;
import appeng.items.contents.CellConfig;
import appeng.util.ConfigInventory;
import com.sts15.enderdrives.db.EnderFluidDBManager;
import com.sts15.enderdrives.db.FluidKeyCacheEntry;
import com.sts15.enderdrives.integration.DriveBlockEntityAccessor;
import com.sts15.enderdrives.items.EnderFluidDiskItem;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import static com.sts15.enderdrives.db.EnderFluidDBManager.running;

// Amounts are in mB
public class EnderFluidDiskInventory implements StorageCell {

    private static final Logger LOGGER = LogManager.getLogger("EnderDrives");
    private static final boolean DEBUG_LOG = false;
    private final ItemStack stack;
    private final int frequency;
    private final int typeLimit;
    private final String scopePrefix;
    private final boolean disabled;
    public static final ICellHandler HANDLER = new Handler();

    public EnderFluidDiskInventory(ItemStack stack) {
        if (!(stack.getItem() instanceof EnderFluidDiskItem item)) { throw new IllegalArgumentException("Item is not an EnderFluidDisk!"); }
        this.stack = stack;
        this.frequency = EnderFluidDiskItem.getFrequency(stack);
        this.typeLimit = item.getTypeLimit();
        this.scopePrefix = EnderFluidDiskItem.getSafeScopePrefix(stack);
        this.disabled = item.isDisabled(stack);
    }

    @Override
    public CellState getStatus() {
        if (disabled) return CellState.FULL;
        int typesUsed = EnderFluidDBManager.getTypeCount(scopePrefix, frequency);
        return EnderDiskInventory.calculateCellState(typesUsed, typeLimit);
    }

    @Override
    public double getIdleDrain() {
        if (disabled) return 0.0;

        long totalMb = Math.max(0L, EnderFluidDBManager.getTotalAmount(scopePrefix, frequency));
        double buckets = totalMb / 1000.0; // normalize to buckets

        double base = 100.0;
        double scale = 0.03;
        double exp = 0.70;

        return base + (scale * Math.pow(buckets, exp));
    }

    @Override
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (disabled) return 0;
        int transferMode = EnderFluidDiskItem.getTransferMode(stack);
        if (transferMode == 2) return 0; // output-only
        if (!(what instanceof AEFluidKey fluidKey)) return 0;
        if (!passesFilter(what)) return 0;
        if (!running) {
            log("Fluid DB not ready for inserts.");
            return 0;
        }

        byte[] serialized = serializeFluidStackToBytes(fluidKey.toStack(1));
        if (serialized.length == 0) return 0;

        long existing = EnderFluidDBManager.getFluidAmount(scopePrefix, frequency, serialized);
        boolean isNewType = existing == 0;
        if (isNewType && EnderFluidDBManager.getTypeCountInclusive(scopePrefix, frequency) >= typeLimit) return 0;

        if (mode == Actionable.MODULATE) {
            EnderFluidDBManager.saveFluid(scopePrefix, frequency, serialized, amount);
            pingDriveForUpdate(source);
        }

        log("Fluid insert: freq=%d scope=%s amount=%d newType=%s mode=%s", frequency, scopePrefix, amount, isNewType, mode);
        return amount;
    }

    @Override
    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (disabled) return 0;
        int transferMode = EnderFluidDiskItem.getTransferMode(stack);
        if (transferMode == 1) return 0; // input-only
        if (!(what instanceof AEFluidKey fluidKey)) return 0;

        byte[] serialized = serializeFluidStackToBytes(fluidKey.toStack(1));
        if (serialized.length == 0) return 0;

        long stored = EnderFluidDBManager.getFluidAmount(scopePrefix, frequency, serialized);
        long toExtract = Math.min(stored, amount);

        if (toExtract > 0 && mode == Actionable.MODULATE) {
            EnderFluidDBManager.saveFluid(scopePrefix, frequency, serialized, -toExtract);
            stack.setPopTime(5);
            pingDriveForUpdate(source);
        }

        log("Fluid extract: freq=%d scope=%s request=%d stored=%d toExtract=%d mode=%s",
                frequency, scopePrefix, amount, stored, toExtract, mode);
        getStatus(); // nudge status cache chain
        return toExtract;
    }

    private boolean passesFilter(AEKey key) {
        ConfigInventory configInv = CellConfig.create(Set.of(AEKeyType.fluids()), stack);
        for (int i = 0; i < configInv.size(); i++) {
            AEKey slotKey = configInv.getKey(i);
            if (slotKey == null) continue;
            if (slotKey.equals(key)) return true;
        }
        return configInv.keySet().isEmpty();
    }

    @Override
    public void persist() {
        // no-op (same as item implementation)
    }

    @Override
    public Component getDescription() {
        return Component.literal("EnderFluidDisk @ Freq " + frequency);
    }

    public void getAvailableStacks(KeyCounter out) {
        List<FluidKeyCacheEntry> entries = EnderFluidDBManager.queryFluidsByFrequency(scopePrefix, frequency);
        for (FluidKeyCacheEntry entry : entries) {
            out.add(entry.aeKey(), entry.count());
        }
    }

    @Override
    public boolean isPreferredStorageFor(AEKey what, IActionSource source) {
        if (!(what instanceof AEFluidKey fk)) return false;
        byte[] serialized = serializeFluidStackToBytes(fk.toStack(1)); // 1 mB key identity is enough
        if (serialized.length == 0) return false;
        long stored = EnderFluidDBManager.getFluidAmount(scopePrefix, frequency, serialized);
        return stored > 0;
    }

    public ItemStack getContainerItem() {
        return this.stack;
    }

    public static CellState getCellStateForStack(ItemStack stack) {
        if (!(stack.getItem() instanceof EnderFluidDiskItem fluidDiskItem)) {
            return CellState.ABSENT;
        }
        int freq = EnderFluidDiskItem.getFrequency(stack);
        String scope = EnderFluidDiskItem.getSafeScopePrefix(stack);
        int typesUsed = EnderFluidDBManager.getTypeCount(scope, freq);
        int typeLimit = fluidDiskItem.getTypeLimit();
        return calculateCellState(typesUsed, typeLimit);
    }

    public static CellState calculateCellState(int typesUsed, int typeLimit) {
        if (typesUsed == 0) return CellState.EMPTY;
        if (typesUsed >= typeLimit) return CellState.FULL;
        float usagePercent = (float) typesUsed / typeLimit;
        return usagePercent >= 0.75f ? CellState.TYPES_FULL : CellState.NOT_EMPTY;
    }

    private void pingDriveForUpdate(IActionSource source) {
        var server = ServerLifecycleHooks.getCurrentServer();
        server.execute(() -> {
            source.machine().ifPresent(host -> {
                var node = host.getActionableNode();
                if (node == null) return;
                IGrid grid = node.getGrid();
                if (grid == null) return;

                var drives = grid.getMachines(DriveBlockEntity.class);
                for (DriveBlockEntity drive : drives) {
                    for (int i = 0; i < drive.getCellCount(); i++) {
                        ItemStack slot = drive.getInternalInventory().getStackInSlot(i);
                        if (!slot.isEmpty() && slot.getItem() instanceof EnderFluidDiskItem) {
                            ((DriveBlockEntityAccessor) drive).enderdrives$triggerVisualUpdate();
                            ((DriveBlockEntityAccessor) drive).enderdrives$recalculateIdlePower();
                            break;
                        }
                    }
                }
            });
        });
    }

    public static byte[] serializeFluidStackToBytes(FluidStack stack) {
        try {
            HolderLookup.Provider provider = ServerLifecycleHooks.getCurrentServer().registryAccess();
            Tag genericTag = stack.save(provider); // writes data-component-aware NBT
            if (!(genericTag instanceof CompoundTag tag)) return new byte[0];

            ByteArrayOutputStream baos = new ByteArrayOutputStream(256);
            try (DataOutputStream dos = new DataOutputStream(baos)) {
                NbtIo.write(tag, dos);
            }
            return baos.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    public static FluidStack deserializeFluidStackFromBytes(byte[] data) {
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data))) {
            CompoundTag tag = NbtIo.read(dis);
            HolderLookup.Provider provider = ServerLifecycleHooks.getCurrentServer().registryAccess();
            // parseOptional returns EMPTY if invalid
            return FluidStack.parseOptional(provider, tag);
        } catch (IOException e) {
            return FluidStack.EMPTY;
        }
    }

    private static void log(String format, Object... args) {
        if (DEBUG_LOG) LOGGER.info("[EnderFluidDiskInventory] " + String.format(format, args));
    }

    private static class Handler implements ICellHandler {
        @Override
        public boolean isCell(ItemStack is) {
            return is != null && is.getItem() instanceof EnderFluidDiskItem;
        }

        @Override
        public @Nullable StorageCell getCellInventory(ItemStack is, @Nullable appeng.api.storage.cells.ISaveProvider host) {
            return isCell(is) ? new EnderFluidDiskInventory(is) : null;
        }
    }
}
