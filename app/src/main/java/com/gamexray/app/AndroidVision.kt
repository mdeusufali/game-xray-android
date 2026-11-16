package com.gamexray.app

import android.graphics.Bitmap
import android.graphics.Rect
import com.gamexray.app.core.Box
import com.gamexray.app.core.ImageFrame
import com.gamexray.app.core.OcrElement
import com.gamexray.app.core.OcrFrame
import com.gamexray.app.core.OcrLine
import com.google.mlkit.vision.text.Text

object AndroidVision {

    fun ocrFrame(result: Text, width: Int, height: Int, timestamp: Long): OcrFrame {
        val lines = mutableListOf<OcrLine>()
        var blockIndex = 0
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val box = normalized(line.boundingBox, width, height) ?: continue
                val elements = mutableListOf<OcrElement>()
                for (element in line.elements.take(16)) {
                    val eb = normalized(element.boundingBox, width, height) ?: continue
                    elements.add(OcrElement(element.text, eb, line.confidence.coerceIn(0f, 1f)))
                }
                lines.add(
                    OcrLine(
                        text = line.text,
                        box = box,
                        confidence = line.confidence.coerceIn(0f, 1f),
                        elements = elements,
                        blockIndex = blockIndex
                    )
                )
            }
            blockIndex++
        }
        return OcrFrame(
            timestamp = timestamp,
            imageWidth = width,
            imageHeight = height,
            rawText = result.text,
            lines = lines
        )
    }

    fun normalized(rect: Rect?, width: Int, height: Int): Box? {
        if (rect == null || width <= 0 || height <= 0) return null
        val left = (rect.left.toFloat() / width).coerceIn(0f, 1f)
        val top = (rect.top.toFloat() / height).coerceIn(0f, 1f)
        val right = (rect.right.toFloat() / width).coerceIn(0f, 1f)
        val bottom = (rect.bottom.toFloat() / height).coerceIn(0f, 1f)
        val box = Box(left, top, right, bottom)
        return if (box.isValid()) box else null
    }

    fun imageFrame(bitmap: Bitmap): ImageFrame {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return ImageFrame(w, h, pixels)
    }

    /** Returns a scaled bitmap; the caller must recycle it only when [created] is true. */
    fun scaled(bitmap: Bitmap, maxWidth: Int): Pair<Bitmap, Boolean> {
        if (bitmap.width <= maxWidth) return bitmap to false
        val height = (bitmap.height.toLong() * maxWidth / bitmap.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, maxWidth, height, true) to true
    }
}
