package com.gamexray.app

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.text.Text
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Best-effort visual card-area analyzer.
 *
 * It looks for card-shaped bright regions in the captured frame and assigns
 * them to the nearest OCR side anchor. This is deliberately conservative:
 * unsupported/ambiguous layouts return an uncertain result, never a fabricated
 * card count. Real-game screenshots are still required to calibrate/validate it.
 */
data class VisualWinnerSignal(val candidate: String, val detail: String)

data class SideCardCounts(
    val andar: Int?,
    val bahar: Int?,
    val total: Int?,
    val confidence: String,
    val andarBoxes: List<Rect> = emptyList(),
    val baharBoxes: List<Rect> = emptyList()
)

object ScreenCardAnalyzer {
    private data class Candidate(val rect: Rect, val score: Double)

    fun winnerHighlightCandidate(bitmap: Bitmap, lines: List<Text.Line>): VisualWinnerSignal {
        var andar: Rect? = null
        var bahar: Rect? = null
        for (line in lines) {
            val box = line.boundingBox ?: continue
            val text = line.text.uppercase()
            if (andar == null && Regex("\\bANDAR\\b").containsMatchIn(text)) andar = Rect(box)
            if (bahar == null && (Regex("\\bBAHAR\\b").containsMatchIn(text) || Regex("\\bBOHAR\\b").containsMatchIn(text))) bahar = Rect(box)
        }
        if (andar == null || bahar == null) return VisualWinnerSignal("RESULT_NOT_DETECTED", "Both side labels are not visible")
        fun luminanceAround(anchor: Rect): Double {
            val cx = anchor.centerX(); val cy = anchor.centerY()
            val left = (cx - anchor.width() * 2).coerceAtLeast(0)
            val right = (cx + anchor.width() * 2).coerceAtMost(bitmap.width - 1)
            val top = (cy - anchor.height() * 2).coerceAtLeast(0)
            val bottom = (cy + anchor.height() * 2).coerceAtMost(bitmap.height - 1)
            var sum = 0L; var n = 0
            val stepX = max(1, (right - left) / 20)
            val stepY = max(1, (bottom - top) / 12)
            var y = top
            while (y <= bottom) {
                var x = left
                while (x <= right) {
                    val c = bitmap.getPixel(x, y)
                    val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
                    sum += (0.299 * r + 0.587 * g + 0.114 * b).toLong()
                    n++
                    x += stepX
                }
                y += stepY
            }
            return if (n == 0) 0.0 else sum.toDouble() / n
        }
        val a = luminanceAround(andar!!)
        val b = luminanceAround(bahar!!)
        val difference = a - b
        return when {
            abs(difference) < 18.0 -> VisualWinnerSignal("RESULT_NOT_DETECTED", "No clear side brightness difference")
            difference > 0 -> VisualWinnerSignal("VISUAL_WINNER_CANDIDATE", "Andar area is brighter (not confirmed; animation/layout may affect it)")
            else -> VisualWinnerSignal("VISUAL_WINNER_CANDIDATE", "Bahar area is brighter (not confirmed; animation/layout may affect it)")
        }
    }

    fun analyze(bitmap: Bitmap, lines: List<Text.Line>): SideCardCounts {
        val anchors = findAnchors(lines, bitmap.width, bitmap.height)
        if (anchors.first == null || anchors.second == null) {
            return SideCardCounts(null, null, null, "UNCERTAIN: both side labels not located")
        }

        // Downsample to limit CPU cost. Candidate boxes are scaled back to source coordinates.
        val scale = 4
        val w = max(1, bitmap.width / scale)
        val h = max(1, bitmap.height / scale)
        val small = Bitmap.createScaledBitmap(bitmap, w, h, false)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== bitmap) small.recycle()

