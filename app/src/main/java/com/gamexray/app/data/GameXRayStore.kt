package com.gamexray.app.data

import android.content.Context
import com.gamexray.app.core.BetSettings
import com.gamexray.app.core.CardCounterSnapshot
import com.gamexray.app.core.FrameAnalysis
import com.gamexray.app.core.ManagedRound
import com.gamexray.app.core.RoundDeduplication
import com.gamexray.app.core.RoundRecord
import com.gamexray.app.core.Side
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local-only persistence. SharedPreferences is used for a compact JSON store so the app keeps
 * working without Room/network dependencies. Writes are synchronized and capped.
 */
class GameXRayStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun readRounds(): List<RoundRecord> {
        val array = readArray(prefs.getString(KEY_ROUNDS, "[]"))
        val out = mutableListOf<RoundRecord>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            JsonCodec.roundRecordFromJson(obj)?.let { out.add(it) }
        }
        return out.distinctBy { it.id }.sortedBy { it.completedAt }
    }

    /** Returns false when the round was already persisted (duplicate result prevention). */
    @Synchronized
    fun appendRound(record: RoundRecord): Boolean {
        val existing = readRounds()
        if (RoundDeduplication.isDuplicate(existing, record)) {
            return false
        }
        val records = existing.associateBy { it.id }.toMutableMap()
        records[record.id] = record
        val sorted = records.values.sortedBy { it.completedAt }.takeLast(MAX_ROUNDS)
        val array = JSONArray()
        sorted.forEach { array.put(JsonCodec.roundRecordToJson(it)) }
        prefs.edit()
            .putString(KEY_ROUNDS, array.toString())
            .putInt("round_count", array.length())
            .putLong("last_saved_round_id", record.id)
            .apply()
        return true
    }

    @Synchronized
    fun saveActive(round: ManagedRound?) {
        val edit = prefs.edit()
        if (round == null) {
            edit.remove(KEY_ACTIVE_ROUND).remove("active_round_id").apply()
            return
        }
        val json = JsonCodec.managedRoundToJson(round)
        edit.putString(KEY_ACTIVE_ROUND, json.toString())
            .putLong("active_round_id", round.id)
            .putString("round_phase", round.stage.name)
            .putString("round_my_side", round.bet?.side?.name ?: "NONE")
            .putString("round_my_stake", round.bet?.stake?.toString() ?: "")
            .putString("round_my_odds", round.bet?.odds?.toString() ?: "")
        if (round.savedAt != null) edit.putLong("last_saved_round_id", round.id)
        edit.apply()
    }

    fun loadActive(): ManagedRound? {
        val raw = prefs.getString(KEY_ACTIVE_ROUND, null) ?: return null
        return try {
            JsonCodec.managedRoundFromJson(JSONObject(raw))
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun saveCardState(snapshot: CardCounterSnapshot) {
        prefs.edit().putString(KEY_CARD_STATE, JsonCodec.cardSnapshotToJson(snapshot).toString()).apply()
    }

    fun loadCardState(): CardCounterSnapshot {
        val raw = prefs.getString(KEY_CARD_STATE, null) ?: return CardCounterSnapshot()
        return try {
            JsonCodec.cardSnapshotFromJson(JSONObject(raw))
        } catch (_: Exception) {
            CardCounterSnapshot()
        }
    }

    @Synchronized
    fun saveAnalysis(analysis: FrameAnalysis, overlayText: String, persistSnapshot: Boolean) {
        val frame = JSONObject()
            .put("time", analysis.ocr.timestamp)
            .put("hash", analysis.snapshotHash)
            .put("rawText", analysis.ocr.rawText.take(8000))
            .put(
                "image",
                JSONObject().put("width", analysis.ocr.imageWidth).put("height", analysis.ocr.imageHeight)
            )
        val lines = JSONArray()
        analysis.ocr.lines.take(160).forEach { lines.put(JsonCodec.lineToJson(it)) }
        frame.put("lines", lines)

        val structured = JsonCodec.parsedToJson(analysis.parsed)
        structured.put("cards", JsonCodec.cardSnapshotToJson(analysis.cards))
        structured.put("visual", JsonCodec.visualToJson(analysis.visual))
        structured.put("winner", JsonCodec.winnerToJson(analysis.winner))
        structured.put("zones", JsonCodec.zonesToJson(analysis.zones))
        analysis.round?.let { structured.put("round", JsonCodec.managedRoundToJson(it)) }
        val candidates = JSONArray()
        analysis.candidates.take(60).forEach {
            candidates.put(
                JSONObject().put("side", it.side.name).put("box", JsonCodec.boxToArray(it.box))
                    .put("confidence", it.confidence.toDouble())
                    .put("fillRatio", it.fillRatio.toDouble())
                    .put("meanLuma", it.meanLuma.toDouble())
                    .put("source", it.source)
                    .put("fingerprint", java.lang.Long.toHexString(it.fingerprint))
                    .put("fingerprint2", java.lang.Long.toHexString(it.fingerprint2))
            )
        }
        structured.put("cardCandidates", candidates)
        structured.put(
            "diagnostics",
            JSONObject()
                .put("snapshotHash", analysis.snapshotHash)
                .put("trackCount", analysis.cards.tracks.size)
                .put("startedNewRound", analysis.startedNewRound)
                .put("newRound", analysis.roundUpdate.newRound)
                .put("droppedIncompleteRoundId", analysis.roundUpdate.droppedIncompleteRoundId ?: JSONObject.NULL)
                .put("scans", prefs.getInt("scan_count", 0))
                .put("frames", prefs.getInt("frames_received", 0))
        )
        frame.put("structured", structured)

        val snapshotEdit = prefs.edit()
            .putString(KEY_LAST_STRUCTURED, frame.toString())
            .putString("last_text", overlayText)
        if (persistSnapshot) {
            val old = readArray(prefs.getString(KEY_OCR_FRAMES, "[]"))
            val array = JSONArray()
            val keepFrom = (old.length() - (MAX_SNAPSHOTS - 1)).coerceAtLeast(0)
            for (i in keepFrom until old.length()) array.put(old.getJSONObject(i))
            array.put(frame)
            snapshotEdit.putString(KEY_OCR_FRAMES, array.toString())
                .putString("last_snapshot_hash", analysis.snapshotHash)
                .putLong("last_snapshot_time", analysis.ocr.timestamp)
        }
        snapshotEdit.apply()
    }

    fun lastStructured(): JSONObject? {
        val raw = prefs.getString(KEY_LAST_STRUCTURED, null) ?: return null
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            null
        }
    }

    fun readFrames(limit: Int = 30): List<JSONObject> {
        val array = readArray(prefs.getString(KEY_OCR_FRAMES, "[]"))
        val out = mutableListOf<JSONObject>()
        if (array.length() == 0) {
            // Fallback to the legady text snapshots written by older versions.
            val legacy = readArray(prefs.getString("history", "[]"))
            for (i in legacy.length() - 1 downTo 0) {
                val obj = legacy.optJSONObject(i) ?: continue
                out.add(
                    JSONObject().put("time", obj.optLong("time"))
                        .put("rawText", obj.optString("text"))
                        .put("legacy", true)
                )
                if (out.size >= limit) break
            }
            return out
        }
        for (i in array.length() - 1 downTo 0) {
            array.optJSONObject(i)?.let { out.add(it) }
            if (out.size >= limit) break
        }
        return out
    }

    @Synchronized
    fun appendStatus(message: String, timestamp: Long = System.currentTimeMillis()) {
        val array = readArray(prefs.getString(KEY_STATUS_LOG, "[]"))
        val out = JSONArray()
        val from = (array.length() - (MAX_STATUS - 1)).coerceAtLeast(0)
        for (i in from until array.length()) out.put(array.getJSONObject(i))
        out.put(JSONObject().put("time", timestamp).put("message", message.take(1000)))
        prefs.edit().putString(KEY_STATUS_LOG, out.toString())
            .putString("last_status", message.take(4000))
            .apply()
    }

    fun readStatusLog(limit: Int = 60): List<Pair<Long, String>> {
        val array = readArray(prefs.getString(KEY_STATUS_LOG, "[]"))
        val out = mutableListOf<Pair<Long, String>>()
        for (i in array.length() - 1 downTo 0) {
            val obj = array.optJSONObject(i) ?: continue
            out.add(obj.optLong("time") to obj.optString("message"))
            if (out.size >= limit) break
        }
        return out
    }

    fun settings(): BetSettings {
        return BetSettings(
            side = Side.fromLabel(prefs.getString("my_side", "NONE")),
            stake = prefs.getString("my_stake", "")?.toDoubleOrNull(),
            odds = prefs.getString("my_odds", "")?.toDoubleOrNull()
        )
    }

    fun saveSettings(settings: BetSettings) {
        prefs.edit()
            .putString("my_side", settings.side.label)
            .putString("my_stake", settings.stake?.toString() ?: "")
            .putString("my_odds", settings.odds?.toString() ?: "")
            .apply()
    }

    /** Manual normalized Andar/Bahar card zones. Null means automatic zones. */
    fun manualCardZones(): com.gamexray.app.core.CardZones? {
        val raw = prefs.getString(KEY_MANUAL_ZONES, null) ?: return null
        return try {
            JsonCodec.zonesFromJson(JSONObject(raw))
        } catch (_: Exception) {
            null
        }
    }

    fun saveManualCardZones(zones: com.gamexray.app.core.CardZones?) {
        val edit = prefs.edit()
        if (zones == null) {
            edit.remove(KEY_MANUAL_ZONES)
        } else {
            edit.putString(KEY_MANUAL_ZONES, JsonCodec.zonesToJson(zones).toString())
        }
        edit.apply()
    }

    fun setCounters(scans: Int, frames: Int) {
        prefs.edit().putInt("scan_count", scans).putInt("frames_received", frames).apply()
    }

    fun counts(): Pair<Int, Int> = prefs.getInt("scan_count", 0) to prefs.getInt("frames_received", 0)

    fun lastSnapshotHash(): String = prefs.getString("last_snapshot_hash", "") ?: ""

    fun lastSnapshotTime(): Long = prefs.getLong("last_snapshot_time", 0L)

    fun lastStatus(): String = prefs.getString("last_status", "No OCR data yet") ?: "No OCR data yet"

    fun rawPrefs(): android.content.SharedPreferences = prefs

    private fun readArray(raw: String?): JSONArray = try {
        JSONArray(raw ?: "[]")
    } catch (_: Exception) {
        JSONArray()
    }

    companion object {
        const val PREFS = "game_xray_local"
        private const val KEY_ROUNDS = "rounds"
        private const val KEY_ACTIVE_ROUND = "active_round"
        private const val KEY_CARD_STATE = "card_state"
        private const val KEY_OCR_FRAMES = "ocr_frames"
        private const val KEY_LAST_STRUCTURED = "last_structured"
        private const val KEY_STATUS_LOG = "status_log"
        private const val KEY_MANUAL_ZONES = "manual_card_zones"
        private const val MAX_ROUNDS = 500
        private const val MAX_SNAPSHOTS = 300
        private const val MAX_STATUS = 200
    }
}
