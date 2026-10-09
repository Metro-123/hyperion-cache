package com.example.hyperion;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the observability surface added for the bundled UI: {@link HyperionCache#diagnostics()}
 * and {@link HyperionCache#snapshot()}. These must be consistent with the cache's own counters
 * and must never mutate state.
 */
class DiagnosticsTest {

    @Test
    void diagnosticsReflectSizeAndCapacity() {
        HyperionCache<String, String> cache = HyperionCache.<String, String>builder()
                .maximumSize(100)
                .build();

        for (int i = 0; i < 10; i++) {
            cache.put("k" + i, "v" + i);
        }

        CacheDiagnostics d = cache.diagnostics();
        assertEquals(10, d.estimatedSize());
        assertEquals(10, d.estimatedWeight());
        assertEquals(100, d.maximumWeight());
        assertEquals(0, d.scheduledTimers(), "no entry has a TTL yet");
        assertEquals(10, d.windowSize() + d.probationSize() + d.protectedSize(),
                "every entry lives in exactly one segment");
        assertEquals(10, d.windowWeight() + d.probationWeight() + d.protectedWeight());
    }

    @Test
    void scheduledTimersTrackTtls() {
        HyperionCache<String, String> cache = HyperionCache.<String, String>builder()
                .maximumSize(100)
                .build();

        cache.put("forever", "x");
        assertEquals(0, cache.diagnostics().scheduledTimers());

        cache.put("short", "y", Duration.ofMinutes(5));
        cache.put("long", "z", Duration.ofHours(2));
        assertEquals(2, cache.diagnostics().scheduledTimers());

        cache.put("short", "y2"); // update without TTL clears its timer
        assertEquals(1, cache.diagnostics().scheduledTimers());

        cache.invalidate("long");
        assertEquals(0, cache.diagnostics().scheduledTimers());
    }

    @Test
    void snapshotExposesSegmentsAndTtl() {
        HyperionCache<String, String> cache = HyperionCache.<String, String>builder()
                .maximumSize(100)
                .build();

        cache.put("plain", "value");
        cache.put("timed", "value", Duration.ofMinutes(10));
        // Frequent reads promote an entry out of the window segment.
        for (int i = 0; i < 5; i++) {
            cache.getIfPresent("timed");
        }

        List<CacheEntryView> views = cache.snapshot();
        assertEquals(2, views.size());

        CacheEntryView plain = find(views, "plain");
        assertEquals("value", plain.value());
        assertEquals(-1, plain.ttlRemainingMs(), "no TTL -> -1 sentinel");
        assertTrue(List.of("window", "probation", "protected").contains(plain.segment()));

        CacheEntryView timed = find(views, "timed");
        assertTrue(timed.ttlRemainingMs() > 0, "a finite TTL is reported in milliseconds");
        assertFalse(timed.segment().equals("none"));
    }

    @Test
    void snapshotAndDiagnosticsDoNotDisturbCounters() {
        HyperionCache<String, String> cache = HyperionCache.<String, String>builder()
                .maximumSize(100)
                .build();
        cache.put("a", "1");
        cache.getIfPresent("a"); // one registered hit
        CacheStats before = cache.stats();

        cache.diagnostics();
        cache.snapshot();
        cache.diagnostics();

        CacheStats after = cache.stats();
        assertEquals(before.hitCount(), after.hitCount());
        assertEquals(before.missCount(), after.missCount());
        assertEquals(before.size(), after.size());
    }

    private static CacheEntryView find(List<CacheEntryView> views, String key) {
        return views.stream()
                .filter(v -> v.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing entry: " + key));
    }

    @Test
    void snapshotIsSortedAndComplete() {
        HyperionCache<Integer, Integer> cache = HyperionCache.<Integer, Integer>builder()
                .maximumSize(50)
                .build();
        for (int i = 20; i >= 1; i--) {
            cache.put(i, i * i);
        }
        List<CacheEntryView> views = cache.snapshot();
        assertEquals(20, views.size());
        assertNotNull(views.get(0).key());
        // Keys are stringified and sorted lexicographically for stable display.
        for (int i = 1; i < views.size(); i++) {
            assertTrue(views.get(i - 1).key().compareTo(views.get(i).key()) <= 0);
        }
    }
}
