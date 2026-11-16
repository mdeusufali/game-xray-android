package com.gamexray.app.core

import kotlin.math.abs

/**
 * Compares side result-label regions against a baseline captured at the start of the round.
 * A side must dominate for several consecutive frames before it becomes a visual candidate.
 * A temporary blink or a single animation frame resets the streak.
 */
class VisualWinnerTracker(
    private val requiredConsecutive: Int = 3,
    private val minLumaDelta: Double = 18.0,
    private val minColorDelta: Double = 22.0,
    private val dominanceRatio: Double = 1.45
) {
    private var baseline: Map<Side, RegionVisualStats>? = null
    private var leading: Side = Side.UNKNOWN
    private var streak: Int = 0
    private var lastEvidence: VisualWinnerEvidence = VisualWinnerEvidence()

    fun reset() {
        baseline = null
        leading = Side.UNKNOWN
        streak = 0
        lastEvidence = VisualWinnerEvidence()
    }

    fun baselineCaptured(): Boolean = baseline != null

    fun observe(
        stats: Map<Side, RegionVisualStats>,
        bettingStage: BettingStage,
        cardsPresent: Boolean
    ): VisualWinnerEvidence {
        val a = stats[Side.ANDAR]
        val b = stats[Side.BAHAR]
        if (a == null || b == null || a.sampleCount == 0 || b.sampleCount == 0) {
            streak = 0
            leading = Side.UNKNOWN
            return VisualWinnerEvidence(reason = "side result regions not located")
        }
        val base = baseline
        if (base == null) {
            baseline = stats.toMap()
            return VisualWinnerEvidence(reason = "visual baseline captured")
        }
        val baseA = base[Side.ANDAR]
        val baseB = base[Side.BAHAR]
        if (baseA == null || baseB == null) {
            baseline = stats.toMap()
            return VisualWinnerEvidence(reason = "visual baseline reset")
        }

        val eligible = bettingStage == BettingStage.BETTING_LOCKED || cardsPresent
        val scoreA = a.winnerScore(baseA, minLumaDelta, minColorDelta)
        val scoreB = b.winnerScore(baseB, minLumaDelta, minColorDelta)

        if (!eligible) {
            // While betting is open and no cards exist, refreshes are usually button animations.
            if (scoreA < 1.0 && scoreB < 1.0) baseline = stats.toMap()
            streak = 0
            leading = Side.UNKNOWN
            return VisualWinnerEvidence(
                scoreAndar = scoreA,
                scoreBahar = scoreB,
                reason = "betting open; visual result evidence ignored"
            )
        }

        val close = abs(scoreA - scoreB) <= maxOf(scoreA, scoreB) * 0.18
        val newLeading = when {
            scoreA >= 1.0 && scoreB >= 1.0 && close -> Side.UNKNOWN
            scoreA >= 1.0 && scoreA > scoreB * dominanceRatio -> Side.ANDAR
            scoreB >= 1.0 && scoreB > scoreA * dominanceRatio -> Side.BAHAR
            else -> Side.UNKNOWN
        }

        if (newLeading == Side.UNKNOWN) {
            streak = 0
            leading = Side.UNKNOWN
            val conflict = scoreA >= 1.0 && scoreB >= 1.0 && close
            lastEvidence = VisualWinnerEvidence(
                conflict = conflict,
                scoreAndar = scoreA,
                scoreBahar = scoreB,
                reason = if (conflict) "both side regions changed similarly" else "no dominant visual change"
            )
            return lastEvidence
        }

        if (newLeading == leading) streak++ else {
            leading = newLeading
            streak = 1
        }
        val candidate = streak >= requiredConsecutive
        lastEvidence = VisualWinnerEvidence(
            side = if (candidate) leading else Side.UNKNOWN,
            candidate = candidate,
            scoreAndar = scoreA,
            scoreBahar = scoreB,
            streak = streak,
            reason = if (candidate) {
                "stable visual change on ${leading.label} (${streak} frames)"
            } else {
                "visual change on ${leading.label} not yet stable (${streak}/$requiredConsecutive)"
            }
        )
        return lastEvidence
    }

    fun last(): VisualWinnerEvidence = lastEvidence
}

/**
 * Combines explicit OCR result wording with stable visual evidence.
 *
 * OCR-only confirmation requires repeated frames. A visual-only highlight remains a candidate
 * and is never treated as a financial result.
 */
