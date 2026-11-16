package com.gamexray.app.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A decoded ARGB frame used by the pure-Kotlin computer-vision code. */
data class ImageFrame(
    val width: Int,
    val height: Int,
    val pixels: IntArray
) {
    init {
        require(width > 0 && height > 0) { "invalid image dimensions" }
        require(pixels.size >= width * height) { "pixel buffer smaller than image" }
    }
}

/**
 * Finds card-like bright, low-saturation rectangles in the Andar/Bahar display zones.
 *
 * This is intentionally conservative: shape, fill ratio, luma and the located side zone must
 * all agree. The [CardCounter] then requires repeated sightings before a card is counted.
 */
object CardShapeDetector {

    private const val MIN_LUMA = 158.0
    private const val CARD_MAX_SATURATION = 0.42
    private const val MIN_COVERAGE = 0.17
    private const val MIN_ASPECT = 0.32
    private const val MAX_ASPECT = 0.98

    fun detect(frame: ImageFrame, zones: CardZones): List<CardCandidate> {
        val w = frame.width
        val h = frame.height
        val n = w * h
        val mask = ByteArray(n)
        val visited = ByteArray(n)
        val labels = IntArray(n)

        val minW = max(8, (w * 0.022).roundToInt())
        val minH = max(12, (h * 0.025).roundToInt())
        val maxW = max(minW + 1, (w * 0.40).roundToInt())
        val maxH = max(minH + 1, (h * 0.55).roundToInt())

        for (y in 0 until h) {
            val ny = y.toFloat() / h
            if (ny < 0.03f || ny > 0.97f) continue
            for (x in 0 until w) {
                val nx = x.toFloat() / w
                if (!eligible(nx, ny, zones)) continue
                val p = frame.pixels[y * w + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val maxC = max(r, max(g, b))
                val minC = min(r, min(g, b))
                val sat = if (maxC == 0) 0.0 else (maxC - minC).toDouble() / maxC
                val luma = 0.299 * r + 0.587 * g + 0.114 * b
                val white = luma >= MIN_LUMA && sat <= CARD_MAX_SATURATION
                val veryBright = luma >= 195.0 && sat <= 0.55
                if (white || veryBright) mask[y * w + x] = 1
            }
        }

        val candidates = mutableListOf<CardCandidate>()
        val queue = IntArray(n)
        var componentId = 0

        for (start in 0 until n) {
            if (mask[start].toInt() == 0 || visited[start].toInt() != 0) continue
            componentId++
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = 1
            labels[start] = componentId

            var minX = w
            var minY = h
            var maxX = -1
            var maxY = -1
            var area = 0
            var lumaSum = 0.0
            var satSum = 0.0

            while (head < tail) {
                val idx = queue[head++]
                val x = idx % w
                val y = idx / w
                area++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                val p = frame.pixels[idx]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                lumaSum += 0.299 * r + 0.587 * g + 0.114 * b
                val maxC = max(r, max(g, b))
                val minC = min(r, min(g, b))
                satSum += if (maxC == 0) 0.0 else (maxC - minC).toDouble() / maxC

                if (x > 0) enqueue(idx - 1, w, mask, visited, labels, componentId, queue, tail).also { tail = it }
                if (x + 1 < w) enqueue(idx + 1, w, mask, visited, labels, componentId, queue, tail).also { tail = it }
                if (y > 0) enqueue(idx - w, w, mask, visited, labels, componentId, queue, tail).also { tail = it }
                if (y + 1 < h) enqueue(idx + w, w, mask, visited, labels, componentId, queue, tail).also { tail = it }
            }

            if (area <= 0 || maxX < minX || maxY < minY) continue
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            if (bw < minW || bh < minH || bw > maxW || bh > maxH) continue
            val fill = area.toFloat() / (bw.toFloat() * bh.toFloat())
            if (fill < MIN_COVERAGE) continue
            val meanLuma = lumaSum / area
            if (meanLuma < MIN_LUMA + 3.0) continue
            val meanSat = satSum / area

            val aspect = bw.toFloat() / bh.toFloat()
            if (aspect in MIN_ASPECT..MAX_ASPECT) {
                makeCandidate(
                    frame, zones, minX, minY, maxX, maxY, area, meanLuma, meanSat, fill,
                    source = "shape"
                )?.let { candidates.add(it) }
            } else if (aspect > MAX_ASPECT && bw > (minW * 1.7)) {
                splitWideComponent(
                    frame, zones, labels, componentId, minX, minY, maxX, maxY,
                    minW, meanLuma, meanSat, candidates
                )
            }
        }

        return candidates
            .filterNot { it.confidence < 0.2f }
            .sortedWith(compareBy({ it.side.ordinal }, { it.box.centerY }, { it.box.centerX }))
    }

    private fun enqueue(
        idx: Int,
        w: Int,
        mask: ByteArray,
        visited: ByteArray,
        labels: IntArray,
        componentId: Int,
        queue: IntArray,
        tail: Int
    ): Int {
        if (idx < 0 || idx >= mask.size) return tail
        if (mask[idx].toInt() == 0 || visited[idx].toInt() != 0) return tail
        visited[idx] = 1
        labels[idx] = componentId
        queue[tail] = idx
        return tail + 1
    }

    private fun splitWideComponent(
        frame: ImageFrame,
        zones: CardZones,
        labels: IntArray,
        componentId: Int,
        minX: Int,
        minY: Int,
        maxX: Int,
        maxY: Int,
        minW: Int,
        meanLuma: Double,
        meanSat: Double,
        out: MutableList<CardCandidate>
    ) {
        val w = frame.width
        val h = frame.height
        val bw = maxX - minX + 1
        val bh = maxY - minY + 1
        val colCount = IntArray(bw)
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                if (labels[y * w + x] == componentId) colCount[x - minX]++
            }
        }
        val valley = max(2, (bh * 0.12).roundToInt()).toFloat()
        val segments = mutableListOf<IntRange>()
        var segStart = -1
        for (i in 0 until bw) {
            val filled = colCount[i] > valley
            if (filled && segStart < 0) segStart = i
            if (!filled && segStart >= 0) {
                if (i - segStart >= minW) segments.add(segStart until i)
                segStart = -1
            }
        }
        if (segStart >= 0 && bw - segStart >= minW) segments.add(segStart until bw)
        if (segments.size < 2) return

