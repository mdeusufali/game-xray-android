package com.gamexray.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrParserTest {
    private val parser = OcrParser()

    @Test
    fun detectsBettingStages() {
        assertEquals(
            BettingStage.BETTING_OPEN,
            parser.parse(ocrFrame(ocrLine("PLACE BET", 0.1f, 0.1f, 0.4f, 0.15f))).bettingStage
        )
        assertEquals(
            BettingStage.BETTING_LOCKED,
            parser.parse(ocrFrame(ocrLine("BET LOCKED", 0.1f, 0.1f, 0.4f, 0.15f))).bettingStage
        )
        assertEquals(
            BettingStage.NOT_DETECTED,
            parser.parse(ocrFrame(ocrLine("SOMETHING ELSE", 0.1f, 0.1f, 0.4f, 0.15f))).bettingStage
        )
    }

    @Test
    fun parsesLabelledPanelTotalsWithoutSumming() {
        val frame = ocrFrame(
            ocrLine("ANDAR", 0.08f, 0.20f, 0.20f, 0.25f),
            ocrLine("BAHAR", 0.70f, 0.20f, 0.82f, 0.25f),
            ocrLine("TOTAL ANDAR ৳1,250", 0.04f, 0.30f, 0.42f, 0.35f),
            ocrLine("TOTAL BAHAR ৳2,000", 0.56f, 0.30f, 0.94f, 0.35f)
        )
        val parsed = parser.parse(frame)

        assertTrue(parsed.hasAndarLabel)
        assertTrue(parsed.hasBaharLabel)
        assertEquals(DetectionStatus.DETECTED, parsed.andarTotal.status)
        assertEquals(DetectionStatus.DETECTED, parsed.baharTotal.status)
        assertEquals(1250.0, parsed.andarTotal.value!!, 0.001)
        assertEquals(2000.0, parsed.baharTotal.value!!, 0.001)
        assertEquals("1,250", parsed.andarTotal.raw)
        // The raw OCR text and the positioned lines are preserved separately.
        assertEquals(frame.lines.size, parsed.sideLabelLines.size + 2)
        assertTrue(frame.rawText.contains("TOTAL ANDAR"))
    }

    @Test
    fun acceptsSideLabelWithCurrency() {
        val parsed = parser.parse(
            ocrFrame(ocrLine("ANDAR : ৳500", 0.08f, 0.30f, 0.40f, 0.35f))
        )
        assertEquals(DetectionStatus.DETECTED, parsed.andarTotal.status)
        assertEquals(500.0, parsed.andarTotal.value!!, 0.001)
    }

    @Test
    fun bareSideNumberIsNotTreatedAsPanelTotal() {
        val parsed = parser.parse(
            ocrFrame(ocrLine("ANDAR 500", 0.08f, 0.30f, 0.40f, 0.35f))
        )
        assertEquals(DetectionStatus.NOT_DETECTED, parsed.andarTotal.status)
        assertNull(parsed.andarTotal.value)
    }

    @Test
    fun lineNamingBothSidesCannotBecomeAPanelTotal() {
        val parsed = parser.parse(
            ocrFrame(
                ocrLine("TOTAL ANDAR ৳1,000 BAHAR ৳2,000", 0.04f, 0.30f, 0.96f, 0.35f)
            )
        )
        assertEquals(DetectionStatus.NOT_DETECTED, parsed.andarTotal.status)
        assertEquals(DetectionStatus.NOT_DETECTED, parsed.baharTotal.status)
        assertNull(parsed.andarTotal.value)
        assertNull(parsed.baharTotal.value)
    }

    @Test
    fun validatesPercentagePair() {
        val valid = parser.parse(
            ocrFrame(
                ocrLine("ANDAR 55%", 0.08f, 0.30f, 0.42f, 0.35f),
                ocrLine("BAHAR 45%", 0.56f, 0.30f, 0.94f, 0.35f)
            )
        )
        assertEquals(55.0, valid.andarPercentage.value!!, 0.001)
        assertEquals(45.0, valid.baharPercentage.value!!, 0.001)

        val invalid = parser.parse(
            ocrFrame(
                ocrLine("ANDAR 60%", 0.08f, 0.30f, 0.42f, 0.35f),
                ocrLine("BAHAR 50%", 0.56f, 0.30f, 0.94f, 0.35f)
            )
        )
        assertEquals(DetectionStatus.UNCERTAIN, invalid.andarPercentage.status)
        assertEquals(DetectionStatus.UNCERTAIN, invalid.baharPercentage.status)
        assertNull(invalid.andarPercentage.value)
        assertNull(invalid.baharPercentage.value)
    }

    @Test
    fun parsesAndValidatesOdds() {
        val parsed = parser.parse(
            ocrFrame(
                ocrLine("ANDAR 1:2.5", 0.08f, 0.30f, 0.42f, 0.35f),
                ocrLine("BAHAR ODDS 1.8", 0.56f, 0.30f, 0.94f, 0.35f)
            )
        )
        assertEquals(2.5, parsed.andarOdds.value!!, 0.001)
        assertEquals(1.8, parsed.baharOdds.value!!, 0.001)

        val invalid = parser.parse(
            ocrFrame(ocrLine("ANDAR 1:0.5", 0.08f, 0.30f, 0.42f, 0.35f))
        )
        assertEquals(DetectionStatus.UNCERTAIN, invalid.andarOdds.status)
        assertNull(invalid.andarOdds.value)
    }

    @Test
    fun parsesCardsDealtAndRejectsOutOfRange() {
        val parsed = parser.parse(
            ocrFrame(ocrLine("CARDS DEALT 5", 0.08f, 0.40f, 0.42f, 0.45f))
        )
        assertEquals(DetectionStatus.DETECTED, parsed.cardsDealtOcr.status)
        assertEquals(5, parsed.cardsDealtOcr.value)

        val rejected = parser.parse(
            ocrFrame(ocrLine("CARDS DEALT 75", 0.08f, 0.40f, 0.42f, 0.45f))
        )
        assertEquals(DetectionStatus.REJECTED, rejected.cardsDealtOcr.status)
        assertNull(rejected.cardsDealtOcr.value)
    }

    @Test
    fun selectsGeometricallyConsistentSideLabelPair() {
        // Boxes copied from a real diagnostic log: the real stacked card-row labels are on the
        // left, while a tiny "BAHAR" heading inside the statistics table scores higher on OCR
        // confidence. The parser must pick the consistent pair, not the two best single labels.
        val parsed = parser.parse(
            ocrFrame(
                ocrLine("ANDAR", 0.139f, 0.469f, 0.336f, 0.492f, 0.84f),
                ocrLine("BAHAR", 0.143f, 0.585f, 0.336f, 0.605f, 0.73f),
                ocrLine("BAHAR", 0.707f, 0.331f, 0.778f, 0.339f, 0.88f)
            )
        )
        assertNotNull(parsed.andarLabel)
        assertNotNull(parsed.baharLabel)
        assertEquals(0.139f, parsed.andarLabel!!.box.left, 0.005f)
        assertEquals(0.469f, parsed.andarLabel!!.box.top, 0.005f)
        assertEquals(0.143f, parsed.baharLabel!!.box.left, 0.005f)
        assertEquals(0.585f, parsed.baharLabel!!.box.top, 0.005f)
        assertEquals(2, parsed.sideLabelLines.size)
    }

    @Test
    fun detectsWinnerWordingPerSide() {
        val andar = parser.parse(
            ocrFrame(ocrLine("ANDAR WINS", 0.08f, 0.60f, 0.42f, 0.65f))
        )
        assertEquals(Side.ANDAR, andar.ocrWinner.side)
        assertFalse(andar.ocrWinner.conflict)
        assertEquals(1, andar.ocrWinner.winners.size)

        val bahar = parser.parse(
            ocrFrame(ocrLine("BAHAR WINNER", 0.56f, 0.60f, 0.94f, 0.65f))
        )
        assertEquals(Side.BAHAR, bahar.ocrWinner.side)
    }

    @Test
    fun conflictingWinnerLinesAreFlagged() {
        val parsed = parser.parse(
            ocrFrame(
                ocrLine("ANDAR WINS", 0.08f, 0.60f, 0.42f, 0.65f),
                ocrLine("BAHAR WINS", 0.56f, 0.62f, 0.94f, 0.67f)
            )
        )
        assertTrue(parsed.ocrWinner.conflict)
        assertEquals(Side.UNKNOWN, parsed.ocrWinner.side)
    }

    @Test
    fun promoAndHistoryWordingIsNotAResult() {
        val parsed = parser.parse(
            ocrFrame(
                ocrLine("BIGGEST WINNER", 0.08f, 0.60f, 0.42f, 0.65f),
                ocrLine("TOP WINNER ANDAR", 0.56f, 0.62f, 0.94f, 0.67f)
            )
        )
        assertFalse(parsed.ocrWinner.conflict)
        assertEquals(Side.UNKNOWN, parsed.ocrWinner.side)
        assertTrue(parsed.ocrWinner.winners.isEmpty())
    }

    @Test
    fun parsesBalanceAndStakeAsSeparateValues() {
        val parsed = parser.parse(
            ocrFrame(
                ocrLine("BALANCE ৳5,000", 0.08f, 0.80f, 0.42f, 0.85f),
                ocrLine("TOTAL STAKE ৳1,000", 0.56f, 0.80f, 0.94f, 0.85f)
            )
        )
        assertEquals(5000.0, parsed.visibleBalance.value!!, 0.001)
        assertEquals(1000.0, parsed.visibleTotalStake.value!!, 0.001)
    }

    @Test
    fun assignsVisibleAmountsByGeometry() {
        val parsed = parser.parse(
            ocrFrame(
                ocrLine("ANDAR", 0.10f, 0.20f, 0.30f, 0.25f),
                ocrLine("BAHAR", 0.70f, 0.20f, 0.90f, 0.25f),
                ocrLine("BET ৳100", 0.10f, 0.35f, 0.30f, 0.40f),
                ocrLine("BET ৳50", 0.70f, 0.35f, 0.90f, 0.40f)
            )
        )
        val andar = parsed.visibleAmounts.filter { it.side == Side.ANDAR }
        val bahar = parsed.visibleAmounts.filter { it.side == Side.BAHAR }
        assertEquals(1, andar.size)
        assertEquals(1, bahar.size)
        assertEquals(100.0, andar.first().value, 0.001)
        assertEquals(50.0, bahar.first().value, 0.001)
        // Individual bets are exposed as a list; they are never summed into the panel total.
        assertNull(parsed.andarTotal.value)
        assertNull(parsed.baharTotal.value)
    }

    @Test
    fun canonicalTextNormalizesBengaliDigitsAndLabels() {
        assertEquals("BAHAR", parser.canonicalText("BOHAR"))
        assertEquals("ANDAR", parser.canonicalText("ANDER"))
        assertEquals("ANDAR", parser.canonicalText("ANDHAR"))
        assertEquals("500", parser.canonicalText("৫০০"))
        assertEquals("ANDAR 1,250", parser.canonicalText("andar ১,২৫০"))
    }

    @Test
    fun numberTokensKeepLineCoordinates() {
        val parsed = parser.parse(
            ocrFrame(
                ocrLine("ANDAR 1:2.5", 0.08f, 0.30f, 0.42f, 0.35f),
                ocrLine("ANDAR 55%", 0.08f, 0.36f, 0.42f, 0.41f)
            )
        )
        assertTrue(parsed.numberTokens.isNotEmpty())
        val odds = parsed.numberTokens.firstOrNull { it.kind == NumberKind.ODDS }
        assertNotNull(odds)
        assertEquals(Side.ANDAR, odds!!.side)
        assertEquals(0, odds.lineIndex)
        assertTrue(odds.lineText.contains("1:2.5"))
        assertTrue(parsed.numberTokens.any { it.kind == NumberKind.PERCENTAGE })
    }

    @Test
    fun panelBadgeAmountIsAcceptedOnlyWithStrictGeometry() {
        // Real layout: big side label on the left, bare total badge at the far right edge.
        val parsed = parser.parse(
            ocrFrame(
                ocrLine("ANDAR", 0.08f, 0.44f, 0.22f, 0.50f),
                ocrLine("BAHAR", 0.08f, 0.57f, 0.22f, 0.63f),
                ocrLine("510", 0.86f, 0.445f, 0.94f, 0.470f),
                ocrLine("270", 0.86f, 0.575f, 0.94f, 0.600f)
            )
        )
        assertEquals(DetectionStatus.DETECTED, parsed.andarTotal.status)
        assertEquals(510.0, parsed.andarTotal.value!!, 0.001)
        assertEquals(DetectionStatus.DETECTED, parsed.baharTotal.status)
        assertEquals(270.0, parsed.baharTotal.value!!, 0.001)
    }

    @Test
    fun badgeOutsidePanelBandOrLeftSideIsNotAPanelTotal() {
        val farVertically = parser.parse(
            ocrFrame(
                ocrLine("ANDAR", 0.08f, 0.44f, 0.22f, 0.50f),
                ocrLine("510", 0.86f, 0.62f, 0.94f, 0.645f)
            )
        )
        assertEquals(DetectionStatus.NOT_DETECTED, farVertically.andarTotal.status)

        val notAtRightEdge = parser.parse(
            ocrFrame(
                ocrLine("ANDAR", 0.08f, 0.44f, 0.22f, 0.50f),
                ocrLine("510", 0.40f, 0.445f, 0.55f, 0.470f)
            )
        )
        assertEquals(DetectionStatus.NOT_DETECTED, notAtRightEdge.andarTotal.status)
    }
}
