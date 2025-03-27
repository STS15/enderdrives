package com.sts15.enderdrives.db;

import java.util.Arrays;
import java.util.Objects;

public record AEKey(String scope, int freq, byte[] itemBytes) implements Comparable<AEKey> {

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AEKey)) return false;
        AEKey aeKey = (AEKey) o;
        return freq == aeKey.freq &&
                scope.equals(aeKey.scope) &&
                Arrays.equals(itemBytes, aeKey.itemBytes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scope, freq, Arrays.hashCode(itemBytes));
    }

    @Override
    public int compareTo(AEKey o) {
        int scopeCmp = this.scope.compareTo(o.scope);
        if (scopeCmp != 0) return scopeCmp;

        int freqCmp = Integer.compare(this.freq, o.freq);
        if (freqCmp != 0) return freqCmp;

        return compareByteArrays(this.itemBytes, o.itemBytes);
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
        return scope + "[" + freq + "]~" + Arrays.toString(itemBytes);
    }
}
