package com.example.hyperion;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HyperionCacheTest {

    @Test
    void putAndGetRoundTrip() {
        HyperionCache<String, String> cache = HyperionCache.<String, String>builder()
                .maximumSize(10)
                .build();
        cache.put("a", "1");
        assertEquals("1", cache.getIfPresent("a"));
        assertEquals(1, cache.estimatedSize());
        assertNull(cache.getIfPresent("missing"));
    }

    @Test
    void nullKeyIsRejected() {
        HyperionCache<String, String> cache = HyperionCache.<String, String>builder()
                .maximumSize(10)
                .build();
        assertThrows(NullPointerException.class, () -> cache.put(null, "1"));
        assertThrows(NullPointerException.class, () -> cache.getIfPresent(null));
    }

    @Test
    void builderRequiresPositiveCapacity() {
        assertThrows(IllegalStateException.class,
                () -> HyperionCache.<String, String>builder().build());
    }

    @Test
    void sizeNeverExceedsMaximum() {
        HyperionCache<Integer, Integer> cache = HyperionCache.<Integer, Integer>builder()
                .maximumSize(10)
                .build();
        for (int i = 0; i < 1000; i++) {
            cache.put(i, i);
        }
        assertTrue(cache.estimatedSize() <= 10, "size was " + cache.estimatedSize());
        assertTrue(cache.estimatedWeight() <= 10);
    }

    @Test
    void frequentlyUsedEntriesResistAColdScan() {
        HyperionCache<String, String> cache = HyperionCache.<String, String>builder()
                .maximumSize(100)
                .build();

        // Warm a working set: each hot key is requested repeatedly.
        for (int i = 0; i < 20; i++) {
            String key = "hot-" + i;
            cache.put(key, "value");
            for (int r = 0; r < 10; r++) {
                cache.getIfPresent(key);
            }
        }

        // A one-shot scan of unique keys must not flush the working set (TinyLFU admission).
        for (int i = 0; i < 500; i++) {
            cache.put("cold-" + i, "x");
        }

        for (int i = 0; i < 20; i++) {
            assertNotNull(cache.getIfPresent("hot-" + i), "hot-" + i + " should have survived");
        }
    }

    @Test
    void updateReplacesValueWithoutGrowing() {
        HyperionCache<String, String> cache = HyperionCache.<String, String>builder()
                .maximumSize(10)
                .build();
        cache.put("a", "1");
        cache.put("a", "2");
        assertEquals("2", cache.getIfPresent("a"));
        assertEquals(1, cache.estimatedSize());
    }

    @Test
    void invalidateRemovesAndReturnsValue() {
        HyperionCache<String, String> cache = HyperionCache.<String, String>builder()
                .maximumSize(10)
                .build();
        cache.put("a", "1");
        assertEquals("1", cache.invalidate("a"));
        assertNull(cache.invalidate("a"));
        assertEquals(0, cache.estimatedSize());
    }

    @Test
    void invalidateAllClearsEverything() {
        HyperionCache<Integer, Integer> cache = HyperionCache.<Integer, Integer>builder()
                .maximumSize(10)
                .build();
        for (int i = 0; i < 5; i++) {
            cache.put(i, i);
        }
        cache.invalidateAll();
        assertEquals(0, cache.estimatedSize());
        assertNull(cache.getIfPresent(0));
    }

    @Test
    void ttlExpiresEntries() throws InterruptedException {
        HyperionCache<String, String> cache = HyperionCache.<String, String>builder()
                .maximumSize(10)
                .build();
        cache.put("k", "v", Duration.ofMillis(40));
        assertEquals("v", cache.getIfPresent("k"));

        Thread.sleep(120);
        cache.cleanUp();
        assertNull(cache.getIfPresent("k"));
        assertTrue(cache.stats().expirationCount() >= 1);
    }

    @Test
    void defaultTtlFromBuilderApplies() throws InterruptedException {
        HyperionCache<String, String> cache = HyperionCache.<String, String>builder()
                .maximumSize(10)
                .expireAfterWrite(Duration.ofMillis(40))
                .build();
        cache.put("k", "v");
        assertEquals("v", cache.getIfPresent("k"));

        Thread.sleep(120);
        assertNull(cache.getIfPresent("k"));
    }

    @Test
    void weightZeroEntriesAreNotCached() {
        HyperionCache<String, String> cache = HyperionCache.<String, String>builder()
                .maximumWeight(100)
                .weigher((key, value) -> 0)
                .build();
        cache.put("a", "1");
        assertEquals(0, cache.estimatedSize());
    }

    @Test
    void weightBoundingRespected() {
        HyperionCache<String, String> cache = HyperionCache.<String, String>builder()
                .maximumWeight(10)
                .weigher((key, value) -> value.length())
                .build();
        for (int i = 0; i < 50; i++) {
            cache.put("k" + i, "xxxxxxxx"); // weight 8 each
        }
        assertTrue(cache.estimatedWeight() <= 10, "weight was " + cache.estimatedWeight());
        assertTrue(cache.estimatedSize() <= 1);
    }

    @Test
    void statsTrackHitsAndMisses() {
        HyperionCache<String, String> cache = HyperionCache.<String, String>builder()
                .maximumSize(10)
                .build();
        cache.put("a", "1");
        cache.getIfPresent("a");
        cache.getIfPresent("a");
        cache.getIfPresent("missing");

        CacheStats stats = cache.stats();
        assertEquals(2, stats.hitCount());
        assertEquals(1, stats.missCount());
        assertEquals(2.0 / 3.0, stats.hitRate(), 1e-9);
    }
}