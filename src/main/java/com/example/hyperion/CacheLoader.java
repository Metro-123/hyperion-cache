package com.example.hyperion;

/**
 * Computes a value for a missing key. Executed at most once per key across concurrent
 * callers (single-flight / request coalescing).
 */
@FunctionalInterface
public interface CacheLoader<K, V> {

    V load(K key) throws Exception;
}