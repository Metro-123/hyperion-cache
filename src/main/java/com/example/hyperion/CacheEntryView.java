package com.example.hyperion;

/**
 * A read-only snapshot of one cache entry, intended for observability and tooling
 * (such as the bundled web UI). It deliberately exposes a human-readable segment name
 * rather than the internal {@code int} state constant.
 *
 * @param key             the entry key (already stringified for safe display)
 * @param value           the entry value (already stringified for safe display)
 * @param segment         one of {@code "window"}, {@code "probation"}, {@code "protected"}
 * @param weight          the entry's configured weight
 * @param ttlRemainingMs  milliseconds until expiry, or {@code -1} if the entry never expires
 */
public record CacheEntryView(
        String key,
        String value,
        String segment,
        int weight,
        long ttlRemainingMs) {
}
