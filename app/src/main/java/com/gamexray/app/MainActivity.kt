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
import android.widget.TextView

class MainActivity : Activity() {
    private val captureRequest = 4101
    private lateinit var info: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 32, 24, 24) }
        info = TextView(this).apply {
            textSize = 16f
            text = "Game X-Ray starter\n\n1. Allow floating window.\n2. Start screen capture and approve Android's prompt.\n3. Switch to your game. The floating panel will read visible text locally."
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
        layout.addView(info); layout.addView(overlay); layout.addView(capture)
        setContentView(layout)
    }

    @Deprecated("Compatibility callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequest && resultCode == RESULT_OK && data != null) {
            val service = Intent(this, CaptureService::class.java).apply {
                putExtra("resultCode", resultCode); putExtra("resultData", data)
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
            info.text = "Capture started. Switch to the game and use the floating panel."
        } else info.text = "Screen capture permission was not granted."
    }
}
