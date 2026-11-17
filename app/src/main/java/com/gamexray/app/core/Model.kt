package com.gamexray.app.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Sides used by the Andar-Bahar game. UNKNOWN is never treated as a bet/result. */
enum class Side(val label: String) {
    ANDAR("ANDAR"),
    BAHAR("BAHAR"),
    UNKNOWN("UNKNOWN");

    companion object {
        fun fromLabel(value: String?): Side = when (value?.trim()?.uppercase()) {
            "ANDAR", "A" -> ANDAR
            "BAHAR", "B" -> BAHAR
            else -> UNKNOWN
        }

        fun opposite(side: Side): Side = when (side) {
            ANDAR -> BAHAR
            BAHAR -> ANDAR
            UNKNOWN -> UNKNOWN
        }
    }
}

/** Normalized screen coordinates (0.0..1.0). */
data class Box(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val area: Float get() = width * height

    fun isValid(): Boolean =
        left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
            right > left && bottom > top && area > 0f

    fun clamped(): Box = Box(
        left.coerceIn(0f, 1f),
        top.coerceIn(0f, 1f),
        right.coerceIn(0f, 1f),
        bottom.coerceIn(0f, 1f)
    )

    fun expanded(dx: Float, dy: Float): Box =
        Box(left - dx, top - dy, right + dx, bottom + dy).clamped()

    fun centerDistance(other: Box): Float {
        val dx = centerX - other.centerX
        val dy = centerY - other.centerY
        return sqrt(dx * dx + dy * dy)
    }

    fun intersects(other: Box): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top

    fun intersectionArea(other: Box): Float {
        val w = min(right, other.right) - max(left, other.left)
        val h = min(bottom, other.bottom) - max(top, other.top)
        return if (w > 0f && h > 0f) w * h else 0f
    }

    fun iou(other: Box): Float {
        val inter = intersectionArea(other)
        val union = area + other.area - inter
        return if (union > 0f) inter / union else 0f
    }

    fun overlapFraction(other: Box): Float {
        val inter = intersectionArea(other)
        return if (area > 0f) inter / area else 0f
    }

    fun contains(x: Float, y: Float): Boolean =
        x >= left && x <= right && y >= top && y <= bottom
}

data class OcrElement(
    val text: String,
    val box: Box,
    val confidence: Float = 1f
)

data class OcrLine(
    val text: String,
    val box: Box,
    val confidence: Float = 1f,
    val elements: List<OcrElement> = emptyList(),
    val blockIndex: Int = 0
)

/**
 * True only when the two side labels sit next to each other horizontally.
 *
 * The real mobile layout stacks ANDAR above BAHAR on the same left edge. Splitting card
 * zones by label centre-X in that layout puts both labels in nearly the same column and
 * assigns cards to the wrong side, so callers must fall back to manual zones instead.
 */
fun labelsAreSideBySide(a: OcrLine?, b: OcrLine?): Boolean {
    if (a == null || b == null) return false
    val dx = abs(a.box.centerX - b.box.centerX)
    val dy = abs(a.box.centerY - b.box.centerY)
    // Genuine side panels sit at roughly the same height. A large dy means the two labels
    // merely happen to be on opposite halves of the screen (e.g. a card-row label and a
    // side name printed inside the statistics table), which must not be treated as a split.
    val rowTolerance = maxOf(0.05f, maxOf(a.box.height, b.box.height) * 2f)
    return dx >= 0.10f && dy <= rowTolerance
}

/**
 * True when both labels sit in the same column with one clearly above the other — the stacked
 * card-row layout used by the real game. This is a stronger signal than raw confidence: a tiny
 * side name inside the statistics table can score higher than the real card-row label.
 */
fun labelsAreStacked(a: OcrLine?, b: OcrLine?): Boolean {
    if (a == null || b == null) return false
    val dx = abs(a.box.centerX - b.box.centerX)
    val dy = abs(a.box.centerY - b.box.centerY)
    val columnTolerance = maxOf(0.07f, maxOf(a.box.width, b.box.width) * 0.5f)
    return dy >= 0.012f && dx <= columnTolerance
}

/** Full OCR result. rawText is kept separate from the positioned line data. */
data class OcrFrame(
    val timestamp: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val rawText: String,
    val lines: List<OcrLine>
)

enum class BettingStage {
    NOT_DETECTED,
    BETTING_OPEN,
    BETTING_LOCKED
}

enum class DetectionStatus(val label: String) {
    DETECTED("DETECTED"),
    UNCERTAIN("UNCERTAIN"),
    NOT_DETECTED("NOT DETECTED"),
    REJECTED("REJECTED")
}

