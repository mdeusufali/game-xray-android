package com.gamexray.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WinnerDetectorTest {

    @Test
    fun ocrResultNeedsRepeatedFramesBeforeConfirmation() {
        val detector = WinnerDetector(requiredOcrFrames = 2)

        val first = detector.evaluate(ocrWinnerSignal(Side.ANDAR), VisualWinnerEvidence())
        assertEquals(WinnerState.RESULT_NOT_DETECTED, first.state)
        assertFalse(first.confirmed)

        val second = detector.evaluate(ocrWinnerSignal(Side.ANDAR), VisualWinnerEvidence())
        assertEquals(WinnerState.WINNER_CONFIRMED, second.state)
        assertEquals(Side.ANDAR, second.side)
        assertTrue(second.confirmed)
    }

    @Test
    fun ocrAndVisualAgreementCanConfirmImmediately() {
        val detector = WinnerDetector(requiredOcrFrames = 3)
        val visual = VisualWinnerEvidence(
            side = Side.BAHAR,
            candidate = true,
            scoreAndar = 0.2,
            scoreBahar = 2.4,
            streak = 3,
            reason = "stable visual change on BAHAR"
        )
        val decision = detector.evaluate(ocrWinnerSignal(Side.BAHAR), visual)
        assertEquals(WinnerState.WINNER_CONFIRMED, decision.state)
        assertEquals(Side.BAHAR, decision.side)
    }

    @Test
    fun visualHighlightAloneRemainsACandidateOnly() {
        val detector = WinnerDetector()
        val visual = VisualWinnerEvidence(
            side = Side.ANDAR,
            candidate = true,
            scoreAndar = 2.0,
            streak = 3,
            reason = "stable visual change"
        )
        val decision = detector.evaluate(OcrWinnerSignal(), visual)
        assertEquals(WinnerState.VISUAL_WINNER_CANDIDATE, decision.state)
        assertEquals(Side.ANDAR, decision.side)
        assertFalse(decision.confirmed)
    }

    @Test
    fun conflictingOcrIsAlwaysAConflict() {
        val detector = WinnerDetector()
        val conflict = OcrWinnerSignal(
            side = Side.UNKNOWN,
            conflict = true,
            conflictReason = "different lines name different winners"
        )
        val decision = detector.evaluate(conflict, VisualWinnerEvidence())
        assertEquals(WinnerState.RESULT_CONFLICT, decision.state)
        assertEquals(Side.UNKNOWN, decision.side)
        assertFalse(decision.confirmed)
    }

    @Test
    fun ambiguousVisualRegionsAreAConflict() {
        val detector = WinnerDetector()
        val decision = detector.evaluate(
            OcrWinnerSignal(),
            VisualWinnerEvidence(conflict = true, reason = "both changed")
        )
        assertEquals(WinnerState.RESULT_CONFLICT, decision.state)
    }

    @Test
    fun confirmedResultIsStickyUntilContradicted() {
        val detector = WinnerDetector(requiredOcrFrames = 2)
        detector.evaluate(ocrWinnerSignal(Side.ANDAR), VisualWinnerEvidence())
        detector.evaluate(ocrWinnerSignal(Side.ANDAR), VisualWinnerEvidence())

        val sticky = detector.evaluate(OcrWinnerSignal(), VisualWinnerEvidence())
        assertEquals(WinnerState.WINNER_CONFIRMED, sticky.state)
        assertEquals(Side.ANDAR, sticky.side)

        val contradiction = detector.evaluate(ocrWinnerSignal(Side.BAHAR), VisualWinnerEvidence())
        assertEquals(WinnerState.RESULT_CONFLICT, contradiction.state)
        assertFalse(contradiction.confirmed)
    }

    @Test
    fun visualContradictionAfterOcrConfirmationIsAConflict() {
        val detector = WinnerDetector(requiredOcrFrames = 2)
        detector.evaluate(ocrWinnerSignal(Side.ANDAR), VisualWinnerEvidence())
        detector.evaluate(ocrWinnerSignal(Side.ANDAR), VisualWinnerEvidence())

        val decision = detector.evaluate(
            OcrWinnerSignal(),
            VisualWinnerEvidence(side = Side.BAHAR, candidate = true, streak = 3)
        )
        assertEquals(WinnerState.RESULT_CONFLICT, decision.state)
    }

    @Test
    fun visualTrackerRequiresConsecutiveDominantFrames() {
        val tracker = VisualWinnerTracker(requiredConsecutive = 3)
        val baseA = visualStats(Side.ANDAR, 50.0, 50.0, 50.0, 50.0)
        val baseB = visualStats(Side.BAHAR, 50.0, 50.0, 50.0, 50.0)
        val baseline = mapOf(Side.ANDAR to baseA, Side.BAHAR to baseB)

        val captured = tracker.observe(baseline, BettingStage.BETTING_LOCKED, cardsPresent = true)
        assertFalse(captured.candidate)
        assertTrue(tracker.baselineCaptured())

        val brightA = visualStats(Side.ANDAR, 110.0, 160.0, 150.0, 140.0, brightRatio = 0.32)
        val changed = mapOf(Side.ANDAR to brightA, Side.BAHAR to baseB)

        val one = tracker.observe(changed, BettingStage.BETTING_LOCKED, cardsPresent = true)
        assertFalse(one.candidate)
        assertEquals(1, one.streak)

        tracker.observe(changed, BettingStage.BETTING_LOCKED, cardsPresent = true)
        val three = tracker.observe(changed, BettingStage.BETTING_LOCKED, cardsPresent = true)
        assertTrue(three.candidate)
        assertEquals(Side.ANDAR, three.side)
        assertEquals(3, three.streak)
    }

    @Test
    fun blinkResetsVisualStreak() {
        val tracker = VisualWinnerTracker(requiredConsecutive = 3)
        val baseA = visualStats(Side.ANDAR, 50.0, 50.0, 50.0, 50.0)
        val baseB = visualStats(Side.BAHAR, 50.0, 50.0, 50.0, 50.0)
        val baseline = mapOf(Side.ANDAR to baseA, Side.BAHAR to baseB)
        tracker.observe(baseline, BettingStage.BETTING_LOCKED, cardsPresent = true)

        val brightA = visualStats(Side.ANDAR, 110.0, 160.0, 150.0, 140.0, brightRatio = 0.32)
        val changed = mapOf(Side.ANDAR to brightA, Side.BAHAR to baseB)
        tracker.observe(changed, BettingStage.BETTING_LOCKED, cardsPresent = true)
        tracker.observe(changed, BettingStage.BETTING_LOCKED, cardsPresent = true)

        val blink = tracker.observe(baseline, BettingStage.BETTING_LOCKED, cardsPresent = true)
        assertFalse(blink.candidate)
        assertEquals(0, blink.streak)

        val after = tracker.observe(changed, BettingStage.BETTING_LOCKED, cardsPresent = true)
        assertFalse(after.candidate)
        assertEquals(1, after.streak)
    }

    @Test
    fun bothRegionsChangingSimilarlyIsAVisualConflict() {
        val tracker = VisualWinnerTracker(requiredConsecutive = 2)
        val base = mapOf(
            Side.ANDAR to visualStats(Side.ANDAR, 50.0, 50.0, 50.0, 50.0),
            Side.BAHAR to visualStats(Side.BAHAR, 50.0, 50.0, 50.0, 50.0)
        )
        tracker.observe(base, BettingStage.BETTING_LOCKED, cardsPresent = true)
        val changed = mapOf(
            Side.ANDAR to visualStats(Side.ANDAR, 110.0, 160.0, 150.0, 140.0, brightRatio = 0.32),
            Side.BAHAR to visualStats(Side.BAHAR, 110.0, 160.0, 150.0, 140.0, brightRatio = 0.32)
        )
        val evidence = tracker.observe(changed, BettingStage.BETTING_LOCKED, cardsPresent = true)
        assertTrue(evidence.conflict)
        assertFalse(evidence.candidate)
    }

    @Test
    fun visualEvidenceIsIgnoredWhileBettingIsOpenWithoutCards() {
        val tracker = VisualWinnerTracker(requiredConsecutive = 1)
        val base = mapOf(
            Side.ANDAR to visualStats(Side.ANDAR, 50.0, 50.0, 50.0, 50.0),
            Side.BAHAR to visualStats(Side.BAHAR, 50.0, 50.0, 50.0, 50.0)
        )
        tracker.observe(base, BettingStage.BETTING_OPEN, cardsPresent = false)
        val changed = mapOf(
            Side.ANDAR to visualStats(Side.ANDAR, 110.0, 160.0, 150.0, 140.0, brightRatio = 0.32),
            Side.BAHAR to base.getValue(Side.BAHAR)
        )
        val evidence = tracker.observe(changed, BettingStage.BETTING_OPEN, cardsPresent = false)
        assertFalse(evidence.candidate)
        assertEquals(0, evidence.streak)
    }

    @Test
    fun visualCandidateIsRetainedAfterTheHighlightDisappears() {
        val detector = WinnerDetector()
        val candidate = VisualWinnerEvidence(
            side = Side.BAHAR,
            candidate = true,
            scoreBahar = 2.2,
            streak = 3,
            reason = "stable visual change"
        )
        val first = detector.evaluate(OcrWinnerSignal(), candidate)
        assertEquals(WinnerState.VISUAL_WINNER_CANDIDATE, first.state)
        assertEquals(Side.BAHAR, first.side)

        // Payout animation removes the glow: the earlier evidence must survive.
        val faded = detector.evaluate(OcrWinnerSignal(), VisualWinnerEvidence())
        assertEquals(WinnerState.VISUAL_WINNER_CANDIDATE, faded.state)
        assertEquals(Side.BAHAR, faded.side)
        assertFalse(faded.confirmed)
    }

    @Test
    fun visualCandidatePlusOppositeOcrWordingIsAConflict() {
        val detector = WinnerDetector()
        val visual = VisualWinnerEvidence(
            side = Side.ANDAR,
            candidate = true,
            scoreAndar = 2.4,
            streak = 3,
            reason = "stable visual change"
        )
        val decision = detector.evaluate(ocrWinnerSignal(Side.BAHAR), visual)
        assertEquals(WinnerState.RESULT_CONFLICT, decision.state)
        assertFalse(decision.confirmed)
    }

    @Test
    fun winnerScoreIgnoresPureDarkeningAndUsesSideColour() {
        val base = visualStats(Side.ANDAR, 120.0, 120.0, 120.0, 120.0)
        // The losing panel dims: lower luma with no colour gain must not score positive.
        val dimmed = visualStats(Side.ANDAR, 70.0, 70.0, 70.0, 70.0)
        assertEquals(0.0, dimmed.winnerScore(base, 18.0, 22.0), 0.001)

        // The blue winner glow raises luma and blue relative to the other channels.
        val blueGlow = visualStats(Side.ANDAR, 150.0, 110.0, 140.0, 200.0)
        assertTrue(blueGlow.winnerScore(base, 18.0, 22.0) > 1.0)

        // A red/orange Bahar glow must score on the Bahar side.
        val redGlow = visualStats(Side.BAHAR, 150.0, 210.0, 120.0, 100.0)
        assertTrue(redGlow.winnerScore(base, 18.0, 22.0) > 1.0)
    }
}
