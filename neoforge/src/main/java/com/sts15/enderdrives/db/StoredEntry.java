package com.sts15.enderdrives.db;

import appeng.api.stacks.AEItemKey;

public record StoredEntry(long count, AEItemKey aeKey) {
    public static final StoredEntry EMPTY = new StoredEntry(0L, null);
}
