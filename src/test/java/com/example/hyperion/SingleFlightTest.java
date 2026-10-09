package com.example.hyperion;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SingleFlightTest {

    @Test
    void concurrentLoadsForSameKeyRunOnce() throws Exception {
        HyperionCache<String, String> cache = HyperionCache.<String, String>builder()
                .maximumSize(100)
                .build();
        AtomicInteger loads = new AtomicInteger();

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return cache.get("key", k -> {
                    loads.incrementAndGet();
                    Thread.sleep(50); // simulate slow I/O
                    return "loaded";
                });
            }));
        }

        start.countDown();
        for (Future<String> result : results) {
            assertEquals("loaded", result.get(5, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertEquals(1, loads.get(), "the loader must run exactly once for concurrent callers");
        assertEquals(1, cache.stats().loadSuccessCount());
        assertEquals("loaded", cache.getIfPresent("key"));
    }

    @Test
    void loaderFailureIsPropagatedAndCounted() {
        HyperionCache<String, String> cache = HyperionCache.<String, String>builder()
                .maximumSize(10)
                .build();

        assertThrows(IllegalStateException.class, () ->
                cache.get("bad", k -> {
                    throw new IllegalStateException("boom");
                }));

        assertEquals(1, cache.stats().loadFailureCount());
    }

    @Test
    void cachedValueShortCircuitsLoader() throws Exception {
        HyperionCache<String, String> cache = HyperionCache.<String, String>builder()
                .maximumSize(10)
                .build();
        cache.put("key", "present");
        AtomicInteger loads = new AtomicInteger();

        String value = cache.get("key", k -> {
            loads.incrementAndGet();
            return "loaded";
        });

        assertEquals("present", value);
        assertEquals(0, loads.get());
    }

    @Test
    void concurrentMixedAccessDoesNotCorruptCache() throws Exception {
        HyperionCache<Integer, Integer> cache = HyperionCache.<Integer, Integer>builder()
                .maximumSize(64)
                .build();

        int threads = 8;
        int ops = 20_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            final int seed = t;
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < ops; i++) {
                    int key = (seed * ops + i) % 256;
                    cache.put(key, key);
                    cache.getIfPresent(key);
                    if ((i & 1023) == 0) {
                        cache.cleanUp();
                    }
                }
                return null;
            }));
        }

        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertTrue(cache.estimatedSize() <= 64, "size was " + cache.estimatedSize());
        assertTrue(cache.estimatedWeight() <= 64);
    }
}