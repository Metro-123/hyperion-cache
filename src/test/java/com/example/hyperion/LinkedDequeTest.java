package com.example.hyperion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class LinkedDequeTest {

    @Test
    void addAndRemoveMaintainOrderAndWeight() {
        LinkedDeque<String, String> deque = new LinkedDeque<>();
        Node<String, String> a = new Node<>("a", "1", 2);
        Node<String, String> b = new Node<>("b", "2", 3);
        Node<String, String> c = new Node<>("c", "3", 5);

        deque.addFirst(a);
        deque.addFirst(b);
        deque.addFirst(c); // MRU..LRU => c, b, a

        assertSame(c, deque.first());
        assertSame(a, deque.last());
        assertEquals(3, deque.size());
        assertEquals(10, deque.weight());

        deque.remove(b);
        assertEquals(2, deque.size());
        assertEquals(7, deque.weight());
        assertSame(c, deque.first());
        assertSame(a, deque.last());
    }

    @Test
    void moveToFrontReordersWithoutChangingWeight() {
        LinkedDeque<String, String> deque = new LinkedDeque<>();
        Node<String, String> a = new Node<>("a", "1", 1);
        Node<String, String> b = new Node<>("b", "2", 1);
        deque.addFirst(a);
        deque.addFirst(b); // b, a

        deque.moveToFront(a);
        assertSame(a, deque.first());
        assertSame(b, deque.last());
        assertEquals(2, deque.size());
        assertEquals(2, deque.weight());
    }

    @Test
    void emptyDequeReturnsNullEnds() {
        LinkedDeque<String, String> deque = new LinkedDeque<>();
        assertNull(deque.first());
        assertNull(deque.last());
        deque.addFirst(new Node<>("x", "1", 1));
        deque.remove(deque.first());
        assertNull(deque.first());
        assertEquals(0, deque.weight());
    }
}