class WinnerDetector(
    private val requiredOcrFrames: Int = 2
) {
    private var ocrSide: Side = Side.UNKNOWN
    private var ocrStreak: Int = 0
    private var ocrEvidence: OcrWinnerSignal = OcrWinnerSignal()
    private var confirmedSide: Side = Side.UNKNOWN
    private var conflict: Boolean = false
    private var candidateSide: Side = Side.UNKNOWN
    private var lastDecision: WinnerDecision = WinnerDecision()

    fun reset() {
        ocrSide = Side.UNKNOWN
        ocrStreak = 0
        ocrEvidence = OcrWinnerSignal()
        confirmedSide = Side.UNKNOWN
        conflict = false
        candidateSide = Side.UNKNOWN
        lastDecision = WinnerDecision()
    }

    fun evaluate(ocr: OcrWinnerSignal, visual: VisualWinnerEvidence): WinnerDecision {
        ocrEvidence = ocr
        if (ocr.conflict) {
            conflict = true
            confirmedSide = Side.UNKNOWN
            lastDecision = WinnerDecision(
                state = WinnerState.RESULT_CONFLICT,
                reason = "OCR result conflict: ${ocr.conflictReason}",
                ocrSide = Side.UNKNOWN,
                visualSide = visual.side
            )
            return lastDecision
        }

        if (ocr.side == Side.UNKNOWN) {
            ocrStreak = 0
            ocrSide = Side.UNKNOWN
        } else if (ocr.side == ocrSide) {
            ocrStreak++
        } else {
            ocrSide = ocr.side
            ocrStreak = 1
        }

        if (visual.conflict) {
            conflict = true
            confirmedSide = Side.UNKNOWN
            lastDecision = WinnerDecision(
                state = WinnerState.RESULT_CONFLICT,
                reason = "visual result regions are ambiguous",
                ocrSide = ocr.side,
                visualSide = Side.UNKNOWN
            )
            return lastDecision
        }

        // A confirmed result is sticky for this round, but direct contradictory evidence wins.
        if (confirmedSide != Side.UNKNOWN) {
            if (visual.candidate && visual.side != Side.UNKNOWN && visual.side != confirmedSide) {
                conflict = true
                confirmedSide = Side.UNKNOWN
                lastDecision = WinnerDecision(
                    state = WinnerState.RESULT_CONFLICT,
                    side = Side.UNKNOWN,
                    reason = "confirmed OCR side disagrees with visual side",
                    ocrSide = ocr.side,
                    visualSide = visual.side
                )
                return lastDecision
            }
            if (ocr.side != Side.UNKNOWN && ocr.side != confirmedSide && ocrStreak >= 1) {
                conflict = true
                confirmedSide = Side.UNKNOWN
                lastDecision = WinnerDecision(
                    state = WinnerState.RESULT_CONFLICT,
                    side = Side.UNKNOWN,
                    reason = "OCR result changed to the opposite side",
                    ocrSide = ocr.side,
                    visualSide = visual.side
                )
                return lastDecision
            }
            lastDecision = WinnerDecision(
                state = WinnerState.WINNER_CONFIRMED,
                side = confirmedSide,
                confidence = 0.95f,
                reason = lastDecision.reason.ifBlank { "winner previously confirmed this round" },
                ocrSide = ocr.side,
                visualSide = visual.side
            )
            return lastDecision
        }

        val visualAgreesWithOcr = ocr.side != Side.UNKNOWN && visual.candidate && visual.side == ocr.side
        val ocrStable = ocr.side != Side.UNKNOWN && ocrStreak >= requiredOcrFrames

        if (visual.candidate && visual.side != Side.UNKNOWN) {
            candidateSide = visual.side
        }

        // A live highlight on the opposite panel directly contradicts stable result wording.
        // It is safer to report a conflict than to save a possibly wrong financial result.
        if (visual.candidate && visual.side != Side.UNKNOWN &&
            ocr.side != Side.UNKNOWN && visual.side != ocr.side
        ) {
            conflict = true
            lastDecision = WinnerDecision(
                state = WinnerState.RESULT_CONFLICT,
                reason = "stable OCR result contradicts the highlighted panel",
                ocrSide = ocr.side,
                visualSide = visual.side
            )
            return lastDecision
        }

        if (visualAgreesWithOcr || ocrStable) {
            val side = if (ocr.side != Side.UNKNOWN) ocr.side else visual.side
            val ocrConf = ocr.strongest?.confidence?.coerceIn(0f, 1f) ?: 0f
            val confidence = if (visualAgreesWithOcr) {
                (0.62f + 0.30f * ocrConf + 0.08f * ((visual.scoreAndar + visual.scoreBahar) / 3.0).toFloat())
                    .coerceIn(0f, 1f)
            } else {
                (0.70f + 0.25f * ocrConf).coerceIn(0f, 1f)
            }
            confirmedSide = side
            conflict = false
            lastDecision = WinnerDecision(
                state = WinnerState.WINNER_CONFIRMED,
                side = side,
                confidence = confidence,
                reason = if (visualAgreesWithOcr) {
                    "OCR result and stable visual change agree on ${side.label}"
                } else {
                    "explicit OCR result repeated (${ocrStreak} frames): ${ocr.strongest?.evidence ?: "result wording"}"
                },
                ocrSide = ocr.side,
                visualSide = visual.side
            )
            return lastDecision
        }

        if (visual.candidate) {
            lastDecision = WinnerDecision(
                state = WinnerState.VISUAL_WINNER_CANDIDATE,
                side = visual.side,
                confidence = 0.5f,
                reason = visual.reason,
                ocrSide = ocr.side,
                visualSide = visual.side
            )
            return lastDecision
        }

        // A candidate or an unresolved conflict is kept for the rest of the round. The payout
        // animation often removes the glow for a frame or two; that must not erase evidence.
        if (candidateSide != Side.UNKNOWN &&
            ocr.side != Side.UNKNOWN && ocr.side != candidateSide
        ) {
            conflict = true
        }
        lastDecision = when {
            conflict -> WinnerDecision(
                state = WinnerState.RESULT_CONFLICT,
                reason = "result conflict unresolved; candidate=${candidateSide.label}",
                ocrSide = ocr.side,
                visualSide = candidateSide
            )
            candidateSide != Side.UNKNOWN -> WinnerDecision(
                state = WinnerState.VISUAL_WINNER_CANDIDATE,
                side = candidateSide,
                confidence = 0.5f,
                reason = "visual winner candidate retained for this round",
                ocrSide = ocr.side,
                visualSide = candidateSide
            )
            else -> WinnerDecision(
                state = WinnerState.RESULT_NOT_DETECTED,
                reason = if (ocr.side != Side.UNKNOWN) {
                    "OCR result wording not yet stable (${ocrStreak}/$requiredOcrFrames)"
                } else {
                    "no confirmed result evidence"
                },
                ocrSide = ocr.side,
                visualSide = visual.side
            )
        }
        return lastDecision
    }

    fun last(): WinnerDecision = lastDecision
}

