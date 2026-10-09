package com.example.hyperion;

/**
 * Determines the "cost" of an entry. The cache is bounded by the sum of weights, not
 * by entry count, so a few large entries can displace many small ones.
 *
 * <p>Return {@code 0} to reject a mapping (it will not be cached); return a negative
 * value to signal a programmer error.
 */
@FunctionalInterface
public interface Weigher<K, V> {

    Weigher<Object, Object> UNIT = (key, value) -> 1;

    int weigh(K key, V value);
}