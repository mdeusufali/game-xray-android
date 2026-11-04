package com.gamexray.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val captureRequest=4101
    private lateinit var info:TextView
    private lateinit var dashboard:TextView
    private lateinit var history:TextView
    private lateinit var stakeInput:EditText
    private lateinit var sideSpinner:Spinner

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs=getSharedPreferences("game_xray_local",MODE_PRIVATE)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(18,20,18,18) }
        val scroll=ScrollView(this)
        val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        body.addView(TextView(this).apply { text="GAME X-RAY"; textSize=26f; setPadding(0,0,0,8) })
        info=TextView(this).apply { text="Local screen OCR • no Network API, WebSocket or web history"; textSize=13f }
        body.addView(info)
        body.addView(Button(this).apply { text="1. Allow floating window"; setOnClickListener {
            if(!Settings.canDrawOverlays(this@MainActivity)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))
            else info.text="Floating window permission enabled."
        }})
        body.addView(Button(this).apply { text="2. Start screen capture"; setOnClickListener {
            if(!Settings.canDrawOverlays(this@MainActivity)){info.text="First allow floating window permission.";return@setOnClickListener}
            val mgr=getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mgr.createScreenCaptureIntent(),captureRequest)
        }})
        body.addView(TextView(this).apply { text="\nMY BET SETTINGS"; textSize=18f })
        body.addView(TextView(this).apply { text="নিজের বাজির দিক নির্বাচন করুন (পাবলিক প্যানেল টোটাল নয়):"; textSize=13f })
        sideSpinner=Spinner(this).apply { adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("NONE","ANDAR","BAHAR")); setSelection(listOf("NONE","ANDAR","BAHAR").indexOf(prefs.getString("my_side","NONE"))) }
        body.addView(sideSpinner)
        stakeInput=EditText(this).apply { hint="নিজের Stake amount"; inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL; setText(prefs.getString("my_stake","") ?: "") }
        body.addView(stakeInput)
        body.addView(Button(this).apply { text="Save my bet settings"; setOnClickListener {
            val amount=stakeInput.text.toString().trim().toDoubleOrNull()
            if(sideSpinner.selectedItem.toString()!="NONE" && (amount==null || amount<=0.0)){info.text="Stake amount valid number হতে হবে।";return@setOnClickListener}
            prefs.edit().putString("my_side",sideSpinner.selectedItem.toString()).putString("my_stake",if(amount!=null) amount.toString() else "").apply()
            info.text="My bet settings saved. P/L only recorded when winner and odds are explicitly readable."
        }})
        body.addView(Button(this).apply { text="Refresh dashboard / history"; setOnClickListener { showData() } })
        body.addView(TextView(this).apply { text="\nLIVE DASHBOARD"; textSize=18f })
        dashboard=TextView(this).apply { textSize=14f; setTextIsSelectable(true); setPadding(0,10,0,16) }
        body.addView(dashboard)
        body.addView(TextView(this).apply { text="SAVED HISTORY & DIAGNOSTIC LOG"; textSize=18f })
        history=TextView(this).apply { textSize=12f; setTextIsSelectable(true) }
        body.addView(history)
        scroll.addView(body)
        root.addView(scroll,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
        setContentView(root)
        showData()
    }

    override fun onResume(){super.onResume();if(::history.isInitialized)showData()}

    private fun showData(){
        val p=getSharedPreferences("game_xray_local",MODE_PRIVATE)
        val snapshots=readArray(p.getString("history","[]"))
        val rounds=readArray(p.getString("rounds","[]"))
        val scans=p.getInt("scan_count",0); val frames=p.getInt("frames_received",0)
        val side=p.getString("my_side","NONE") ?: "NONE"; val stake=p.getString("my_stake","") ?: ""
        val winners=rounds.length()
        var andar=0; var bahar=0; var pnl=0.0; var pnlCount=0
        for(i in 0 until rounds.length()){
            val r=rounds.optJSONObject(i) ?: continue
            when(r.optString("winner")){"ANDAR"->andar++;"BAHAR"->bahar++}
            if(!r.isNull("pnl")){pnl+=r.optDouble("pnl");pnlCount++}
        }
        val coverage=if(winners>0) "Recorded outcomes: $winners (not necessarily 200)" else "No confirmed outcomes yet"
        val sampleStart=(rounds.length()-200).coerceAtLeast(0)
        var andar200=0; var bahar200=0
        for(i in sampleStart until rounds.length()){
            when(rounds.optJSONObject(i)?.optString("winner")){"ANDAR"->andar200++;"BAHAR"->bahar200++}
        }
        val sampleCount=andar200+bahar200
        val stats=if(sampleCount>0) "Last $sampleCount confirmed saved rounds (max 200): Andar $andar200 (${String.format(Locale.US, "%.1f", andar200*100.0/sampleCount)}%) / Bahar $bahar200 (${String.format(Locale.US, "%.1f", bahar200*100.0/sampleCount)}%)" else "Last-200 statistics: unavailable until confirmed results are captured"
        val last=p.getString("last_status","No OCR data yet") ?: "No OCR data yet"
        dashboard.text="Capture frames: $frames\nOCR scans: $scans\nSnapshots retained: ${snapshots.length()} / 1000\nRound records retained: $winners / 500\nMy selected side: $side\nMy stake: ${if(stake.isBlank()) "not set" else stake}\n$coverage\n$stats\nP/L: ${if(pnlCount>0) String.format(Locale.US,"%.2f (from $pnlCount fully calculated rounds)",pnl) else "not calculated — confirmed winner + my side/stake + readable odds required"}\n\nLATEST OCR DASHBOARD\n$last\n\nNote: OCR-visible history percentages are shown only when validated. No prediction or guaranteed win claim."
        val fmt=SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.getDefault())
        val out=StringBuilder("ROUND HISTORY (newest first)\n")
        for(i in rounds.length()-1 downTo 0){
            val r=rounds.optJSONObject(i)?:continue
            out.append(fmt.format(Date(r.optLong("time")))).append(" • ").append(r.optString("winner"))
            out.append(" • my bet ").append(r.optString("mySide","NONE")).append(" • stake ").append(r.optString("myStake","not set"))
            if(!r.isNull("pnl"))out.append(" • P/L ").append(r.optDouble("pnl"))
            else out.append(" • P/L unavailable")
            out.append("\n")
        }
        if(rounds.length()==0)out.append("No explicit winner result captured yet.\n")
        out.append("\nLATEST 30 OCR SNAPSHOTS (up to 1000 retained)\n")
        for(i in snapshots.length()-1 downTo (snapshots.length()-30).coerceAtLeast(0)){
            val s=snapshots.optJSONObject(i)?:continue
            out.append("\n--- ").append(fmt.format(Date(s.optLong("time")))).append(" ---\n")
            out.append(s.optString("text").take(1600)).append("\n")
        }
        history.text=out.toString()
    }

    private fun readArray(s:String?)=try{JSONArray(s ?: "[]")}catch(_:Exception){JSONArray()}

    @Deprecated("Compatibility callback")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==captureRequest && resultCode==RESULT_OK && data!=null){
            val service=Intent(this,CaptureService::class.java).apply{putExtra("resultCode",resultCode);putExtra("resultData",data)}
            if(Build.VERSION.SDK_INT>=26)startForegroundService(service) else startService(service)
            info.text="Capture started. Switch to the game screen; OCR runs locally."
        } else info.text="Screen capture permission was not granted."
    }
}