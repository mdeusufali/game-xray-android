package com.gamexray.app.data

import com.gamexray.app.core.BetFreeze
import com.gamexray.app.core.BetSettings
import com.gamexray.app.core.BettingStage
import com.gamexray.app.core.Box
import com.gamexray.app.core.CardCounterSnapshot
import com.gamexray.app.core.CardTrack
import com.gamexray.app.core.CardZone
import com.gamexray.app.core.CardZones
import com.gamexray.app.core.DetectionStatus
import com.gamexray.app.core.ManagedRound
import com.gamexray.app.core.MoneyPanelState
import com.gamexray.app.core.MoneyReading
import com.gamexray.app.core.NumberKind
import com.gamexray.app.core.NumberToken
import com.gamexray.app.core.OcrElement
import com.gamexray.app.core.OcrLine
import com.gamexray.app.core.OcrWinner
import com.gamexray.app.core.OcrWinnerSignal
import com.gamexray.app.core.ParsedScreen
import com.gamexray.app.core.RegionVisualStats
import com.gamexray.app.core.RoundRecord
import com.gamexray.app.core.RoundStage
import com.gamexray.app.core.Side
import com.gamexray.app.core.ValueReading
import com.gamexray.app.core.VisibleAmount
import com.gamexray.app.core.VisualWinnerEvidence
import com.gamexray.app.core.WinnerDecision
import com.gamexray.app.core.WinnerState
import org.json.JSONArray
import org.json.JSONObject

/** JSON conversion kept outside the pure core so unit tests do not require Android. */
object JsonCodec {

    fun putNullable(obj: JSONObject, name: String, value: Double?): JSONObject {
        if (value == null || !value.isFinite()) obj.put(name, JSONObject.NULL) else obj.put(name, value)
        return obj
    }

    fun putNullable(obj: JSONObject, name: String, value: Int?): JSONObject {
        if (value == null) obj.put(name, JSONObject.NULL) else obj.put(name, value)
        return obj
    }

    fun putNullableLong(obj: JSONObject, name: String, value: Long?): JSONObject {
        if (value == null) obj.put(name, JSONObject.NULL) else obj.put(name, value)
        return obj
    }

    fun nullableDouble(obj: JSONObject, name: String): Double? {
        if (!obj.has(name) || obj.isNull(name)) return null
        val value = obj.optDouble(name, Double.NaN)
        return if (value.isFinite()) value else null
    }

    fun nullableInt(obj: JSONObject, name: String): Int? {
        if (!obj.has(name) || obj.isNull(name)) return null
        return obj.optInt(name)
    }

    fun nullableLong(obj: JSONObject, name: String): Long? {
        if (!obj.has(name) || obj.isNull(name)) return null
        return obj.optLong(name)
    }

    fun boxToArray(box: Box?): JSONArray {
        if (box == null) return JSONArray()
        return JSONArray().put(box.left.toDouble()).put(box.top.toDouble())
            .put(box.right.toDouble()).put(box.bottom.toDouble())
    }

    fun boxFromArray(array: JSONArray?): Box? {
        if (array == null || array.length() < 4) return null
        val box = Box(
            array.optDouble(0, Double.NaN).toFloat(),
            array.optDouble(1, Double.NaN).toFloat(),
            array.optDouble(2, Double.NaN).toFloat(),
            array.optDouble(3, Double.NaN).toFloat()
        )
        return if (box.isValid()) box else null
    }

    fun readingToJson(reading: MoneyReading): JSONObject = JSONObject()
        .also { putNullable(it, "value", reading.value) }
        .put("status", reading.status.name)
        .put("raw", reading.raw)
        .put("reason", reading.reason)
        .put("lineIndex", reading.lineIndex)

    fun readingFromJson(obj: JSONObject?): MoneyReading {
        if (obj == null) return MoneyReading.notDetected()
        return MoneyReading(
            value = nullableDouble(obj, "value"),
            status = enumOrDefault(obj.optString("status"), DetectionStatus.NOT_DETECTED),
            raw = obj.optString("raw", ""),
            reason = obj.optString("reason", ""),
            lineIndex = obj.optInt("lineIndex", -1)
        )
    }