enum class WinnerState {
    WINNER_CONFIRMED,
    VISUAL_WINNER_CANDIDATE,
    RESULT_NOT_DETECTED,
    RESULT_CONFLICT
}

enum class RoundStage {
    IDLE,
    BETTING_OPEN,
    BETTING_LOCKED,
    CARDS_DEALT,
    RESULT_DETECTED,
    WINNER_CONFIRMED,
    COMPLETED
}

data class MoneyReading(
    val value: Double? = null,
    val status: DetectionStatus = DetectionStatus.NOT_DETECTED,
    val raw: String = "",
    val reason: String = "not present",
    val lineIndex: Int = -1
) {
    companion object {
        fun notDetected(reason: String = "no labelled total found") =
            MoneyReading(null, DetectionStatus.NOT_DETECTED, "", reason)

        fun uncertain(raw: String, reason: String, lineIndex: Int = -1) =
            MoneyReading(null, DetectionStatus.UNCERTAIN, raw, reason, lineIndex)
    }
}

data class ValueReading<T>(
    val value: T? = null,
    val status: DetectionStatus = DetectionStatus.NOT_DETECTED,
    val raw: String = "",
    val reason: String = "not present",
    val lineIndex: Int = -1
)

enum class NumberKind {
    MONEY,
    PERCENTAGE,
    ODDS,
    CARD_COUNT,
    UNKNOWN
}

data class NumberToken(
    val value: Double,
    val raw: String,
    val kind: NumberKind,
    val lineIndex: Int,
    val lineText: String,
    val confidence: Float
) {
    /** Used only by the UI/diagnostics and for side assignment where geometry is clear. */
    var side: Side = Side.UNKNOWN
}

data class VisibleAmount(
    val side: Side,
    val value: Double,
    val raw: String,
    val lineIndex: Int,
    val lineText: String,
    val confidence: Float,
    val reason: String
)

data class OcrWinner(
    val side: Side,
    val lineIndex: Int,
    val lineText: String,
    val confidence: Float,
    val evidence: String,
    val resultPhrase: String
)

data class OcrWinnerSignal(
    val side: Side = Side.UNKNOWN,
    val winners: List<OcrWinner> = emptyList(),
    val conflict: Boolean = false,
    val conflictReason: String = ""
) {
    val strongest: OcrWinner? get() = winners.maxByOrNull { it.confidence }
}

data class ParsedScreen(
    val stageText: String = "NOT DETECTED",
    val bettingStage: BettingStage = BettingStage.NOT_DETECTED,
    val andarLabel: OcrLine? = null,
    val baharLabel: OcrLine? = null,
    val andarTotal: MoneyReading = MoneyReading.notDetected(),
    val baharTotal: MoneyReading = MoneyReading.notDetected(),
    val andarOdds: ValueReading<Double> = ValueReading(),
    val baharOdds: ValueReading<Double> = ValueReading(),
    val andarPercentage: ValueReading<Double> = ValueReading(),
    val baharPercentage: ValueReading<Double> = ValueReading(),
    val cardsDealtOcr: ValueReading<Int> = ValueReading(),
    val ocrWinner: OcrWinnerSignal = OcrWinnerSignal(),
    val visibleAmounts: List<VisibleAmount> = emptyList(),
    val visibleBalance: MoneyReading = MoneyReading.notDetected(),
    val visibleTotalStake: MoneyReading = MoneyReading.notDetected(),
    val numberTokens: List<NumberToken> = emptyList(),
    val sideLabelLines: List<OcrLine> = emptyList()
) {
    val hasAndarLabel: Boolean get() = andarLabel != null
    val hasBaharLabel: Boolean get() = baharLabel != null
}

data class CardZone(
    val side: Side,
    val area: Box,
    val source: String
)

data class CardZones(
    val andar: Box? = null,
    val bahar: Box? = null,
    val andarLabelRegion: Box? = null,
    val baharLabelRegion: Box? = null,
    val source: String = "not located"
) {
    fun areaFor(side: Side): Box? = when (side) {
        Side.ANDAR -> andar
        Side.BAHAR -> bahar
        Side.UNKNOWN -> null
    }

    fun assign(box: Box): Side {
        val a = andar
        val b = bahar
        if (a == null && b == null) return Side.UNKNOWN
        if (a != null && b != null) {
            val oa = box.overlapFraction(a)
            val ob = box.overlapFraction(b)
            if (oa > 0f || ob > 0f) {
                if (abs(oa - ob) < 0.03f) {
                    // Prefer the nearest zone centre when a box straddles the split.
                    return if (box.centerDistance(a) <= box.centerDistance(b)) Side.ANDAR else Side.BAHAR
                }
                return if (oa > ob) Side.ANDAR else Side.BAHAR
            }
            return if (box.centerDistance(a) <= box.centerDistance(b)) Side.ANDAR else Side.BAHAR
        }
        val onlySide = if (a != null) Side.ANDAR else Side.BAHAR
        val only = a ?: b!!
        if (box.intersects(only)) return onlySide
        return if (box.centerX < only.centerX) {
            if (onlySide == Side.ANDAR) Side.ANDAR else Side.BAHAR
        } else {
            if (onlySide == Side.BAHAR) Side.BAHAR else Side.ANDAR
        }
    }

    fun labelRegion(side: Side): Box? = when (side) {
        Side.ANDAR -> andarLabelRegion
        Side.BAHAR -> baharLabelRegion
        Side.UNKNOWN -> null
    }
}

