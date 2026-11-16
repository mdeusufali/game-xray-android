package com.gamexray.app.core

import kotlin.math.abs

/**
 * Guards local round history against duplicate results.
 *
 * A round is normally keyed by its ID, but an OCR/state transition can occasionally produce a
 * second record for the same physical round with a different ID. Records that share the same
 * winner, card counts and personal bet inside a short time window are treated as duplicates.
 */
object RoundDeduplication {
    const val DEFAULT_WINDOW_MS = 5_000L

    fun isDuplicate(
        existing: List<RoundRecord>,
        candidate: RoundRecord,
        windowMs: Long = DEFAULT_WINDOW_MS
    ): Boolean {
        val sameId = existing.firstOrNull { it.id == candidate.id }
        if (sameId != null) {
            // Same round may only be replaced by a strictly newer revision.
            return candidate.revision <= sameId.revision
        }
        return existing.any { record ->
            abs(record.completedAt - candidate.completedAt) <= windowMs &&
                record.winner == candidate.winner &&
                record.winnerState == candidate.winnerState &&
                record.andarCards == candidate.andarCards &&
                record.baharCards == candidate.baharCards &&
                record.mySide == candidate.mySide &&
                record.myStake == candidate.myStake &&
                record.myOdds == candidate.myOdds
        }
    }
}
