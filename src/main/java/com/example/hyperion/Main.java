package com.example.hyperion;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A guided tour of Hyperion. Run with:
 * <pre>
 *   mvn -q compile
 *   java -cp target/classes com.example.hyperion.Main
 * </pre>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        evictionDemo();
        ttlDemo();
        singleFlightDemo();
        statsDemo();
    }

    private static void evictionDemo() {
        System.out.println("== W-TinyLFU eviction ==");
        HyperionCache<String, String> cache = HyperionCache.<String, String>builder()
                .maximumSize(100)
                .build();

        for (int i = 0; i < 20; i++) {
            String key = "hot-" + i;
            cache.put(key, "value");
            for (int r = 0; r < 10; r++) {
                cache.getIfPresent(key);
            }
        }
        long hitsBefore = cache.stats().hitCount();

        for (int i = 0; i < 500; i++) {
            cache.put("scan-" + i, "x"); // one-shot keys
        }

        int survivors = 0;
        for (int i = 0; i < 20; i++) {
            if (cache.getIfPresent("hot-" + i) != null) {
                survivors++;
            }
        }
        System.out.printf("  working set survived a 500-key scan: %d/20 (size=%d, hits grew by %d)%n%n",
                survivors, cache.estimatedSize(), cache.stats().hitCount() - hitsBefore);
    }

    private static void ttlDemo() throws InterruptedException {
        System.out.println("== Per-entry TTL (hashed timer wheel) ==");
        HyperionCache<String, String> cache = HyperionCache.<String, String>builder()
                .maximumSize(100)
                .build();

        cache.put("session", "abc", Duration.ofMillis(300));
        cache.put("config", "forever");
        System.out.println("  immediately: session=" + cache.getIfPresent("session")
                + ", config=" + cache.getIfPresent("config"));

        Thread.sleep(450);
        cache.cleanUp();
        System.out.println("  after 450ms: session=" + cache.getIfPresent("session")
                + ", config=" + cache.getIfPresent("config")
                + ", expirations=" + cache.stats().expirationCount() + "\n");
    }

    private static void singleFlightDemo() throws Exception {
        System.out.println("== Single-flight loading ==");
        HyperionCache<String, String> cache = HyperionCache.<String, String>builder()
                .maximumSize(100)
                .build();

        AtomicInteger loaderCalls = new AtomicInteger();
        CacheLoader<String, String> slowLoader = key -> {
            loaderCalls.incrementAndGet();
            Thread.sleep(200); // pretend this is a slow database call
            return "value-from-db-for-" + key;
        };

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return cache.get("shared-key", slowLoader);
            }));
        }
        start.countDown();
        String first = results.get(0).get();
        for (Future<String> result : results) {
            result.get();
        }
        pool.shutdown();
        System.out.printf("  %d concurrent callers -> loader invoked %d time(s): \"%s\"%n%n",
                threads, loaderCalls.get(), first);
    }

    private static void statsDemo() {
        System.out.println("== Statistics ==");
        HyperionCache<Integer, Integer> cache = HyperionCache.<Integer, Integer>builder()
                .maximumSize(50)
                .build();
        for (int i = 0; i < 200; i++) {
            cache.put(i, i);
        }
        for (int i = 0; i < 200; i++) {
            cache.getIfPresent(i % 80);
        }
        System.out.println("  " + cache.stats());
    }
}