    fun doubleReadingToJson(reading: ValueReading<Double>): JSONObject = JSONObject()
        .also { putNullable(it, "value", reading.value) }
        .put("status", reading.status.name)
        .put("raw", reading.raw)
        .put("reason", reading.reason)
        .put("lineIndex", reading.lineIndex)

    fun doubleReadingFromJson(obj: JSONObject?): ValueReading<Double> {
        if (obj == null) return ValueReading()
        return ValueReading(
            value = nullableDouble(obj, "value"),
            status = enumOrDefault(obj.optString("status"), DetectionStatus.NOT_DETECTED),
            raw = obj.optString("raw", ""),
            reason = obj.optString("reason", ""),
            lineIndex = obj.optInt("lineIndex", -1)
        )
    }

    fun intReadingToJson(reading: ValueReading<Int>): JSONObject = JSONObject()
        .also { putNullable(it, "value", reading.value) }
        .put("status", reading.status.name)
        .put("raw", reading.raw)
        .put("reason", reading.reason)
        .put("lineIndex", reading.lineIndex)

    fun intReadingFromJson(obj: JSONObject?): ValueReading<Int> {
        if (obj == null) return ValueReading()
        return ValueReading(
            value = nullableInt(obj, "value"),
            status = enumOrDefault(obj.optString("status"), DetectionStatus.NOT_DETECTED),
            raw = obj.optString("raw", ""),
            reason = obj.optString("reason", ""),
            lineIndex = obj.optInt("lineIndex", -1)
        )
    }

    fun visibleAmountToJson(amount: VisibleAmount): JSONObject = JSONObject()
        .put("side", amount.side.name)
        .put("value", amount.value)
        .put("raw", amount.raw)
        .put("lineIndex", amount.lineIndex)
        .put("lineText", amount.lineText)
        .put("confidence", amount.confidence.toDouble())
        .put("reason", amount.reason)

    fun visibleAmountFromJson(obj: JSONObject): VisibleAmount = VisibleAmount(
        side = enumOrDefault(obj.optString("side"), Side.UNKNOWN),
        value = obj.optDouble("value", 0.0),
        raw = obj.optString("raw"),
        lineIndex = obj.optInt("lineIndex", -1),
        lineText = obj.optString("lineText"),
        confidence = obj.optDouble("confidence", 0.0).toFloat(),
        reason = obj.optString("reason")
    )

    fun moneyPanelToJson(state: MoneyPanelState): JSONObject = JSONObject()
        .put("side", state.side.name)
        .also { putNullable(it, "current", state.current) }
        .also { putNullable(it, "currentFrame", state.currentFrame) }
        .also { putNullable(it, "previous", state.previous) }
        .also { putNullable(it, "difference", state.difference) }
        .put("status", state.status.name)
        .put("reason", state.reason)
        .put("lastUpdated", state.lastUpdated)
        .put("individual", JSONArray().also { array -> state.individual.forEach { array.put(visibleAmountToJson(it)) } })

    fun moneyPanelFromJson(obj: JSONObject?): MoneyPanelState {
        if (obj == null) return MoneyPanelState(Side.UNKNOWN)
        val individual = mutableListOf<VisibleAmount>()
        val array = obj.optJSONArray("individual") ?: JSONArray()
        for (i in 0 until array.length()) array.optJSONObject(i)?.let { individual.add(visibleAmountFromJson(it)) }
        return MoneyPanelState(
            side = enumOrDefault(obj.optString("side"), Side.UNKNOWN),
            current = nullableDouble(obj, "current"),
            currentFrame = nullableDouble(obj, "currentFrame"),
            previous = nullableDouble(obj, "previous"),
            difference = nullableDouble(obj, "difference"),
            status = enumOrDefault(obj.optString("status"), DetectionStatus.NOT_DETECTED),
            reason = obj.optString("reason", ""),
            lastUpdated = obj.optLong("lastUpdated", 0L),
            individual = individual
        )
    }

