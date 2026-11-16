package com.gamexray.app.core

import kotlin.math.abs

/**
 * Keeps previous/current panel totals per side for the active round.
 *
 * Displayed panel totals are already totals; frames are never summed. A new difference is
 * produced only when two reliable numeric totals are known. Repeated identical OCR frames do
 * not create new changes.
 */
class MoneyTracker {
    private var andar = MoneyPanelState(Side.ANDAR)
    private var bahar = MoneyPanelState(Side.BAHAR)
    private var roundId: Long = 0L

    fun reset(roundId: Long) {
        this.roundId = roundId
        andar = MoneyPanelState(Side.ANDAR)
        bahar = MoneyPanelState(Side.BAHAR)
    }

    fun state(side: Side): MoneyPanelState = when (side) {
        Side.ANDAR -> andar
        Side.BAHAR -> bahar
        Side.UNKNOWN -> MoneyPanelState(Side.UNKNOWN)
    }

    fun update(
        side: Side,
        reading: MoneyReading,
        individual: List<VisibleAmount>,
        timestamp: Long
    ): MoneyPanelState {
        if (side == Side.UNKNOWN) return MoneyPanelState(Side.UNKNOWN)
        val state = state(side)
        val thisFrameSideAmounts = individual.filter { it.side == side }
        val updated = when (reading.status) {
            DetectionStatus.DETECTED -> {
                val value = reading.value
                if (!Validation.money(value)) {
                    state.copy(
                        currentFrame = null,
                        status = DetectionStatus.UNCERTAIN,
                        reason = "displayed total failed validation",
                        individual = thisFrameSideAmounts
                    )
                } else {
                    val v = value!!
                    if (state.current == null) {
                        state.copy(
                            current = v,
                            currentFrame = v,
                            previous = null,
                            difference = null,
                            status = DetectionStatus.DETECTED,
                            reason = reading.reason,
                            lastUpdated = timestamp,
                            individual = thisFrameSideAmounts
                        )
                    } else if (abs(state.current - v) < 0.005) {
                        state.copy(
                            currentFrame = v,
                            status = DetectionStatus.DETECTED,
                            reason = reading.reason,
                            lastUpdated = timestamp,
                            individual = thisFrameSideAmounts
                        )
                    } else {
                        val previous = state.current
                        val diff = Validation.moneyDifference(previous, v)
                        state.copy(
                            current = v,
                            currentFrame = v,
                            previous = previous,
                            difference = diff,
                            status = if (diff == null) DetectionStatus.UNCERTAIN else DetectionStatus.DETECTED,
                            reason = if (diff == null) "difference could not be validated" else reading.reason,
                            lastUpdated = timestamp,
                            individual = thisFrameSideAmounts
                        )
                    }
                }
            }
            DetectionStatus.UNCERTAIN, DetectionStatus.REJECTED -> {
                state.copy(
                    currentFrame = null,
                    status = reading.status,
                    reason = reading.reason,
                    individual = thisFrameSideAmounts
                )
            }
            DetectionStatus.NOT_DETECTED -> {
                state.copy(
                    currentFrame = null,
                    status = DetectionStatus.NOT_DETECTED,
                    reason = reading.reason,
                    individual = thisFrameSideAmounts
                )
            }
        }
        when (side) {
            Side.ANDAR -> andar = updated
            Side.BAHAR -> bahar = updated
            Side.UNKNOWN -> Unit
        }
        return updated
    }

    fun export(): Pair<MoneyPanelState, MoneyPanelState> = andar.copy() to bahar.copy()

    fun restore(saved: Pair<MoneyPanelState, MoneyPanelState>?, roundId: Long) {
        this.roundId = roundId
        andar = saved?.first ?: MoneyPanelState(Side.ANDAR)
        bahar = saved?.second ?: MoneyPanelState(Side.BAHAR)
    }

    fun activeRoundId(): Long = roundId
}
