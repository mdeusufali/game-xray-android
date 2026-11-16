package com.gamexray.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTrackerTest {
    private fun reading(value: Double?) = MoneyReading(
        value = value,
        status = DetectionStatus.DETECTED,
        raw = value?.toString() ?: "",
        reason = "test total"
    )

    @Test
    fun tracksPreviousCurrentAndDifferencePerSide() {
        val tracker = MoneyTracker()
        tracker.reset(1L)

        val first = tracker.update(Side.ANDAR, reading(1_000.0), emptyList(), 1_000L)
        assertEquals(1_000.0, first.current!!, 0.001)
        assertNull(first.previous)
        assertNull(first.difference)

        val same = tracker.update(Side.ANDAR, reading(1_000.0), emptyList(), 2_000L)
        assertEquals(1_000.0, same.current!!, 0.001)
        assertNull(same.previous)
        assertNull(same.difference)

        val grown = tracker.update(Side.ANDAR, reading(1_250.0), emptyList(), 3_000L)
        assertEquals(1_250.0, grown.current!!, 0.001)
        assertEquals(1_000.0, grown.previous!!, 0.001)
        assertEquals(250.0, grown.difference!!, 0.001)
    }

    @Test
    fun negativeDifferenceIsReported() {
        val tracker = MoneyTracker()
        tracker.reset(1L)
        tracker.update(Side.ANDAR, reading(1_250.0), emptyList(), 1_000L)
        val updated = tracker.update(Side.ANDAR, reading(900.0), emptyList(), 2_000L)
        assertEquals(1_250.0, updated.previous!!, 0.001)
        assertEquals(900.0, updated.current!!, 0.001)
        assertEquals(-350.0, updated.difference!!, 0.001)
    }

    @Test
    fun missingReadingDoesNotEraseLastReliableTotal() {
        val tracker = MoneyTracker()
        tracker.reset(1L)
        tracker.update(Side.ANDAR, reading(1_000.0), emptyList(), 1_000L)
        tracker.update(Side.ANDAR, reading(1_250.0), emptyList(), 2_000L)

        val missing = tracker.update(
            Side.ANDAR,
            MoneyReading.notDetected("total hidden by animation"),
            emptyList(),
            3_000L
        )
        assertEquals(DetectionStatus.NOT_DETECTED, missing.status)
        assertEquals(1_250.0, missing.current!!, 0.001)
        assertEquals(1_000.0, missing.previous!!, 0.001)
        assertEquals(250.0, missing.difference!!, 0.001)
    }

    @Test
    fun invalidDetectedValueBecomesUncertainWithoutChangingTotals() {
        val tracker = MoneyTracker()
        tracker.reset(1L)
        tracker.update(Side.BAHAR, reading(2_000.0), emptyList(), 1_000L)

        val invalid = tracker.update(
            Side.BAHAR,
            MoneyReading(Double.NaN, DetectionStatus.DETECTED, "x", "test"),
            emptyList(),
            2_000L
        )
        assertEquals(DetectionStatus.UNCERTAIN, invalid.status)
        assertEquals(2_000.0, invalid.current!!, 0.001)
        assertNull(invalid.currentFrame)

        val uncertain = tracker.update(
            Side.BAHAR,
            MoneyReading.uncertain("x", "unreadable"),
            emptyList(),
            3_000L
        )
        assertEquals(DetectionStatus.UNCERTAIN, uncertain.status)
        assertNull(uncertain.currentFrame)
        assertEquals(2_000.0, uncertain.current!!, 0.001)
    }

    @Test
    fun sidesAreIndependentAndNeverSummed() {
        val tracker = MoneyTracker()
        tracker.reset(9L)
        tracker.update(Side.ANDAR, reading(1_000.0), emptyList(), 1_000L)
        tracker.update(Side.BAHAR, reading(2_000.0), emptyList(), 1_000L)

        val andar = tracker.state(Side.ANDAR)
        val bahar = tracker.state(Side.BAHAR)
        assertEquals(1_000.0, andar.current!!, 0.001)
        assertEquals(2_000.0, bahar.current!!, 0.001)
        // A Bahar change does not create an Andar difference.
        tracker.update(Side.BAHAR, reading(2_500.0), emptyList(), 2_000L)
        assertNull(tracker.state(Side.ANDAR).difference)
        assertEquals(500.0, tracker.state(Side.BAHAR).difference!!, 0.001)
    }

    @Test
    fun resetClearsRoundTotals() {
        val tracker = MoneyTracker()
        tracker.reset(1L)
        tracker.update(Side.ANDAR, reading(1_000.0), emptyList(), 1_000L)
        tracker.reset(2L)
        val state = tracker.state(Side.ANDAR)
        assertNull(state.current)
        assertNull(state.previous)
        assertNull(state.difference)
        assertEquals(2L, tracker.activeRoundId())
    }
}