    fun betSettingsToJson(settings: BetSettings): JSONObject = JSONObject()
        .put("side", settings.side.name)
        .also { putNullable(it, "stake", settings.stake) }
        .also { putNullable(it, "odds", settings.odds) }

    fun betSettingsFromJson(obj: JSONObject?): BetSettings {
        if (obj == null) return BetSettings()
        return BetSettings(
            side = enumOrDefault(obj.optString("side"), Side.UNKNOWN),
            stake = nullableDouble(obj, "stake"),
            odds = nullableDouble(obj, "odds")
        )
    }

    fun betFreezeToJson(bet: BetFreeze?): Any {
        if (bet == null) return JSONObject.NULL
        return JSONObject()
            .put("side", bet.side.name)
            .also { putNullable(it, "stake", bet.stake) }
            .also { putNullable(it, "odds", bet.odds) }
            .put("frozenAt", bet.frozenAt)
            .put("locked", bet.locked)
            .put("source", bet.source)
    }

    fun betFreezeFromJson(obj: JSONObject?): BetFreeze? {
        if (obj == null) return null
        return BetFreeze(
            side = enumOrDefault(obj.optString("side"), Side.UNKNOWN),
            stake = nullableDouble(obj, "stake"),
            odds = nullableDouble(obj, "odds"),
            frozenAt = obj.optLong("frozenAt", 0L),
            locked = obj.optBoolean("locked", false),
            source = obj.optString("source", "")
        )
    }

    fun cardTrackToJson(track: CardTrack): JSONObject = JSONObject()
        .put("id", track.id)
        .put("side", track.side.name)
        .put("box", boxToArray(track.box))
        .put("fingerprint", track.fingerprint)
        .put("fingerprint2", track.fingerprint2)
        .put("firstSeenFrame", track.firstSeenFrame)
        .put("lastSeenFrame", track.lastSeenFrame)
        .put("hits", track.hits)
        .put("confirmed", track.confirmed)
        .put("uncertain", track.uncertain)
        .put("lastConfidence", track.lastConfidence.toDouble())
        .put("missedFrames", track.missedFrames)

    fun cardTrackFromJson(obj: JSONObject): CardTrack = CardTrack(
        id = obj.optLong("id", 0L),
        side = enumOrDefault(obj.optString("side"), Side.UNKNOWN),
        box = boxFromArray(obj.optJSONArray("box")) ?: Box(0f, 0f, 0f, 0f),
        fingerprint = obj.optLong("fingerprint", 0L),
        fingerprint2 = obj.optLong("fingerprint2", 0L),
        firstSeenFrame = obj.optLong("firstSeenFrame", 0L),
        lastSeenFrame = obj.optLong("lastSeenFrame", 0L),
        hits = obj.optInt("hits", 1),
        confirmed = obj.optBoolean("confirmed", false),
        uncertain = obj.optBoolean("uncertain", false),
        lastConfidence = obj.optDouble("lastConfidence", 0.0).toFloat(),
        missedFrames = obj.optInt("missedFrames", 0)
    )

    fun cardSnapshotToJson(snapshot: CardCounterSnapshot): JSONObject {
        val tracks = JSONArray()
        snapshot.tracks.forEach { tracks.put(cardTrackToJson(it)) }
        return JSONObject()
            .put("andarCount", snapshot.andarCount)
            .put("baharCount", snapshot.baharCount)
            .put("uncertainAndar", snapshot.uncertainAndar)
            .put("uncertainBahar", snapshot.uncertainBahar)
            .put("pendingAndar", snapshot.pendingAndar)
            .put("pendingBahar", snapshot.pendingBahar)
            .put("unassigned", snapshot.unassigned)
            .put("frameIndex", snapshot.frameIndex)
            .put("tracks", tracks)
    }

