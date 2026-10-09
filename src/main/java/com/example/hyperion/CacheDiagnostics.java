package com.example.hyperion;

/**
 * A read-only snapshot of the cache's internal structure, exposed for observability and
 * the bundled UI. Where {@link CacheStats} reports counters, this reports the live shape
 * of the W-TinyLFU segments and the timer wheel.
 *
 * @param estimatedSize     number of entries currently held
 * @param estimatedWeight   total weight currently held
 * @param maximumWeight     configured maximum weight
 * @param windowSize        entries in the admission "window"
 * @param probationSize     entries in the probation segment
 * @param protectedSize     entries in the protected (hot) segment
 * @param windowWeight      total weight in the window
 * @param probationWeight   total weight in probation
 * @param protectedWeight   total weight in protected
 * @param scheduledTimers   entries currently scheduled on the timer wheel
 */
public record CacheDiagnostics(
        long estimatedSize,
        long estimatedWeight,
        long maximumWeight,
        int windowSize,
        int probationSize,
        int protectedSize,
        long windowWeight,
        long probationWeight,
        long protectedWeight,
        int scheduledTimers) {
}