        for (seg in segments) {
            var y0 = h
            var y1 = -1
            var area = 0
            for (y in minY..maxY) {
                for (x in (minX + seg.first)..(minX + seg.last)) {
                    if (labels[y * w + x] == componentId) {
                        area++
                        if (y < y0) y0 = y
                        if (y > y1) y1 = y
                    }
                }
            }
            if (area <= 0 || y1 < y0) continue
            val x0 = minX + seg.first
            val x1 = minX + seg.last
            val sw = x1 - x0 + 1
            val sh = y1 - y0 + 1
            val aspect = sw.toFloat() / sh.toFloat()
            if (aspect !in MIN_ASPECT..MAX_ASPECT) continue
            val fill = area.toFloat() / (sw.toFloat() * sh.toFloat())
            makeCandidate(
                frame, zones, x0, y0, x1, y1, area, meanLuma, meanSat, fill,
                source = "split-shape"
            )?.let { out.add(it) }
        }
    }

    private fun makeCandidate(
        frame: ImageFrame,
        zones: CardZones,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        area: Int,
        meanLuma: Double,
        meanSat: Double,
        fill: Float,
        source: String
    ): CardCandidate? {
        val w = frame.width
        val h = frame.height
        val box = Box(x0.toFloat() / w, y0.toFloat() / h, (x1 + 1).toFloat() / w, (y1 + 1).toFloat() / h)
        if (!box.isValid()) return null
        if (box.top < 0.035f || box.bottom > 0.965f) return null
        // Compare physical pixel proportions; normalized coordinates are stretched on a
        // portrait screen, so a tall card can look wide after normalization.
        val aspect = (box.width * w) / (box.height * h)
        if (aspect !in MIN_ASPECT..MAX_ASPECT) return null
        if (fill < MIN_COVERAGE) return null

        val side = zones.assign(box)
        if (side != Side.UNKNOWN) {
            val zone = zones.areaFor(side) ?: return null
            if (box.overlapFraction(zone) < 0.55f) return null
            val label = zones.labelRegion(side)
            if (label != null && box.overlapFraction(label) > 0.35f) return null
        } else {
            // Without located side labels a card cannot be attributed safely.
            return null
        }

        val aspectScore = 1.0 - (abs(aspect.toDouble() - 0.66) / 0.66).coerceIn(0.0, 1.0)
        val fillScore: Double = when {
            fill in 0.30f..0.92f -> 1.0
            fill in 0.17f..0.30f -> ((fill - 0.17f) / 0.13f).toDouble()
            else -> ((1.0 - fill) / 0.08).coerceIn(0.0, 1.0)
        }
        val lumaScore = ((meanLuma - 158.0) / 80.0).coerceIn(0.0, 1.0)
        val sizeScore = (box.width / 0.11f).toDouble().coerceIn(0.35, 1.0)
        var confidence = (0.36 * aspectScore + 0.28 * fillScore + 0.18 * lumaScore + 0.18 * sizeScore).toFloat()
        if (meanSat > 0.30) confidence *= 0.85f
        confidence = confidence.coerceIn(0f, 1f)

        val (fp, fp2) = fingerprint(frame, box)
        return CardCandidate(
            box = box,
            side = side,
            confidence = confidence,
            fingerprint = fp,
            fingerprint2 = fp2,
            fillRatio = fill,
            meanLuma = meanLuma.toFloat(),
            source = source
        )
    }

    /**
     * dHash-like 64-bit fingerprint plus a 64-bit average hash. Both are luminance based so
     * small brightness changes do not create a new card identity.
     */
    fun fingerprint(frame: ImageFrame, box: Box): Pair<Long, Long> {
        val cols = 9
        val rows = 8
        val values = DoubleArray(cols * rows)
        val x0 = (box.left * frame.width).roundToInt().coerceIn(0, frame.width - 1)
        val y0 = (box.top * frame.height).roundToInt().coerceIn(0, frame.height - 1)
        val x1 = (box.right * frame.width).roundToInt().coerceIn(x0 + 1, frame.width)
        val y1 = (box.bottom * frame.height).roundToInt().coerceIn(y0 + 1, frame.height)
        val bw = (x1 - x0).coerceAtLeast(1)
        val bh = (y1 - y0).coerceAtLeast(1)

        for (ry in 0 until rows) {
            for (rx in 0 until cols) {
                val px0 = x0 + (rx * bw / cols)
                val px1 = x0 + (((rx + 1) * bw / cols).coerceAtLeast(rx * bw / cols + 1)).coerceAtMost(frame.width)
                val py0 = y0 + (ry * bh / rows)
                val py1 = y0 + (((ry + 1) * bh / rows).coerceAtLeast(ry * bh / rows + 1)).coerceAtMost(frame.height)
                var sum = 0.0
                var count = 0
                var sy = py0
                while (sy < py1) {
                    var sx = px0
                    while (sx < px1) {
                        val p = frame.pixels[sy * frame.width + sx]
                        val r = (p shr 16) and 0xFF
                        val g = (p shr 8) and 0xFF
                        val b = p and 0xFF
                        sum += 0.299 * r + 0.587 * g + 0.114 * b
                        count++
                        sx += max(1, (px1 - px0) / 3)
                    }
                    sy += max(1, (py1 - py0) / 3)
                }
                values[ry * cols + rx] = if (count > 0) sum / count else 0.0
            }
        }

        var dHash = 0L
        var bit = 0
        for (ry in 0 until rows) {
            for (rx in 0 until cols - 1) {
                if (values[ry * cols + rx] > values[ry * cols + rx + 1]) {
                    dHash = dHash or (1L shl bit)
                }
                bit++
            }
        }
        val sorted = values.sorted()
        val median = sorted[sorted.size / 2]
        var aHash = 0L
        for (i in 0 until min(64, values.size)) {
            if (values[i] > median) aHash = aHash or (1L shl i)
        }
        return dHash to aHash
    }

    private fun eligible(nx: Float, ny: Float, zones: CardZones): Boolean {
        val a = zones.andar
        val b = zones.bahar
        if (a == null && b == null) return true
        if (a != null && a.contains(nx, ny)) return true
        if (b != null && b.contains(nx, ny)) return true
        return false
    }

    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    fun fingerprintSimilarity(a: CardCandidate, b: CardCandidate): Double {
        val h1 = hamming(a.fingerprint, b.fingerprint)
        val h2 = hamming(a.fingerprint2, b.fingerprint2)
        return (1.0 - (h1 + h2) / 128.0).coerceIn(0.0, 1.0)
    }
}