    fun cardSnapshotFromJson(obj: JSONObject?): CardCounterSnapshot {
        if (obj == null) return CardCounterSnapshot()
        val tracks = mutableListOf<CardTrack>()
        val array = obj.optJSONArray("tracks") ?: JSONArray()
        for (i in 0 until array.length()) array.optJSONObject(i)?.let { tracks.add(cardTrackFromJson(it)) }
        return CardCounterSnapshot(
            andarCount = obj.optInt("andarCount", 0),
            baharCount = obj.optInt("baharCount", 0),
            uncertainAndar = obj.optInt("uncertainAndar", 0),
            uncertainBahar = obj.optInt("uncertainBahar", 0),
            pendingAndar = obj.optInt("pendingAndar", 0),
            pendingBahar = obj.optInt("pendingBahar", 0),
            unassigned = obj.optInt("unassigned", 0),
            frameIndex = obj.optLong("frameIndex", 0L),
            tracks = tracks
        )
    }

    fun managedRoundToJson(round: ManagedRound): JSONObject {
        val individual = JSONArray()
        round.andarMoney.individual.forEach { individual.put(visibleAmountToJson(it)) }
        return JSONObject()
            .put("id", round.id)
            .put("startedAt", round.startedAt)
            .put("stage", round.stage.name)
            .put("bettingStage", round.bettingStage.name)
            .put("bet", betFreezeToJson(round.bet))
            .also { putNullableLong(it, "betLockedAt", round.betLockedAt) }
            .put("andarCards", round.andarCards)
            .put("baharCards", round.baharCards)
            .also { putNullable(it, "cardsDealtOcr", round.cardsDealtOcr) }
            .put("winnerSide", round.winnerSide.name)
            .put("winnerState", round.winnerState.name)
            .put("winnerConfidence", round.winnerConfidence.toDouble())
            .put("winnerReason", round.winnerReason)
            .put("andarMoney", moneyPanelToJson(round.andarMoney))
            .put("baharMoney", moneyPanelToJson(round.baharMoney))
            .also { putNullable(it, "andarOdds", round.andarOdds) }
            .also { putNullable(it, "baharOdds", round.baharOdds) }
            .put("lastStageText", round.lastStageText)
            .put("lastObservationAt", round.lastObservationAt)
            .also { putNullableLong(it, "completedAt", round.completedAt) }
            .also { putNullableLong(it, "savedAt", round.savedAt) }
            .put("detectionStatus", round.detectionStatus)
    }

    fun managedRoundFromJson(obj: JSONObject?): ManagedRound? {
        if (obj == null) return null
        val id = obj.optLong("id", 0L)
        if (id <= 0L) return null
        return ManagedRound(
            id = id,
            startedAt = obj.optLong("startedAt", id),
            stage = enumOrDefault(obj.optString("stage"), RoundStage.IDLE),
            bettingStage = enumOrDefault(obj.optString("bettingStage"), BettingStage.NOT_DETECTED),
            bet = betFreezeFromJson(obj.optJSONObject("bet")),
            betLockedAt = nullableLong(obj, "betLockedAt"),
            andarCards = obj.optInt("andarCards", 0),
            baharCards = obj.optInt("baharCards", 0),
            cardsDealtOcr = nullableInt(obj, "cardsDealtOcr"),
            winnerSide = enumOrDefault(obj.optString("winnerSide"), Side.UNKNOWN),
            winnerState = enumOrDefault(obj.optString("winnerState"), WinnerState.RESULT_NOT_DETECTED),
            winnerConfidence = obj.optDouble("winnerConfidence", 0.0).toFloat(),
            winnerReason = obj.optString("winnerReason"),
            andarMoney = moneyPanelFromJson(obj.optJSONObject("andarMoney")),
            baharMoney = moneyPanelFromJson(obj.optJSONObject("baharMoney")),
            andarOdds = nullableDouble(obj, "andarOdds"),
            baharOdds = nullableDouble(obj, "baharOdds"),
            lastStageText = obj.optString("lastStageText"),
            lastObservationAt = obj.optLong("lastObservationAt", 0L),
            completedAt = nullableLong(obj, "completedAt"),
            savedAt = nullableLong(obj, "savedAt"),
            detectionStatus = obj.optString("detectionStatus", "in progress")
        )
    }

