package com.gamexray.app.core

import kotlin.math.abs
import kotlin.math.min

/**
 * Tracks card candidates over time and counts each physical card exactly once.
 *
 * A card is confirmed only after it has been matched in [requiredStableFrames] frames. Cards
 * that move are re-matched by fingerprint as well as overlap. Confirmed tracks are retained
 * until [reset] is called at a genuine round boundary, so a card disappearing behind an
 * animation and reappearing is not counted twice.
 */
class CardCounter(
    private val requiredStableFrames: Int = 2,
    private val minConfidence: Float = 0.42f
) {
    private val tracks = mutableListOf<CardTrack>()
    private var nextId = 1L
    private var frameIndex = 0L

    fun reset() {
        tracks.clear()
        nextId = 1L
        frameIndex = 0L
    }

    fun restore(snapshot: CardCounterSnapshot) {
        tracks.clear()
        tracks.addAll(snapshot.tracks)
        nextId = (snapshot.tracks.maxOfOrNull { it.id } ?: 0L) + 1L
        frameIndex = snapshot.frameIndex
    }

    fun snapshot(): CardCounterSnapshot {
        val confirmed = tracks.filter { it.confirmed }
        return CardCounterSnapshot(
            andarCount = confirmed.count { it.side == Side.ANDAR },
            baharCount = confirmed.count { it.side == Side.BAHAR },
            uncertainAndar = tracks.count { it.side == Side.ANDAR && !it.confirmed && it.uncertain },
            uncertainBahar = tracks.count { it.side == Side.BAHAR && !it.confirmed && it.uncertain },
            pendingAndar = tracks.count { it.side == Side.ANDAR && !it.confirmed && !it.uncertain },
            pendingBahar = tracks.count { it.side == Side.BAHAR && !it.confirmed && !it.uncertain },
            unassigned = 0,
            frameIndex = frameIndex,
            tracks = tracks.toList()
        )
    }

    fun update(candidates: List<CardCandidate>): CardCounterSnapshot {
        frameIndex++
        val assigned = candidates.filter { it.side != Side.UNKNOWN && it.box.isValid() }
        val deduped = deduplicate(assigned)
        val matchedTrackIds = mutableSetOf<Long>()

        for (candidate in deduped.sortedByDescending { it.confidence }) {
            val best = bestTrack(candidate)
            if (best != null) {
                merge(best, candidate)
                matchedTrackIds.add(best.id)
            } else {
                val track = CardTrack(
                    id = nextId++,
                    side = candidate.side,
                    box = candidate.box,
                    fingerprint = candidate.fingerprint,
                    fingerprint2 = candidate.fingerprint2,
                    firstSeenFrame = frameIndex,
                    lastSeenFrame = frameIndex,
                    hits = 1,
                    confirmed = requiredStableFrames <= 1 && candidate.confidence >= minConfidence,
                    uncertain = candidate.confidence < minConfidence,
                    lastConfidence = candidate.confidence,
                    missedFrames = 0
                )
                tracks.add(track)
                matchedTrackIds.add(track.id)
            }
        }

        val iterator = tracks.iterator()
        while (iterator.hasNext()) {
            val track = iterator.next()
            if (track.id in matchedTrackIds) continue
            track.missedFrames++
            if (!track.confirmed && track.missedFrames >= 2) iterator.remove()
        }
        return snapshot()
    }

    private fun deduplicate(candidates: List<CardCandidate>): List<CardCandidate> {
        val out = mutableListOf<CardCandidate>()
        for (candidate in candidates.sortedByDescending { it.confidence }) {
            val duplicate = out.any { existing ->
                existing.side == candidate.side &&
                    (existing.box.iou(candidate.box) > 0.70f ||
                        (CardShapeDetector.fingerprintSimilarity(existing, candidate) > 0.94 &&
                            existing.box.centerDistance(candidate.box) < 0.04f))
            }
            if (!duplicate) out.add(candidate)
        }
        return out
    }

    private fun bestTrack(candidate: CardCandidate): CardTrack? {
        var best: CardTrack? = null
        var bestScore = 0.0
        for (track in tracks) {
            if (track.side != candidate.side) continue
            val iou = track.box.iou(candidate.box)
            val fp = syntheticSimilarity(track, candidate)
            val sizeSim = min(
                min(track.box.width, candidate.box.width) / maxOf(track.box.width, candidate.box.width, 1e-5f),
                min(track.box.height, candidate.box.height) / maxOf(track.box.height, candidate.box.height, 1e-5f)
            ).toDouble()
            val dist = candidate.box.centerDistance(track.box).coerceAtMost(1f)
            val score = 0.48 * iou + 0.32 * fp + 0.12 * sizeSim + 0.08 * (1.0 - dist)
            // Fingerprint-only matching must still be close in space. Otherwise two identical
            // face-down cards dealt beside each other would be merged into one card.
            val proximity = dist <= maxOf(0.035f, 0.85f * maxOf(track.box.width, candidate.box.width))
            val stronglySame = iou > 0.55f ||
                (fp > 0.90 && proximity) ||
                (fp > 0.97 && sizeSim > 0.75 && dist < 0.06f)
            // A pure fingerprint/size match must also be spatially plausible; otherwise two
            // identical face-down cards dealt side by side collapse into one track.
            val spatiallyPlausible = iou > 0.05f || proximity
            if (((score >= 0.47 && spatiallyPlausible) || stronglySame) && score > bestScore) {
                best = track
                bestScore = score
            }
        }
        return best
    }

    private fun syntheticSimilarity(track: CardTrack, candidate: CardCandidate): Double {
        val h1 = CardShapeDetector.hamming(track.fingerprint, candidate.fingerprint)
        val h2 = CardShapeDetector.hamming(track.fingerprint2, candidate.fingerprint2)
        return (1.0 - (h1 + h2) / 128.0).coerceIn(0.0, 1.0)
    }

    private fun merge(track: CardTrack, candidate: CardCandidate) {
        // Smooth movement while keeping a stable identity.
        track.box = Box(
            track.box.left * 0.35f + candidate.box.left * 0.65f,
            track.box.top * 0.35f + candidate.box.top * 0.65f,
            track.box.right * 0.35f + candidate.box.right * 0.65f,
            track.box.bottom * 0.35f + candidate.box.bottom * 0.65f
        )
        if (candidate.confidence >= 0.45f) {
            track.fingerprint = candidate.fingerprint
            track.fingerprint2 = candidate.fingerprint2
        }
        track.lastSeenFrame = frameIndex
        track.lastConfidence = candidate.confidence
        track.missedFrames = 0
        track.hits++
        if (track.hits >= requiredStableFrames && candidate.confidence >= minConfidence) {
            track.confirmed = true
            track.uncertain = false
        } else if (candidate.confidence < minConfidence) {
            track.uncertain = true
        }
    }

    fun tracks(): List<CardTrack> = tracks.toList()

    /**
     * Rebuilds a snapshot from persisted tracks. Confirmed tracks survive service restarts,
     * which prevents recounting an existing card in the same round.
     */
    fun exportForPersistence(): CardCounterSnapshot = snapshot().copy(tracks = tracks.toList())

    fun resetIfNewRound(roundId: Long, persistedRoundId: Long): Boolean {
        if (roundId != persistedRoundId) {
            reset()
            return true
        }
        return false
    }

    companion object {
        fun similarity(a: Long, b: Long): Double =
            (1.0 - java.lang.Long.bitCount(a xor b) / 64.0).coerceIn(0.0, 1.0)
    }
}
