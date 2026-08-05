package com.sts15.enderdrives.util;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

public final class StackCodecHelper {
    private StackCodecHelper() {
    }

    public static CompoundTag encodeItemStack(HolderLookup.Provider registries, ItemStack stack) {
        var ops = registries.createSerializationContext(NbtOps.INSTANCE);
        return ItemStack.CODEC.encodeStart(ops, stack)
                .getOrThrow()
                .asCompound()
                .orElseThrow(() -> new IllegalStateException("Item stack codec did not produce a compound tag"));
    }

    public static ItemStack decodeItemStack(HolderLookup.Provider registries, CompoundTag tag) {
        var ops = registries.createSerializationContext(NbtOps.INSTANCE);
        return ItemStack.CODEC.parse(ops, tag).getOrThrow();
    }

    public static CompoundTag encodeFluidStack(HolderLookup.Provider registries, FluidStack stack) {
        var ops = registries.createSerializationContext(NbtOps.INSTANCE);
        return FluidStack.CODEC.encodeStart(ops, stack)
                .getOrThrow()
                .asCompound()
                .orElseThrow(() -> new IllegalStateException("Fluid stack codec did not produce a compound tag"));
    }

    public static FluidStack decodeFluidStack(HolderLookup.Provider registries, CompoundTag tag) {
        var ops = registries.createSerializationContext(NbtOps.INSTANCE);
        return FluidStack.CODEC.parse(ops, tag).getOrThrow();
    }
}