    fun roundRecordToJson(record: RoundRecord): JSONObject = JSONObject()
        .put("id", record.id)
        .put("startedAt", record.startedAt)
        .also { putNullableLong(it, "lockedAt", record.lockedAt) }
        .put("completedAt", record.completedAt)
        .put("time", record.completedAt)
        .put("andarCards", record.andarCards)
        .put("baharCards", record.baharCards)
        .put("totalCards", record.totalCards)
        .also { putNullable(it, "cardsDealtOcr", record.cardsDealtOcr) }
        .also { putNullable(it, "andarTotal", record.andarTotal) }
        .also { putNullable(it, "baharTotal", record.baharTotal) }
        .also { putNullable(it, "andarPreviousTotal", record.andarPreviousTotal) }
        .also { putNullable(it, "baharPreviousTotal", record.baharPreviousTotal) }
        .also { putNullable(it, "andarDifference", record.andarDifference) }
        .also { putNullable(it, "baharDifference", record.baharDifference) }
        .also { putNullable(it, "andarOdds", record.andarOdds) }
        .also { putNullable(it, "baharOdds", record.baharOdds) }
        .put("mySide", record.mySide.name)
        .also { putNullable(it, "myStake", record.myStake) }
        .also { putNullable(it, "myOdds", record.myOdds) }
        .put("winner", record.winner.name)
        .put("winnerState", record.winnerState.name)
        .put("winnerConfidence", record.winnerConfidence.toDouble())
        .put("detectionStatus", record.detectionStatus)
        .put("isWin", record.isWin ?: JSONObject.NULL)
        .also { putNullable(it, "grossReturn", record.grossReturn) }
        .also { putNullable(it, "netProfit", record.netProfit) }
        .also { putNullable(it, "lossAmount", record.lossAmount) }
        .put("pnl", record.netProfit ?: JSONObject.NULL)
        .put("settlementStatus", record.settlementStatus)
        .put("settlementReason", record.settlementReason)
        .put("rawWinnerReason", record.rawWinnerReason)
        .put("ocrSnapshotHash", record.ocrSnapshotHash)
        .put("revision", record.revision)

    fun roundRecordFromJson(obj: JSONObject): RoundRecord? {
        val id = obj.optLong("id", obj.optLong("time", 0L))
        if (id <= 0L) return null
        val time = obj.optLong("completedAt", obj.optLong("time", id))
        val winner = enumOrDefault(obj.optString("winner"), Side.UNKNOWN)
        val mySide = enumOrDefault(obj.optString("mySide"), Side.UNKNOWN)
        val myStake = nullableDouble(obj, "myStake")
        val myOdds = nullableDouble(obj, "myOdds")
        val storedPnl = nullableDouble(obj, "netProfit") ?: nullableDouble(obj, "pnl")
        val winnerState = if (obj.has("winnerState")) {
            enumOrDefault(obj.optString("winnerState"), WinnerState.RESULT_NOT_DETECTED)
        } else {
            if (winner == Side.ANDAR || winner == Side.BAHAR) WinnerState.WINNER_CONFIRMED else WinnerState.RESULT_NOT_DETECTED
        }
        var isWin: Boolean? = null
        if (obj.has("isWin") && !obj.isNull("isWin")) isWin = obj.optBoolean("isWin")
        else if (storedPnl != null) isWin = storedPnl >= 0.0
        else if (winner != Side.UNKNOWN && mySide != Side.UNKNOWN) isWin = winner == mySide
        val gross = nullableDouble(obj, "grossReturn") ?: when {
            isWin == true && myStake != null && myOdds != null -> myStake * myOdds
            isWin == false -> 0.0
            else -> null
        }
        val loss = nullableDouble(obj, "lossAmount") ?: when {
            isWin == false && myStake != null -> myStake
            isWin == true -> 0.0
            else -> null
        }
        return RoundRecord(
            id = id,
            startedAt = obj.optLong("startedAt", obj.optLong("time", id)),
            lockedAt = nullableLong(obj, "lockedAt"),
            completedAt = time,
            andarCards = obj.optInt("andarCards", 0),
            baharCards = obj.optInt("baharCards", 0),
            totalCards = obj.optInt("totalCards", obj.optInt("andarCards", 0) + obj.optInt("baharCards", 0)),
            cardsDealtOcr = nullableInt(obj, "cardsDealtOcr"),
            andarTotal = nullableDouble(obj, "andarTotal"),
            baharTotal = nullableDouble(obj, "baharTotal"),
            andarPreviousTotal = nullableDouble(obj, "andarPreviousTotal"),
            baharPreviousTotal = nullableDouble(obj, "baharPreviousTotal"),
            andarDifference = nullableDouble(obj, "andarDifference"),
            baharDifference = nullableDouble(obj, "baharDifference"),
            andarOdds = nullableDouble(obj, "andarOdds"),
            baharOdds = nullableDouble(obj, "baharOdds"),
            mySide = mySide,
            myStake = myStake,
            myOdds = myOdds,
            winner = winner,
            winnerState = winnerState,
            winnerConfidence = obj.optDouble("winnerConfidence", 0.0).toFloat(),
            detectionStatus = obj.optString("detectionStatus", obj.optString("source", "OCR result")),
            isWin = isWin,
            grossReturn = gross,
            netProfit = storedPnl,
            lossAmount = loss,
            settlementStatus = obj.optString("settlementStatus", if (isWin == true) "win" else if (isWin == false) "loss" else "unavailable"),
            settlementReason = obj.optString("settlementReason", ""),
            rawWinnerReason = obj.optString("rawWinnerReason", ""),
            ocrSnapshotHash = obj.optString("ocrSnapshotHash", ""),
            revision = obj.optInt("revision", 1)
        )
    }

