package com.gamexray.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.gamexray.app.core.Accounting
import com.gamexray.app.core.BetSettings
import com.gamexray.app.core.Box
import com.gamexray.app.core.CardZones
import com.gamexray.app.core.DetectionStatus
import com.gamexray.app.core.ManagedRound
import com.gamexray.app.core.MoneyPanelState
import com.gamexray.app.core.OverallStats
import com.gamexray.app.core.RoundRecord
import com.gamexray.app.core.RoundStage
import com.gamexray.app.core.Side
import com.gamexray.app.core.WinnerState
import com.gamexray.app.data.GameXRayStore
import com.gamexray.app.data.JsonCodec
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private val captureRequest = 4101
    private lateinit var store: GameXRayStore
    private lateinit var info: TextView
    private lateinit var dashboard: TextView
    private lateinit var historyView: TextView
    private lateinit var diagnosticsView: TextView
    private lateinit var stakeInput: EditText
    private lateinit var oddsInput: EditText
    private lateinit var andarZoneInput: EditText
    private lateinit var baharZoneInput: EditText
    private lateinit var sideSpinner: Spinner
    private lateinit var dashboardButton: Button
    private lateinit var historyButton: Button
    private lateinit var diagnosticsButton: Button
    private val handler = Handler(Looper.getMainLooper())
    private var mode = MODE_DASHBOARD
    private var refreshPosted = false

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            render()
            refreshPosted = true
            handler.postDelayed(this, if (mode == MODE_DIAGNOSTICS) 5000L else 2500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = GameXRayStore(this)
        val prefs = store.rawPrefs()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 20, 18, 18)
        }
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        body.addView(TextView(this).apply {
            text = "GAME X-RAY"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, 6)
        })
        info = TextView(this).apply {
            text = "Full-screen local OCR + on-device card/visual analysis. No Network API, WebSocket or web history."
            textSize = 13f
            setPadding(0, 0, 0, 8)
        }
        body.addView(info)

        val captureRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        captureRow.addView(Button(this).apply {
            text = "1. Overlay permission"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                } else {
                    info.text = "Floating window permission enabled."
                }
            }
        })
        captureRow.addView(Button(this).apply {
            text = "2. Start capture"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    info.text = "First allow the floating window permission."
                    return@setOnClickListener
                }
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(manager.createScreenCaptureIntent(), captureRequest)
            }
        })
        captureRow.addView(Button(this).apply {
            text = "Stop"
            setOnClickListener {
                stopService(Intent(this@MainActivity, CaptureService::class.java))
                info.text = "Capture stop requested."
            }
        })
        body.addView(captureRow)

        body.addView(TextView(this).apply {
            text = "\nMY BET SETTINGS\nনিজের বাজির দিক, stake ও odds দিন (পাবলিক প্যানেল টোটাল নয়)।\nSettings betting lock-এ freeze হয়; পরে বদলালে পুরোনো round বদলাবে না।"
            textSize = 14f
        })
        val sides = listOf("NONE", "ANDAR", "BAHAR")
        sideSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, sides)
            setSelection(sides.indexOf(prefs.getString("my_side", "NONE")).coerceAtLeast(0))
        }
        body.addView(sideSpinner)
        stakeInput = EditText(this).apply {
            hint = "নিজের Stake amount"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(prefs.getString("my_stake", "") ?: "")
        }
        body.addView(stakeInput)
        oddsInput = EditText(this).apply {
            hint = "নিজের bet-এর odds (যেমন 1.9) — ঐচ্ছিক"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(prefs.getString("my_odds", "") ?: "")
        }
        body.addView(oddsInput)
        body.addView(Button(this).apply {
            text = "Save my bet settings"
            setOnClickListener {
                val side = Side.fromLabel(sideSpinner.selectedItem?.toString())
                val amountText = stakeInput.text.toString().trim()
                val oddsText = oddsInput.text.toString().trim()
                val amount = amountText.toDoubleOrNull()
                val odds = oddsText.toDoubleOrNull()
                if (side != Side.UNKNOWN && (amount == null || amount <= 0.0 || !amount.isFinite())) {
                    info.text = "নিজের Stake একটি ধনাত্মক সংখ্যা হতে হবে।"
                    return@setOnClickListener
                }
                if (oddsText.isNotBlank() && (odds == null || odds < 1.01 || odds > 1000.0)) {
                    info.text = "Odds 1.01 থেকে 1000-এর মধ্যে দিন।"
                    return@setOnClickListener
                }
                store.saveSettings(BetSettings(side, amount, odds))
                info.text = "Settings saved. Historical rounds use their frozen side/stake/odds."
                render()
            }
        })

        body.addView(TextView(this).apply {
            text = "\nCARD ZONES (advanced)\n" +
                "কার্ডের সারি OCR ঠিক না ধরলে নিজে বসান। ফরম্যাট: left,top,right,bottom " +
                "(0–100 %, অথবা 0–1 fraction)। দুটোই খালি রাখলে auto zones ব্যবহার হবে।\n" +
                "Real layout default — ANDAR: 28,22.4,81.8,29.4 | BAHAR: 28,29.4,81.8,37.8"
            textSize = 14f
        })
        val existingZones = store.manualCardZones()
        andarZoneInput = EditText(this).apply {
            hint = "ANDAR zone: left,top,right,bottom"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setText(
                existingZones?.andar?.let {
                    "${pct(it.left)},${pct(it.top)},${pct(it.right)},${pct(it.bottom)}"
                } ?: ""
            )
        }
        body.addView(andarZoneInput)
        baharZoneInput = EditText(this).apply {
            hint = "BAHAR zone: left,top,right,bottom"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setText(
                existingZones?.bahar?.let {
                    "${pct(it.left)},${pct(it.top)},${pct(it.right)},${pct(it.bottom)}"
                } ?: ""
            )
        }
        body.addView(baharZoneInput)
        val zoneRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        zoneRow.addView(Button(this).apply {
            text = "Save card zones"
            setOnClickListener {
                try {
                    val andar = parseZoneInput(andarZoneInput.text.toString(), "ANDAR")
                    val bahar = parseZoneInput(baharZoneInput.text.toString(), "BAHAR")
                    if (andar == null && bahar == null) {
                        store.saveManualCardZones(null)
                        info.text = "Card zones cleared: automatic OCR zones will be used."
                    } else {
                        if (andar != null && bahar != null && andar.centerY >= bahar.centerY) {
                            info.text = "ANDAR zone অবশ্যই BAHAR zone-এর উপরে থাকতে হবে।"
                            return@setOnClickListener
                        }
                        store.saveManualCardZones(
                            CardZones(
                                andar = andar,
                                bahar = bahar,
                                source = "manual settings; verify against real frames"
                            )
                        )
                        info.text = "Manual card zones saved. New frames use them immediately."
                    }
                    render()
                } catch (e: IllegalArgumentException) {
                    info.text = e.message ?: "Card zone ফরম্যাট ভুল।"
                }
            }
        })
        zoneRow.addView(Button(this).apply {
            text = "Clear / auto"
            setOnClickListener {
                andarZoneInput.setText("")
                baharZoneInput.setText("")
                store.saveManualCardZones(null)
                info.text = "Card zones cleared: automatic OCR zones will be used."
                render()
            }
        })
        body.addView(zoneRow)

        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        dashboardButton = Button(this).apply {
            text = "DASHBOARD"
            setOnClickListener { switchMode(MODE_DASHBOARD) }
        }
        historyButton = Button(this).apply {
            text = "HISTORY"
            setOnClickListener { switchMode(MODE_HISTORY) }
        }
        diagnosticsButton = Button(this).apply {
            text = "DIAGNOSTICS"
            setOnClickListener { switchMode(MODE_DIAGNOSTICS) }
        }
        modeRow.addView(dashboardButton)
        modeRow.addView(historyButton)
        modeRow.addView(diagnosticsButton)
        modeRow.addView(Button(this).apply {
            text = "REFRESH"
            setOnClickListener { render() }
        })
        body.addView(modeRow)

        dashboard = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(0, 12, 0, 24)
        }
        historyView = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(0, 12, 0, 24)
            visibility = View.GONE
        }
        diagnosticsView = TextView(this).apply {
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(0, 12, 0, 24)
            visibility = View.GONE
        }
        body.addView(dashboard)
        body.addView(historyView)
        body.addView(diagnosticsView)

        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        updateModeButtons()
        render()
    }

    override fun onResume() {
        super.onResume()
        if (!refreshPosted) {
            refreshPosted = true
            handler.post(refreshRunnable)
        } else {
            render()
        }
    }

    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        refreshPosted = false
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun switchMode(newMode: Int) {
        mode = newMode
        updateModeButtons()
        render()
    }

    private fun updateModeButtons() {
        val active = 0xFF1565C0.toInt()
        dashboardButton.setTextColor(if (mode == MODE_DASHBOARD) active else 0xFF333333.toInt())
        historyButton.setTextColor(if (mode == MODE_HISTORY) active else 0xFF333333.toInt())
        diagnosticsButton.setTextColor(if (mode == MODE_DIAGNOSTICS) active else 0xFF333333.toInt())
        dashboard.visibility = if (mode == MODE_DASHBOARD) View.VISIBLE else View.GONE
        historyView.visibility = if (mode == MODE_HISTORY) View.VISIBLE else View.GONE
        diagnosticsView.visibility = if (mode == MODE_DIAGNOSTICS) View.VISIBLE else View.GONE
    }

    private fun render() {
        try {
            val rounds = store.readRounds()
            val active = store.loadActive()
            val frames = store.readFrames(if (mode == MODE_DIAGNOSTICS) 20 else 1)
            when (mode) {
                MODE_DASHBOARD -> dashboard.text = buildDashboard(active, rounds, frames.firstOrNull())
                MODE_HISTORY -> historyView.text = buildHistory(rounds)
                MODE_DIAGNOSTICS -> diagnosticsView.text = buildDiagnostics(frames, rounds)
            }
        } catch (e: Exception) {
            val message = "Dashboard render error: ${e.javaClass.simpleName}: ${e.message}"
            when (mode) {
                MODE_HISTORY -> historyView.text = message
                MODE_DIAGNOSTICS -> diagnosticsView.text = message
                else -> dashboard.text = message
            }
        }
    }

    private fun buildDashboard(active: ManagedRound?, rounds: List<RoundRecord>, latest: JSONObject?): String {
        val stats = Accounting.overall(rounds)
        val lastRecord = rounds.maxByOrNull { it.completedAt }
        val round = active
        val settlement = round?.let {
            Accounting.settle(
                side = it.bet?.side ?: Side.UNKNOWN,
                stake = it.bet?.stake,
                odds = it.bet?.odds,
                winner = it.winnerSide.takeIf { side -> side != Side.UNKNOWN },
                winnerState = it.winnerState
            )
        }
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return buildString {
            append("Updated: ").append(fmt.format(Date())).append('\n')
            append("Capture: frames ").append(store.counts().second).append(", OCR scans ").append(store.counts().first)
            append('\n')
            append("Latest OCR status: ").append(store.lastStatus().lineSequence().firstOrNull() ?: "No OCR data")
            append("\n\nA. GAME STATUS\n")
            if (round == null) {
                append("Round ID: NOT ACTIVE\n")
                append("Stage: ").append(if (lastRecord != null) "LAST ROUND COMPLETED" else "IDLE").append('\n')
                append("Cards dealt: NOT DETECTED\n")
                append("Andar cards: 0\nBahar cards: 0\n")
                if (lastRecord != null) {
                    append("Last round #").append(lastRecord.id).append(" at ")
                        .append(fmt.format(Date(lastRecord.completedAt))).append('\n')
                }
            } else {
                append("Round ID: ").append(round.id).append('\n')
                append("Stage: ").append(round.stage.name).append('\n')
                append("Betting stage: ").append(round.bettingStage.name)
                    .append(" (").append(round.lastStageText).append(")\n")
                append("Cards dealt (OCR): ").append(
                    round.cardsDealtOcr?.toString() ?: "NOT DETECTED"
                ).append('\n')
                append("Cards detected (image CV): total ").append(round.totalCards)
                    .append("  (Andar ").append(round.andarCards)
                    .append(", Bahar ").append(round.baharCards).append(")\n")
                val cardInfo = latestCards(latest)
                append("Uncertain cards (latest frame): A=").append(cardInfo?.optInt("uncertainAndar", 0) ?: 0)
                    .append(" B=").append(cardInfo?.optInt("uncertainBahar", 0) ?: 0)
                    .append(" • pending A=").append(cardInfo?.optInt("pendingAndar", 0) ?: 0)
                    .append(" B=").append(cardInfo?.optInt("pendingBahar", 0) ?: 0).append('\n')
                append("Round started: ").append(fmt.format(Date(round.startedAt))).append('\n')
                append("Betting locked: ").append(round.betLockedAt?.let { fmt.format(Date(it)) } ?: "no")
                    .append('\n')
                append("Completed: ").append(round.completedAt?.let { fmt.format(Date(it)) } ?: "no").append('\n')
            }
            append("\nB. BETTING PANEL\n")
            appendMoneyPanel(this, "Andar", round?.andarMoney, fmt)
            appendMoneyPanel(this, "Bahar", round?.baharMoney, fmt)
            append("Andar odds: ").append(round?.andarOdds?.let { "1:$it" } ?: "NOT DETECTED").append('\n')
            append("Bahar odds: ").append(round?.baharOdds?.let { "1:$it" } ?: "NOT DETECTED").append('\n')
            append("Andar percentages: ").append(
                latestParsedDouble(latest, "andarPercentage")?.let { "${formatNumber(it)}%" } ?: "not reliably detected"
            ).append(" • Bahar: ").append(
                latestParsedDouble(latest, "baharPercentage")?.let { "${formatNumber(it)}%" } ?: "not reliably detected"
            ).append('\n')
            val balance = latestParsedReading(latest, "visibleBalance")
            append("Visible balance (informational): ").append(balance ?: "NOT DETECTED").append('\n')

            append("\nC. MY BET\n")
            val settings = store.settings()
            append("Saved settings: side ").append(settings.side.label)
                .append(", stake ").append(settings.stake?.let { formatNumber(it) } ?: "not set")
                .append(", odds ").append(settings.odds?.let { "1:$it" } ?: "not set").append('\n')
            append("Round frozen bet: ")
            val bet = round?.bet
            if (bet == null) {
                append("not frozen yet\n")
            } else {
                append("side ").append(bet.side.label)
                    .append(", stake ").append(bet.stake?.let { formatNumber(it) } ?: "not set")
                    .append(", odds ").append(bet.odds?.let { "1:$it" } ?: "not set")
                    .append(", ").append(if (bet.locked) "LOCKED" else "open")
                    .append(" (").append(bet.source).append(")\n")
            }
            append("Current round personal stake: ")
                .append(bet?.stake?.let { formatNumber(it) } ?: "NOT DETECTED").append('\n')

            append("\nD. RESULT\n")
            if (round == null) {
                append("Detected winner: NOT DETECTED\n")
                append("Detection status: ").append(WinnerState.RESULT_NOT_DETECTED.name).append('\n')
                append("Win or loss: unavailable\nProfit/loss: unavailable\n")
            } else {
                append("Detected winner: ").append(
                    round.winnerSide.takeIf { it != Side.UNKNOWN }?.label ?: "NOT DETECTED"
                ).append('\n')
                append("Detection status: ").append(round.winnerState.name).append('\n')
                append("Confidence: ").append((round.winnerConfidence.coerceIn(0f, 1f) * 100).roundToInt())
                    .append("%\n")
                append("Reason: ").append(round.winnerReason.ifBlank { "no result evidence" }).append('\n')
                if (settlement?.calculated == true) {
                    append("Win or loss: ").append(if (settlement.isWin == true) "WIN" else "LOSS").append('\n')
                    append("Gross return: ").append(settlement.grossReturn?.let { formatNumber(it) } ?: "-")
                        .append(" • Net profit: ").append(settlement.netProfit?.let { formatSigned(it) } ?: "-")
                        .append(" • Loss amount: ").append(settlement.lossAmount?.let { formatNumber(it) } ?: "-")
                        .append('\n')
                    append("Note: gross return is total returned amount; net profit is gross minus stake.\n")
                } else {
                    append("Win or loss: unavailable\n")
                    append("Profit/loss: not calculated — ")
                        .append(settlement?.reason ?: "confirmed winner, personal side/stake/odds required").append('\n')
                }
            }

            append("\nE. OVERALL STATISTICS\n")
            appendOverall(this, stats)
            if (lastRecord != null) {
                append("Last saved result: #").append(lastRecord.id).append(" ")
                    .append(lastRecord.winner.label).append(" • my ")
                    .append(lastRecord.mySide.label).append(" • net ")
                    .append(lastRecord.netProfit?.let { formatSigned(it) } ?: "unavailable").append('\n')
            }
            append("\nNote: image-based card counting and visual highlight detection are implemented on-device, but real-game layout/lighting still needs screenshot validation.\n")
        }
    }

    private fun latestCards(frame: JSONObject?): JSONObject? =
        frame?.optJSONObject("structured")?.optJSONObject("cards")

    private fun appendMoneyPanel(out: StringBuilder, label: String, state: MoneyPanelState?, fmt: SimpleDateFormat) {
        out.append(label).append(" panel total: ")
        if (state == null) {
            out.append("NOT DETECTED\n")
            return
        }
        if (state.status == DetectionStatus.DETECTED && state.currentFrame != null) {
            out.append(formatNumber(state.currentFrame)).append(" [DETECTED]\n")
        } else {
            out.append(state.status.label)
            if (state.current != null) out.append(" (last known ").append(formatNumber(state.current)).append(')')
            out.append(" — ").append(state.reason).append('\n')
        }
        out.append("  Previous total: ").append(state.previous?.let { formatNumber(it) } ?: "NOT DETECTED")
            .append(" • Current: ")
            .append(state.currentFrame?.let { formatNumber(it) } ?: state.current?.let { "${formatNumber(it)} (last known)" } ?: "NOT DETECTED")
            .append(" • Difference: ")
            .append(state.difference?.let { formatSigned(it) } ?: "NOT DETECTED")
            .append('\n')
        if (state.individual.isNotEmpty()) {
            out.append("  Individual visible amounts: ")
            out.append(state.individual.take(8).joinToString(" • ") { formatNumber(it.value) })
            out.append('\n')
        } else {
            out.append("  Individual visible amounts: none reliably identified\n")
        }
        if (state.lastUpdated > 0) {
            out.append("  Total last reliably updated: ").append(fmt.format(Date(state.lastUpdated))).append('\n')
        }
    }

    private fun appendOverall(out: StringBuilder, stats: OverallStats) {
        out.append("Total completed rounds: ").append(stats.totalRounds).append('\n')
        out.append("Wins: ").append(stats.wins).append(" • Losses: ").append(stats.losses)
            .append(" • Settled rounds: ").append(stats.settledRounds)
            .append(" • Unsettled: ").append(stats.unsettledRounds).append('\n')
        out.append("Total stake (settled): ").append(formatNumber(stats.totalStake)).append('\n')
        out.append("Total profit (positive net): +").append(formatNumber(stats.totalProfit)).append('\n')
        out.append("Total loss (negative net): -").append(formatNumber(stats.totalLoss)).append('\n')
        out.append("Net result: ").append(formatSigned(stats.netResult)).append('\n')
        out.append("Total gross return: ").append(formatNumber(stats.totalGrossReturn)).append('\n')
        out.append("Winner counts: Andar ").append(stats.andarWins).append(" • Bahar ").append(stats.baharWins).append('\n')
    }

    private fun buildHistory(rounds: List<RoundRecord>): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val stats = Accounting.overall(rounds)
        val cumulative = mutableMapOf<Long, Double>()
        var running = 0.0
        for (record in rounds) {
            record.netProfit?.let { running += it }
            cumulative[record.id] = running
        }
        return buildString {
            append("ROUND HISTORY (newest first, retained ").append(rounds.size).append("/500)\n")
            append("Overall: ").append(stats.wins).append(" wins, ").append(stats.losses)
                .append(" losses, net ").append(formatSigned(stats.netResult)).append("\n\n")
            if (rounds.isEmpty()) {
                append("No completed rounds yet. A round is saved only after a confirmed winner.\n")
            }
            for (record in rounds.asReversed()) {
                append("#").append(record.id).append("  ").append(fmt.format(Date(record.completedAt))).append('\n')
                append("  Cards: Andar ").append(record.andarCards).append(" • Bahar ").append(record.baharCards)
                    .append(" • total ").append(record.totalCards)
                    .append(" • OCR dealt ").append(record.cardsDealtOcr ?: "NOT DETECTED").append('\n')
                append("  Panel totals: Andar ").append(record.andarTotal?.let { formatNumber(it) } ?: "NOT DETECTED")
                    .append(" (prev ").append(record.andarPreviousTotal?.let { formatNumber(it) } ?: "-")
                    .append(", Δ ").append(record.andarDifference?.let { formatSigned(it) } ?: "-").append(")")
                    .append(" • Bahar ").append(record.baharTotal?.let { formatNumber(it) } ?: "NOT DETECTED")
                    .append(" (prev ").append(record.baharPreviousTotal?.let { formatNumber(it) } ?: "-")
                    .append(", Δ ").append(record.baharDifference?.let { formatSigned(it) } ?: "-").append(")\n")
                append("  Panel odds: Andar ").append(record.andarOdds?.let { "1:$it" } ?: "NOT DETECTED")
                    .append(" • Bahar ").append(record.baharOdds?.let { "1:$it" } ?: "NOT DETECTED").append('\n')
                append("  My bet: ").append(record.mySide.label)
                    .append(" • stake ").append(record.myStake?.let { formatNumber(it) } ?: "not set")
                    .append(" • odds ").append(record.myOdds?.let { "1:$it" } ?: "not set").append('\n')
                append("  Winner: ").append(record.winner.label)
                    .append(" • ").append(record.winnerState.name)
                    .append(" • confidence ").append((record.winnerConfidence.coerceIn(0f, 1f) * 100).roundToInt())
                    .append("%\n")
                append("  Result: ").append(
                    when (record.isWin) {
                        true -> "WIN"
                        false -> "LOSS"
                        null -> "unavailable"
                    }
                ).append(" • gross return ").append(record.grossReturn?.let { formatNumber(it) } ?: "-")
                    .append(" • net ").append(record.netProfit?.let { formatSigned(it) } ?: "-")
                    .append(" • loss ").append(record.lossAmount?.let { formatNumber(it) } ?: "-")
                    .append(" • cumulative net ").append(formatSigned(cumulative[record.id] ?: 0.0)).append('\n')
                append("  Detection: ").append(record.detectionStatus).append('\n')
                if (record.settlementReason.isNotBlank() && record.netProfit == null) {
                    append("  P/L unavailable reason: ").append(record.settlementReason).append('\n')
                }
                append('\n')
            }
        }
    }

    private fun buildDiagnostics(frames: List<JSONObject>, rounds: List<RoundRecord>): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val latest = frames.firstOrNull()
        return buildString {
            append("DIAGNOSTIC LOG\n")
            append("Completed rounds stored: ").append(rounds.size).append(" • OCR frames stored: ")
                .append("[see latest below]").append('\n')
            append("Capture frames: ").append(store.counts().second).append(" • OCR scans: ")
                .append(store.counts().first).append('\n')
            append("Latest status: ").append(store.lastStatus()).append("\n\n")

            append("LATEST RAW OCR TEXT (separate from structured data)\n")
            append((latest?.optString("rawText", "") ?: "No OCR frame yet").take(6000)).append("\n\n")

            append("LATEST STRUCTURED PARSED DATA\n")
            val structured = latest?.optJSONObject("structured")
            append(
                structured?.toString(2)?.take(16000)
                    ?: "No structured data yet. Start screen capture while the game is visible."
            ).append("\n\n")

            append("POSITIONED OCR LINES (normalized bounding boxes)\n")
            val lines = latest?.optJSONArray("lines")
            if (lines == null || lines.length() == 0) {
                append("No positioned lines available.\n")
            } else {
                for (i in 0 until minOf(lines.length(), 80)) {
                    val line = lines.optJSONObject(i) ?: continue
                    val box = line.optJSONArray("box")
                    val coords = if (box != null && box.length() >= 4) {
                        String.format(
                            Locale.US,
                            "[%.3f,%.3f -> %.3f,%.3f]",
                            box.optDouble(0),
                            box.optDouble(1),
                            box.optDouble(2),
                            box.optDouble(3)
                        )
                    } else "[no box]"
                    append(coords).append(" conf=").append(String.format(Locale.US, "%.2f", line.optDouble("confidence")))
                        .append("  ").append(line.optString("text")).append('\n')
                }
            }
            append("\nRECENT OCR FRAMES\n")
            if (frames.isEmpty()) append("No frames stored.\n")
            frames.take(10).forEach {
                append("--- ").append(fmt.format(Date(it.optLong("time"))))
                    .append(" hash=").append(it.optString("hash"))
                    .append(if (it.optBoolean("legacy")) " legacy" else "").append(" ---\n")
                append(it.optString("rawText").take(900)).append('\n')
            }

            append("\nSTATUS / DIAGNOSTIC MESSAGES\n")
            val log = store.readStatusLog(80)
            if (log.isEmpty()) append("No diagnostic messages.\n")
            log.forEach { (time, message) ->
                append(fmt.format(Date(time))).append("  ").append(message).append('\n')
            }
            append("\nKNOWN LIMITATION: card-shape and highlight detection are synthetic-image tested; validate against real game screenshots/lighting before relying on counts or visual-only winners.\n")
        }
    }

    private fun latestParsedDouble(frame: JSONObject?, key: String): Double? {
        val parsed = frame?.optJSONObject("structured") ?: return null
        val reading = parsed.optJSONObject(key) ?: return null
        return JsonCodec.nullableDouble(reading, "value")
    }

    private fun latestParsedReading(frame: JSONObject?, key: String): String? {
        val parsed = frame?.optJSONObject("structured") ?: return null
        val reading = parsed.optJSONObject(key) ?: return null
        val value = JsonCodec.nullableDouble(reading, "value") ?: return null
        return formatNumber(value) + " [" + reading.optString("status") + "]"
    }

    private fun formatNumber(value: Double): String =
        if (value.isFinite() && value % 1.0 == 0.0) value.toLong().toString()
        else String.format(Locale.US, "%.2f", value)

    private fun formatSigned(value: Double): String {
        val prefix = if (value > 0) "+" else ""
        return prefix + formatNumber(value)
    }

    /** Percentage text for a normalized 0..1 zone edge. */
    private fun pct(value: Float): String {
        val p = value * 100f
        return if (p % 1f == 0f) p.toInt().toString() else String.format(Locale.US, "%.1f", p)
    }

    /**
     * Parses "left,top,right,bottom" as percentages (0..100) or fractions (0..1). Blank means
     * automatic zones for that side.
     */
    private fun parseZoneInput(raw: String, label: String): Box? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val parts = text.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size != 4) {
            throw IllegalArgumentException("$label zone-এ ঠিক ৪টি সংখ্যা দিন: left,top,right,bottom")
        }
        val values = parts.map {
            it.toFloatOrNull() ?: throw IllegalArgumentException("$label zone-এ '$it' সংখ্যা নয়")
        }
        val scale = if (values.any { it > 1f }) 100f else 1f
        val box = Box(values[0] / scale, values[1] / scale, values[2] / scale, values[3] / scale)
        if (!box.isValid() || box.left < 0f || box.top < 0f || box.right > 1f || box.bottom > 1f) {
            throw IllegalArgumentException("$label zone 0–100 % সীমার মধ্যে এবং right>left, bottom>top হতে হবে")
        }
        if (box.width < 0.05f || box.height < 0.02f) {
            throw IllegalArgumentException("$label zone খুব ছোট; পুরো কার্ডের সারি ঢাকতে হবে")
        }
        return box
    }

    @Deprecated("Compatibility callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequest && resultCode == RESULT_OK && data != null) {
            val service = Intent(this, CaptureService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("resultData", data)
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
            info.text = "Capture started. Switch to the game screen; analysis runs locally."
        } else {
            info.text = "Screen capture permission was not granted."
        }
    }

    companion object {
        private const val MODE_DASHBOARD = 0
        private const val MODE_HISTORY = 1
        private const val MODE_DIAGNOSTICS = 2
    }
}
