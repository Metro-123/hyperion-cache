/*
 * Hyperion (browser edition)
 * ----------------------------------------------------------------------------
 * A faithful JavaScript port of the Java HyperionCache, so the same dashboard
 * runs with no server at all (which is what lets it deploy to Vercel).
 *
 * Mirrors the Java design:
 *   - W-TinyLFU eviction (window -> probation -> protected), O(1) linked lists
 *   - frequency admission via an exact counting sketch with halving "aging"
 *   - per-entry TTL with lazy expiry + an explicit cleanUp()
 *   - weight-based bounding (default weigher: 1 per entry)
 *   - hit/miss/eviction/expiration statistics and a diagnostics snapshot
 */
(function (global) {
    "use strict";

    const SEG = { NONE: 0, WINDOW: 1, PROBATION: 2, PROTECTED: 3 };
    const SEG_NAME = { 1: "window", 2: "probation", 3: "protected", 0: "none" };

    // ------------------------------------------------------------- linked list

    class DList {
        constructor() {
            this.head = null;
            this.tail = null;
            this.size = 0;
            this.weight = 0;
        }

        addFirst(node) {
            node.p = null;
            node.n = this.head;
            if (this.head) {
                this.head.p = node;
            }
            this.head = node;
            if (!this.tail) {
                this.tail = node;
            }
            this.size++;
            this.weight += node.weight;
        }

        remove(node) {
            if (node.p) {
                node.p.n = node.n;
            } else {
                this.head = node.n;
            }
            if (node.n) {
                node.n.p = node.p;
            } else {
                this.tail = node.p;
            }
            node.p = null;
            node.n = null;
            this.size--;
            this.weight -= node.weight;
        }

        moveToFront(node) {
            if (this.head === node) {
                return;
            }
            this.remove(node);
            this.addFirst(node);
        }

        last() {
            return this.tail;
        }

        clear() {
            let n = this.head;
            while (n) {
                const next = n.n;
                n.p = null;
                n.n = null;
                n.state = SEG.NONE;
                n = next;
            }
            this.head = null;
            this.tail = null;
            this.size = 0;
            this.weight = 0;
        }
    }

    // --------------------------------------------------------- frequency sketch

    class FrequencySketch {
        constructor(maximumWeight) {
            this.counts = new Map();
            // Halve once the sketch grows well past the cache, mimicking the
            // recency bias of the real Count-Min sketch's periodic aging.
            this.halveAt = Math.max(1024, maximumWeight * 10);
        }

        increment(key) {
            const c = (this.counts.get(key) || 0) + 1;
            this.counts.set(key, c);
            if (this.counts.size > this.halveAt) {
                this.halve();
            }
        }

        frequency(key) {
            return this.counts.get(key) || 0;
        }

        halve() {
            for (const [k, v] of this.counts) {
                const nv = Math.floor(v / 2);
                if (nv <= 0) {
                    this.counts.delete(k);
                } else {
                    this.counts.set(k, nv);
                }
            }
        }

        clear() {
            this.counts.clear();
        }
    }

    // ------------------------------------------------------------- eviction policy

    class TinyLfuPolicy {
        constructor(maximum, sketch) {
            this.sketch = sketch;
            this.window = new DList();
            this.probation = new DList();
            this.protectedSeg = new DList();

            this.windowMax = Math.max(1, Math.floor(maximum / 100));
            this.mainMax = Math.max(1, maximum - this.windowMax);
            this.protectedMax = Math.max(1, Math.floor(this.mainMax * 0.8));
        }

        segFor(state) {
            if (state === SEG.WINDOW) return this.window;
            if (state === SEG.PROBATION) return this.probation;
            return this.protectedSeg;
        }

        onAccess(node) {
            this.sketch.increment(node.key);
            switch (node.state) {
                case SEG.WINDOW:
                    this.window.moveToFront(node);
                    break;
                case SEG.PROTECTED:
                    this.protectedSeg.moveToFront(node);
                    break;
                case SEG.PROBATION: {
                    this.probation.remove(node);
                    this.protectedSeg.addFirst(node);
                    node.state = SEG.PROTECTED;
                    if (this.protectedSeg.weight > this.protectedMax) {
                        const demoted = this.protectedSeg.last();
                        if (demoted) {
                            this.protectedSeg.remove(demoted);
                            this.probation.addFirst(demoted);
                            demoted.state = SEG.PROBATION;
                        }
                    }
                    break;
                }
                default:
                    break;
            }
        }

        onInsert(node) {
            this.window.addFirst(node);
            node.state = SEG.WINDOW;
            if (this.window.weight <= this.windowMax) {
                return null;
            }
            const candidate = this.window.last();
            this.window.remove(candidate);
            candidate.state = SEG.NONE;

            const mainWeight = this.probation.weight + this.protectedSeg.weight;
            if (mainWeight + candidate.weight <= this.mainMax) {
                this.probation.addFirst(candidate);
                candidate.state = SEG.PROBATION;
                return null;
            }

            const victim = this.probation.last();
            if (!victim) {
                this.probation.addFirst(candidate);
                candidate.state = SEG.PROBATION;
                return null;
            }

            if (this.sketch.frequency(candidate.key) > this.sketch.frequency(victim.key)) {
                this.probation.remove(victim);
                victim.state = SEG.NONE;
                this.probation.addFirst(candidate);
                candidate.state = SEG.PROBATION;
                return victim;
            }
            return candidate;
        }

        remove(node) {
            switch (node.state) {
                case SEG.WINDOW:
                    this.window.remove(node);
                    break;
                case SEG.PROBATION:
                    this.probation.remove(node);
                    break;
                case SEG.PROTECTED:
                    this.protectedSeg.remove(node);
                    break;
                default:
                    return;
            }
            node.state = SEG.NONE;
        }

        evict() {
            let victim = this.probation.last();
            if (!victim) victim = this.window.last();
            if (!victim) victim = this.protectedSeg.last();
            if (!victim) return null;
            this.remove(victim);
            return victim;
        }

        weight() {
            return this.window.weight + this.probation.weight + this.protectedSeg.weight;
        }

        clear() {
            this.window.clear();
            this.probation.clear();
            this.protectedSeg.clear();
        }
    }

    // ------------------------------------------------------------------ the cache

    class HyperionCache {
        constructor(maximumSize, defaultTtlMs) {
            this.maximumWeight = Math.max(1, maximumSize | 0);
            this.defaultTtlMs = defaultTtlMs || 0;
            this.index = new Map();
            this.sketch = new FrequencySketch(this.maximumWeight);
            this.policy = new TinyLfuPolicy(this.maximumWeight, this.sketch);
            this.hitCount = 0;
            this.missCount = 0;
            this.evictionCount = 0;
            this.expirationCount = 0;
            this.loadSuccessCount = 0;
            this.loadFailureCount = 0;
        }

        _now() {
            return Date.now();
        }

        getIfPresent(key) {
            const now = this._now();
            const node = this.index.get(key);
            if (!node) {
                this.missCount++;
                return null;
            }
            if (node.expireAt <= now) {
                this._remove(node);
                this.expirationCount++;
                this.missCount++;
                return null;
            }
            this.sketch.increment(key);
            this.policy.onAccess(node);
            this.hitCount++;
            return node.value;
        }

        put(key, value, ttlMs) {
            const ttl = ttlMs === undefined || ttlMs === null || ttlMs === 0
                ? this.defaultTtlMs
                : ttlMs;
            const now = this._now();
            const expireAt = ttl > 0 ? now + ttl : Infinity;

            let node = this.index.get(key);
            if (node) {
                node.value = value;
                node.expireAt = expireAt;
                this.sketch.increment(key);
                this.policy.onAccess(node);
                return;
            }

            node = { key, value, weight: 1, expireAt, state: SEG.NONE, p: null, n: null };
            this.sketch.increment(key);
            this.index.set(key, node);

            const rejected = this.policy.onInsert(node);
            if (rejected) {
                this.index.delete(rejected.key);
                this.evictionCount++;
            }

            while (this.policy.weight() > this.maximumWeight) {
                const victim = this.policy.evict();
                if (!victim) break;
                this.index.delete(victim.key);
                this.evictionCount++;
            }
        }

        invalidate(key) {
            const node = this.index.get(key);
            if (!node) return null;
            const old = node.value;
            this._remove(node);
            return old;
        }

        invalidateAll() {
            this.index.clear();
            this.policy.clear();
            this.sketch.clear();
        }

        cleanUp() {
            const now = this._now();
            const expired = [];
            for (const node of this.index.values()) {
                if (node.expireAt <= now) {
                    expired.push(node);
                }
            }
            for (const node of expired) {
                this._remove(node);
                this.expirationCount++;
            }
            return expired.length;
        }

        _remove(node) {
            this.index.delete(node.key);
            this.policy.remove(node);
        }

        estimatedSize() {
            return this.index.size;
        }

        stats() {
            const requests = this.hitCount + this.missCount;
            const hitRate = requests === 0 ? 1 : this.hitCount / requests;
            return {
                hitCount: this.hitCount,
                missCount: this.missCount,
                requestCount: requests,
                hitRate: hitRate,
                missRate: 1 - hitRate,
                loadSuccessCount: this.loadSuccessCount,
                loadFailureCount: this.loadFailureCount,
                evictionCount: this.evictionCount,
                expirationCount: this.expirationCount,
                size: this.index.size
            };
        }

        diagnostics() {
            let scheduled = 0;
            for (const node of this.index.values()) {
                if (node.expireAt !== Infinity) scheduled++;
            }
            return {
                estimatedSize: this.index.size,
                estimatedWeight: this.policy.weight(),
                maximumWeight: this.maximumWeight,
                windowSize: this.policy.window.size,
                probationSize: this.policy.probation.size,
                protectedSize: this.policy.protectedSeg.size,
                windowWeight: this.policy.window.weight,
                probationWeight: this.policy.probation.weight,
                protectedWeight: this.policy.protectedSeg.weight,
                scheduledTimers: scheduled
            };
        }

        snapshot() {
            const now = this._now();
            const out = [];
            for (const node of this.index.values()) {
                if (node.expireAt <= now) continue;
                out.push({
                    key: String(node.key),
                    value: String(node.value),
                    segment: SEG_NAME[node.state] || "none",
                    weight: node.weight,
                    ttlRemainingMs: node.expireAt === Infinity
                        ? -1
                        : Math.max(0, node.expireAt - now)
                });
            }
            out.sort((a, b) => (a.key < b.key ? -1 : a.key > b.key ? 1 : 0));
            return out;
        }
    }

    // --------------------------------------------------------------- LRU baseline

    class LruCache {
        constructor(capacity) {
            this.capacity = capacity;
            this.map = new Map();
        }

        getIfPresent(key) {
            if (!this.map.has(key)) return null;
            const v = this.map.get(key);
            this.map.delete(key);
            this.map.set(key, v);
            return v;
        }

        put(key, value) {
            if (this.map.has(key)) this.map.delete(key);
            this.map.set(key, value);
            if (this.map.size > this.capacity) {
                this.map.delete(this.map.keys().next().value);
            }
        }
    }

    // ------------------------------------------------------------------ simulation

    function result(name, hitRate, hits, misses, hotSurvivors, hotSize) {
        const r = { name, hitRate, hits, misses };
        if (hotSize > 0) {
            r.hotSurvivors = hotSurvivors;
            r.hotSize = hotSize;
            r.survivalRate = hotSurvivors / hotSize;
        }
        return r;
    }

    function scanWorkload(cache, name, hotSize, scanCount) {
        for (let i = 0; i < hotSize; i++) {
            const key = "hot-" + i;
            cache.put(key, "v" + i);
            for (let r = 0; r < 10; r++) cache.getIfPresent(key);
        }
        let hits = 0;
        let misses = 0;
        for (let i = 0; i < scanCount; i++) {
            const key = "scan-" + i;
            if (cache.getIfPresent(key) === null) {
                misses++;
                cache.put(key, "s" + i);
            } else {
                hits++;
            }
        }
        let survivors = 0;
        for (let i = 0; i < hotSize; i++) {
            if (cache.getIfPresent("hot-" + i) !== null) survivors++;
        }
        const hitRate = hits + misses === 0 ? 0 : hits / (hits + misses);
        return result(name, hitRate, hits, misses, survivors, hotSize);
    }

    function zipfCdf(n, exponent) {
        const cdf = new Float64Array(n);
        let sum = 0;
        for (let i = 1; i <= n; i++) {
            sum += 1 / Math.pow(i, exponent);
            cdf[i - 1] = sum;
        }
        for (let i = 0; i < n; i++) cdf[i] /= sum;
        return cdf;
    }

    function sample(cdf, u) {
        let lo = 0;
        let hi = cdf.length - 1;
        while (lo < hi) {
            const mid = (lo + hi) >>> 1;
            if (cdf[mid] < u) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    // deterministic PRNG (mulberry32) so results are reproducible
    function rng(seed) {
        let a = seed >>> 0;
        return function () {
            a |= 0;
            a = (a + 0x6D2B79F5) | 0;
            let t = Math.imul(a ^ (a >>> 15), 1 | a);
            t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
            return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
        };
    }

    function zipfWorkload(cache, name, universe, operations) {
        const cdf = zipfCdf(universe, 1.2);
        const random = rng(42);
        let hits = 0;
        let misses = 0;
        for (let i = 0; i < operations; i++) {
            const rank = sample(cdf, random()) + 1;
            const key = "key-" + rank;
            if (cache.getIfPresent(key) === null) {
                misses++;
                cache.put(key, String(rank));
            } else {
                hits++;
            }
        }
        const hitRate = hits + misses === 0 ? 0 : hits / (hits + misses);
        return result(name, hitRate, hits, misses, -1, 0);
    }

    function runSimulation(scenario, capacity) {
        const cap = Math.max(10, Math.min(20000, capacity | 0));
        if (scenario === "zipf") {
            const universe = Math.max(100, cap * 20);
            const operations = Math.max(2000, Math.min(150000, cap * 120));
            const h = zipfWorkload(new HyperionCache(cap, 0), "Hyperion (W-TinyLFU)", universe, operations);
            const l = zipfWorkload(new LruCache(cap), "Naive LRU", universe, operations);
            return {
                scenario: "zipf",
                title: "Skewed (Zipfian) access",
                blurb: "A realistic power-law workload over " + universe + " keys, " +
                    operations + " requests. This is the mix real caches actually see.",
                capacity: cap,
                results: [h, l]
            };
        }
        const hotSize = Math.max(10, Math.floor(cap / 2));
        const scanCount = Math.max(100, Math.min(60000, cap * 10));
        const h = scanWorkload(new HyperionCache(cap, 0), "Hyperion (W-TinyLFU)", hotSize, scanCount);
        const l = scanWorkload(new LruCache(cap), "Naive LRU", hotSize, scanCount);
        return {
            scenario: "scan",
            title: "Scan resistance",
            blurb: "A hot working set of " + hotSize + " keys is warmed, then hit by " +
                scanCount + " one-shot keys. Watch which cache keeps its hot data.",
            capacity: cap,
            results: [h, l]
        };
    }

    global.Hyperion = {
        createCache: (maxSize, defaultTtlMs) => new HyperionCache(maxSize, defaultTtlMs),
        runSimulation
    };
})(window);
