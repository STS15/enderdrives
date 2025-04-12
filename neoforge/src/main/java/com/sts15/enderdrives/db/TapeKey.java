package com.sts15.enderdrives.db;

import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;

public record TapeKey(byte[] itemBytes) implements Comparable<TapeKey>, Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TapeKey other)) return false;
        return Arrays.equals(itemBytes, other.itemBytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(itemBytes);
    }

    @Override
    public int compareTo(TapeKey other) {
        return compareByteArrays(this.itemBytes, other.itemBytes);
    }

    private static int compareByteArrays(byte[] a, byte[] b) {
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int cmp = Byte.compare(a[i], b[i]);
            if (cmp != 0) return cmp;
        }
        return Integer.compare(a.length, b.length);
    }

    @Override
    public String toString() {
        return Arrays.toString(itemBytes);
    }
}
