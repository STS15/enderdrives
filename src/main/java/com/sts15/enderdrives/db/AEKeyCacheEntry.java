package com.sts15.enderdrives.db;

import appeng.api.stacks.AEItemKey;

public record AEKeyCacheEntry(AEKey dbKey, AEItemKey aeKey, long count) {}

