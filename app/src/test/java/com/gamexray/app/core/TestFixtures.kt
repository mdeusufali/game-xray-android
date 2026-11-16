package com.gamexray.app.core

/** Small helpers shared by the pure-JVM unit tests. */

fun ocrLine(
    text: String,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    confidence: Float = 0.96f
): OcrLine = OcrLine(
    text = text,
    box = Box(left, top, right, bottom),
    confidence = confidence
)

fun ocrFrame(
    vararg lines: OcrLine,
    timestamp: Long = 1_000L,
    width: Int = 1080,
    height: Int = 2400
): OcrFrame = OcrFrame(
    timestamp = timestamp,
    imageWidth = width,
    imageHeight = height,
    rawText = lines.joinToString("\n") { it.text },
    lines = lines.toList()
)

data class PixelRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

fun imageWithRects(
    width: Int,
    height: Int,
    rects: List<PixelRect>,
    background: Int = 0xFF202020.toInt(),
    foreground: Int = 0xFFFFFFFF.toInt()
): ImageFrame {
    val pixels = IntArray(width * height) { background }
    for (rect in rects) {
        for (y in rect.top.coerceAtLeast(0) until rect.bottom.coerceAtMost(height)) {
            for (x in rect.left.coerceAtLeast(0) until rect.right.coerceAtMost(width)) {
                pixels[y * width + x] = foreground
            }
        }
    }
    return ImageFrame(width, height, pixels)
}

fun testZones(): CardZones = CardZones(
    andar = Box(0.02f, 0.20f, 0.48f, 0.95f),
    bahar = Box(0.52f, 0.20f, 0.98f, 0.95f),
    andarLabelRegion = Box(0.05f, 0.06f, 0.35f, 0.12f),
    baharLabelRegion = Box(0.60f, 0.06f, 0.90f, 0.12f),
    source = "test"
)

fun testCandidate(
    side: Side,
    left: Float,
    top: Float,
    width: Float = 0.10f,
    height: Float = 0.16f,
    confidence: Float = 0.90f,
    fingerprint: Long = 0x0F0F0F0F0F0F0F0FL
): CardCandidate = CardCandidate(
    box = Box(left, top, left + width, top + height),
    side = side,
    confidence = confidence,
    fingerprint = fingerprint,
    fingerprint2 = fingerprint.inv(),
    fillRatio = 0.85f,
    meanLuma = 235f,
    source = "test"
)

fun ocrWinnerSignal(side: Side, confidence: Float = 0.95f): OcrWinnerSignal =
    if (side == Side.UNKNOWN) {
        OcrWinnerSignal()
    } else {
        OcrWinnerSignal(
            side = side,
            winners = listOf(
                OcrWinner(
                    side = side,
                    lineIndex = 0,
                    lineText = "${side.label} WINS",
                    confidence = confidence,
                    evidence = "test result line",
                    resultPhrase = "WINS"
                )
            )
        )
    }

fun visualStats(
    side: Side,
    luma: Double,
    red: Double,
    green: Double,
    blue: Double,
    brightRatio: Double = 0.10,
    colorfulness: Double = 20.0
): RegionVisualStats = RegionVisualStats(
    side = side,
    meanLuma = luma,
    meanR = red,
    meanG = green,
    meanB = blue,
    brightRatio = brightRatio,
    colorfulness = colorfulness,
    sampleCount = 500
)

fun roundRecord(
    id: Long,
    completedAt: Long = 10_000L,
    winner: Side = Side.ANDAR,
    winnerState: WinnerState = WinnerState.WINNER_CONFIRMED,
    andarCards: Int = 2,
    baharCards: Int = 1,
    mySide: Side = Side.ANDAR,
    myStake: Double? = 100.0,
    myOdds: Double? = 2.0,
    netProfit: Double? = null,
    grossReturn: Double? = null,
    lossAmount: Double? = null,
    revision: Int = 1
): RoundRecord = RoundRecord(
    id = id,
    startedAt = completedAt - 60_000L,
    lockedAt = completedAt - 30_000L,
    completedAt = completedAt,
    andarCards = andarCards,
    baharCards = baharCards,
    totalCards = andarCards + baharCards,
    cardsDealtOcr = andarCards + baharCards,
    andarTotal = 1_000.0,
    baharTotal = 1_500.0,
    andarOdds = 2.0,
    baharOdds = 3.0,
    mySide = mySide,
    myStake = myStake,
    myOdds = myOdds,
    winner = winner,
    winnerState = winnerState,
    winnerConfidence = 0.95f,
    detectionStatus = "WINNER_CONFIRMED; test",
    isWin = netProfit?.let { it > 0.0 },
    grossReturn = grossReturn,
    netProfit = netProfit,
    lossAmount = lossAmount,
    settlementStatus = when {
        netProfit == null -> "unavailable"
        netProfit > 0.0 -> "win"
        else -> "loss"
    },
    settlementReason = "test",
    rawWinnerReason = "test",
    ocrSnapshotHash = "hash-$id",
    revision = revision
)
