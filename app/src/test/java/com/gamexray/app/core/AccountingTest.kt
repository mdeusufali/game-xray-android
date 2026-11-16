package com.gamexray.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountingTest {

    @Test
    fun winningBetCalculatesGrossAndNetProfit() {
        val settlement = Accounting.settle(
            side = Side.ANDAR,
            stake = 100.0,
            odds = 2.5,
            winner = Side.ANDAR,
            winnerState = WinnerState.WINNER_CONFIRMED
        )
        assertTrue(settlement.calculated)
        assertEquals("win", settlement.status)
        assertEquals(true, settlement.isWin)
        assertEquals(250.0, settlement.grossReturn!!, 0.001)
        assertEquals(150.0, settlement.netProfit!!, 0.001)
        assertEquals(0.0, settlement.lossAmount!!, 0.001)
    }

    @Test
    fun losingBetLosesExactlyTheStake() {
        val settlement = Accounting.settle(
            side = Side.ANDAR,
            stake = 100.0,
            odds = 2.5,
            winner = Side.BAHAR,
            winnerState = WinnerState.WINNER_CONFIRMED
        )
        assertTrue(settlement.calculated)
        assertEquals("loss", settlement.status)
        assertEquals(false, settlement.isWin)
        assertEquals(0.0, settlement.grossReturn!!, 0.001)
        assertEquals(-100.0, settlement.netProfit!!, 0.001)
        assertEquals(100.0, settlement.lossAmount!!, 0.001)
    }

    @Test
    fun unconfirmedWinnerNeverProducesFinancialResult() {
        val settlement = Accounting.settle(
            side = Side.ANDAR,
            stake = 100.0,
            odds = 2.5,
            winner = Side.ANDAR,
            winnerState = WinnerState.VISUAL_WINNER_CANDIDATE
        )
        assertFalse(settlement.calculated)
        assertNull(settlement.netProfit)
        assertNull(settlement.grossReturn)
        assertEquals("unavailable", settlement.status)
    }

    @Test
    fun missingPersonalDataNeverProducesFinancialResult() {
        val noSide = Accounting.settle(
            side = Side.UNKNOWN,
            stake = 100.0,
            odds = 2.5,
            winner = Side.ANDAR,
            winnerState = WinnerState.WINNER_CONFIRMED
        )
        assertFalse(noSide.calculated)

        val noStake = Accounting.settle(
            side = Side.ANDAR,
            stake = null,
            odds = 2.5,
            winner = Side.ANDAR,
            winnerState = WinnerState.WINNER_CONFIRMED
        )
        assertFalse(noStake.calculated)
        assertTrue(noStake.reason.contains("stake"))

        val invalidOdds = Accounting.settle(
            side = Side.ANDAR,
            stake = 100.0,
            odds = 0.5,
            winner = Side.ANDAR,
            winnerState = WinnerState.WINNER_CONFIRMED
        )
        assertFalse(invalidOdds.calculated)
        assertTrue(invalidOdds.reason.contains("odds"))
    }

    @Test
    fun overallStatsSeparateProfitLossAndUnsettledRounds() {
        val records = listOf(
            roundRecord(
                id = 1,
                completedAt = 1_000L,
                winner = Side.ANDAR,
                mySide = Side.ANDAR,
                myStake = 100.0,
                myOdds = 2.5,
                netProfit = 150.0,
                grossReturn = 250.0
            ),
            // Same ID with a higher revision must not be counted twice.
            roundRecord(
                id = 1,
                completedAt = 1_000L,
                winner = Side.ANDAR,
                mySide = Side.ANDAR,
                myStake = 100.0,
                myOdds = 2.5,
                netProfit = 150.0,
                grossReturn = 250.0,
                revision = 2
            ),
            roundRecord(
                id = 2,
                completedAt = 2_000L,
                winner = Side.BAHAR,
                mySide = Side.ANDAR,
                myStake = 100.0,
                myOdds = 2.0,
                netProfit = -100.0,
                grossReturn = 0.0,
                lossAmount = 100.0
            ),
            roundRecord(
                id = 3,
                completedAt = 3_000L,
                winner = Side.ANDAR,
                mySide = Side.UNKNOWN,
                myStake = null,
                myOdds = null,
                netProfit = null
            )
        )

        val stats = Accounting.overall(records)
        assertEquals(3, stats.totalRounds)
        assertEquals(2, stats.settledRounds)
        assertEquals(1, stats.unsettledRounds)
        assertEquals(1, stats.wins)
        assertEquals(1, stats.losses)
        assertEquals(200.0, stats.totalStake, 0.001)
        assertEquals(150.0, stats.totalProfit, 0.001)
        assertEquals(100.0, stats.totalLoss, 0.001)
        assertEquals(50.0, stats.netResult, 0.001)
        assertEquals(250.0, stats.totalGrossReturn, 0.001)
        assertEquals(2, stats.andarWins)
        assertEquals(1, stats.baharWins)
    }

    @Test
    fun zeroNetProfitIsNotCountedAsAWin() {
        val stats = Accounting.overall(
            listOf(
                roundRecord(id = 10, netProfit = 0.0, grossReturn = 100.0)
            )
        )
        assertEquals(1, stats.settledRounds)
        assertEquals(0, stats.wins)
        assertEquals(0, stats.losses)
        assertEquals(0.0, stats.netResult, 0.001)
    }

    @Test
    fun overallDoesNotCountUnconfirmedCandidatesAsSideWins() {
        val stats = Accounting.overall(
            listOf(
                roundRecord(
                    id = 20,
                    winner = Side.ANDAR,
                    winnerState = WinnerState.VISUAL_WINNER_CANDIDATE,
                    netProfit = null
                ),
                roundRecord(
                    id = 21,
                    winner = Side.BAHAR,
                    winnerState = WinnerState.RESULT_CONFLICT,
                    netProfit = null
                ),
                roundRecord(
                    id = 22,
                    winner = Side.BAHAR,
                    winnerState = WinnerState.WINNER_CONFIRMED,
                    netProfit = null
                )
            )
        )
        assertEquals(3, stats.totalRounds)
        assertEquals(0, stats.settledRounds)
        assertEquals(0, stats.andarWins)
        assertEquals(1, stats.baharWins)
    }
}