/** Builds the Andar/Bahar card zones from OCR side-label geometry. */
object CardZoneLocator {

    /**
     * Normalized zones for the real mobile layout seen in diagnostics: the Andar row is above
     * the Bahar row on the right half of the table, and the ornate cut card sits to their right
     * (excluded by the right edge).
     */
    fun realLayoutZones(): CardZones = CardZones(
        andar = Box(0.28f, 0.224f, 0.818f, 0.294f),
        bahar = Box(0.28f, 0.294f, 0.818f, 0.378f),
        source = "default real layout"
    )

    fun locate(parsed: ParsedScreen, manual: CardZones? = null): CardZones {
        val auto = autoLocate(parsed)
        val panelA = panelLabelRegion(parsed, Side.ANDAR)
        val panelB = panelLabelRegion(parsed, Side.BAHAR)
        if (manual != null && (manual.andar != null || manual.bahar != null)) {
            return manual.copy(
                andarLabelRegion = manual.andarLabelRegion ?: panelA ?: auto.andarLabelRegion,
                baharLabelRegion = manual.baharLabelRegion ?: panelB ?: auto.baharLabelRegion,
                source = manual.source.ifBlank { "manual zones" }
            )
        }
        return auto.copy(
            andarLabelRegion = panelA ?: auto.andarLabelRegion,
            baharLabelRegion = panelB ?: auto.baharLabelRegion
        )
    }

