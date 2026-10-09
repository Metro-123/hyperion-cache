package com.example.hyperion;

/**
 * A {@code d}-row Count-Min Sketch used to estimate how often each key has been
 * requested. TinyLFU uses these approximate frequencies to decide whether a newly
 * admitted entry is more valuable than the entry it would evict.
 *
 * <p><b>Why approximate?</b> Storing an exact counter per key would cost as much memory
 * as the cache itself. A sketch bounds four counters per key regardless of cardinality.
 *
 * <p><b>Accuracy.</b> Conservative update (only increment counters that currently equal
 * the row minimum) reduces over-estimation, and periodic halving gives the estimate a
 * recency bias so stale "hot" keys decay.
 *
 * <p>Not thread-safe; the owning cache serializes all access.
 */
final class FrequencySketch {

    private static final int ROWS = 4;
    private static final int MAX_COUNT = 15;

    private final int tableSize;
    private final int tableMask;
    private final int[] counters;
    private final int[] indices = new int[ROWS];
    private final long sampleSize;
    private long increments;

    FrequencySketch(long maximum) {
        int size = 16;
        while (size < maximum && size < (1 << 30)) {
            size <<= 1;
        }
        this.tableSize = size;
        this.tableMask = size - 1;
        this.counters = new int[ROWS * size];
        this.sampleSize = Math.max(10L, (long) size * 10L);
    }

    /** Floor of the minimum counter across the four rows: a lower bound on true count. */
    int frequency(Object key) {
        int hash = key.hashCode();
        int min = Integer.MAX_VALUE;
        for (int row = 0; row < ROWS; row++) {
            int count = counters[index(hash, row)];
            if (count < min) {
                min = count;
            }
            if (min == 0) {
                return 0;
            }
        }
        return min;
    }

    void increment(Object key) {
        int hash = key.hashCode();
        int min = Integer.MAX_VALUE;
        for (int row = 0; row < ROWS; row++) {
            int idx = index(hash, row);
            indices[row] = idx;
            int count = counters[idx];
            if (count < min) {
                min = count;
            }
        }
        if (min >= MAX_COUNT) {
            return;
        }
        // Conservative update: raise only the counters sitting at the row minimum.
        for (int row = 0; row < ROWS; row++) {
            if (counters[indices[row]] == min) {
                counters[indices[row]]++;
            }
        }
        if (++increments >= sampleSize) {
            reset();
        }
    }

    private void reset() {
        for (int i = 0; i < counters.length; i++) {
            counters[i] >>>= 1;
        }
        increments >>>= 1;
    }

    private int index(int hash, int row) {
        int h = spread(hash + row * 0x9E3779B9);
        return row * tableSize + (h & tableMask);
    }

    private static int spread(int h) {
        h ^= (h >>> 16);
        h *= 0x7feb352d;
        h ^= (h >>> 15);
        h *= 0x846ca68b;
        h ^= (h >>> 16);
        return h;
    }
}