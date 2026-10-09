package com.example.hyperion;

/**
 * The <b>W-TinyLFU</b> eviction policy — the same family of algorithm used by Caffeine.
 *
 * <p>A plain LRU evicts the oldest item, which is terrible for scan-heavy workloads: a
 * burst of one-shot reads flushes the genuinely hot set. TinyLFU instead keeps a small
 * frequency sketch and only admits a new entry past the eviction point if it is
 * estimated to be at least as valuable as the entry it would replace.
 *
 * <p>The cache is split into three LRU segments:
 * <pre>
 *   window (≈1%)            main (≈99%)
 *   +----------+     +------------------+   +-------------------+
 *   |  newest  | --&gt; |    probation     | &lt;--&gt; |     protected     |
 *   |  entries |     |  (admission gate)|     |  (frequently used)|
 *   +----------+     +------------------+   +-------------------+
 * </pre>
 * <ul>
 *   <li>New entries always land in the <b>window</b>, getting a fair chance to be used.</li>
 *   <li>When the window overflows, its LRU victim becomes a <i>candidate</i> for the main
 *       region. It is admitted to <b>probation</b> only if its frequency beats the
 *       probation victim's; otherwise it is dropped (the "TinyLFU admission" filter).</li>
 *   <li>A hit in probation <b>promotes</b> the entry to <b>protected</b>; when protected
 *       overflows, its LRU is demoted back to probation.</li>
 * </ul>
 *
 * <p>All operations are O(1). Not thread-safe; serialized by the owning cache.
 */
final class TinyLfuPolicy<K, V> {

    static final int NONE = 0;
    static final int WINDOW = 1;
    static final int PROBATION = 2;
    static final int PROTECTED = 3;

    private final FrequencySketch sketch;
    private final LinkedDeque<K, V> window = new LinkedDeque<>();
    private final LinkedDeque<K, V> probation = new LinkedDeque<>();
    private final LinkedDeque<K, V> protectedSegment = new LinkedDeque<>();

    private final long windowMax;
    private final long mainMax;
    private final long protectedMax;

    TinyLfuPolicy(long maximum, FrequencySketch sketch) {
        this.sketch = sketch;
        this.windowMax = Math.max(1, maximum / 100);
        this.mainMax = Math.max(1, maximum - windowMax);
        this.protectedMax = Math.max(1, (long) (mainMax * 0.8));
    }

    /** Called on every read hit: refresh recency, promote through the segments, record frequency. */
    void onAccess(Node<K, V> node) {
        sketch.increment(node.key);
        switch (node.state) {
            case WINDOW:
                window.moveToFront(node);
                break;
            case PROTECTED:
                protectedSegment.moveToFront(node);
                break;
            case PROBATION:
                probation.remove(node);
                protectedSegment.addFirst(node);
                node.state = PROTECTED;
                if (protectedSegment.weight() > protectedMax) {
                    Node<K, V> demoted = protectedSegment.last();
                    if (demoted != null) {
                        protectedSegment.remove(demoted);
                        probation.addFirst(demoted);
                        demoted.state = PROBATION;
                    }
                }
                break;
            default:
                break;
        }
    }

    /**
     * Places a brand new node into the window and, if the window overflows, runs the
     * TinyLFU admission filter.
     *
     * @return the node that must be evicted from the cache (the rejected candidate or the
     *         admitted victim), or {@code null} if nothing needs eviction
     */
    Node<K, V> onInsert(Node<K, V> node) {
        window.addFirst(node);
        node.state = WINDOW;
        if (window.weight() <= windowMax) {
            return null;
        }

        Node<K, V> candidate = window.last();
        window.remove(candidate);
        candidate.state = NONE;

        // The main region still has room, so admit the candidate unconditionally.
        long mainWeight = probation.weight() + protectedSegment.weight();
        if (mainWeight + candidate.weight <= mainMax) {
            probation.addFirst(candidate);
            candidate.state = PROBATION;
            return null;
        }

        // Main is full: run the TinyLFU admission filter against the probation victim.
        Node<K, V> victim = probation.last();
        if (victim == null) {
            probation.addFirst(candidate);
            candidate.state = PROBATION;
            return null;
        }

        if (sketch.frequency(candidate.key) > sketch.frequency(victim.key)) {
            probation.remove(victim);
            victim.state = NONE;
            probation.addFirst(candidate);
            candidate.state = PROBATION;
            return victim;
        }
        return candidate; // not valuable enough — drop the newcomer
    }

    /** Detaches a node from whichever segment holds it. Safe to call on an already-detached node. */
    void remove(Node<K, V> node) {
        switch (node.state) {
            case WINDOW:
                window.remove(node);
                break;
            case PROBATION:
                probation.remove(node);
                break;
            case PROTECTED:
                protectedSegment.remove(node);
                break;
            default:
                return;
        }
        node.state = NONE;
    }

    /** Hard-bound eviction used when weights push the cache over capacity: probation first. */
    Node<K, V> evict() {
        Node<K, V> victim = probation.last();
        if (victim == null) {
            victim = window.last();
        }
        if (victim == null) {
            victim = protectedSegment.last();
        }
        if (victim == null) {
            return null;
        }
        remove(victim);
        return victim;
    }

    long weight() {
        return window.weight() + probation.weight() + protectedSegment.weight();
    }

    int windowSize() {
        return window.size();
    }

    int probationSize() {
        return probation.size();
    }

    int protectedSize() {
        return protectedSegment.size();
    }

    long windowWeight() {
        return window.weight();
    }

    long probationWeight() {
        return probation.weight();
    }

    long protectedWeight() {
        return protectedSegment.weight();
    }

    void clear() {
        window.clear();
        probation.clear();
        protectedSegment.clear();
    }
}