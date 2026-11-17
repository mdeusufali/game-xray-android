package com.gamexray.app.core

/** Per-frame result of the pure analysis pipeline. */
data class FrameAnalysis(
    val ocr: OcrFrame,
    val parsed: ParsedScreen,
    val zones: CardZones,
    val candidates: List<CardCandidate>,
    val cards: CardCounterSnapshot,
    val visual: VisualWinnerEvidence,
    val winner: WinnerDecision,
    val roundUpdate: RoundUpdate,
    val startedNewRound: Boolean,
    val snapshotHash: String
) {
    val round: ManagedRound? get() = roundUpdate.round
}

/**
 * End-to-end, Android-independent pipeline:
 * OCR -> structured parse -> card zones/detection -> duplicate-safe counting -> visual evidence
 * -> winner decision -> round state -> accounting record.
 */
class FrameEngine(
    private val clock: () -> Long = System::currentTimeMillis,
    idGenerator: (Long, Long) -> Long = { now, last -> maxOf(now, last + 1L) }
) {
    val parser = OcrParser()
    val cardCounter = CardCounter()
    val visualTracker = VisualWinnerTracker()
    val winnerDetector = WinnerDetector()
    val roundManager = RoundManager(clock, idGenerator)
    var lastAnalysis: FrameAnalysis? = null
        private set

    fun process(
        ocr: OcrFrame,
        candidates: List<CardCandidate>,
        visualStats: Map<Side, RegionVisualStats>,
        settings: BetSettings,
        manualZones: CardZones? = null
    ): FrameAnalysis {
        val time = ocr.timestamp
        val parsed = parser.parse(ocr)
        val zones = CardZoneLocator.locate(parsed, manualZones)

        val started = roundManager.prepareRound(
            time = time,
            bettingStage = parsed.bettingStage,
            bettingStageText = parsed.stageText,
            settings = settings,
            cardsPresent = candidates.any { it.side != Side.UNKNOWN && it.confidence >= 0.42f }
        )
        if (started) {
            cardCounter.reset()
            visualTracker.reset()
            winnerDetector.reset()
        }

        // A locked round stays eligible for visual result evidence even on frames where the game
        // briefly hides BET LOCKED (payout animations) or OCR misses the stage line.
        val effectiveBettingStage = if (roundManager.current()?.isBetLocked == true) {
            BettingStage.BETTING_LOCKED
        } else {
            parsed.bettingStage
        }

        // During an open betting period any visible card belongs to the previous round or to a
        // transition animation. Ignoring candidates until the bet locks is the round-start grace
        // period; it prevents stale cards from being counted into the new round.
        val countableCandidates = if (parsed.bettingStage == BettingStage.BETTING_OPEN) {
            emptyList()
        } else {
            candidates
        }
        val cards = cardCounter.update(countableCandidates)
        val visual = visualTracker.observe(
            stats = visualStats,
            bettingStage = effectiveBettingStage,
            cardsPresent = cards.totalCount > 0
        )
        val winner = winnerDetector.evaluate(parsed.ocrWinner, visual)

        val obs = FrameObservation(
            time = time,
            bettingStage = parsed.bettingStage,
            bettingStageText = parsed.stageText,
            cards = cards,
            cardsDealtOcr = parsed.cardsDealtOcr,
            andarTotal = parsed.andarTotal,
            baharTotal = parsed.baharTotal,
            visibleAmounts = parsed.visibleAmounts,
            andarOdds = parsed.andarOdds,
            baharOdds = parsed.baharOdds,
            winner = winner,
            settings = settings,
            ocrSnapshotHash = snapshotHash(ocr, parsed)
        )
        val update = roundManager.observe(obs)
        val finalUpdate = if (started && !update.newRound) update.copy(newRound = true) else update
        val analysis = FrameAnalysis(
            ocr = ocr,
            parsed = parsed,
            zones = zones,
            candidates = candidates,
            cards = cards,
            visual = visual,
            winner = winner,
            roundUpdate = finalUpdate,
            startedNewRound = started,
            snapshotHash = obs.ocrSnapshotHash
        )
        lastAnalysis = analysis
        return analysis
    }

    fun currentRound(): ManagedRound? = roundManager.current()

    fun restore(
        round: ManagedRound?,
        cards: CardCounterSnapshot
    ) {
        roundManager.restore(round)
        cardCounter.restore(cards)
    }

    fun reset() {
        cardCounter.reset()
        visualTracker.reset()
        winnerDetector.reset()
        roundManager.reset()
        lastAnalysis = null
    }

    fun snapshotHash(ocr: OcrFrame, parsed: ParsedScreen): String {
        val builder = StringBuilder(256)
        builder.append(ocr.rawText.take(3000))
        builder.append('|').append(parsed.bettingStage).append('|').append(parsed.stageText)
        builder.append('|').append(parsed.andarTotal.value).append('|').append(parsed.baharTotal.value)
        builder.append('|').append(parsed.andarOdds.value).append('|').append(parsed.baharOdds.value)
        builder.append('|').append(parsed.cardsDealtOcr.value)
        builder.append('|').append(parsed.ocrWinner.side).append('|').append(parsed.ocrWinner.conflict)
        builder.append('|').append(parsed.visibleAmounts.joinToString(",") { "${it.side}:${it.value}" })
        return Integer.toHexString(builder.toString().hashCode())
    }
}
