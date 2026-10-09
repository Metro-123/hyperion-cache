package com.example.hyperion;

/**
 * A single cache entry. The same object is referenced simultaneously from:
 * <ul>
 *   <li>the cache's {@code HashMap} index (by {@link #key}),</li>
 *   <li>exactly one eviction deque (via {@link #prev}/{@code next}), and</li>
 *   <li>at most one timer-wheel bucket (via {@code tPrev}/{@code tNext}).</li>
 * </ul>
 * Keeping these links on one object is what lets every operation stay O(1).
 */
final class Node<K, V> {

    final K key;
    V value;
    final int weight;
    long expireNanos = Long.MAX_VALUE;

    Node<K, V> prev;
    Node<K, V> next;

    /** Which policy segment currently owns this node (see {@link TinyLfuPolicy}). */
    int state = TinyLfuPolicy.NONE;

    Node<K, V> tPrev;
    Node<K, V> tNext;
    long remainingRounds;
    boolean timerScheduled;

    Node(K key, V value, int weight) {
        this.key = key;
        this.value = value;
        this.weight = weight;
    }

    @Override
    public String toString() {
        return String.valueOf(key) + '=' + value;
    }
}