    /**
     * Result-highlight regions are the whole side panels, not the small card-row labels. The
     * real layout stacks ANDAR above BAHAR on the left, and the winner glow covers the panel.
     */
    private fun panelLabelRegion(parsed: ParsedScreen, side: Side): Box? {
        val line = parsed.sideLabelLines
            .filter {
                it.box.centerX < 0.5f && it.box.centerY in 0.30f..0.80f &&
                    it.text.uppercase().contains(side.label)
            }
            .maxByOrNull { it.box.area }
            ?: return null
        val box = line.box
        return Box(
            0.03f,
            (box.top - 0.02f).coerceAtLeast(0.03f),
            0.97f,
            (box.bottom + 0.045f).coerceAtMost(0.95f)
        )
    }

    private fun autoLocate(parsed: ParsedScreen): CardZones {
        val a = parsed.andarLabel
        val b = parsed.baharLabel
        if (a == null && b == null) {
            return CardZones(source = "no ANDAR/BAHAR labels located")
        }
        val labelTop = listOfNotNull(a?.box?.top, b?.box?.top).minOrNull() ?: 0.1f
        val top = (labelTop - 0.13f).coerceIn(0.03f, 0.55f)
        val bottom = 0.95f

        if (a != null && b != null && labelsAreSideBySide(a, b)) {
            val split = ((a.box.centerX + b.box.centerX) / 2f).coerceIn(0.22f, 0.78f)
            return CardZones(
                andar = Box(0.004f, top, (split - 0.003f).coerceAtLeast(0.10f), bottom),
                bahar = Box((split + 0.003f).coerceAtMost(0.90f), top, 0.996f, bottom),
                source = "auto from OCR labels"
            )
        }

        // Real layout: the row labels are stacked on the right of the table. The Andar row is
        // the band above the labels' midpoint and Bahar is the band below it.
        if (a != null && b != null &&
            a.box.centerX > 0.5f && b.box.centerX > 0.5f &&
            a.box.centerY < b.box.centerY
        ) {
            val midY = ((a.box.centerY + b.box.centerY) / 2f).coerceIn(0.20f, 0.50f)
            val rowTop = (minOf(a.box.top, b.box.top) - 0.060f).coerceIn(0.16f, 0.34f)
            val rowBottom = (maxOf(a.box.bottom, b.box.bottom) + 0.075f).coerceIn(0.32f, 0.48f)
            return CardZones(
                andar = Box(0.28f, rowTop, 0.818f, midY),
                bahar = Box(0.28f, midY, 0.818f, rowBottom),
                source = "auto from stacked row labels"
            )
        }

        // The only labels may be the big side-panel labels on the left. Their card rows still
        // follow the same known real layout; a manual override can replace this safely.
        if (a != null && b != null && a.box.centerY < b.box.centerY &&
            listOf(a, b).all { it.box.centerX < 0.5f }
        ) {
            val fallback = realLayoutZones()
            return fallback.copy(
                source = "auto fallback real layout; verify card counts"
            )
        }

        if (a != null && b == null) {
            val split = if (a.box.centerX < 0.5f) 0.5f else 0.22f
            return CardZones(
                andar = if (a.box.centerX < 0.5f) Box(0.004f, top, split - 0.003f, bottom) else null,
                bahar = if (a.box.centerX >= 0.5f) Box(split + 0.003f, top, 0.996f, bottom) else null,
                andarLabelRegion = a.box.expanded(0.06f, 0.025f).clamped(),
                source = "auto from ANDAR label only"
            )
        }
        if (b != null) {
            val split = if (b.box.centerX >= 0.5f) 0.5f else 0.78f
            return CardZones(
                bahar = if (b.box.centerX >= 0.5f) Box(split + 0.003f, top, 0.996f, bottom) else null,
                andar = if (b.box.centerX < 0.5f) Box(0.004f, top, split - 0.003f, bottom) else null,
                baharLabelRegion = b.box.expanded(0.06f, 0.025f).clamped(),
                source = "auto from BAHAR label only"
            )
        }
        return CardZones(source = "labels could not be split safely")
    }
}

