package com.gamexray.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoundDeduplicationTest {

    @Test
    fun sameRoundAndRevisionIsDuplicate() {
        val existing = roundRecord(id = 10, completedAt = 1_000L, revision = 2)
        val candidate = roundRecord(id = 10, completedAt = 1_000L, revision = 2)
        assertTrue(RoundDeduplication.isDuplicate(listOf(existing), candidate))
    }

    @Test
    fun newerRevisionOfSameRoundReplacesOldRecord() {
        val existing = roundRecord(id = 10, completedAt = 1_000L, revision = 1)
        val candidate = roundRecord(id = 10, completedAt = 1_000L, revision = 2)
        assertFalse(RoundDeduplication.isDuplicate(listOf(existing), candidate))
    }

    @Test
    fun sameResultWithDifferentIdInsideWindowIsDuplicate() {
        val existing = roundRecord(id = 10, completedAt = 1_000L)
        val candidate = roundRecord(id = 11, completedAt = 3_500L)
        assertTrue(RoundDeduplication.isDuplicate(listOf(existing), candidate))
    }

    @Test
    fun sameResultOutsideWindowIsAllowed() {
        val existing = roundRecord(id = 10, completedAt = 1_000L)
        val candidate = roundRecord(id = 11, completedAt = 20_000L)
        assertFalse(RoundDeduplication.isDuplicate(listOf(existing), candidate))
    }

    @Test
    fun differentWinnerOrCardsIsNotADuplicate() {
        val existing = roundRecord(id = 10, completedAt = 1_000L)
        assertFalse(
            RoundDeduplication.isDuplicate(
                listOf(existing),
                roundRecord(id = 11, completedAt = 1_500L, winner = Side.BAHAR)
            )
        )
        assertFalse(
            RoundDeduplication.isDuplicate(
                listOf(existing),
                roundRecord(id = 12, completedAt = 1_500L, andarCards = 4)
            )
        )
    }

    @Test
    fun differentPersonalBetIsNotADuplicate() {
        val existing = roundRecord(id = 10, completedAt = 1_000L)
        val candidate = roundRecord(id = 11, completedAt = 1_500L, myStake = 250.0)
        assertFalse(RoundDeduplication.isDuplicate(listOf(existing), candidate))
    }
}
