package com.example.hyperion;

/**
 * A doubly linked list of {@link Node}s with sentinels, used as an LRU ordering.
 * Sentinel nodes remove all boundary checks, so every operation is branch-free and O(1).
 * The list also tracks the total weight of its members so the eviction policy can reason
 * about capacity in weight units.
 */
final class LinkedDeque<K, V> {

    private final Node<K, V> head;
    private final Node<K, V> tail;
    private int size;
    private long weight;

    LinkedDeque() {
        this.head = new Node<>(null, null, 0);
        this.tail = new Node<>(null, null, 0);
        head.next = tail;
        tail.prev = head;
    }

    void addFirst(Node<K, V> node) {
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
        size++;
        weight += node.weight;
    }

    void remove(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
        node.prev = null;
        node.next = null;
        size--;
        weight -= node.weight;
    }

    void moveToFront(Node<K, V> node) {
        if (head.next == node) {
            return;
        }
        remove(node);
        addFirst(node);
    }

    /** Most-recently-used node, or {@code null} when empty. */
    Node<K, V> first() {
        return head.next == tail ? null : head.next;
    }

    /** Least-recently-used node, or {@code null} when empty. */
    Node<K, V> last() {
        return tail.prev == head ? null : tail.prev;
    }

    int size() {
        return size;
    }

    long weight() {
        return weight;
    }

    void clear() {
        Node<K, V> n = head.next;
        while (n != tail) {
            Node<K, V> next = n.next;
            n.prev = null;
            n.next = null;
            n.state = TinyLfuPolicy.NONE;
            n = next;
        }
        head.next = tail;
        tail.prev = head;
        size = 0;
        weight = 0;
    }
}