/** Validates and settles the user's own bet for a completed round. */
object Accounting {
    fun settle(
        side: Side,
        stake: Double?,
        odds: Double?,
        winner: Side?,
        winnerState: WinnerState
    ): Settlement {
        if (winnerState != WinnerState.WINNER_CONFIRMED) {
            return Settlement(
                status = "unavailable",
                side = side,
                stake = stake,
                odds = odds,
                winner = winner,
                reason = "winner is not confirmed"
            )
        }
        if (side != Side.ANDAR && side != Side.BAHAR) {
            return Settlement("unavailable", side, stake, odds, winner, reason = "personal side is not set")
        }
        if (!Validation.money(stake) || stake == null || stake <= 0.0) {
            return Settlement("unavailable", side, stake, odds, winner, reason = "personal stake is missing or invalid")
        }
        if (!Validation.odds(odds)) {
            return Settlement("unavailable", side, stake, odds, winner, reason = "personal odds are missing or invalid")
        }
        if (winner != Side.ANDAR && winner != Side.BAHAR) {
            return Settlement("unavailable", side, stake, odds, winner, reason = "confirmed winner side is missing")
        }
        val wins = side == winner
        return if (wins) {
            val gross = stake * odds!!
            Settlement(
                status = "win",
                side = side,
                stake = stake,
                odds = odds,
                winner = winner,
                isWin = true,
                grossReturn = gross,
                netProfit = gross - stake,
                lossAmount = 0.0,
                reason = "selected side matches confirmed winner"
            )
        } else {
            Settlement(
                status = "loss",
                side = side,
                stake = stake,
                odds = odds,
                winner = winner,
                isWin = false,
                grossReturn = 0.0,
                netProfit = -stake,
                lossAmount = stake,
                reason = "selected side does not match confirmed winner"
            )
        }
    }

    fun overall(records: List<RoundRecord>): OverallStats {
        val unique = records.distinctBy { it.id }
        var settled = 0
        var wins = 0
        var losses = 0
        var totalStake = 0.0
        var totalProfit = 0.0
        var totalLoss = 0.0
        var net = 0.0
        var gross = 0.0
        var andarWins = 0
        var baharWins = 0
        for (record in unique) {
            // A visual candidate or an unresolved round must never be counted as a side win.
            if (record.winnerState == WinnerState.WINNER_CONFIRMED) {
                when (record.winner) {
                    Side.ANDAR -> andarWins++
                    Side.BAHAR -> baharWins++
                    Side.UNKNOWN -> Unit
                }
            }
            val pnl = record.netProfit
            if (pnl == null) continue
            settled++
            totalStake += record.myStake ?: 0.0
            net += pnl
            gross += record.grossReturn ?: 0.0
            if (pnl > 0.0) {
                wins++
                totalProfit += pnl
            } else if (pnl < 0.0) {
                losses++
                totalLoss += -pnl
            }
        }
        return OverallStats(
            totalRounds = unique.size,
            settledRounds = settled,
            wins = wins,
            losses = losses,
            totalStake = totalStake,
            totalProfit = totalProfit,
            totalLoss = totalLoss,
            netResult = net,
            totalGrossReturn = gross,
            unsettledRounds = unique.size - settled,
            andarWins = andarWins,
            baharWins = baharWins
        )
    }
}
