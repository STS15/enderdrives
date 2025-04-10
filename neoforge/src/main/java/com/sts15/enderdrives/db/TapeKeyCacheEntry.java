package com.sts15.enderdrives.db;

import appeng.api.stacks.AEItemKey;

public record TapeKeyCacheEntry (byte[] itemBytes, AEItemKey aeKey, long count) { }
