package com.sts15.enderdrives.db;

import net.neoforged.neoforge.fluids.FluidStack;

import java.util.List;

public record FluidDiskTypeInfo(
        int typeCount,
        int typeLimit,
        long totalAmount,
        List<FluidStack> topFluids
) {}
