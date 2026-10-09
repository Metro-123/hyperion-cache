package com.example.hyperion.ui;

import com.example.hyperion.HyperionCache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Runs head-to-head cache workloads so the UI can <b>show</b>, not just claim, why
 * W-TinyLFU beats a naive LRU. Two scenarios are offered:
 *
 * <ul>
 *   <li><b>scan</b> — a hot working set is warmed, then flooded by a burst of one-shot
 *       keys. A plain LRU lets the scan flush the hot set; TinyLFU's frequency admission
 *       refuses to admit the worthless newcomers. We report how much of the hot set
 *       survived and the scan-phase hit rate.</li>
 *   <li><b>zipf</b> — a realistic skewed (Zipfian) access distribution. We report the
 *       steady-state hit rate of each cache.</li>
 * </ul>
 *
 * An LRU is included purely as the baseline; it is implemented inline with
 * {@link LinkedHashMap} so the demo remains dependency-free.
 */
public final class DemoEngine {

    private static final long SEED = 42L;

    public String run(String scenario, int capacity) {
        return switch (scenario) {
            case "zipf" -> zipf(capacity);
            case "scan" -> scan(capacity);
            default -> scan(capacity);
        };
    }

    // ------------------------------------------------------------------ scenarios

    private String scan(int capacity) {
        int hotSize = Math.max(10, capacity / 2);
        int scanCount = Math.max(100, capacity * 10);

        CacheUnderTest hyperion = hyperionCache(capacity);
        CacheUnderTest lru = lruCache(capacity);

        Result h = scanWorkload(hyperion, hotSize, scanCount);
        Result l = scanWorkload(lru, hotSize, scanCount);

        return Json.obj()
                .field("scenario", "scan")
                .field("title", "Scan resistance")
                .field("blurb", "A hot working set of " + hotSize + " keys is warmed, then hit by "
                        + scanCount + " one-shot keys. Watch which cache keeps its hot data.")
                .field("capacity", capacity)
                .rawField("results", resultsArray(h, l, hotSize))
                .end()
                .done();
    }

    private String zipf(int capacity) {
        int universe = Math.max(100, capacity * 20);
        int operations = Math.max(2_000, capacity * 200);

        CacheUnderTest hyperion = hyperionCache(capacity);
        CacheUnderTest lru = lruCache(capacity);

        Result h = zipfWorkload(hyperion, universe, operations);
        Result l = zipfWorkload(lru, universe, operations);

        return Json.obj()
                .field("scenario", "zipf")
                .field("title", "Skewed (Zipfian) access")
                .field("blurb", "A realistic power-law workload over " + universe + " keys, "
                        + operations + " requests. This is the mix real caches actually see.")
                .field("capacity", capacity)
                .rawField("results", resultsArray(h, l, 0))
                .end()
                .done();
    }

    // ------------------------------------------------------------------ workloads

    private static Result scanWorkload(CacheUnderTest cache, int hotSize, int scanCount) {
        // Warm phase: repeatedly read the hot set so its frequency dominates.
        for (int i = 0; i < hotSize; i++) {
            String key = "hot-" + i;
            cache.put(key, "v" + i);
            for (int r = 0; r < 10; r++) {
                cache.get(key);
            }
        }

        // Scan phase: a flood of first-and-only-once keys.
        long hits = 0;
        long misses = 0;
        for (int i = 0; i < scanCount; i++) {
            String key = "scan-" + i;
            if (cache.get(key) == null) {
                misses++;
                cache.put(key, "s" + i);
            } else {
                hits++;
            }
        }

        int survivors = 0;
        for (int i = 0; i < hotSize; i++) {
            if (cache.get("hot-" + i) != null) {
                survivors++;
            }
        }

        double hitRate = (hits + misses) == 0 ? 0.0 : (double) hits / (hits + misses);
        return new Result(cache.name(), hitRate, hits, misses, survivors, hotSize);
    }

    private static Result zipfWorkload(CacheUnderTest cache, int universe, int operations) {
        double[] cdf = zipfCdf(universe, 1.2);
        Random random = new Random(SEED);
        long hits = 0;
        long misses = 0;
        for (int i = 0; i < operations; i++) {
            int rank = sample(cdf, random.nextDouble()) + 1;
            String key = "key-" + rank;
            if (cache.get(key) == null) {
                misses++;
                cache.put(key, Integer.toString(rank));
            } else {
                hits++;
            }
        }
        double hitRate = (hits + misses) == 0 ? 0.0 : (double) hits / (hits + misses);
        return new Result(cache.name(), hitRate, hits, misses, -1, 0);
    }

    private static String resultsArray(Result hyperion, Result lru, int hotSize) {
        StringBuilder sb = new StringBuilder("[");
        sb.append(resultJson(hyperion, hotSize));
        sb.append(',');
        sb.append(resultJson(lru, hotSize));
        sb.append(']');
        return sb.toString();
    }

    private static String resultJson(Result r, int hotSize) {
        Json.Writer w = Json.obj()
                .field("name", r.name)
                .field("hitRate", r.hitRate)
                .field("hits", r.hits)
                .field("misses", r.misses);
        if (r.hotSize > 0) {
            w.field("hotSurvivors", r.hotSurvivors)
                    .field("hotSize", r.hotSize)
                    .field("survivalRate", (double) r.hotSurvivors / r.hotSize);
        }
        return w.end().done();
    }

    // ------------------------------------------------------------------ adapters

    private interface CacheUnderTest {
        String name();

        String get(String key);

        void put(String key, String value);
    }

    private static CacheUnderTest hyperionCache(int capacity) {
        HyperionCache<String, String> cache = HyperionCache.<String, String>builder()
                .maximumSize(capacity)
                .build();
        return new CacheUnderTest() {
            @Override
            public String name() {
                return "Hyperion (W-TinyLFU)";
            }

            @Override
            public String get(String key) {
                return cache.getIfPresent(key);
            }

            @Override
            public void put(String key, String value) {
                cache.put(key, value);
            }
        };
    }

    private static CacheUnderTest lruCache(int capacity) {
        Map<String, String> lru = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > capacity;
            }
        };
        return new CacheUnderTest() {
            @Override
            public String name() {
                return "Naive LRU";
            }

            @Override
            public String get(String key) {
                return lru.get(key);
            }

            @Override
            public void put(String key, String value) {
                lru.put(key, value);
            }
        };
    }

    // ------------------------------------------------------------------ zipf math

    private static double[] zipfCdf(int n, double exponent) {
        double[] cdf = new double[n];
        double sum = 0.0;
        for (int i = 1; i <= n; i++) {
            sum += 1.0 / Math.pow(i, exponent);
            cdf[i - 1] = sum;
        }
        for (int i = 0; i < n; i++) {
            cdf[i] /= sum;
        }
        return cdf;
    }

    private static int sample(double[] cdf, double u) {
        int lo = 0;
        int hi = cdf.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cdf[mid] < u) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private record Result(String name, double hitRate, long hits, long misses,
                          int hotSurvivors, int hotSize) {
    }
}
