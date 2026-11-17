package com.gamexray.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
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
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.gamexray.app.core.Accounting
import com.gamexray.app.core.BettingStage
import com.gamexray.app.core.CardShapeDetector
import com.gamexray.app.core.CardZoneLocator
import com.gamexray.app.core.FrameAnalysis
import com.gamexray.app.core.FrameEngine
import com.gamexray.app.core.ManagedRound
import com.gamexray.app.core.RoundStage
import com.gamexray.app.core.Side
import com.gamexray.app.core.VisualRegionAnalyzer
import com.gamexray.app.core.WinnerState
import com.gamexray.app.data.GameXRayStore
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var overlay: LinearLayout? = null
    private var windowManager: WindowManager? = null
    private var status: TextView? = null
    private var scans = 0
    private var frames = 0
    private var lastProcessAt = 0L
    private var lastPersistedHash = ""
    private var lastPersistAt = 0L
    private var lastLoggedKey = ""
    private var scanIntervalMs = 900L
    private var stopping = false
    private val busy = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var store: GameXRayStore
    private lateinit var engine: FrameEngine
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override fun onCreate() {
        super.onCreate()
        store = GameXRayStore(this)
        engine = FrameEngine()
        val active = store.loadActive()
        if (active != null) {
            engine.restore(active, store.loadCardState())
        }
        scans = store.counts().first
        frames = store.counts().second
        lastPersistedHash = store.lastSnapshotHash()
        lastPersistAt = store.lastSnapshotTime()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Game X-Ray is capturing")
            .setContentText("Local screen OCR and card tracking active")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (projection != null) return START_NOT_STICKY
        val code = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("resultData")
        if (code == Activity.RESULT_OK && data != null) {
            beginCapture(code, data)
        } else {
            store.appendStatus("Capture permission was not granted; service stopped.")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun beginCapture(code: Int, data: Intent) {
        try {
            val metrics = resources.displayMetrics
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(code, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    if (!stopping) stopSelf()
                }
            }, mainHandler)

            reader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 3)
            showFloatingPanel()
            reader?.setOnImageAvailableListener({ source ->
                val image = try {
                    source.acquireLatestImage()
                } catch (_: Exception) {
                    null
                } ?: return@setOnImageAvailableListener
                frames++
                val now = SystemClock.elapsedRealtime()
                if (now - lastProcessAt < scanIntervalMs) {
                    image.close()
                    return@setOnImageAvailableListener
                }
                if (!busy.compareAndSet(false, true)) {
                    image.close()
                    return@setOnImageAvailableListener
                }
                lastProcessAt = now
                executor.execute { processImage(image) }
            }, mainHandler)

            display = projection?.createVirtualDisplay(
                "GameXRay",
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader?.surface,
                null,
                mainHandler
            )
            if (display == null) {
                store.appendStatus("ERROR: capture display did not start")
                updateOverlay("ERROR: capture display did not start")
                stopSelf()
            } else {
                updateOverlay("Capture ready. Waiting for the game screen…")
            }
        } catch (e: Exception) {
            store.appendStatus("Capture start error: ${e.javaClass.simpleName}: ${e.message}")
            updateOverlay("Capture start error: ${e.message}")
            stopSelf()
        }
    }

    private fun processImage(image: Image) {
        val bitmaps = mutableListOf<Bitmap>()
        var imageClosed = false
        try {
            val base = imageToBitmap(image)
            image.close()
            imageClosed = true
            bitmaps.add(base)
            val timestamp = System.currentTimeMillis()

            val ocrBitmap = scaleFor(base, 1000, bitmaps)
            val text = try {
                Tasks.await(
                    recognizer.process(InputImage.fromBitmap(ocrBitmap, 0)),
                    25,
                    TimeUnit.SECONDS
                )
            } catch (e: Exception) {
                if (!stopping && !isCancellation(e)) {
                    store.appendStatus("OCR error: ${e.javaClass.simpleName}: ${e.message}")
                }
                return
            }
            // MediaProjection also captures Game X-Ray itself. Frames showing our own settings or
            // diagnostics must never feed the parser or the round state machine.
            if (AppForeground.appActivityResumed || isOwnUiText(text.text)) return
            scans++
            val ocrFrame = AndroidVision.ocrFrame(text, ocrBitmap.width, ocrBitmap.height, timestamp)

            val cvBitmap = scaleFor(base, 480, bitmaps)
            val cvFrame = AndroidVision.imageFrame(cvBitmap)
            val parsed = engine.parser.parse(ocrFrame)
            val manualZones = store.manualCardZones()
            val zones = CardZoneLocator.locate(parsed, manualZones)
            val candidates = CardShapeDetector.detect(cvFrame, zones)
            val visualStats = VisualRegionAnalyzer.analyze(cvFrame, zones)
            val settings = store.settings()
            val analysis = engine.process(ocrFrame, candidates, visualStats, settings, manualZones)

            persist(analysis)
        } catch (e: Exception) {
            if (!stopping && !isCancellation(e)) {
                store.appendStatus("Capture error: ${e.javaClass.simpleName}: ${e.message}")
                updateOverlay("Capture error: ${e.message}")
            }
        } finally {
            if (!imageClosed) {
                try {
                    image.close()
                } catch (_: Exception) {
                    // already closed
                }
            }
            for (bitmap in bitmaps.distinct()) {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            store.setCounters(scans, frames)
            busy.set(false)
        }
    }

    private fun scaleFor(base: Bitmap, maxWidth: Int, sink: MutableList<Bitmap>): Bitmap {
        val (scaled, created) = AndroidVision.scaled(base, maxWidth)
        if (created) sink.add(scaled)
        return scaled
    }

    private fun persist(analysis: FrameAnalysis) {
        store.setCounters(scans, frames)
        val overlayText = buildOverlayText(analysis)
        val round = analysis.round
        if (round != null) {
            store.saveActive(round)
            scanIntervalMs = if (round.stage == RoundStage.BETTING_LOCKED ||
                round.stage == RoundStage.CARDS_DEALT ||
                round.stage == RoundStage.RESULT_DETECTED ||
                round.stage == RoundStage.WINNER_CONFIRMED
            ) 650L else 900L
        }
        store.saveCardState(analysis.cards)

        val completed = analysis.roundUpdate.completed
        if (completed != null) {
            val saved = store.appendRound(completed)
            if (saved) {
                store.appendStatus(
                    "Round ${completed.id} completed: winner ${completed.winner.label}, " +
                        "My ${completed.mySide.label} stake=${completed.myStake ?: "n/a"}, " +
                        "net=${completed.netProfit?.let { formatMoney(it) } ?: "unavailable"}"
                )
                lastLoggedKey = ""
            }
        }

        // Rounds closed on a genuine new-round boundary without a financial result are still
        // kept once in history, clearly marked unsettled (candidate/conflict preserved).
        for (record in analysis.roundUpdate.finalizedRecords) {
            val saved = store.appendRound(record)
            if (saved) {
                store.appendStatus(
                    "Round ${record.id} closed unsettled: ${record.detectionStatus} " +
                        "(winner=${record.winnerState}:${record.winner.label})"
                )
                lastLoggedKey = ""
            }
        }

        val shouldPersist = analysis.snapshotHash != lastPersistedHash ||
            analysis.ocr.timestamp - lastPersistAt >= 5000L
        store.saveAnalysis(analysis, overlayText, shouldPersist)
        if (shouldPersist) {
            lastPersistedHash = analysis.snapshotHash
            lastPersistAt = analysis.ocr.timestamp
        }

        val logKey = buildString {
            append(analysis.parsed.bettingStage).append('|')
            append(round?.id).append('|').append(round?.stage).append('|')
            append(round?.andarCards).append('|').append(round?.baharCards).append('|')
            append(round?.andarMoney?.current).append('|').append(round?.baharMoney?.current).append('|')
            append(analysis.winner.state).append('|').append(analysis.winner.side)
        }
        if (logKey != lastLoggedKey) {
            lastLoggedKey = logKey
            val stage = round?.stage?.name ?: "IDLE"
            store.appendStatus(
                "Stage=$stage betting=${analysis.parsed.bettingStage} round=${round?.id ?: 0} " +
                    "cards A=${round?.andarCards ?: 0} B=${round?.baharCards ?: 0} " +
                    "winner=${analysis.winner.state}:${analysis.winner.side.label}"
            )
        }
        updateOverlay(overlayText)
    }

    private fun buildOverlayText(analysis: FrameAnalysis): String {
        val round = analysis.round
        val winner = analysis.winner
        val settlement = round?.let {
            Accounting.settle(
                side = it.bet?.side ?: Side.UNKNOWN,
                stake = it.bet?.stake,
                odds = it.bet?.odds,
                winner = it.winnerSide.takeIf { side -> side != Side.UNKNOWN },
                winnerState = it.winnerState
            )
        }
        fun money(value: Double?): String =
            if (value == null) "NOT DETECTED" else formatMoney(value)

        return buildString {
            append("Round ").append(round?.id ?: 0).append(" • ").append(round?.stage?.name ?: "IDLE").append('\n')
            append("Cards A=").append(round?.andarCards ?: 0)
                .append(" B=").append(round?.baharCards ?: 0)
                .append(" total=").append(round?.totalCards ?: 0)
                .append(" (CV lower bound)")
                .append(" | OCR dealt=").append(round?.cardsDealtOcr ?: "NOT DETECTED").append('\n')
            append("Andar ").append(money(round?.andarMoney?.current))
                .append(if (round?.andarMoney?.status == com.gamexray.app.core.DetectionStatus.DETECTED) "" else " (${round?.andarMoney?.status?.label ?: "NOT DETECTED"})")
                .append(" • Bahar ").append(money(round?.baharMoney?.current)).append('\n')
            append("Odds A ").append(round?.andarOdds?.let { "1:$it" } ?: "NOT DETECTED")
                .append(" • B ").append(round?.baharOdds?.let { "1:$it" } ?: "NOT DETECTED").append('\n')
            append("My ").append(round?.bet?.side?.label ?: "NOT SET")
                .append(" stake ").append(round?.bet?.stake?.let { formatMoney(it) } ?: "not set")
                .append(" odds ").append(round?.bet?.odds?.let { "1:$it" } ?: "not set").append('\n')
            append("Winner: ").append(winner.side.label)
                .append(" (").append(winner.state.name).append(")\n")
            if (settlement?.calculated == true) {
                append(if (settlement.isWin == true) "WIN" else "LOSS")
                    .append(" net ").append(formatSigned(settlement.netProfit ?: 0.0))
            } else {
                append("P/L unavailable")
            }
            append("\nFrames ").append(frames).append(" • Scans ").append(scans)
        }
    }

    private fun updateOverlay(text: String) {
        status?.post {
            status?.text = text
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL, "Game X-Ray capture", NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
        }
    }

    private fun showFloatingPanel() {
        if (!Settings.canDrawOverlays(this)) {
            store.appendStatus("Floating window permission missing; cannot show overlay.")
            stopSelf()
            return
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 12, 14, 12)
            setBackgroundColor(0xEE14212B.toInt())
        }
        box.addView(TextView(this).apply {
            text = "GAME X-RAY • LIVE"
            textSize = 13f
            setTextColor(0xFF55DDFF.toInt())
        })
        status = TextView(this).apply {
            text = "Screen capture active"
            textSize = 11f
            setTextColor(0xFFFFFFFF.toInt())
            maxLines = 18
        }
        box.addView(status)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "DASHBOARD"
            textSize = 11f
            setOnClickListener {
                val intent = Intent(this@CaptureService, MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(intent)
            }
        })
        buttons.addView(Button(this).apply {
            text = "STOP"
            textSize = 11f
            setOnClickListener { stopSelf() }
        })
        box.addView(buttons)

        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 12
            y = 120
        }
        box.setOnTouchListener(object : android.view.View.OnTouchListener {
            var startX = 0
            var startY = 0
            var touchX = 0f
            var touchY = 0f
            override fun onTouch(v: android.view.View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = startX - (event.rawX - touchX).toInt()
                        params.y = startY + (event.rawY - touchY).toInt()
                        windowManager?.updateViewLayout(box, params)
                        return true
                    }
                }
                return false
            }
        })
        overlay = box
        windowManager?.addView(box, params)
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val stride = plane.rowStride
        val pixelStride = plane.pixelStride
        val paddedWidth = image.width + (stride - pixelStride * image.width) / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        if (paddedWidth == image.width) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return cropped
    }

    private fun formatMoney(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString()
        else String.format(Locale.US, "%.2f", value)

    /** Extra guard for the case where the lifecycle flag is stale (e.g. an overlaying Activity). */
    private fun isOwnUiText(raw: String): Boolean {
        val u = raw.uppercase(Locale.US)
        val markers = arrayOf(
            "MY BET SETTINGS",
            "CARD ZONES",
            "LATEST RAW OCR TEXT",
            "DIAGNOSTIC LOG",
            "KNOWN LIMITATION",
            "SAVE MY BET SETTINGS"
        )
        return markers.count { u.contains(it) } >= 2
    }

    /** ML Kit reports "Task is already canceled" while capture is being stopped on purpose. */
    private fun isCancellation(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is java.util.concurrent.CancellationException) return true
            val text = (current.javaClass.simpleName + " " + (current.message ?: "")).lowercase(Locale.US)
            if (text.contains("cancel")) return true
            if (current.cause === current) break
            current = current.cause
        }
        return false
    }

    private fun formatSigned(value: Double): String {
        val prefix = if (value > 0) "+" else ""
        return prefix + formatMoney(value)
    }

    override fun onDestroy() {
        stopping = true
        try {
            engine.currentRound()?.let { store.saveActive(it) }
            store.saveCardState(engine.cardCounter.exportForPersistence())
            store.setCounters(scans, frames)
            store.appendStatus("Capture stopped.")
        } catch (_: Exception) {
            // Avoid blocking shutdown on persistence errors.
        }
        try {
            overlay?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
            // overlay already removed
        }
        try {
            display?.release()
        } catch (_: Exception) {
            // ignore
        }
        try {
            reader?.close()
        } catch (_: Exception) {
            // ignore
        }
        try {
            projection?.stop()
        } catch (_: Exception) {
            // ignore
        }
        try {
            recognizer.close()
        } catch (_: Exception) {
            // ignore
        }
        executor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "gx_capture"
        private const val NOTIFICATION_ID = 7
    }
}
