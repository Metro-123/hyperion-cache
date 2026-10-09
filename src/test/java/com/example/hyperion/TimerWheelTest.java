package com.example.hyperion;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimerWheelTest {

    @Test
    void firesTimerWhenDeadlineReached() {
        List<Node<String, String>> expired = new ArrayList<>();
        TimerWheel<String, String> wheel = new TimerWheel<>(16, 1_000_000L, expired::add);
        Node<String, String> node = new Node<>("k", "v", 1);

        long now = System.nanoTime();
        wheel.schedule(node, now + 5_000_000L, now);

        wheel.advance(now);
        assertTrue(expired.isEmpty(), "must not fire before the deadline");

        wheel.advance(now + 10_000_000L);
        assertEquals(1, expired.size());
        assertSame(node, expired.get(0));
        assertFalse(node.timerScheduled);
    }

    @Test
    void cancellingPreventsFiring() {
        List<Node<String, String>> expired = new ArrayList<>();
        TimerWheel<String, String> wheel = new TimerWheel<>(16, 1_000_000L, expired::add);
        Node<String, String> node = new Node<>("k", "v", 1);

        long now = System.nanoTime();
        wheel.schedule(node, now + 5_000_000L, now);
        wheel.remove(node);

        wheel.advance(now + 10_000_000L);
        assertTrue(expired.isEmpty(), "a cancelled timer must not fire");
    }

    @Test
    void timersBeyondOneRotationUseRounds() {
        List<Node<String, String>> expired = new ArrayList<>();
        // 16 slots * 1 ms = 16 ms per rotation.
        TimerWheel<String, String> wheel = new TimerWheel<>(16, 1_000_000L, expired::add);
        Node<String, String> node = new Node<>("k", "v", 1);

        long now = System.nanoTime();
        wheel.schedule(node, now + 50_000_000L, now); // ~3 rotations away

        wheel.advance(now + 30_000_000L);
        assertTrue(expired.isEmpty(), "should still be waiting");

        wheel.advance(now + 60_000_000L);
        assertEquals(1, expired.size());
    }

    @Test
    void pastDeadlineFiresImmediately() {
        List<Node<String, String>> expired = new ArrayList<>();
        TimerWheel<String, String> wheel = new TimerWheel<>(16, 1_000_000L, expired::add);
        Node<String, String> node = new Node<>("k", "v", 1);

        long now = System.nanoTime();
        wheel.schedule(node, now - 1L, now);
        assertEquals(1, expired.size());
    }
}