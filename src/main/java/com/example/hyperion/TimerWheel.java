package com.example.hyperion;

/**
 * A hashed timing wheel for O(1) expiration, in the style of Netty's
 * {@code HashedWheelTimer}.
 *
 * <p>Expiration by scanning the whole cache (or a heap) is O(log n) or worse. A wheel
 * buckets time into a fixed circular array of slots; a timer is dropped into the slot it
 * will expire in, and each time we advance past a slot we process only that slot. Because
 * timers can live longer than one full rotation, each carries a {@code remainingRounds}
 * countdown so it fires on the correct lap.
 *
 * <p>Advancement is driven by cache activity (and {@link HyperionCache#cleanUp()}) rather
 * than a background thread, so the cache stays dependency-free and deterministic to test.
 * Correctness of reads does not depend on timely advancement: {@link HyperionCache}
 * additionally checks each entry's deadline lazily on access.
 *
 * <p>Not thread-safe; serialized by the owning cache.
 */
final class TimerWheel<K, V> {

    @FunctionalInterface
    interface Listener<K, V> {
        void onExpire(Node<K, V> node);
    }

    private final Node<K, V>[] slots;
    private final int mask;
    private final int bucketShift;
    private final long tickNanos;
    private final Listener<K, V> listener;

    private long currentTick;
    private int scheduledCount;

    @SuppressWarnings("unchecked")
    TimerWheel(int bucketCount, long tickNanos, Listener<K, V> listener) {
        if (Integer.bitCount(bucketCount) != 1) {
            throw new IllegalArgumentException("bucketCount must be a power of two: " + bucketCount);
        }
        this.slots = (Node<K, V>[]) new Node[bucketCount];
        for (int i = 0; i < bucketCount; i++) {
            Node<K, V> sentinel = new Node<>(null, null, 0);
            sentinel.tPrev = sentinel;
            sentinel.tNext = sentinel;
            slots[i] = sentinel;
        }
        this.mask = bucketCount - 1;
        this.bucketShift = Integer.numberOfTrailingZeros(bucketCount);
        this.tickNanos = tickNanos;
        this.listener = listener;
        this.currentTick = System.nanoTime() / tickNanos;
    }

    /** Schedules (or reschedules) {@code node} to expire at {@code deadlineNanos}. */
    void schedule(Node<K, V> node, long deadlineNanos, long nowNanos) {
        remove(node);
        long delay = deadlineNanos - nowNanos;
        if (delay <= 0) {
            listener.onExpire(node);
            return;
        }
        long ticks = (delay + tickNanos - 1) / tickNanos; // ceil: never fire early
        node.remainingRounds = ticks >>> bucketShift;
        int index = (int) ((currentTick + ticks) & mask);

        Node<K, V> sentinel = slots[index];
        node.tPrev = sentinel.tPrev;
        node.tNext = sentinel;
        sentinel.tPrev.tNext = node;
        sentinel.tPrev = node;
        node.timerScheduled = true;
        scheduledCount++;
    }

    /** Removes {@code node} from the wheel if it is currently scheduled. */
    void remove(Node<K, V> node) {
        if (!node.timerScheduled) {
            return;
        }
        node.tPrev.tNext = node.tNext;
        node.tNext.tPrev = node.tPrev;
        node.tPrev = null;
        node.tNext = null;
        node.timerScheduled = false;
        scheduledCount--;
    }

    /** Number of timers currently registered on the wheel. */
    int scheduledCount() {
        return scheduledCount;
    }

    /** Advances to {@code nowNanos}, firing all timers that came due. Returns how many fired. */
    int advance(long nowNanos) {
        long targetTick = nowNanos / tickNanos;
        int fired = 0;
        while (currentTick < targetTick) {
            currentTick++;
            Node<K, V> sentinel = slots[(int) (currentTick & mask)];
            Node<K, V> node = sentinel.tNext;
            while (node != sentinel) {
                Node<K, V> next = node.tNext;
                if (node.remainingRounds > 0) {
                    node.remainingRounds--;
                } else {
                    node.tPrev.tNext = node.tNext;
                    node.tNext.tPrev = node.tPrev;
                    node.tPrev = null;
                    node.tNext = null;
                    node.timerScheduled = false;
                    scheduledCount--;
                    fired++;
                    listener.onExpire(node);
                }
                node = next;
            }
        }
        return fired;
    }
}