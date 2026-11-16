package com.gamexray.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardDetectionTest {

    private fun threeCardFrame(dx: Int = 0, dy: Int = 0) = imageWithRects(
        400,
        800,
        listOf(
            PixelRect(30 + dx, 260 + dy, 90 + dx, 360 + dy),
            PixelRect(100 + dx, 260 + dy, 160 + dx, 360 + dy),
            PixelRect(260 + dx, 260 + dy, 320 + dx, 360 + dy)
        )
    )

    @Test
    fun detectsCardsSeparatelyInAndarAndBaharZones() {
        val zones = testZones()
        val candidates = CardShapeDetector.detect(threeCardFrame(), zones)

        assertEquals(2, candidates.count { it.side == Side.ANDAR })
        assertEquals(1, candidates.count { it.side == Side.BAHAR })
        assertTrue(candidates.all { it.confidence >= 0.42f })
        assertTrue(candidates.all { it.box.isValid() })
    }

    @Test
    fun countsEachCardOnceAcrossStableFrames() {
        val zones = testZones()
        val counter = CardCounter(requiredStableFrames = 2)

        val first = counter.update(CardShapeDetector.detect(threeCardFrame(), zones))
        assertEquals(0, first.andarCount)
        assertEquals(0, first.baharCount)
        assertEquals(2, first.pendingAndar)
        assertEquals(1, first.pendingBahar)

        val second = counter.update(CardShapeDetector.detect(threeCardFrame(), zones))
        assertEquals(2, second.andarCount)
        assertEquals(1, second.baharCount)
        assertEquals(3, second.totalCount)
        assertEquals(0, second.pendingTotal)

        val third = counter.update(CardShapeDetector.detect(threeCardFrame(), zones))
        assertEquals(3, third.totalCount)
    }

    @Test
    fun movingCardsAreReMatchedAndNotRecounted() {
        val zones = testZones()
        val counter = CardCounter(requiredStableFrames = 2)
        counter.update(CardShapeDetector.detect(threeCardFrame(), zones))
        counter.update(CardShapeDetector.detect(threeCardFrame(), zones))

        repeat(3) {
            val moved = counter.update(CardShapeDetector.detect(threeCardFrame(dx = 8, dy = 6), zones))
            assertEquals(3, moved.totalCount)
            assertEquals(0, moved.pendingTotal)
        }
        assertEquals(2, counter.tracks().count { it.side == Side.ANDAR })
        assertEquals(1, counter.tracks().count { it.side == Side.BAHAR })
    }

    @Test
    fun identicalAdjacentCardsStaySeparate() {
        val zones = testZones()
        val counter = CardCounter(requiredStableFrames = 2)
        val candidates = CardShapeDetector.detect(threeCardFrame(), zones)
            .filter { it.side == Side.ANDAR }
        assertEquals(2, candidates.size)

        counter.update(candidates)
        val confirmed = counter.update(candidates)
        assertEquals(2, confirmed.andarCount)
    }

    @Test
    fun duplicateCandidateInSameFrameIsCountedOnce() {
        val zones = testZones()
        val counter = CardCounter(requiredStableFrames = 2)
        val card = CardShapeDetector.detect(threeCardFrame(), zones)
            .first { it.side == Side.ANDAR }
        val shifted = card.copy(
            box = Box(
                card.box.left + 0.01f,
                card.box.top + 0.005f,
                card.box.right + 0.01f,
                card.box.bottom + 0.005f
            )
        )

        counter.update(listOf(card, shifted))
        val snapshot = counter.update(listOf(card, shifted))
        assertEquals(1, snapshot.andarCount)
    }

    @Test
    fun lowConfidenceCandidatesAreUncertainAndNotCounted() {
        val counter = CardCounter(requiredStableFrames = 2)
        val weak = testCandidate(Side.ANDAR, 0.10f, 0.30f, confidence = 0.30f)

        counter.update(listOf(weak))
        val snapshot = counter.update(listOf(weak))
        assertEquals(0, snapshot.andarCount)
        assertEquals(1, snapshot.uncertainAndar)
    }

    @Test
    fun confirmedTracksSurviveFramesWithoutDetection() {
        val zones = testZones()
        val counter = CardCounter(requiredStableFrames = 2)
        counter.update(CardShapeDetector.detect(threeCardFrame(), zones))
        counter.update(CardShapeDetector.detect(threeCardFrame(), zones))

        val empty = counter.update(emptyList())
        assertEquals(3, empty.totalCount)
        counter.update(emptyList())
        assertEquals(3, counter.snapshot().totalCount)
    }

    @Test
    fun resetOnlyAtGenuineRoundBoundary() {
        val zones = testZones()
        val counter = CardCounter(requiredStableFrames = 2)
        counter.update(CardShapeDetector.detect(threeCardFrame(), zones))
        counter.update(CardShapeDetector.detect(threeCardFrame(), zones))
        assertEquals(3, counter.snapshot().totalCount)

        assertFalse(counter.resetIfNewRound(roundId = 7, persistedRoundId = 7))
        assertEquals(3, counter.snapshot().totalCount)

        assertTrue(counter.resetIfNewRound(roundId = 8, persistedRoundId = 7))
        assertEquals(0, counter.snapshot().totalCount)
    }

    @Test
    fun restoredSnapshotKeepsCountsAfterServiceRestart() {
        val zones = testZones()
        val counter = CardCounter(requiredStableFrames = 2)
        counter.update(CardShapeDetector.detect(threeCardFrame(), zones))
        counter.update(CardShapeDetector.detect(threeCardFrame(), zones))
        val saved = counter.exportForPersistence()

        val restored = CardCounter(requiredStableFrames = 2)
        restored.restore(saved)
        val after = restored.update(emptyList())
        assertEquals(2, after.andarCount)
        assertEquals(1, after.baharCount)
    }

    @Test
    fun detectsPortraitScreenCardProportions() {
        val zones = testZones()
        val frame = imageWithRects(
            1080,
            2400,
            listOf(PixelRect(100, 800, 250, 1020))
        )
        val candidates = CardShapeDetector.detect(frame, zones)
        assertEquals(1, candidates.size)
        assertEquals(Side.ANDAR, candidates.first().side)
        assertTrue(candidates.first().confidence >= 0.42f)
    }

    @Test
    fun zoneLocatorBuildsSeparateSideRegionsFromLabels() {
        val parser = OcrParser()
        val parsed = parser.parse(
            ocrFrame(
                ocrLine("ANDAR", 0.10f, 0.20f, 0.30f, 0.25f),
                ocrLine("BAHAR", 0.70f, 0.20f, 0.90f, 0.25f)
            )
        )
        val zones = CardZoneLocator.locate(parsed)
        assertNotNull(zones.andar)
        assertNotNull(zones.bahar)
        assertTrue(zones.andar!!.right <= zones.bahar!!.left)
        assertEquals("auto from OCR labels", zones.source)
    }

    @Test
    fun visualStatsChangeWhenRegionBecomesBrighter() {
        val dark = imageWithRects(400, 800, emptyList())
        val bright = imageWithRects(
            400,
            800,
            listOf(PixelRect(30, 60, 160, 100))
        )
        val region = Box(0.05f, 0.06f, 0.45f, 0.14f)
        val before = VisualRegionAnalyzer.stats(dark, region, Side.ANDAR)
        val after = VisualRegionAnalyzer.stats(bright, region, Side.ANDAR)
        assertTrue(after.meanLuma > before.meanLuma)
        assertTrue(after.brightRatio > before.brightRatio)
    }

    @Test
    fun stackedRightRowLabelsProduceStackedRowBandZones() {
        val parser = OcrParser()
        val parsed = parser.parse(
            ocrFrame(
                ocrLine("ANDAR", 0.712f, 0.279f, 0.813f, 0.290f),
                ocrLine("BAHAR", 0.723f, 0.297f, 0.809f, 0.308f)
            )
        )
        val zones = CardZoneLocator.locate(parsed)
        assertNotNull(zones.andar)
        assertNotNull(zones.bahar)
        val andar = zones.andar!!
        val bahar = zones.bahar!!
        // The stacked layout must split on Y, never on X.
        assertEquals(andar.bottom, bahar.top, 0.0001f)
        assertTrue(andar.top < bahar.top)
        assertTrue(andar.bottom > andar.top)
        assertTrue(bahar.bottom > bahar.top)
        // Rows sit in the right half of the table, clear of the left side panels.
        assertTrue(andar.left >= 0.20f)
        assertTrue(bahar.left >= 0.20f)
        assertTrue(andar.right <= 0.90f)
    }

    @Test
    fun manualZonesOverrideAutoOrMissingLabels() {
        val manual = CardZones(
            andar = Box(0.30f, 0.22f, 0.80f, 0.29f),
            bahar = Box(0.30f, 0.30f, 0.80f, 0.38f),
            source = "manual settings"
        )
        // No OCR labels at all: manual zones still work.
        val noLabels = CardZoneLocator.locate(OcrParser().parse(ocrFrame()), manual)
        assertEquals(manual.andar, noLabels.andar)
        assertEquals(manual.bahar, noLabels.bahar)
        assertEquals("manual settings", noLabels.source)

        // With labels present, manual zones still win.
        val withLabels = OcrParser().parse(
            ocrFrame(
                ocrLine("ANDAR", 0.712f, 0.279f, 0.813f, 0.290f),
                ocrLine("BAHAR", 0.723f, 0.297f, 0.809f, 0.308f)
            )
        )
        val overridden = CardZoneLocator.locate(withLabels, manual)
        assertEquals(manual.andar, overridden.andar)
        assertEquals(manual.bahar, overridden.bahar)
    }
}
