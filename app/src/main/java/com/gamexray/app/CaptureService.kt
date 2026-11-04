package com.gamexray.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var overlay: LinearLayout? = null
    private var windowManager: WindowManager? = null
    private var status: TextView? = null
    private var scans = 0
    private var frames = 0
    private var lastText = ""
    private var roundPhase = "IDLE"
    private var roundId = 0L
    private val busy = AtomicBoolean(false)
    private val prefs by lazy { getSharedPreferences("game_xray_local", Context.MODE_PRIVATE) }
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("gx", "Game X-Ray capture", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, "gx")
        startForeground(7, notification.setContentTitle("Game X-Ray is capturing").setContentText("Local screen OCR active").setSmallIcon(android.R.drawable.ic_menu_view).build())
        val code = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION") val data = intent?.getParcelableExtra<Intent>("resultData")
        if (code == Activity.RESULT_OK && data != null) beginCapture(code, data)
        return START_NOT_STICKY
    }

    private fun beginCapture(code: Int, data: Intent) {
        scans = prefs.getInt("scan_count", 0); frames = prefs.getInt("frames_received", 0)
        val m = resources.displayMetrics
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, Handler(Looper.getMainLooper()))
        reader = ImageReader.newInstance(m.widthPixels, m.heightPixels, PixelFormat.RGBA_8888, 3)
        showFloatingPanel()
        reader?.setOnImageAvailableListener({ src ->
            val img = try { src.acquireLatestImage() } catch (_: Exception) { null }
            if (img != null) { if (busy.compareAndSet(false, true)) process(img) else { img.close() } }
        }, Handler(Looper.getMainLooper()))
        display = projection?.createVirtualDisplay("GameXRay", m.widthPixels, m.heightPixels, m.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, Handler(Looper.getMainLooper()))
        status?.text = if (display == null) "ERROR: capture display did not start" else "Capture ready. Waiting for game screen…"
    }

    private fun process(image: Image) {
        var bitmap: android.graphics.Bitmap? = null
        try {
            frames++
            bitmap = imageToBitmap(image); image.close()
            val input = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(input).addOnSuccessListener { result ->
                scans++
                val lines = mutableListOf<String>()
                for (block in result.textBlocks) for (line in block.lines) {
                    val b = line.boundingBox
                    val y = if (b != null && bitmap!!.height > 0) (b.centerY().toFloat() / bitmap!!.height) else -1f
                    lines.add(line.text.trim())
                    // Keep normalized line geometry for diagnostics and future layout calibration.
                    if (y >= 0f) Unit
                }
                analyze(result, lines)
            }.addOnFailureListener { err ->
                saveStatus("OCR error: ${err.javaClass.simpleName}: ${err.message}")
            }.addOnCompleteListener {
                bitmap?.recycle()
                busy.set(false)
            }
        } catch (e: Exception) {
            try { image.close() } catch (_: Exception) {}
            bitmap?.recycle()
            saveStatus("Capture error: ${e.javaClass.simpleName}: ${e.message}")
            busy.set(false)
        }
    }

    private fun analyze(result: Text, lines: List<String>) {
        val raw = lines.joinToString(" | ").take(4000)
        val normalized = raw.uppercase().replace(Regex("\\s+"), " ")
        // BET LOCKED takes precedence when both labels appear in one OCR frame.
        val stage = when {
            Regex("\\bBET\\s+LOCKED\\b").containsMatchIn(normalized) -> "BET LOCKED"
            Regex("\\bPLACE\\s+BET\\b").containsMatchIn(normalized) -> "PLACE BET"
            else -> "NOT DETECTED"
        }
        // Never treat a bare percentage or an odds ratio as a panel money total.
        fun panelTotal(side: String): String {
            val patterns = listOf(
                Regex("(?i)\\b(?:TOTAL|AMOUNT|BET\\s*AMOUNT)\\s+" + side + "\\b\\s*[:=-]?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)"),
                Regex("(?i)\\b" + side + "\\s+(?:TOTAL|AMOUNT|BET\\s*AMOUNT)\\b\\s*[:=-]?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
            )
            for (line in lines) {
                if (line.contains("%") || Regex("1\\s*[:/]\\s*[0-9]").containsMatchIn(line)) continue
                for (pattern in patterns) {
                    val value = pattern.find(line)?.groupValues?.getOrNull(1)?.replace(",", "")?.toDoubleOrNull()
                    if (value != null && value in 0.01..100000000.0) return formatNum(value)
                }
            }
            return "not detected"
        }
        fun oddsFor(side: String): String {
            val sideIndex = lines.indexOfFirst { Regex("\\b" + side + "\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            val candidates = if (sideIndex >= 0) {
                ((sideIndex - 1).coerceAtLeast(0)..(sideIndex + 2).coerceAtMost(lines.lastIndex)).map { lines[it] }
            } else emptyList()
            val pattern = Regex("(?i)1\\s*[:/]\\s*([0-9]+(?:\\.[0-9]+)?)")
            for (line in candidates) {
                val value = pattern.find(line)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                if (value != null && value in 1.01..1000.0) return formatNum(value)
            }
            return "not detected"
        }
        val andarPanel = lines.filter { it.contains("ANDAR", true) }.joinToString(" / ").take(400)
        val baharPanel = lines.filter { it.contains("BAHAR", true) }.joinToString(" / ").take(400)
        val andarTotal = panelTotal("ANDAR")
        val baharTotal = panelTotal("BAHAR")
        val oddsA = oddsFor("ANDAR")
        val oddsB = oddsFor("BAHAR")
        val dealtLine = lines.firstOrNull { it.contains("CARDS", true) && it.contains("DEALT", true) } ?: ""
        val dealt = Regex("(?i)CARDS\\s+DEALT\\s*[:#-]?\\s*(\\d{1,2})").find(dealtLine)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..52 }?.toString() ?: "not detected"
        val stakeLine = lines.firstOrNull { it.contains("TOTAL STAKE", true) } ?: ""
        val visibleStakeLabel = if (stakeLine.isNotBlank()) "visible label only; not assumed to be personal stake" else "not detected"
        val histLine = lines.filter { it.contains("%") || it.contains("HISTORY", true) || it.contains("LAST", true) }.joinToString(" | ").take(600)
        val percentages = Regex("(?i)ANDAR\\s*([0-9]{1,3}(?:\\.[0-9]+)?)\\s*%.*?BAHAR\\s*([0-9]{1,3}(?:\\.[0-9]+)?)\\s*%").find(histLine)
        val pA = percentages?.groupValues?.get(1)?.toDoubleOrNull()
        val pB = percentages?.groupValues?.get(2)?.toDoubleOrNull()
        val pct = if (pA != null && pB != null && pA in 0.0..100.0 && pB in 0.0..100.0 && abs(pA+pB-100.0)<=2.0) "Andar ${formatNum(pA)}% / Bahar ${formatNum(pB)}%" else "not reliably detected"
        // Require result wording on the same OCR line as the side; ignore promotional winner labels.
        fun explicitWinnerOnLine(line: String): String {
            val u = line.uppercase()
            if (u.contains("BIGGEST WINNER") || u.contains("TOP WINNER")) return "NOT DETECTED"
            if (!Regex("\\b(WINS|WON|WINNER|RESULT)\\b").containsMatchIn(u)) return "NOT DETECTED"
            val hasA = Regex("\\bANDAR\\b").containsMatchIn(u)
            val hasB = Regex("\\bBAHAR\\b").containsMatchIn(u)
            return when {
                hasA && !hasB -> "ANDAR"
                hasB && !hasA -> "BAHAR"
                else -> "NOT DETECTED"
            }
        }
        val winner = lines.asSequence().map { explicitWinnerOnLine(it) }
            .firstOrNull { it != "NOT DETECTED" } ?: "NOT DETECTED"
        updateRoundState(stage, winner, raw)
        val sum = if (andarTotal != "not detected" && baharTotal != "not detected") formatNum(andarTotal.toDouble()+baharTotal.toDouble()) else "not detected"
        val snapshot = "STAGE: $stage\nANDAR PANEL: $andarPanel\nBAHAR PANEL: $baharPanel\nANDAR PANEL TOTAL: $andarTotal\nBAHAR PANEL TOTAL: $baharTotal\nPANEL SUM: $sum\nODDS: Andar 1:$oddsA | Bahar 1:$oddsB\nCARDS DEALT: $dealt\nVISIBLE TOTAL STAKE TEXT: $visibleStakeLabel\nHISTORY PERCENTAGES: $pct\nHISTORY RAW: $histLine\nEXPLICIT WINNER: $winner\nOCR RAW: $raw"
        if (snapshot != lastText) { lastText = snapshot; saveSnapshot(snapshot) }
        prefs.edit().putInt("scan_count", scans).putInt("frames_received", frames).putString("last_status", snapshot).putString("last_text", snapshot).apply()
        status?.post { status?.text = "Frames: $frames | Scans: $scans\n$stage\nAndar: $andarTotal • Bahar: $baharTotal\nOdds: 1:$oddsA / 1:$oddsB\nCards dealt: $dealt\nWinner: $winner" }
    }

    private fun updateRoundState(stage: String, winner: String, raw: String) {
        if (stage == "PLACE BET" && roundPhase != "BETTING") {
            roundPhase = "BETTING"
            roundId = System.currentTimeMillis()
            prefs.edit().putString("round_phase", roundPhase).putLong("active_round_id", roundId).apply()
        }
        if (stage == "BET LOCKED" && roundPhase == "BETTING") {
            roundPhase = "LOCKED"
            prefs.edit().putString("round_phase", roundPhase).apply()
        }
        // Save at most once, and only after BET LOCKED for the active round.
        if (winner != "NOT DETECTED" && roundPhase == "LOCKED" && roundId > 0L) {
            if (prefs.getLong("last_saved_round_id", 0L) == roundId) return
            val rounds = readArray("rounds")
            val personalStake = prefs.getString("my_stake", "") ?: ""
            val side = prefs.getString("my_side", "NONE") ?: "NONE"
            val oddsText = if (side == "ANDAR")
                Regex("(?i)ANDAR[^|\\n]*?1\\s*[:/]\\s*([0-9.]+)").find(raw)?.groupValues?.get(1)
            else if (side == "BAHAR")
                Regex("(?i)BAHAR[^|\\n]*?1\\s*[:/]\\s*([0-9.]+)").find(raw)?.groupValues?.get(1)
            else null
            val stake = personalStake.toDoubleOrNull()
            val odds = oddsText?.toDoubleOrNull()
            val pnl = if (stake != null && stake > 0 && side in listOf("ANDAR", "BAHAR") && odds != null && odds >= 1.0) {
                if (side == winner) stake * (odds - 1.0) else -stake
            } else null
            rounds.put(JSONObject().put("id", roundId).put("time", System.currentTimeMillis())
                .put("winner", winner).put("source", "explicit same-line OCR result")
                .put("mySide", side).put("myStake", personalStake).put("pnl", pnl ?: JSONObject.NULL))
            val keep = JSONArray()
            for (i in (rounds.length() - 499).coerceAtLeast(0) until rounds.length()) keep.put(rounds.getJSONObject(i))
            prefs.edit().putString("rounds", keep.toString()).putInt("round_count", keep.length())
                .putLong("last_saved_round_id", roundId).putString("round_phase", "RESULT").apply()
            roundPhase = "RESULT"
        }
    }

    private fun readArray(key: String) = try { JSONArray(prefs.getString(key, "[]")) } catch (_: Exception) { JSONArray() }
    private fun saveSnapshot(text: String) {
        val old = readArray("history"); val arr = JSONArray()
        for (i in (old.length()-999).coerceAtLeast(0) until old.length()) arr.put(old.getJSONObject(i))
        arr.put(JSONObject().put("time", System.currentTimeMillis()).put("text", text.take(4000)))
        prefs.edit().putString("history", arr.toString()).putInt("history_count", arr.length()).apply()
    }
    private fun saveStatus(message: String) { prefs.edit().putString("last_status", message).apply(); status?.post { status?.text = message } }
    private fun formatNum(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else String.format(java.util.Locale.US, "%.2f", v)
    private fun imageToBitmap(image: Image): android.graphics.Bitmap {
        val p=image.planes[0]; val stride=p.rowStride; val pixel=p.pixelStride
        val paddedWidth=image.width+(stride-pixel*image.width)/pixel
        val padded=android.graphics.Bitmap.createBitmap(paddedWidth,image.height,android.graphics.Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(p.buffer)
        val cropped=android.graphics.Bitmap.createBitmap(padded,0,0,image.width,image.height); padded.recycle(); return cropped
    }

    private fun showFloatingPanel() {
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        windowManager=getSystemService(WINDOW_SERVICE) as WindowManager
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(12,10,12,10); setBackgroundColor(0xEE14212B.toInt()) }
        box.addView(TextView(this).apply { text="GAME X-RAY • LIVE"; textSize=13f; setTextColor(0xFF55DDFF.toInt()) })
        status=TextView(this).apply { text="Screen capture active"; textSize=11f; setTextColor(0xFFFFFFFF.toInt()); maxLines=10 }
        box.addView(status); box.addView(Button(this).apply { text="STOP"; setOnClickListener { stopSelf() } })
        val type=if(Build.VERSION.SDK_INT>=26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val params=WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,type,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SECURE,PixelFormat.TRANSLUCENT).apply { gravity=Gravity.TOP or Gravity.END; x=12; y=120 }
        box.setOnTouchListener(object: android.view.View.OnTouchListener {
            var sx=0; var sy=0; var tx=0f; var ty=0f
            override fun onTouch(v:android.view.View,e:MotionEvent):Boolean { when(e.action){ MotionEvent.ACTION_DOWN->{sx=params.x;sy=params.y;tx=e.rawX;ty=e.rawY;return true}; MotionEvent.ACTION_MOVE->{params.x=sx-(e.rawX-tx).toInt();params.y=sy+(e.rawY-ty).toInt();windowManager?.updateViewLayout(box,params);return true} };return false }
        })
        overlay=box; windowManager?.addView(box,params)
    }
    override fun onDestroy() {
        prefs.edit().putInt("scan_count",scans).putInt("frames_received",frames).putString("last_text",lastText).apply()
        try { overlay?.let { windowManager?.removeView(it) } } catch (_:Exception) {}
        display?.release(); reader?.close(); projection?.stop(); recognizer.close(); super.onDestroy()
    }
    override fun onBind(intent:Intent?):IBinder?=null
}