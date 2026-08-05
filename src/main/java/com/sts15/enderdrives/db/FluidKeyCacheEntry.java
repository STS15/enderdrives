package com.sts15.enderdrives.db;

import appeng.api.stacks.AEFluidKey;

public record FluidKeyCacheEntry(AEKey dbKey, AEFluidKey aeKey, long count) {}