/** Colour/luma statistics for a side's result-label region. */
object VisualRegionAnalyzer {
    fun analyze(frame: ImageFrame, zones: CardZones): Map<Side, RegionVisualStats> {
        val out = mutableMapOf<Side, RegionVisualStats>()
        for (side in listOf(Side.ANDAR, Side.BAHAR)) {
            val region = zones.labelRegion(side) ?: continue
            out[side] = stats(frame, region, side)
        }
        return out
    }

    fun stats(frame: ImageFrame, region: Box, side: Side): RegionVisualStats {
        val x0 = (region.left * frame.width).toInt().coerceIn(0, frame.width - 1)
        val x1 = (region.right * frame.width).toInt().coerceIn(x0 + 1, frame.width)
        val y0 = (region.top * frame.height).toInt().coerceIn(0, frame.height - 1)
        val y1 = (region.bottom * frame.height).toInt().coerceIn(y0 + 1, frame.height)
        var count = 0
        var luma = 0.0
        var rSum = 0.0
        var gSum = 0.0
        var bSum = 0.0
        var bright = 0
        var colour = 0.0
        val step = if ((x1 - x0) * (y1 - y0) > 20_000) 2 else 1
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val p = frame.pixels[y * frame.width + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val l = 0.299 * r + 0.587 * g + 0.114 * b
                rSum += r
                gSum += g
                bSum += b
                luma += l
                if (l >= 175) bright++
                colour += (max(r, max(g, b)) - min(r, min(g, b))).toDouble()
                count++
                x += step
            }
            y += step
        }
        if (count == 0) {
            return RegionVisualStats(side, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0)
        }
        return RegionVisualStats(
            side = side,
            meanLuma = luma / count,
            meanR = rSum / count,
            meanG = gSum / count,
            meanB = bSum / count,
            brightRatio = bright.toDouble() / count,
            colorfulness = colour / count,
            sampleCount = count
        )
    }
}
