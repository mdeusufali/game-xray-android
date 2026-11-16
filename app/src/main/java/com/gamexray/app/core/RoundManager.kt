package com.gamexray.app.core

import kotlin.math.max

data class FrameObservation(
    val time: Long,
    val bettingStage: BettingStage,
    val bettingStageText: String,
    val cards: CardCounterSnapshot,
    val cardsDealtOcr: ValueReading<Int>,
    val andarTotal: MoneyReading,
    val baharTotal: MoneyReading,
    val visibleAmounts: List<VisibleAmount> = emptyList(),
    val andarOdds: ValueReading<Double> = ValueReading(),
    val baharOdds: ValueReading<Double> = ValueReading(),
    val winner: WinnerDecision = WinnerDecision(),
    val settings: BetSettings = BetSettings(),
    val ocrSnapshotHash: String = ""
)

data class ManagedRound(
    val id: Long,
    val startedAt: Long,
    var stage: RoundStage = RoundStage.BETTING_OPEN,
    var bettingStage: BettingStage = BettingStage.NOT_DETECTED,
    var bet: BetFreeze? = null,
    var betLockedAt: Long? = null,
    var andarCards: Int = 0,
    var baharCards: Int = 0,
    var cardsDealtOcr: Int? = null,
    var winnerSide: Side = Side.UNKNOWN,
    var winnerState: WinnerState = WinnerState.RESULT_NOT_DETECTED,
    var winnerConfidence: Float = 0f,
    var winnerReason: String = "",
    var andarMoney: MoneyPanelState = MoneyPanelState(Side.ANDAR),
    var baharMoney: MoneyPanelState = MoneyPanelState(Side.BAHAR),
    var andarOdds: Double? = null,
    var baharOdds: Double? = null,
    var lastStageText: String = "NOT DETECTED",
    var lastObservationAt: Long = 0L,
    var completedAt: Long? = null,
    var savedAt: Long? = null,
    var detectionStatus: String = "in progress"
) {
    val totalCards: Int get() = andarCards + baharCards
    val isBetLocked: Boolean get() = betLockedAt != null || bet?.locked == true
    val isTerminal: Boolean get() = stage == RoundStage.COMPLETED
}

data class RoundUpdate(
    val round: ManagedRound?,
    val completed: RoundRecord? = null,
    val newRound: Boolean = false,
    val droppedIncompleteRoundId: Long? = null,
    /**
     * Unresolved rounds that were closed because a genuine new round started. They may carry
     * a visual candidate or a conflict, but never a financial result.
     */
    val finalizedRecords: List<RoundRecord> = emptyList()
)

/**
 * Reliable round lifecycle. A round ID is created once for a betting-open period, bets are
 * frozen at lock, card counts are monotonic within the round, and a record is emitted only
 * once after a confirmed winner.
 */
