package com.gamexray.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
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
    private lateinit var dashboard: TextView
    private lateinit var history: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(20,24,20,20)}
        val scroll=ScrollView(this)
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val title=TextView(this).apply{text="GAME X-RAY";textSize=25f;setPadding(0,0,0,12)}
        info=TextView(this).apply{textSize=14f;text="Local screen analysis • no API, WebSocket or web history"}
        dashboard=TextView(this).apply{textSize=15f;setTextIsSelectable(true);setPadding(0,16,0,16)}
        val overlay=Button(this).apply{text="1. Allow floating window";setOnClickListener{
            if(!Settings.canDrawOverlays(this@MainActivity)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))
            else info.text="Floating window permission is enabled."
        }}
        val capture=Button(this).apply{text="2. Start screen capture";setOnClickListener{
            if(!Settings.canDrawOverlays(this@MainActivity)){info.text="First allow floating window permission.";return@setOnClickListener}
            val manager=getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(manager.createScreenCaptureIntent(),captureRequest)
        }}
        val refresh=Button(this).apply{text="Refresh dashboard / history";setOnClickListener{showHistory()}}
        val hTitle=TextView(this).apply{text="\nSAVED SNAPSHOTS & ROUND HISTORY";textSize=18f}
        history=TextView(this).apply{textSize=13f;setTextIsSelectable(true)}
        body.addView(title);body.addView(info);body.addView(overlay);body.addView(capture);body.addView(refresh)
        body.addView(TextView(this).apply{text="\nLIVE ANALYSIS DASHBOARD";textSize=18f})
        body.addView(dashboard);body.addView(hTitle);body.addView(history)
        scroll.addView(body)
        root.addView(scroll,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
        setContentView(root)
        showHistory()
    }

    override fun onResume(){super.onResume();if(::history.isInitialized)showHistory()}

    private fun showHistory(){
        val prefs=getSharedPreferences("game_xray_local",MODE_PRIVATE)
        val scans=prefs.getInt("scan_count",0)
        val frames=prefs.getInt("frames_received",0)
        val snapshots=try{JSONArray(prefs.getString("history","[]"))}catch(_:Exception){JSONArray()}
        val newSnapshots=try{JSONArray(prefs.getString("snapshots","[]"))}catch(_:Exception){JSONArray()}
        val rounds=try{JSONArray(prefs.getString("rounds","[]"))}catch(_:Exception){JSONArray()}
        val status=prefs.getString("last_status","No OCR data yet") ?: "No OCR data yet"
        dashboard.text="Scans: $scans\nFrames received: $frames\nSaved snapshots: ${maxOf(snapshots.length(),newSnapshots.length())} / 1000\nSaved result records: ${rounds.length()} / 500\n\n$status\n\nImportant: historical percentages describe past displayed data; they are not future win probabilities. Winner and P/L are not predictions."
        val out=StringBuilder("LOCAL DATA ONLY\n")
        val formatter=SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.getDefault())
        out.append("\nROUND RESULTS (explicit OCR result text only)\n")
        for(i in rounds.length()-1 downTo 0){
            val item=rounds.optJSONObject(i)?:continue
            out.append(formatter.format(Date(item.optLong("time")))).append(" • ").append(item.optString("winner")).append("\n")
        }
        if(rounds.length()==0)out.append("No confirmed winner text stored.\n")
        out.append("\nLATEST OCR SNAPSHOTS\n")
        val source=if(newSnapshots.length()>0)newSnapshots else snapshots
        for(i in source.length()-1 downTo maxOf(0,source.length()-30)){
            val item=source.optJSONObject(i)?:continue
            out.append("\n--- ").append(formatter.format(Date(item.optLong("time")))).append(" ---\n")
            out.append(item.optString("summary",item.optString("text")).take(900)).append("\n")
        }
        history.text=out.toString()
    }

    @Deprecated("Compatibility callback")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==captureRequest&&resultCode==RESULT_OK&&data!=null){
            val service=Intent(this,CaptureService::class.java).apply{putExtra("resultCode",resultCode);putExtra("resultData",data)}
            if(Build.VERSION.SDK_INT>=26)startForegroundService(service) else startService(service)
            info.text="Capture started. Switch to the game; OCR is processed on this phone."
        }else info.text="Screen capture permission was not granted."
    }
}