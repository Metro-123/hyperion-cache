package com.example.hyperion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrequencySketchTest {

    @Test
    void unseenKeyHasZeroFrequency() {
        FrequencySketch sketch = new FrequencySketch(1000);
        assertEquals(0, sketch.frequency("never-seen"));
    }

    @Test
    void frequencyTracksRepeatedIncrements() {
        FrequencySketch sketch = new FrequencySketch(1000);
        assertEquals(0, sketch.frequency(42));
        sketch.increment(42);
        assertEquals(1, sketch.frequency(42));
        sketch.increment(42);
        sketch.increment(42);
        assertEquals(3, sketch.frequency(42));
    }

    @Test
    void frequenciesAreIndependentPerKey() {
        FrequencySketch sketch = new FrequencySketch(1000);
        for (int i = 0; i < 5; i++) {
            sketch.increment("hot");
        }
        sketch.increment("cold");
        assertEquals(5, sketch.frequency("hot"));
        assertEquals(1, sketch.frequency("cold"));
    }

    @Test
    void countsSaturateAtMaximum() {
        FrequencySketch sketch = new FrequencySketch(16);
        for (int i = 0; i < 100; i++) {
            sketch.increment("x");
        }
        assertTrue(sketch.frequency("x") <= 15, "count must not exceed the saturation bound");
    }
}