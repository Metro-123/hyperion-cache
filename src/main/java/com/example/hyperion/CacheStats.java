package com.example.hyperion;

/**
 * Immutable snapshot of a cache's counters.
 */
public record CacheStats(
        long hitCount,
        long missCount,
        long loadSuccessCount,
        long loadFailureCount,
        long evictionCount,
        long expirationCount,
        long size) {

    public long requestCount() {
        return hitCount + missCount;
    }

    /** Fraction of lookups served from the cache. 1.0 when there were no requests. */
    public double hitRate() {
        long total = requestCount();
        return total == 0 ? 1.0 : (double) hitCount / total;
    }

    public double missRate() {
        return 1.0 - hitRate();
    }

    public long loadCount() {
        return loadSuccessCount + loadFailureCount;
    }

    @Override
    public String toString() {
        return String.format(
                "CacheStats{size=%d, hits=%d, misses=%d, hitRate=%.3f, evictions=%d, expirations=%d, "
                        + "loads(ok/fail)=%d/%d}",
                size, hitCount, missCount, hitRate(), evictionCount, expirationCount,
                loadSuccessCount, loadFailureCount);
    }
}