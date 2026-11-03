package com.gamexray.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val captureRequest = 4101
    private lateinit var info: TextView
    private lateinit var history: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 32, 24, 24) }
        info = TextView(this).apply {
            textSize = 16f
            text = "Game X-Ray\n\n1. Allow floating window.\n2. Start screen capture and approve Android's prompt.\n3. Switch to your game. OCR text changes are saved locally on this phone."
        }
        val overlay = Button(this).apply {
            text = "1. Allow floating window"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else info.text = "Floating window permission is enabled."
            }
        }
        val capture = Button(this).apply {
            text = "2. Start screen capture"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    info.text = "First allow floating window permission."
                    return@setOnClickListener
                }
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(manager.createScreenCaptureIntent(), captureRequest)
            }
        }
        val historyTitle = TextView(this).apply {
            text = "\nSaved scan history (on this phone)"
            textSize = 18f
        }
        history = TextView(this).apply { textSize = 13f; setTextIsSelectable(true) }
        val scroll = ScrollView(this).apply { addView(history) }
        layout.addView(info)
        layout.addView(overlay)
        layout.addView(capture)
        layout.addView(historyTitle)
        layout.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(layout)
        showHistory()
    }

    override fun onResume() {
        super.onResume()
        if (::history.isInitialized) showHistory()
    }

    private fun showHistory() {
        val prefs = getSharedPreferences("game_xray_local", MODE_PRIVATE)
        val scans = prefs.getInt("scan_count", 0)
        val raw = prefs.getString("history", "[]") ?: "[]"
        val items = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        val out = StringBuilder("Total scans: $scans\nSaved text snapshots: ${items.length()}\n")
        val formatter = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        for (i in items.length() - 1 downTo 0) {
            val item = items.optJSONObject(i) ?: continue
            out.append("\n--- ").append(formatter.format(Date(item.optLong("time")))).append(" ---\n")
            out.append(item.optString("text").take(600)).append("\n")
        }
        if (items.length() == 0) out.append("\nNo saved scans yet. Start capture and let OCR detect changing text.")
        history.text = out.toString()
    }

    @Deprecated("Compatibility callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequest && resultCode == RESULT_OK && data != null) {
            val service = Intent(this, CaptureService::class.java).apply {
                putExtra("resultCode", resultCode); putExtra("resultData", data)
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
            info.text = "Capture started. Switch to the game. Changed OCR text is saved locally."
        } else info.text = "Screen capture permission was not granted."
    }
}
