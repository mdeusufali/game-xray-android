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
import android.os.IBinder
import android.os.Handler
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
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.android.gms.tasks.Tasks
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var overlay: LinearLayout? = null
    private var windowManager: WindowManager? = null
    private var status: TextView? = null
    private var scans = 0
    private var framesReceived = 0
    private var lastText = ""
    private val busy = AtomicBoolean(false)
    private val prefs by lazy { getSharedPreferences("game_xray_local", Context.MODE_PRIVATE) }
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channel = NotificationChannel("gx", "Game X-Ray capture", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        startForeground(7, Notification.Builder(this, "gx")
            .setContentTitle("Game X-Ray is capturing")
            .setContentText("Screen capture is active until you stop it")
            .setSmallIcon(android.R.drawable.ic_menu_view).build())

        val code = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("resultData")
        if (code == Activity.RESULT_OK && data != null) beginCapture(code, data)
        return START_NOT_STICKY
    }

    private fun beginCapture(code: Int, data: Intent) {
        scans = prefs.getInt("scan_count", 0)
        framesReceived = prefs.getInt("frames_received", 0)
        lastText = prefs.getString("last_text", "") ?: ""
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val density = metrics.densityDpi
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, null)
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        showFloatingPanel()
        status?.text = "Starting display capture...\nScreen: ${w}x${h}"
        reader?.setOnImageAvailableListener({ source ->
            val image = try { source.acquireLatestImage() } catch (e: Exception) {
                status?.post { status?.text = "Frame read error: ${e.message}" }
                null
            }
            if (image != null) {
                if (busy.compareAndSet(false, true)) readFrame(image) else {
                    image.close()
                    status?.post { status?.text = "Frames received: $framesReceived\nOCR busy; waiting for next frame..." }
                }
            } else {
                status?.post { status?.text = "Waiting for screen frames...\nIf this remains, Android did not deliver a frame." }
            }
        }, Handler(Looper.getMainLooper()))
        display = projection?.createVirtualDisplay(
            "GameXRay", w, h, density, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface, null, Handler(Looper.getMainLooper())
        )
        if (display == null) {
            status?.text = "ERROR: VirtualDisplay could not start"
        } else {
            status?.text = "Capture connected. Waiting for first frame...\nScreen: ${w}x${h}"
        }
    }

    private fun readFrame(image: Image) {
        try {
            framesReceived++
            val frameWidth = image.width
            val frameHeight = image.height
            status?.post { status?.text = "Frames received: $framesReceived\nFrame: ${frameWidth}x${frameHeight}\nOCR processing..." }
            prefs.edit().putInt("frames_received", framesReceived)
                .putString("last_status", "Frames received: $framesReceived | OCR processing")
                .apply()
            val bitmap = imageToBitmap(image)
            image.close()
            // Coordinates are proportional to the captured screen. These boxes match
            // the supplied 691x1536 QueenMaker mobile layout; they are OCR zones, not
            // computer-vision proof that a card/chip has been identified.
            val w = bitmap.width
            val h = bitmap.height
            fun crop(l: Float, t: Float, r: Float, b: Float): android.graphics.Bitmap {
                val x = (w * l).toInt().coerceIn(0, w - 1)
                val y = (h * t).toInt().coerceIn(0, h - 1)
                val right = (w * r).toInt().coerceIn(x + 1, w)
                val bottom = (h * b).toInt().coerceIn(y + 1, h)
                return android.graphics.Bitmap.createBitmap(bitmap, x, y, right - x, bottom - y)
            }
            val zones = listOf(
                "CARDS" to crop(0.42f, 0.25f, 0.96f, 0.40f),
                "ANDAR_BAHAR" to crop(0.02f, 0.44f, 0.98f, 0.68f),
                "DEALT" to crop(0.28f, 0.78f, 0.76f, 0.84f),
                "STATS" to crop(0.02f, 0.90f, 0.98f, 0.98f)
            )
            val tasks = zones.map { (_, zone) -> recognizer.process(InputImage.fromBitmap(zone, 0)) }
            Tasks.whenAllSuccess<com.google.mlkit.vision.text.Text>(tasks)
                .addOnSuccessListener { results ->
                    scans++
                    val sections = zones.indices.joinToString("\n") { i ->
                        "${zones[i].first}: ${results[i].text.replace("\n", " ").take(280)}"
                    }
                    val full = "REGION OCR (raw; verify visually)\n$sections"
                    val dealt = Regex("(?i)CARDS DEALT\\s*[:#]?\\s*(\\d{1,2})")
                        .find(sections)?.groupValues?.getOrNull(1) ?: "not read"
                    val cards = results[0].text.replace("\n", " ").take(180)
                    val andarBahar = results[1].text.replace("\n", " ").take(220)
                    val summary = "Scans: $scans\nSaved text snapshots: ${prefs.getInt("history_count", 0)} / 50" +
                        "\nCards area (raw): $cards\nCards dealt: $dealt\nAndar/Bahar area (raw): $andarBahar"
                    prefs.edit().putInt("scan_count", scans).apply()
                    if (full != lastText) {
                        lastText = full
                        saveTextSnapshot(full)
                    }
                    val liveSummary = "Frames received: $framesReceived\n$summary"
                    prefs.edit().putString("last_status", liveSummary).apply()
                    status?.post { status?.text = liveSummary }
                }
                .addOnFailureListener {
                    status?.post { status?.text = "Scans: $scans\nRegion OCR error: ${it.message}" }
                }
                .addOnCompleteListener {
                    zones.forEach { it.second.recycle() }
                    bitmap.recycle()
                    busy.set(false)
                }
        } catch (e: Exception) {
            try { image.close() } catch (_: Exception) {}
            val errorText = "Frames received: $framesReceived\nCapture error: ${e.javaClass.simpleName}: ${e.message}"
            prefs.edit().putString("last_status", errorText).apply()
            status?.post { status?.text = errorText }
            busy.set(false)
        }
    }

    private fun saveTextSnapshot(text: String) {
        val old = try { JSONArray(prefs.getString("history", "[]")) } catch (_: Exception) { JSONArray() }
        val entry = JSONObject().put("time", System.currentTimeMillis()).put("text", text.take(1200))
        val updated = JSONArray()
        // Keep the newest 49 old entries, then append the new one: maximum 50.
        val start = (old.length() - 49).coerceAtLeast(0)
        for (i in start until old.length()) updated.put(old.getJSONObject(i))
        updated.put(entry)
        prefs.edit()
            .putString("history", updated.toString())
            .putInt("history_count", updated.length())
            .putString("last_text", text)
            .apply()
    }

    private fun imageToBitmap(image: Image): android.graphics.Bitmap {
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val paddedWidth = image.width + (rowStride - pixelStride * image.width) / pixelStride
        val padded = android.graphics.Bitmap.createBitmap(paddedWidth, image.height, android.graphics.Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        val cropped = android.graphics.Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return cropped
    }

    private fun showFloatingPanel() {
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 10, 12, 10)
            setBackgroundColor(0xEE14212B.toInt())
        }
        val heading = TextView(this).apply { text = "GAME X-RAY • LIVE"; textSize = 13f; setTextColor(0xFF55DDFF.toInt()) }
        status = TextView(this).apply { text = "Screen capture active"; textSize = 11f; setTextColor(0xFFFFFFFF.toInt()); maxLines = 12 }
        val stop = Button(this).apply { text = "STOP"; setOnClickListener { stopSelf() } }
        box.addView(heading); box.addView(status); box.addView(stop)

        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SECURE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 12; y = 120 }

        box.setOnTouchListener(object : android.view.View.OnTouchListener {
            var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
            override fun onTouch(v: android.view.View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> { sx = params.x; sy = params.y; tx = event.rawX; ty = event.rawY; return true }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = sx - (event.rawX - tx).toInt()
                        params.y = sy + (event.rawY - ty).toInt()
                        windowManager?.updateViewLayout(box, params); return true
                    }
                }
                return false
            }
        })
        overlay = box
        windowManager?.addView(box, params)
    }

    override fun onDestroy() {
        prefs.edit().putInt("scan_count", scans).putInt("frames_received", framesReceived)
            .putString("last_text", lastText).apply()
        try { overlay?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        display?.release()
        reader?.close()
        projection?.stop()
        recognizer.close()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