class RoundManager(
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: (Long, Long) -> Long = { now, last -> max(now, last + 1L) }
) {
    private var active: ManagedRound? = null
    private var lastIssuedId: Long = 0L
    private val moneyTracker = MoneyTracker()
    private val pendingFinalized = mutableListOf<RoundRecord>()

    fun reset() {
        active = null
        lastIssuedId = 0L
        pendingFinalized.clear()
    }

    fun restore(round: ManagedRound?) {
        active = round
        lastIssuedId = max(lastIssuedId, round?.id ?: 0L)
        if (round != null) {
            moneyTracker.restore(round.andarMoney to round.baharMoney, round.id)
        }
    }

    fun current(): ManagedRound? = active

    fun moneyState(side: Side): MoneyPanelState = moneyTracker.state(side)

    /**
     * Called by the frame engine before card tracking so a genuine round boundary can reset
     * card/visual trackers before candidates from the new round are processed.
     */
    fun prepareRound(
        time: Long,
        bettingStage: BettingStage,
        bettingStageText: String,
        settings: BetSettings,
        cardsPresent: Boolean
    ): Boolean {
        val probe = FrameObservation(
            time = time,
            bettingStage = bettingStage,
            bettingStageText = bettingStageText,
            cards = CardCounterSnapshot(),
            cardsDealtOcr = ValueReading(),
            andarTotal = MoneyReading.notDetected(),
            baharTotal = MoneyReading.notDetected(),
            settings = settings
        )
        val shouldStart = when {
            bettingStage == BettingStage.BETTING_OPEN -> {
                val current = active
                current == null || current.stage != RoundStage.BETTING_OPEN
            }
            active == null -> bettingStage == BettingStage.BETTING_LOCKED || cardsPresent
            else -> false
        }
        if (shouldStart) {
            finalizePrevious(now = time)
            startRound(probe, startingWithCards = cardsPresent)
            return true
        }
        return false
    }

    /**
     * Closes the previous round when a genuine new round begins. A locked round with cards or
     * any result evidence is saved once as unsettled (candidate/conflict kept, no P/L);
     * an empty betting-open period is simply discarded.
     */
    private fun finalizePrevious(now: Long) {
        val previous = active ?: return
        if (previous.savedAt != null) return
        if (shouldFinalize(previous)) {
            pendingFinalized.add(finalizeUnresolved(previous, now))
        }
    }

    private fun shouldFinalize(round: ManagedRound): Boolean =
        round.savedAt == null && round.stage != RoundStage.BETTING_OPEN &&
            (round.isBetLocked || round.totalCards > 0 ||
                round.winnerState != WinnerState.RESULT_NOT_DETECTED)

    fun observe(obs: FrameObservation): RoundUpdate {
        val now = obs.time
        var newRound = false
        var dropped: Long? = null
        val finalized = mutableListOf<RoundRecord>()
        if (pendingFinalized.isNotEmpty()) {
            finalized.addAll(pendingFinalized)
            pendingFinalized.clear()
        }

        val shouldStart = shouldStartNewRound(obs)
        if (shouldStart) {
            val previous = active
            if (previous != null && previous.savedAt == null && shouldFinalize(previous)) {
                finalized.add(finalizeUnresolved(previous, now))
            } else if (previous != null && previous.savedAt == null &&
                previous.stage != RoundStage.BETTING_OPEN
            ) {
                dropped = previous.id
            }
            startRound(obs, startingWithCards = obs.cards.totalCount > 0)
            newRound = true
        }

        val round = active ?: return RoundUpdate(
            null,
            newRound = newRound,
            droppedIncompleteRoundId = dropped,
            finalizedRecords = finalized
        )
        round.lastObservationAt = now
        round.lastStageText = obs.bettingStageText
        if (obs.bettingStage != BettingStage.NOT_DETECTED) round.bettingStage = obs.bettingStage

        // Bet settings are live while betting is open and frozen on lock/first dealt card.
        if (!round.isBetLocked && round.stage == RoundStage.BETTING_OPEN) {
            round.bet = freeze(obs.settings, round.startedAt, false, "betting open")
        }

        // Card accumulation is monotonic inside a round; missed frames never reduce it.
        round.andarCards = max(round.andarCards, obs.cards.andarCount)
        round.baharCards = max(round.baharCards, obs.cards.baharCount)
        if (obs.cardsDealtOcr.status == DetectionStatus.DETECTED) {
            val dealt = obs.cardsDealtOcr.value
            val resultPhase = obs.bettingStage == BettingStage.BETTING_LOCKED ||
                round.totalCards > 0
            // Ignore a stale "cards dealt" label that is still visible during the open betting
            // period of a new round.
            if (resultPhase && Validation.cardCount(dealt)) {
                round.cardsDealtOcr = max(round.cardsDealtOcr ?: 0, dealt!!)
            }
        }

        val lockNow = obs.bettingStage == BettingStage.BETTING_LOCKED || round.totalCards > 0
        if (lockNow && !round.isBetLocked) {
            round.betLockedAt = now
            round.bet = freeze(obs.settings, now, true, "betting locked")
        }
        if (round.isBetLocked && round.stage == RoundStage.BETTING_OPEN) {
            round.stage = RoundStage.BETTING_LOCKED
        }
        if (round.totalCards > 0) {
            advance(round, RoundStage.CARDS_DEALT)
            if (!round.isBetLocked) {
                round.betLockedAt = now
                round.bet = freeze(obs.settings, now, true, "cards appeared; betting treated as locked")
            }
        } else if (obs.bettingStage == BettingStage.BETTING_LOCKED) {
            advance(round, RoundStage.BETTING_LOCKED)
        }

        round.andarMoney = moneyTracker.update(Side.ANDAR, obs.andarTotal, obs.visibleAmounts, now)
        round.baharMoney = moneyTracker.update(Side.BAHAR, obs.baharTotal, obs.visibleAmounts, now)

        if (obs.andarOdds.status == DetectionStatus.DETECTED && Validation.odds(obs.andarOdds.value)) {
            round.andarOdds = obs.andarOdds.value
        }
        if (obs.baharOdds.status == DetectionStatus.DETECTED && Validation.odds(obs.baharOdds.value)) {
            round.baharOdds = obs.baharOdds.value
        }

        // Ignore stale result text during the first open frame of a brand-new round. Once a
        // record is saved for this round, later animations must not rewrite its result.
        val canAcceptResult = (round.isBetLocked || round.totalCards > 0) && round.savedAt == null
        if (canAcceptResult) {
            when (obs.winner.state) {
                WinnerState.WINNER_CONFIRMED -> {
                    val winner = obs.winner.side
                    if (winner == Side.ANDAR || winner == Side.BAHAR) {
                        round.winnerSide = winner
                        round.winnerState = WinnerState.WINNER_CONFIRMED
                        round.winnerConfidence = obs.winner.confidence
                        round.winnerReason = obs.winner.reason
                        advance(round, RoundStage.RESULT_DETECTED)
                        advance(round, RoundStage.WINNER_CONFIRMED)
                        round.detectionStatus = "winner confirmed: ${obs.winner.reason}"
                    }
                }
                WinnerState.VISUAL_WINNER_CANDIDATE -> {
                    round.winnerSide = obs.winner.side
                    round.winnerState = WinnerState.VISUAL_WINNER_CANDIDATE
                    round.winnerConfidence = obs.winner.confidence
                    round.winnerReason = obs.winner.reason
                    round.detectionStatus = "visual winner candidate only; waiting for confirmation"
                }
                WinnerState.RESULT_CONFLICT -> {
                    round.winnerSide = Side.UNKNOWN
                    round.winnerState = WinnerState.RESULT_CONFLICT
                    round.winnerConfidence = 0f
                    round.winnerReason = obs.winner.reason
                    round.detectionStatus = "result conflict; no financial result saved"
                }
                WinnerState.RESULT_NOT_DETECTED -> {
                    // A candidate or a conflict is evidence for this round. Payout animations
                    // often hide the highlight for a frame, so the state must not be erased.
                    if (round.winnerState == WinnerState.RESULT_NOT_DETECTED) {
                        round.winnerState = WinnerState.RESULT_NOT_DETECTED
                        round.winnerSide = Side.UNKNOWN
                        round.winnerConfidence = 0f
                    }
                }
            }
        }

        var completed: RoundRecord? = null
        if (round.winnerState == WinnerState.WINNER_CONFIRMED &&
            round.winnerSide != Side.UNKNOWN &&
            round.completedAt == null &&
            round.savedAt == null
        ) {
            round.completedAt = now
            val record = buildRecord(round, now, obs)
            round.stage = RoundStage.COMPLETED
            round.savedAt = now
            round.detectionStatus = record.detectionStatus
            completed = record
        }
        return RoundUpdate(
            round,
            completed = completed,
            newRound = newRound,
            droppedIncompleteRoundId = dropped,
            finalizedRecords = finalized
        )
    }

    /**
     * Saves the current round without inventing a winner. The candidate/conflict state is kept
     * for the history view, but Accounting cannot settle it, so no P/L is produced.
     */
    private fun finalizeUnresolved(round: ManagedRound, now: Long): RoundRecord {
        round.completedAt = now
        round.savedAt = now
        round.stage = RoundStage.COMPLETED
        round.detectionStatus = when (round.winnerState) {
            WinnerState.VISUAL_WINNER_CANDIDATE -> "UNSETTLED: visual winner candidate only; no financial result"
            WinnerState.RESULT_CONFLICT -> "UNSETTLED: result conflict; no financial result"
            else -> "UNSETTLED: no winner detected before the round ended"
        }
        return buildRecord(round, now, null)
    }

    private fun startRound(obs: FrameObservation, startingWithCards: Boolean) {
        val now = obs.time
        val id = idGenerator(now, lastIssuedId)
        lastIssuedId = id
        val startingStage = when {
            obs.bettingStage == BettingStage.BETTING_OPEN -> RoundStage.BETTING_OPEN
            startingWithCards || obs.cards.totalCount > 0 -> RoundStage.CARDS_DEALT
            else -> RoundStage.BETTING_LOCKED
        }
        val locked = startingStage != RoundStage.BETTING_OPEN
        active = ManagedRound(
            id = id,
            startedAt = now,
            stage = startingStage,
            bettingStage = obs.bettingStage,
            bet = freeze(obs.settings, now, locked, if (locked) "betting already locked at round start" else "betting open"),
            betLockedAt = if (locked) now else null,
            lastStageText = obs.bettingStageText,
            lastObservationAt = now,
            andarMoney = MoneyPanelState(Side.ANDAR),
            baharMoney = MoneyPanelState(Side.BAHAR),
            detectionStatus = "round started"
        )
        moneyTracker.reset(id)
    }

    private fun shouldStartNewRound(obs: FrameObservation): Boolean {
        val current = active
        if (obs.bettingStage == BettingStage.BETTING_OPEN) {
            if (current == null) return true
            // Repeated PLACE BET frames during the same betting period are the same round.
            return current.stage != RoundStage.BETTING_OPEN
        }
        if (current == null) {
            // Service may start after the betting lock; start a round so cards/result can be
            // tracked, but only when concrete evidence exists.
            return obs.bettingStage == BettingStage.BETTING_LOCKED ||
                obs.cards.totalCount > 0 ||
                obs.winner.state == WinnerState.WINNER_CONFIRMED
        }
        return false
    }

    private fun advance(round: ManagedRound, target: RoundStage) {
        if (round.stage == RoundStage.COMPLETED) return
        if (target.ordinal > round.stage.ordinal) round.stage = target
    }

    private fun freeze(settings: BetSettings, time: Long, locked: Boolean, source: String): BetFreeze {
        val side = if (settings.side == Side.ANDAR || settings.side == Side.BAHAR) settings.side else Side.UNKNOWN
        val stake = settings.stake?.takeIf { Validation.money(it) && it > 0.0 }
        val odds = settings.odds?.takeIf { Validation.odds(it) }
        return BetFreeze(side, stake, odds, time, locked, source)
    }

    private fun buildRecord(round: ManagedRound, now: Long, obs: FrameObservation?): RoundRecord {
        val bet = round.bet
        val settlement = Accounting.settle(
            side = bet?.side ?: Side.UNKNOWN,
            stake = bet?.stake,
            odds = bet?.odds,
            winner = round.winnerSide,
            winnerState = round.winnerState
        )
        val detection = buildString {
            if (round.detectionStatus.startsWith("UNSETTLED:")) {
                append(round.detectionStatus).append("; ")
            }
            append(if (round.winnerState == WinnerState.WINNER_CONFIRMED) "WINNER_CONFIRMED" else round.winnerState.name)
            append("; ")
            append(round.winnerReason.ifBlank {
                obs?.winner?.reason ?: "no result evidence before the round ended"
            })
            append("; visual=")
            append(obs?.winner?.visualSide?.label ?: round.winnerSide.label)
            append("; ocr=")
            append(obs?.winner?.ocrSide?.label ?: Side.UNKNOWN.label)
            if (!settlement.calculated) {
                append("; P/L unavailable: ")
                append(settlement.reason)
            }
        }
        return RoundRecord(
            id = round.id,
            startedAt = round.startedAt,
            lockedAt = round.betLockedAt,
            completedAt = now,
            andarCards = round.andarCards,
            baharCards = round.baharCards,
            totalCards = round.totalCards,
            cardsDealtOcr = round.cardsDealtOcr,
            andarTotal = round.andarMoney.current,
            baharTotal = round.baharMoney.current,
            andarPreviousTotal = round.andarMoney.previous,
            baharPreviousTotal = round.baharMoney.previous,
            andarDifference = round.andarMoney.difference,
            baharDifference = round.baharMoney.difference,
            andarOdds = round.andarOdds,
            baharOdds = round.baharOdds,
            mySide = bet?.side ?: Side.UNKNOWN,
            myStake = bet?.stake,
            myOdds = bet?.odds,
            winner = round.winnerSide,
            winnerState = round.winnerState,
            winnerConfidence = round.winnerConfidence,
            detectionStatus = detection,
            isWin = settlement.isWin,
            grossReturn = settlement.grossReturn,
            netProfit = settlement.netProfit,
            lossAmount = settlement.lossAmount,
            settlementStatus = settlement.status,
            settlementReason = settlement.reason,
            rawWinnerReason = round.winnerReason,
            ocrSnapshotHash = obs?.ocrSnapshotHash ?: ""
        )
    }
}