        val seen = BooleanArray(pixels.size)
        val candidates = ArrayList<Candidate>()
        val queue = IntArray(pixels.size.coerceAtMost(200_000))
        for (start in pixels.indices) {
            if (seen[start] || !isCardFace(pixels[start])) continue
            var head = 0
            var tail = 0
            if (tail >= queue.size) break
            queue[tail++] = start
            seen[start] = true
            var minX = start % w; var maxX = minX
            var minY = start / w; var maxY = minY
            var count = 0
            while (head < tail) {
                val idx = queue[head++]
                val x = idx % w; val y = idx / w
                count++
                minX = min(minX, x); maxX = max(maxX, x)
                minY = min(minY, y); maxY = max(maxY, y)
                val neighbors = intArrayOf(
                    if (x > 0) idx - 1 else -1,
                    if (x + 1 < w) idx + 1 else -1,
                    if (y > 0) idx - w else -1,
                    if (y + 1 < h) idx + w else -1
                )
                for (n in neighbors) {
                    if (n >= 0 && !seen[n] && isCardFace(pixels[n]) && tail < queue.size) {
                        seen[n] = true
                        queue[tail++] = n
                    }
                }
            }
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            val ratio = bw.toDouble() / bh.coerceAtLeast(1)
            val area = bw * bh
            // Typical portrait card shape, with generous tolerance for scaling/rounding.
            if (area in 90..7000 && ratio in 0.48..0.88 && bh in 18..180 && count >= area * 0.22) {
                val rect = Rect(minX * scale, minY * scale,
                    min(bitmap.width, (maxX + 1) * scale), min(bitmap.height, (maxY + 1) * scale))
                candidates.add(Candidate(rect, count.toDouble() / area))
            }
        }

        val a = anchors.first!!
        val b = anchors.second!!
        val aBoxes = ArrayList<Rect>()
        val bBoxes = ArrayList<Rect>()
        for (candidate in candidates) {
            val r = candidate.rect
            val cx = r.centerX().toDouble()
            val cy = r.centerY().toDouble()
            val da = distance(cx, cy, a.first, a.second)
            val db = distance(cx, cy, b.first, b.second)
            // Only assign candidates spatially closer to one side anchor.
            if (abs(da - db) < min(bitmap.width, bitmap.height) * 0.035) continue
            if (da < db) aBoxes.add(r) else bBoxes.add(r)
        }

        // Remove duplicate/overlapping boxes generated by fragmented card-face pixels.
        val cleanA = deduplicate(aBoxes)
        val cleanB = deduplicate(bBoxes)
        val total = cleanA.size + cleanB.size
        val confidence = when {
            total == 0 -> "UNCERTAIN: no card-shaped regions found"
            total > 52 -> "UNCERTAIN: implausible card count"
            else -> "VISUAL_CANDIDATE: $total card-shaped regions; verify on real game screen"
        }
        val certain = confidence.startsWith("VISUAL_CANDIDATE")
        return SideCardCounts(
            if (certain) cleanA.size else null,
            if (certain) cleanB.size else null,
            if (certain) total else null,
            confidence, cleanA, cleanB
        )
    }

    private fun findAnchors(lines: List<Text.Line>, width: Int, height: Int): Pair<Pair<Double, Double>?, Pair<Double, Double>?> {
        var a: Pair<Double, Double>? = null
        var b: Pair<Double, Double>? = null
        for (line in lines) {
            val box = line.boundingBox ?: continue
            val text = line.text.uppercase().replace("0", "O")
            val center = Pair(box.centerX().toDouble(), box.centerY().toDouble())
            if (a == null && Regex("\\bANDAR\\b").containsMatchIn(text)) a = center
            if (b == null && (Regex("\\bBAHAR\\b").containsMatchIn(text) || Regex("\\bBOHAR\\b").containsMatchIn(text))) b = center
        }
        return Pair(a, b)
    }

    private fun isCardFace(color: Int): Boolean {
        val r = (color shr 16) and 255
        val g = (color shr 8) and 255
        val b = color and 255
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        return minC >= 205 && maxC - minC <= 48
    }

    private fun distance(x1: Double, y1: Double, x2: Double, y2: Double): Double {
        val dx = x1 - x2; val dy = y1 - y2
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun deduplicate(input: List<Rect>): List<Rect> {
        val result = ArrayList<Rect>()
        for (r in input.sortedByDescending { it.width() * it.height() }) {
            if (result.none { Rect.intersects(it, r) || (abs(it.centerX() - r.centerX()) < 12 && abs(it.centerY() - r.centerY()) < 12) }) {
                result.add(r)
            }
        }
        return result
    }
}
