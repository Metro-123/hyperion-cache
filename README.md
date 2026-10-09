# Hyperion

A production-shaped, dependency-free in-memory cache for the JVM — and a study in the
data structures that make modern caches fast. It implements the **W-TinyLFU** eviction
policy (the algorithm behind Caffeine), a **hashed timer wheel** for O(1) TTL, and
**single-flight** asynchronous loading, all in ~1,100 lines of carefully commented Java.

```
== W-TinyLFU eviction ==
  working set survived a 500-key scan: 20/20 (size=100, hits grew by 20)

== Per-entry TTL (hashed timer wheel) ==
  immediately: session=abc, config=forever
  after 450ms: session=null, config=forever, expirations=1

== Single-flight loading ==
  16 concurrent callers -> loader invoked 1 time(s): "value-from-db-for-shared-key"

== Statistics ==
  CacheStats{size=50, hits=0, misses=200, hitRate=0.000, evictions=150, expirations=0, ...}
```

## What makes it hard (and interesting)

A cache looks trivial — `get`/`put` — until you demand **all** of these at once:

| Requirement | Naive answer | What Hyperion does |
|---|---|---|
| Evict the right thing | LRU | **W-TinyLFU**: frequency-based admission so scans can't flush hot data |
| Expire entries cheaply | scan/heap O(log n) | **Hashed timer wheel** O(1) insert/remove |
| Don't stampede on misses | lock the whole load | **Single-flight** coalescing via `CompletableFuture` |
| Bound by memory, not count | count entries | **Weigher** bounds total weight |
| Observe behaviour | guess | **`CacheStats`** with hit rate, evictions, expirations |

## Architecture

```
                    put(key)                         get(key) hit
                        │                                │
                        ▼                                ▼
                ┌───────────────┐               ┌───────────────┐
                │  HashMap index│◀─────────────▶│  candidate /  │
                │  key -> Node  │               │  promote      │
                └───────┬───────┘               └───────┬───────┘
                        │                               │
        ┌───────────────┴───────────────────────────────┴───────────────┐
        │                        TinyLfuPolicy                            │
        │   window (1%)  ──►  probation (≈20%)  ◄──►  protected (≈80%)    │
        │        ▲                     ▲                                 │
        │        │            FrequencySketch (Count-Min, aging)          │
        └────────┼─────────────────────┼─────────────────────────────────┘
                 │                     │
        ┌────────┴─────────────────────┴──────────┐
        │   TimerWheel (2048 slots × 1ms, rounds)  │  O(1) TTL
        └──────────────────────────────────────────┘
```

Every `Node` is referenced from three places at once — the hash index, exactly one
eviction deque, and at most one timer-wheel bucket — which is precisely why every
operation stays O(1).

### Components

| Class | Responsibility | Key idea |
|---|---|---|
| `HyperionCache` | Public facade, locking, stats | One `ReentrantLock`; the loader runs *outside* it |
| `TinyLfuPolicy` | Eviction | window → probation → protected, frequency admission |
| `FrequencySketch` | Frequency estimator | 4-row Count-Min sketch, conservative update, halving decay |
| `TimerWheel` | TTL scheduling | 2048 buckets, `remainingRounds` (Netty style), no background thread |
| `LinkedDeque` / `Node` | Ordered segments | Sentinel doubly linked lists carrying weight |
| `CacheStats` | Observability | Immutable snapshot record |

## Algorithms, briefly

**W-TinyLFU admission.** A plain LRU dies under scans: reading 500 unique keys once
evicts genuinely hot data. TinyLFU keeps a small sketch of historical access frequency
and only lets a new key displace an incumbent if it is estimated to be *more* valuable.
New keys get a fair trial in the tiny `window`; when they age out they must beat the
`probation` victim or be dropped. The result is near-optimal hit rates across mixed
workloads — the reason Caffeine displaced Guava's LRU.

**Count-Min sketch.** Storing an exact counter per key would cost as much as the cache
itself. A 4-row sketch bounds memory to a constant per key: the estimate is the minimum
of four hashed counters (a provable lower bound on the true count). Conservative update
increments only the counters at the row minimum to reduce over-estimation, and periodic
halving gives the estimate a recency bias so stale "hot" keys fade.

**Hashed timer wheel.** Expiration is scheduled, not scanned. Each timer knows which
bucket (by deadline) and how many full wheel rotations remain (`remainingRounds`), so
rescheduling a long TTL is O(1) and advancement only touches the current slot. We drive
the wheel from cache activity rather than a background thread, keeping the library
deterministic and thread-free; reads *also* check deadlines lazily, so correctness never
depends on the wheel being advanced.

**Single-flight loading.** Concurrent misses for the same key register a
`CompletableFuture` in a `ConcurrentHashMap`. The first caller loads; the rest await the
same future. One database round-trip instead of N.

## Correctness & complexity

| Operation | Time | Notes |
|---|---|---|
| `getIfPresent` | O(1) avg | hash + segment move + sketch update |
| `put` | O(1) avg | may trigger O(1) eviction/admission |
| `get(key, loader)` | O(load) once | other callers block only on the shared future |
| timer schedule/remove | O(1) | |
| wheel advance | O(1) amortised per tick | |

All shared state is guarded by a single `ReentrantLock`. This is deliberate: the policy
mutates on *reads* (recency/frequency), so a read-write lock buys nothing. The loader is
invoked outside the lock so slow I/O cannot block other threads.

