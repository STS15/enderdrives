package com.sts15.enderdrives.db;

import net.minecraft.world.item.ItemStack;

import java.util.List;

public record DiskTypeInfo(
        int typeCount,
        int typeLimit,
        long totalItemCount,
        List<ItemStack> topStacks
) {}