    fun winnerToJson(decision: WinnerDecision): JSONObject = JSONObject()
        .put("state", decision.state.name)
        .put("side", decision.side.name)
        .put("confidence", decision.confidence.toDouble())
        .put("reason", decision.reason)
        .put("ocrSide", decision.ocrSide.name)
        .put("visualSide", decision.visualSide.name)

    fun winnerFromJson(obj: JSONObject?): WinnerDecision {
        if (obj == null) return WinnerDecision()
        return WinnerDecision(
            state = enumOrDefault(obj.optString("state"), WinnerState.RESULT_NOT_DETECTED),
            side = enumOrDefault(obj.optString("side"), Side.UNKNOWN),
            confidence = obj.optDouble("confidence", 0.0).toFloat(),
            reason = obj.optString("reason"),
            ocrSide = enumOrDefault(obj.optString("ocrSide"), Side.UNKNOWN),
            visualSide = enumOrDefault(obj.optString("visualSide"), Side.UNKNOWN)
        )
    }

    fun visualToJson(evidence: VisualWinnerEvidence): JSONObject = JSONObject()
        .put("side", evidence.side.name)
        .put("candidate", evidence.candidate)
        .put("conflict", evidence.conflict)
        .put("scoreAndar", evidence.scoreAndar)
        .put("scoreBahar", evidence.scoreBahar)
        .put("streak", evidence.streak)
        .put("reason", evidence.reason)

    fun ocrWinnerToJson(signal: OcrWinnerSignal): JSONObject {
        val winners = JSONArray()
        signal.winners.forEach {
            winners.put(
                JSONObject().put("side", it.side.name).put("lineIndex", it.lineIndex)
                    .put("lineText", it.lineText).put("confidence", it.confidence.toDouble())
                    .put("evidence", it.evidence).put("resultPhrase", it.resultPhrase)
            )
        }
        return JSONObject().put("side", signal.side.name).put("conflict", signal.conflict)
            .put("conflictReason", signal.conflictReason).put("winners", winners)
    }