data class CardCandidate(
    val box: Box,
    val side: Side,
    val confidence: Float,
    val fingerprint: Long,
    val fingerprint2: Long,
    val fillRatio: Float,
    val meanLuma: Float,
    val source: String = "shape"
)

data class CardTrack(
    var id: Long,
    var side: Side,
    var box: Box,
    var fingerprint: Long,
    var fingerprint2: Long,
    var firstSeenFrame: Long,
    var lastSeenFrame: Long,
    var hits: Int,
    var confirmed: Boolean,
    var uncertain: Boolean,
    var lastConfidence: Float,
    var missedFrames: Int = 0
)

data class CardCounterSnapshot(
    val andarCount: Int = 0,
    val baharCount: Int = 0,
    val uncertainAndar: Int = 0,
    val uncertainBahar: Int = 0,
    val pendingAndar: Int = 0,
    val pendingBahar: Int = 0,
    val unassigned: Int = 0,
    val frameIndex: Long = 0,
    val tracks: List<CardTrack> = emptyList()
) {
    val totalCount: Int get() = andarCount + baharCount
    val uncertainTotal: Int get() = uncertainAndar + uncertainBahar
    val pendingTotal: Int get() = pendingAndar + pendingBahar
}

data class RegionVisualStats(
    val side: Side,
    val meanLuma: Double,
    val meanR: Double,
    val meanG: Double,
    val meanB: Double,
    val brightRatio: Double,
    val colorfulness: Double,
    val sampleCount: Int
) {
    fun delta(other: RegionVisualStats): VisualDelta {
        val dl = abs(meanLuma - other.meanLuma)
        val dr = meanR - other.meanR
        val dg = meanG - other.meanG
        val db = meanB - other.meanB
        val colorDistance = sqrt(dr * dr + dg * dg + db * db)
        return VisualDelta(dl, colorDistance, abs(brightRatio - other.brightRatio))
    }

    /**
     * Winner highlights only ever make a panel brighter: the winning side glows in its own
     * colour (blue for Andar, red for Bahar) while the losing panel dims. Scoring only the
     * positive luma / side-colour gain prevents "both panels changed" false conflicts, where
     * an unsigned distance sees one side brightening and the other side dimming equally.
     */
    fun winnerScore(other: RegionVisualStats, minLuma: Double, minColor: Double): Double {
        val lumaGain = (meanLuma - other.meanLuma).coerceAtLeast(0.0)
        val colourGain = when (side) {
            Side.ANDAR -> (meanB - other.meanB).coerceAtLeast(0.0)
            Side.BAHAR -> (meanR - other.meanR).coerceAtLeast(0.0)
            Side.UNKNOWN -> 0.0
        }
        val brightGain = (brightRatio - other.brightRatio).coerceAtLeast(0.0)
        return 0.55 * (lumaGain / minLuma.coerceAtLeast(0.001)) +
            0.30 * (colourGain / minColor.coerceAtLeast(0.001)) +
            0.15 * (brightGain / 0.15)
    }
}

data class VisualDelta(
    val luma: Double,
    val color: Double,
    val brightRatio: Double
) {
    /** Dimensionless score where 1.0 means exactly at the detection threshold. */
    fun score(minLuma: Double, minColor: Double): Double =
        0.55 * (luma / minLuma.coerceAtLeast(0.001)) +
            0.30 * (color / minColor.coerceAtLeast(0.001)) +
            0.15 * (brightRatio / 0.15)
}

data class VisualWinnerEvidence(
    val side: Side = Side.UNKNOWN,
    val candidate: Boolean = false,
    val conflict: Boolean = false,
    val scoreAndar: Double = 0.0,
    val scoreBahar: Double = 0.0,
    val streak: Int = 0,
    val reason: String = "no visual evidence"
)

