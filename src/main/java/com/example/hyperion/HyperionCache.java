package com.example.hyperion;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Hyperion: a self-contained, production-shaped in-memory cache.
 *
 * <p>Combines four hard pieces into one coherent structure:
 * <ol>
 *   <li><b>W-TinyLFU eviction</b> — {@link TinyLfuPolicy} + {@link FrequencySketch} give
 *       near-optimal hit rates on real workloads, far better than plain LRU.</li>
 *   <li><b>O(1) per-entry TTL</b> — a {@link TimerWheel} expires entries without scanning.</li>
 *   <li><b>Single-flight loading</b> — {@link #get(Object, CacheLoader)} coalesces concurrent
 *       loads of the same key into exactly one call to the loader.</li>
 *   <li><b>Weight-based bounding</b> — a {@link Weigher} lets one entry count for many units.</li>
 * </ol>
 *
 * <p><b>Thread safety.</b> All state mutations are guarded by a single {@link ReentrantLock}.
 * This is deliberate and correct; the loader for single-flight execution runs <i>outside</i>
 * the lock so slow I/O cannot block readers. For extreme read throughput the natural next
 * step is striping the index and segments (as Caffeine does), which is noted in the README.
 *
 * <p>Keys must not be {@code null}; values may be.
 */
public final class HyperionCache<K, V> {

    private static final int WHEEL_SLOTS = 2048;
    private static final long WHEEL_TICK_NANOS = 1_000_000L; // 1 ms

    private final long maximumWeight;
    private final Weigher<K, V> weigher;
    private final long defaultTtlNanos;

    private final Map<K, Node<K, V>> index = new HashMap<>();
    private final FrequencySketch sketch;
    private final TinyLfuPolicy<K, V> policy;
    private final TimerWheel<K, V> wheel;
    private final ConcurrentHashMap<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    private long hitCount;
    private long missCount;
    private long evictionCount;
    private long expirationCount;
    private final AtomicLong loadSuccessCount = new AtomicLong();
    private final AtomicLong loadFailureCount = new AtomicLong();

    private HyperionCache(Builder<K, V> builder) {
        this.maximumWeight = builder.maximumWeight;
        this.weigher = builder.weigher;
        this.defaultTtlNanos = builder.defaultTtlNanos;
        this.sketch = new FrequencySketch(maximumWeight);
        this.policy = new TinyLfuPolicy<>(maximumWeight, sketch);
        this.wheel = new TimerWheel<>(WHEEL_SLOTS, WHEEL_TICK_NANOS, this::onExpired);
    }

    public static <K, V> Builder<K, V> builder() {
        return new Builder<>();
    }

    // ------------------------------------------------------------------ reads

    /** Returns the value for {@code key}, or {@code null} if absent or expired. */
    public V getIfPresent(K key) {
        Objects.requireNonNull(key, "key must not be null");
        lock.lock();
        try {
            long now = System.nanoTime();
            wheel.advance(now);
            Node<K, V> node = index.get(key);
            if (node == null) {
                missCount++;
                return null;
            }
            if (isExpired(node, now)) {
                removeNode(node);
                expirationCount++;
                missCount++;
                return null;
            }
            sketch.increment(key);
            policy.onAccess(node);
            hitCount++;
            return node.value;
        } finally {
            lock.unlock();
        }
    }

    /** Alias for {@link #getIfPresent(Object)}, for symmetry with {@link #put}. */
    public V get(K key) {
        return getIfPresent(key);
    }

    /**
     * Returns the cached value, computing it via {@code loader} on a miss. Concurrent calls
     * for the same key result in a single loader invocation; the others wait for its result.
     */
    public V get(K key, CacheLoader<K, V> loader) throws Exception {
        Objects.requireNonNull(loader, "loader must not be null");

        V present = getIfPresent(key);
        if (present != null) {
            return present;
        }

        CompletableFuture<V> mine = new CompletableFuture<>();
        CompletableFuture<V> existing = inFlight.putIfAbsent(key, mine);
        if (existing != null) {
            return await(existing);
        }

        try {
            V loaded = loader.load(key);
            loadSuccessCount.incrementAndGet();
            put(key, loaded);
            mine.complete(loaded);
            return loaded;
        } catch (Throwable t) {
            loadFailureCount.incrementAndGet();
            mine.completeExceptionally(t);
            if (t instanceof Exception) {
                throw (Exception) t;
            }
            throw new RuntimeException(t);
        } finally {
            inFlight.remove(key, mine);
        }
    }

    // ---------------------------------------------------------------- writes

    /** Inserts or updates {@code key} using the cache's default TTL. */
    public void put(K key, V value) {
        putInternal(key, value, defaultTtlNanos);
    }

    /** Inserts or updates {@code key} with a per-entry TTL. */
    public void put(K key, V value, Duration ttl) {
        Objects.requireNonNull(ttl, "ttl must not be null");
        putInternal(key, value, ttl.toNanos());
    }

    private void putInternal(K key, V value, long ttlNanos) {
        Objects.requireNonNull(key, "key must not be null");
        int weight = weigher.weigh(key, value);
        if (weight < 0) {
            throw new IllegalArgumentException("weigher returned a negative weight: " + weight);
        }

        lock.lock();
        try {
            long now = System.nanoTime();
            wheel.advance(now);

            Node<K, V> node = index.get(key);

            if (weight == 0) {
                if (node != null) {
                    removeNode(node);
                }
                return;
            }

            if (node != null) {
                node.value = value;
                applyExpiry(node, ttlNanos, now);
                sketch.increment(key);
                policy.onAccess(node);
                return;
            }

            Node<K, V> created = new Node<>(key, value, weight);
            sketch.increment(key);
            applyExpiry(created, ttlNanos, now);
            index.put(key, created);

            Node<K, V> rejectedOrVictim = policy.onInsert(created);
            if (rejectedOrVictim != null) {
                removeNode(rejectedOrVictim);
                evictionCount++;
            }

            while (policy.weight() > maximumWeight) {
                Node<K, V> victim = policy.evict();
                if (victim == null) {
                    break;
                }
                removeNode(victim);
                evictionCount++;
            }
        } finally {
            lock.unlock();
        }
    }

    /** Removes {@code key}, returning the previously cached value (or {@code null}). */
    public V invalidate(K key) {
        Objects.requireNonNull(key, "key must not be null");
        lock.lock();
        try {
            Node<K, V> node = index.get(key);
            if (node == null) {
                return null;
            }
            V old = node.value;
            removeNode(node);
            return old;
        } finally {
            lock.unlock();
        }
    }

    /** Removes every entry. */
    public void invalidateAll() {
        lock.lock();
        try {
            for (Node<K, V> node : index.values()) {
                wheel.remove(node);
            }
            index.clear();
            policy.clear();
            inFlight.clear();
        } finally {
            lock.unlock();
        }
    }

    // -------------------------------------------------------------- observability

    /** Forces the timer wheel to process due expirations. */
    public void cleanUp() {
        lock.lock();
        try {
            wheel.advance(System.nanoTime());
        } finally {
            lock.unlock();
        }
    }

    public long estimatedSize() {
        lock.lock();
        try {
            return index.size();
        } finally {
            lock.unlock();
        }
    }

    /** Total weight currently held; never exceeds the configured maximum. */
    public long estimatedWeight() {
        lock.lock();
        try {
            return policy.weight();
        } finally {
            lock.unlock();
        }
    }

    public CacheStats stats() {
        lock.lock();
        try {
            return new CacheStats(
                    hitCount,
                    missCount,
                    loadSuccessCount.get(),
                    loadFailureCount.get(),
                    evictionCount,
                    expirationCount,
                    index.size());
        } finally {
            lock.unlock();
        }
    }

    /**
     * A live, read-only snapshot of the cache's internal shape: the W-TinyLFU segment
     * sizes and weights plus timer-wheel occupancy. Intended for dashboards and tests;
     * it takes the cache lock briefly and never mutates state.
     */
    public CacheDiagnostics diagnostics() {
        lock.lock();
        try {
            return new CacheDiagnostics(
                    index.size(),
                    policy.weight(),
                    maximumWeight,
                    policy.windowSize(),
                    policy.probationSize(),
                    policy.protectedSize(),
                    policy.windowWeight(),
                    policy.probationWeight(),
                    policy.protectedWeight(),
                    wheel.scheduledCount());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns a stable, ordered view of every live entry, for display purposes. Expired
     * entries that the wheel has not yet reclaimed are filtered out. Values are stringified
     * so the view is safe to hand to any presentation layer.
     */
    public List<CacheEntryView> snapshot() {
        lock.lock();
        try {
            long now = System.nanoTime();
            List<CacheEntryView> out = new ArrayList<>(index.size());
            for (Node<K, V> node : index.values()) {
                if (isExpired(node, now)) {
                    continue;
                }
                long ttlMs = node.expireNanos == Long.MAX_VALUE
                        ? -1L
                        : Math.max(0L, (node.expireNanos - now) / 1_000_000L);
                out.add(new CacheEntryView(
                        String.valueOf(node.key),
                        String.valueOf(node.value),
                        segmentName(node.state),
                        node.weight,
                        ttlMs));
            }
            out.sort((a, b) -> a.key().compareTo(b.key()));
            return out;
        } finally {
            lock.unlock();
        }
    }

    private static String segmentName(int state) {
        return switch (state) {
            case TinyLfuPolicy.WINDOW -> "window";
            case TinyLfuPolicy.PROBATION -> "probation";
            case TinyLfuPolicy.PROTECTED -> "protected";
            default -> "none";
        };
    }

    // ------------------------------------------------------------------ internals

    private void applyExpiry(Node<K, V> node, long ttlNanos, long now) {
        if (ttlNanos <= 0) {
            node.expireNanos = Long.MAX_VALUE;
            wheel.remove(node);
            return;
        }
        long deadline = now + ttlNanos;
        node.expireNanos = deadline;
        wheel.schedule(node, deadline, now);
    }

    private void removeNode(Node<K, V> node) {
        index.remove(node.key);
        policy.remove(node);
        wheel.remove(node);
    }

    private void onExpired(Node<K, V> node) {
        index.remove(node.key);
        policy.remove(node);
        expirationCount++;
    }

    private static boolean isExpired(Node<?, ?> node, long now) {
        return node.expireNanos <= now;
    }

    private static <V> V await(CompletableFuture<V> future) throws Exception {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while awaiting an in-flight load", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new RuntimeException(cause);
        }
    }

    // ------------------------------------------------------------------- builder

    /** Fluent configuration for {@link HyperionCache}. */
    public static final class Builder<K, V> {
        private long maximumWeight = -1;
        private Weigher<K, V> weigher = (key, value) -> 1;
        private long defaultTtlNanos = 0L;

        private Builder() {
        }

        /** Maximum total weight before eviction begins. Required. */
        public Builder<K, V> maximumWeight(long maximumWeight) {
            this.maximumWeight = maximumWeight;
            return this;
        }

        /** Maximum number of entries (equivalent to a unit weigher). */
        public Builder<K, V> maximumSize(long maximumSize) {
            this.maximumWeight = maximumSize;
            this.weigher = (key, value) -> 1;
            return this;
        }

        public Builder<K, V> weigher(Weigher<K, V> weigher) {
            this.weigher = Objects.requireNonNull(weigher, "weigher");
            return this;
        }

        /** Default time-to-live applied by {@link #put(Object, Object)}. */
        public Builder<K, V> expireAfterWrite(Duration ttl) {
            this.defaultTtlNanos = Objects.requireNonNull(ttl, "ttl").toNanos();
            return this;
        }

        public HyperionCache<K, V> build() {
            if (maximumWeight <= 0) {
                throw new IllegalStateException("maximumWeight/maximumSize must be set and positive");
            }
            return new HyperionCache<>(this);
        }
    }
}