    fun ocrWinnerFromJson(obj: JSONObject?): OcrWinnerSignal {
        if (obj == null) return OcrWinnerSignal()
        val winners = mutableListOf<OcrWinner>()
        val array = obj.optJSONArray("winners") ?: JSONArray()
        for (i in 0 until array.length()) {
            val w = array.optJSONObject(i) ?: continue
            winners.add(
                OcrWinner(
                    side = enumOrDefault(w.optString("side"), Side.UNKNOWN),
                    lineIndex = w.optInt("lineIndex", -1),
                    lineText = w.optString("lineText"),
                    confidence = w.optDouble("confidence", 0.0).toFloat(),
                    evidence = w.optString("evidence"),
                    resultPhrase = w.optString("resultPhrase")
                )
            )
        }
        return OcrWinnerSignal(
            side = enumOrDefault(obj.optString("side"), Side.UNKNOWN),
            winners = winners,
            conflict = obj.optBoolean("conflict", false),
            conflictReason = obj.optString("conflictReason")
        )
    }

    fun parsedToJson(parsed: ParsedScreen): JSONObject {
        val amounts = JSONArray()
        parsed.visibleAmounts.forEach { amounts.put(visibleAmountToJson(it)) }
        val tokens = JSONArray()
        parsed.numberTokens.forEach {
            tokens.put(
                JSONObject().put("value", it.value).put("raw", it.raw)
                    .put("kind", it.kind.name).put("lineIndex", it.lineIndex)
                    .put("lineText", it.lineText).put("side", it.side.name)
                    .put("confidence", it.confidence.toDouble())
            )
        }
        return JSONObject()
            .put("stageText", parsed.stageText)
            .put("bettingStage", parsed.bettingStage.name)
            .put("andarLabel", lineToJson(parsed.andarLabel))
            .put("baharLabel", lineToJson(parsed.baharLabel))
            .put("andarTotal", readingToJson(parsed.andarTotal))
            .put("baharTotal", readingToJson(parsed.baharTotal))
            .put("andarOdds", doubleReadingToJson(parsed.andarOdds))
            .put("baharOdds", doubleReadingToJson(parsed.baharOdds))
            .put("andarPercentage", doubleReadingToJson(parsed.andarPercentage))
            .put("baharPercentage", doubleReadingToJson(parsed.baharPercentage))
            .put("cardsDealtOcr", intReadingToJson(parsed.cardsDealtOcr))
            .put("ocrWinner", ocrWinnerToJson(parsed.ocrWinner))
            .put("visibleAmounts", amounts)
            .put("visibleBalance", readingToJson(parsed.visibleBalance))
            .put("visibleTotalStake", readingToJson(parsed.visibleTotalStake))
            .put("numberTokens", tokens)
    }

    fun parsedFromJson(obj: JSONObject?): ParsedScreen {
        if (obj == null) return ParsedScreen()
        val amounts = mutableListOf<VisibleAmount>()
        val amountArray = obj.optJSONArray("visibleAmounts") ?: JSONArray()
        for (i in 0 until amountArray.length()) amountArray.optJSONObject(i)?.let { amounts.add(visibleAmountFromJson(it)) }
        val tokens = mutableListOf<NumberToken>()
        val tokenArray = obj.optJSONArray("numberTokens") ?: JSONArray()
        for (i in 0 until tokenArray.length()) {
            val t = tokenArray.optJSONObject(i) ?: continue
            val token = NumberToken(
                value = t.optDouble("value", 0.0),
                raw = t.optString("raw"),
                kind = enumOrDefault(t.optString("kind"), NumberKind.UNKNOWN),
                lineIndex = t.optInt("lineIndex", -1),
                lineText = t.optString("lineText"),
                confidence = t.optDouble("confidence", 0.0).toFloat()
            )
            token.side = enumOrDefault(t.optString("side"), Side.UNKNOWN)
            tokens.add(token)
        }
        return ParsedScreen(
            stageText = obj.optString("stageText", "NOT DETECTED"),
            bettingStage = enumOrDefault(obj.optString("bettingStage"), BettingStage.NOT_DETECTED),
            andarLabel = lineFromJson(obj.optJSONObject("andarLabel")),
            baharLabel = lineFromJson(obj.optJSONObject("baharLabel")),
            andarTotal = readingFromJson(obj.optJSONObject("andarTotal")),
            baharTotal = readingFromJson(obj.optJSONObject("baharTotal")),
            andarOdds = doubleReadingFromJson(obj.optJSONObject("andarOdds")),
            baharOdds = doubleReadingFromJson(obj.optJSONObject("baharOdds")),
            andarPercentage = doubleReadingFromJson(obj.optJSONObject("andarPercentage")),
            baharPercentage = doubleReadingFromJson(obj.optJSONObject("baharPercentage")),
            cardsDealtOcr = intReadingFromJson(obj.optJSONObject("cardsDealtOcr")),
            ocrWinner = ocrWinnerFromJson(obj.optJSONObject("ocrWinner")),
            visibleAmounts = amounts,
            visibleBalance = readingFromJson(obj.optJSONObject("visibleBalance")),
            visibleTotalStake = readingFromJson(obj.optJSONObject("visibleTotalStake")),
            numberTokens = tokens
        )
    }