data class WinnerDecision(
    val state: WinnerState = WinnerState.RESULT_NOT_DETECTED,
    val side: Side = Side.UNKNOWN,
    val confidence: Float = 0f,
    val reason: String = "no result evidence",
    val ocrSide: Side = Side.UNKNOWN,
    val visualSide: Side = Side.UNKNOWN
) {
    val confirmed: Boolean get() = state == WinnerState.WINNER_CONFIRMED && side != Side.UNKNOWN
}

data class BetSettings(
    val side: Side = Side.UNKNOWN,
    val stake: Double? = null,
    val odds: Double? = null
)

data class BetFreeze(
    val side: Side,
    val stake: Double?,
    val odds: Double?,
    val frozenAt: Long,
    val locked: Boolean,
    val source: String
)

data class MoneyPanelState(
    val side: Side,
    val current: Double? = null,
    val currentFrame: Double? = null,
    val previous: Double? = null,
    val difference: Double? = null,
    val status: DetectionStatus = DetectionStatus.NOT_DETECTED,
    val reason: String = "not detected",
    val lastUpdated: Long = 0L,
    val individual: List<VisibleAmount> = emptyList()
) {
    val hasCurrent: Boolean get() = current != null
}

data class RoundRecord(
    val id: Long,
    val startedAt: Long,
    val lockedAt: Long? = null,
    val completedAt: Long,
    val andarCards: Int,
    val baharCards: Int,
    val totalCards: Int,
    val cardsDealtOcr: Int?,
    val andarTotal: Double?,
    val baharTotal: Double?,
    val andarPreviousTotal: Double? = null,
    val baharPreviousTotal: Double? = null,
    val andarDifference: Double? = null,
    val baharDifference: Double? = null,
    val andarOdds: Double?,
    val baharOdds: Double?,
    val mySide: Side,
    val myStake: Double?,
    val myOdds: Double?,
    val winner: Side,
    val winnerState: WinnerState,
    val winnerConfidence: Float,
    val detectionStatus: String,
    val isWin: Boolean? = null,
    val grossReturn: Double? = null,
    val netProfit: Double? = null,
    val lossAmount: Double? = null,
    val settlementStatus: String = "unavailable",
    val settlementReason: String = "",
    val rawWinnerReason: String = "",
    val ocrSnapshotHash: String = "",
    val revision: Int = 1
) {
    val pnl: Double? get() = netProfit
}

data class Settlement(
    val status: String,
    val side: Side,
    val stake: Double?,
    val odds: Double?,
    val winner: Side?,
    val isWin: Boolean? = null,
    val grossReturn: Double? = null,
    val netProfit: Double? = null,
    val lossAmount: Double? = null,
    val reason: String = ""
) {
    val calculated: Boolean get() = netProfit != null
}

data class OverallStats(
    val totalRounds: Int = 0,
    val settledRounds: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val totalStake: Double = 0.0,
    val totalProfit: Double = 0.0,
    val totalLoss: Double = 0.0,
    val netResult: Double = 0.0,
    val totalGrossReturn: Double = 0.0,
    val unsettledRounds: Int = 0,
    val andarWins: Int = 0,
    val baharWins: Int = 0
)

/** Validation thresholds used by OCR parsing and tests. */
object Validation {
    const val MIN_MONEY = 0.0
    const val MAX_MONEY = 100_000_000.0
    const val MIN_ODDS = 1.01
    const val MAX_ODDS = 1000.0
    const val MIN_PERCENT = 0.0
    const val MAX_PERCENT = 100.0
    const val MAX_DECK_SIZE = 52
    const val MAX_PERCENT_PAIR_DEVIATION = 2.0

    fun money(value: Double?): Boolean =
        value != null && value.isFinite() && value >= MIN_MONEY && value <= MAX_MONEY

    fun odds(value: Double?): Boolean =
        value != null && value.isFinite() && value >= MIN_ODDS && value <= MAX_ODDS

    fun percent(value: Double?): Boolean =
        value != null && value.isFinite() && value >= MIN_PERCENT && value <= MAX_PERCENT

    fun percentagesPair(a: Double?, b: Double?): Boolean =
        percent(a) && percent(b) && abs((a ?: 0.0) + (b ?: 0.0) - 100.0) <= MAX_PERCENT_PAIR_DEVIATION

    fun cardCount(value: Int?): Boolean =
        value != null && value in 0..MAX_DECK_SIZE

    fun moneyDifference(from: Double?, to: Double?): Double? {
        if (!money(from) || !money(to)) return null
        val result = (to ?: 0.0) - (from ?: 0.0)
        return if (result.isFinite()) result else null
    }
}