## Build & run

Requires JDK 21 and Maven.

```bash
mvn test                                   # 33 tests
mvn -q compile
java -cp target/classes com.example.hyperion.Main

# or build a single runnable jar (manifest Main-Class + bundled resources)
mvn -q package                             # -> target/hyperion-cache.jar
java -jar target/hyperion-cache.jar        # launches the web dashboard
```

On Windows you can simply **double-click `Start Hyperion.bat`**, which builds the jar on
first run (if needed) and then launches the dashboard.

The test suite covers the sketch, the deque, the timer wheel, eviction fairness (a hot
working set surviving a cold scan), TTL expiry, weight bounding, single-flight
coalescing, concurrent stress, and the diagnostics/UI surface.

## Web UI

Hyperion ships with an optional, **zero-dependency** web dashboard. It is served from the
JDK's built-in `com.sun.net.httpserver.HttpServer` with the frontend stored as static
resources, so attaching a full UI adds **no third-party dependencies** to the library.

**Live demo:** <https://hyperion-cache.vercel.app>

```bash
mvn -q compile
java -cp target/classes com.example.hyperion.ui.UiLauncher        # http://localhost:7373
```

The launcher opens your browser automatically. Arguments are optional:

```bash
java -cp target/classes com.example.hyperion.ui.UiLauncher [port] [capacity] [ttlMs]
```

### What the dashboard shows

| View | What it does |
|---|---|
| **Dashboard** | Live counters, a hit-rate gauge, and a **visualization of the W-TinyLFU segments** (window → probation → protected) with colour-coded entry pills and TTLs. |
| **Playground** | Call `put`, `getIfPresent`, `invalidate`, `invalidateAll`, `cleanUp`, and rebuild capacity/TTL interactively. |
| **Workload Lab** | Runs head-to-head benchmarks of **Hyperion (W-TinyLFU) vs a naive LRU** on a scan-resistant workload and a Zipfian workload, showing why admission control wins. |

The UI is backed by a small JSON API (`/api/state`, `/api/put`, `/api/get`,
`/api/invalidate`, `/api/clear`, `/api/cleanup`, `/api/config`, `/api/simulate`) built on
the JDK-only `Json` helper. The observability surface it reads is part of the public API:
`HyperionCache#diagnostics()` and `HyperionCache#snapshot()`.

### Static browser edition

Static hosts (Vercel, GitHub Pages, Netlify) cannot run a JVM, so the `web/` directory
contains a **faithful JavaScript port** of the cache — the same W-TinyLFU policy
(window → probation → protected with O(1) linked-list segments), the same frequency
sketch with halving decay, per-entry TTL, hit/miss/eviction/expiration statistics, the
diagnostics snapshot, and the Hyperion-vs-LRU workload lab — running entirely in the
browser. It is plain HTML/CSS/JS with **no server and no build step**, so the deployed
demo is fully interactive rather than a screenshot.

```bash
npx serve web        # preview the static edition locally
```

`vercel.json` points Vercel's `outputDirectory` at `web/`, so a `git push` redeploys the
live demo automatically.

## API

```java
HyperionCache<Long, User> cache = HyperionCache.<Long, User>builder()
        .maximumWeight(10_000)                      // required: bound in weight units
        .weigher((id, user) -> user.bytes())        // optional (default: 1 per entry)
        .expireAfterWrite(Duration.ofMinutes(10))   // optional default TTL
        .build();

cache.put(42L, user);
cache.put(43L, user, Duration.ofSeconds(30));       // per-entry TTL

User u = cache.getIfPresent(42L);
User loaded = cache.get(42L, id -> repository.find(id)); // single-flight, may throw

cache.invalidate(42L);
cache.cleanUp();                                    // force due expirations
CacheStats stats = cache.stats();
```

## Honest limitations / next steps

Written to be *correct and comprehensible*, not to win a microbenchmark. Deliberate
trade-offs, each an excellent interview talking point:

- **Coarse-grained locking.** Caffeine stripes the index, the LRUs and the sketch so
  readers rarely contend. That is the single biggest throughput lever here.
- **`HashMap`, not an open-addressing table.** Simpler, but a purpose-built long-keyed
  table removes boxing and indirection.
- **Int counters, not packed 4-bit counters.** The real sketch packs counters into
  `long[]`; ours trades memory for readability.
- **No write-back / async refresh / removal listeners.** Natural extensions.
- **Wheel advanced by activity.** An idle cache holds expired entries until the next
  touch; a background scheduler would reclaim them promptly.

## Interview cheat-sheet

- *Why not `LinkedHashMap(accessOrder=true)`?* Fine for LRU, but it can't express
  admission-by-frequency and it reorders on read under one lock. See the sibling
  `lru-cache` project for that three-line version.
- *Why a sketch instead of a `Map<K, Long>`?* Bounded memory; the cache's value is that
  it's *small*.
- *Why does the loader run outside the lock?* To avoid holding the cache hostage during
  slow I/O, and to keep single-flight coalescing independent of the eviction lock.
- *How would you make it lock-free?* Striped locks per segment; a `ConcurrentHashMap`
  index; per-thread sketch buffers merged periodically; `LongAdder` counters. This is
  exactly how Caffeine evolved.