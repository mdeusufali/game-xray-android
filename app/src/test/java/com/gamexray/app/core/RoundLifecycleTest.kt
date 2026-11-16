package com.gamexray.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoundLifecycleTest {

    private fun openFrame(time: Long) = ocrFrame(
        ocrLine("PLACE BET", 0.20f, 0.78f, 0.45f, 0.83f),
        timestamp = time
    )

    private fun lockedFrame(time: Long, winnerLine: String? = null) = ocrFrame(
        *listOfNotNull(
            ocrLine("BET LOCKED", 0.20f, 0.78f, 0.45f, 0.83f),
            winnerLine?.let { ocrLine(it, 0.10f, 0.60f, 0.42f, 0.65f) }
        ).toTypedArray(),
        timestamp = time
    )

    private fun andarCards() = listOf(
        testCandidate(Side.ANDAR, 0.10f, 0.30f),
        testCandidate(Side.ANDAR, 0.30f, 0.30f)
    )

    private fun newEngine() = FrameEngine(idGenerator = { now, last -> maxOf(now, last + 1L) })

    @Test
    fun personalBetIsLiveUntilLockThenFrozenIntoTheRecord() {
        val engine = newEngine()
        val first = engine.process(
            openFrame(1_000L),
            emptyList(),
            emptyMap(),
            BetSettings(Side.ANDAR, 100.0, 2.0)
        )
        assertTrue(first.startedNewRound)
        assertEquals(RoundStage.BETTING_OPEN, first.round!!.stage)
        assertEquals(Side.ANDAR, first.round!!.bet!!.side)

        val second = engine.process(
            openFrame(2_000L),
            emptyList(),
            emptyMap(),
            BetSettings(Side.BAHAR, 200.0, 3.0)
        )
        assertFalse(second.startedNewRound)
        assertEquals(Side.BAHAR, second.round!!.bet!!.side)
        assertEquals(200.0, second.round!!.bet!!.stake!!, 0.001)

        engine.process(
            lockedFrame(3_000L),
            emptyList(),
            emptyMap(),
            BetSettings(Side.BAHAR, 200.0, 3.0)
        )
        // Later edits are ignored because the bet is frozen.
        engine.process(
            lockedFrame(4_000L),
            andarCards(),
            emptyMap(),
            BetSettings(Side.ANDAR, 999.0, 5.0)
        )
        engine.process(
            lockedFrame(5_000L, "ANDAR WINS"),
            andarCards(),
            emptyMap(),
            BetSettings(Side.ANDAR, 999.0, 5.0)
        )
        val completion = engine.process(
            lockedFrame(6_000L, "ANDAR WINS"),
            andarCards(),
            emptyMap(),
            BetSettings(Side.ANDAR, 999.0, 5.0)
        )

        val record = completion.roundUpdate.completed
        assertNotNull(record)
        assertEquals(Side.BAHAR, record!!.mySide)
        assertEquals(200.0, record.myStake!!, 0.001)
        assertEquals(3.0, record.myOdds!!, 0.001)
        assertEquals(Side.ANDAR, record.winner)
        assertEquals(WinnerState.WINNER_CONFIRMED, record.winnerState)
        assertEquals(2, record.andarCards)
        assertEquals(0, record.baharCards)
        assertEquals(false, record.isWin)
        assertEquals(-200.0, record.netProfit!!, 0.001)
        assertEquals(200.0, record.lossAmount!!, 0.001)
        assertEquals("loss", record.settlementStatus)

        // The record is only emitted once.
        val later = engine.process(
            lockedFrame(7_000L, "ANDAR WINS"),
            andarCards(),
            emptyMap(),
            BetSettings(Side.ANDAR, 999.0, 5.0)
        )
        assertNull(later.roundUpdate.completed)
    }

    @Test
    fun newBettingPeriodStartsANewRoundWithANewId() {
        val engine = newEngine()
        engine.process(openFrame(1_000L), emptyList(), emptyMap(), BetSettings())
        engine.process(lockedFrame(2_000L, "BAHAR WINS"), emptyList(), emptyMap(), BetSettings())
        val completed = engine.process(
            lockedFrame(3_000L, "BAHAR WINS"),
            emptyList(),
            emptyMap(),
            BetSettings()
        )
        assertNotNull(completed.roundUpdate.completed)
        val oldId = completed.round!!.id

        val next = engine.process(openFrame(4_000L), emptyList(), emptyMap(), BetSettings())
        assertTrue(next.startedNewRound)
        assertTrue(next.roundUpdate.newRound)
        assertNotEquals(oldId, next.round!!.id)
        assertEquals(RoundStage.BETTING_OPEN, next.round!!.stage)
        assertNull(next.round!!.bet?.stake)
    }

    @Test
    fun staleCardsDuringOpenBettingAreNotCounted() {
        val engine = newEngine()
        val first = engine.process(
            openFrame(1_000L),
            andarCards(),
            emptyMap(),
            BetSettings()
        )
        val second = engine.process(
            openFrame(2_000L),
            andarCards(),
            emptyMap(),
            BetSettings()
        )
        assertEquals(0, first.round!!.totalCards)
        assertEquals(0, second.round!!.totalCards)
        assertEquals(RoundStage.BETTING_OPEN, second.round!!.stage)

        engine.process(lockedFrame(3_000L), andarCards(), emptyMap(), BetSettings())
        val locked = engine.process(lockedFrame(4_000L), andarCards(), emptyMap(), BetSettings())
        assertEquals(2, locked.round!!.andarCards)
        assertEquals(RoundStage.CARDS_DEALT, locked.round!!.stage)
    }

    @Test
    fun roundWithoutPersonalBetIsStillSavedButPnlIsUnavailable() {
        val engine = newEngine()
        engine.process(openFrame(1_000L), emptyList(), emptyMap(), BetSettings())
        engine.process(lockedFrame(2_000L, "ANDAR WINS"), emptyList(), emptyMap(), BetSettings())
        val result = engine.process(
            lockedFrame(3_000L, "ANDAR WINS"),
            emptyList(),
            emptyMap(),
            BetSettings()
        )

        val record = result.roundUpdate.completed
        assertNotNull(record)
        assertEquals(Side.UNKNOWN, record!!.mySide)
        assertNull(record.myStake)
        assertNull(record.netProfit)
        assertEquals("unavailable", record.settlementStatus)
        assertTrue(record.detectionStatus.contains("P/L unavailable"))
    }

    @Test
    fun conflictingWinnerTextPreventsRoundCompletion() {
        val engine = newEngine()
        engine.process(openFrame(1_000L), emptyList(), emptyMap(), BetSettings())
        val conflictFrame = ocrFrame(
            ocrLine("BET LOCKED", 0.20f, 0.78f, 0.45f, 0.83f),
            ocrLine("ANDAR WINS", 0.10f, 0.60f, 0.42f, 0.65f),
            ocrLine("BAHAR WINS", 0.58f, 0.62f, 0.90f, 0.67f),
            timestamp = 2_000L
        )
        val first = engine.process(conflictFrame, emptyList(), emptyMap(), BetSettings())
        val second = engine.process(
            conflictFrame.copy(timestamp = 3_000L),
            emptyList(),
            emptyMap(),
            BetSettings()
        )

        assertNull(first.roundUpdate.completed)
        assertNull(second.roundUpdate.completed)
        assertEquals(WinnerState.RESULT_CONFLICT, second.round!!.winnerState)
        assertEquals(Side.UNKNOWN, second.round!!.winnerSide)
        assertNull(second.round!!.savedAt)
    }

    @Test
    fun cardCountsNeverDecreaseInsideARound() {
        val engine = newEngine()
        engine.process(openFrame(1_000L), emptyList(), emptyMap(), BetSettings())
        engine.process(lockedFrame(2_000L), andarCards(), emptyMap(), BetSettings())
        val withCards = engine.process(lockedFrame(3_000L), andarCards(), emptyMap(), BetSettings())
        assertEquals(2, withCards.round!!.andarCards)

        // Missing detections (animation/hidden cards) do not reduce the stored count.
        val hidden = engine.process(lockedFrame(4_000L), emptyList(), emptyMap(), BetSettings())
        assertEquals(2, hidden.round!!.andarCards)
    }

    @Test
    fun missingCardsDealtTextIsNotInvented() {
        val engine = newEngine()
        engine.process(openFrame(1_000L), emptyList(), emptyMap(), BetSettings())
        val locked = engine.process(lockedFrame(2_000L, "ANDAR WINS"), emptyList(), emptyMap(), BetSettings())
        assertNull(locked.round!!.cardsDealtOcr)
    }

    @Test
    fun ocrCardsDealtIsCapturedAfterLock() {
        val engine = newEngine()
        engine.process(openFrame(1_000L), emptyList(), emptyMap(), BetSettings())
        val frame = ocrFrame(
            ocrLine("BET LOCKED", 0.20f, 0.78f, 0.45f, 0.83f),
            ocrLine("CARDS DEALT 4", 0.10f, 0.50f, 0.42f, 0.55f),
            timestamp = 2_000L
        )
        val locked = engine.process(frame, emptyList(), emptyMap(), BetSettings())
        assertEquals(4, locked.round!!.cardsDealtOcr)
    }

    @Test
    fun unresolvedConflictRoundIsFinalizedOnceOnNewRoundBoundary() {
        val engine = newEngine()
        engine.process(openFrame(1_000L), emptyList(), emptyMap(), BetSettings())
        val conflictFrame = ocrFrame(
            ocrLine("BET LOCKED", 0.20f, 0.78f, 0.45f, 0.83f),
            ocrLine("ANDAR WINS", 0.10f, 0.60f, 0.42f, 0.65f),
            ocrLine("BAHAR WINS", 0.58f, 0.62f, 0.90f, 0.67f),
            timestamp = 2_000L
        )
        val conflicting = engine.process(conflictFrame, andarCards(), emptyMap(), BetSettings())
        assertEquals(WinnerState.RESULT_CONFLICT, conflicting.round!!.winnerState)
        engine.process(
            conflictFrame.copy(timestamp = 2_500L),
            andarCards(),
            emptyMap(),
            BetSettings()
        )

        val next = engine.process(openFrame(3_000L), emptyList(), emptyMap(), BetSettings())
        val finalized = next.roundUpdate.finalizedRecords
        assertEquals(1, finalized.size)
        val record = finalized.first()
        assertEquals(WinnerState.RESULT_CONFLICT, record.winnerState)
        assertNull(record.netProfit)
        assertTrue(record.detectionStatus.contains("UNSETTLED"))
        assertEquals(2, record.andarCards)

        // A later frame in the new betting period must not emit the same record again.
        val later = engine.process(openFrame(4_000L), emptyList(), emptyMap(), BetSettings())
        assertTrue(later.roundUpdate.finalizedRecords.isEmpty())
        assertTrue(engine.roundManager.current()!!.id != record.id)
    }
}