    fun lineToJson(line: OcrLine?): Any {
        if (line == null) return JSONObject.NULL
        val elements = JSONArray()
        line.elements.take(20).forEach {
            elements.put(
                JSONObject().put("text", it.text).put("box", boxToArray(it.box))
                    .put("confidence", it.confidence.toDouble())
            )
        }
        return JSONObject()
            .put("text", line.text)
            .put("box", boxToArray(line.box))
            .put("confidence", line.confidence.toDouble())
            .put("blockIndex", line.blockIndex)
            .put("elements", elements)
    }

    fun lineFromJson(obj: JSONObject?): OcrLine? {
        if (obj == null) return null
        val box = boxFromArray(obj.optJSONArray("box")) ?: return null
        val elements = mutableListOf<OcrElement>()
        val array = obj.optJSONArray("elements") ?: JSONArray()
        for (i in 0 until array.length()) {
            val e = array.optJSONObject(i) ?: continue
            val eb = boxFromArray(e.optJSONArray("box")) ?: continue
            elements.add(OcrElement(e.optString("text"), eb, e.optDouble("confidence", 0.0).toFloat()))
        }
        return OcrLine(
            text = obj.optString("text"),
            box = box,
            confidence = obj.optDouble("confidence", 0.0).toFloat(),
            elements = elements,
            blockIndex = obj.optInt("blockIndex", 0)
        )
    }

    fun zonesToJson(zones: CardZones): JSONObject = JSONObject()
        .put("andar", boxToArray(zones.andar))
        .put("bahar", boxToArray(zones.bahar))
        .put("andarLabelRegion", boxToArray(zones.andarLabelRegion))
        .put("baharLabelRegion", boxToArray(zones.baharLabelRegion))
        .put("source", zones.source)

    fun zonesFromJson(obj: JSONObject?): CardZones {
        if (obj == null) return CardZones()
        return CardZones(
            andar = boxFromArray(obj.optJSONArray("andar")),
            bahar = boxFromArray(obj.optJSONArray("bahar")),
            andarLabelRegion = boxFromArray(obj.optJSONArray("andarLabelRegion")),
            baharLabelRegion = boxFromArray(obj.optJSONArray("baharLabelRegion")),
            source = obj.optString("source")
        )
    }

    fun statsToJson(stats: Map<Side, RegionVisualStats>): JSONObject {
        val obj = JSONObject()
        for ((side, value) in stats) {
            obj.put(
                side.name,
                JSONObject().put("meanLuma", value.meanLuma).put("meanR", value.meanR)
                    .put("meanG", value.meanG).put("meanB", value.meanB)
                    .put("brightRatio", value.brightRatio).put("colorfulness", value.colorfulness)
                    .put("sampleCount", value.sampleCount)
            )
        }
        return obj
    }

    fun boolFromJson(value: Boolean?): Any = value ?: JSONObject.NULL

    inline fun <reified T : Enum<T>> enumOrDefault(name: String, fallback: T): T {
        return try {
            enumValues<T>().firstOrNull { it.name == name } ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }
}
