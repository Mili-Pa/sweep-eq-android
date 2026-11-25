package com.example.sweepeq

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

private class Rec(val result: SweepResult, val outType: Int)

class MainActivity : Activity() {

    companion object {
        val BG = Color.rgb(16, 16, 24)
        val FG = Color.rgb(220, 220, 230)

        /** Graphic-EQ band layouts: 3 (low/mid/high), 5 (Android's default), 7, 10 (octaves), 15 (2/3 octave). */
        val EQ_PRESETS = arrayOf(
            doubleArrayOf(125.0, 1000.0, 8000.0),
            doubleArrayOf(60.0, 230.0, 910.0, 3600.0, 14000.0),
            doubleArrayOf(63.0, 160.0, 400.0, 1000.0, 2500.0, 6300.0, 16000.0),
            doubleArrayOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0),
            doubleArrayOf(25.0, 40.0, 63.0, 100.0, 160.0, 250.0, 400.0, 630.0, 1000.0, 1600.0, 2500.0, 4000.0, 6300.0, 10000.0, 16000.0)
        )
    }

    private val fft = Fft(Audio.N)
    private val pw = DoubleArray(Audio.N / 2)
    private val micNames = arrayOf("Mic: Auto", "Mic: Main (MIC)", "Mic: Second (CAMCORDER)")
    private val deviceNames = arrayOf("Device: Flat", "Device: Earbuds/IEM", "Device: Headphones", "Device: Speakers", "Device: Car")
    private val eqTypeNames = arrayOf("EQ: Graphic", "EQ: Parametric")
    private val smoothNames = arrayOf("Smooth: 1/3 oct", "Smooth: 1/6 oct")
    private val repeatNames = arrayOf("Runs: 1", "Runs: 2", "Runs: 3")
    private val profileNames = arrayOf("Profile: Safe", "Profile: Balanced", "Profile: Aggressive")
    private val viewNames = arrayOf("View: Overview", "View: Profiles", "View: Filters", "View: Raw+confidence")
    private val bandNames = arrayOf("Bands: 3", "Bands: 5", "Bands: 7", "Bands: 10", "Bands: 15")
    private val strengthNames = arrayOf(
        "Strength: Auto (max 18 dB)", "Strength: Auto (max 12 dB)",
        "Strength: x1.0", "Strength: x0.75", "Strength: x0.5", "Strength: x0.25"
    )

    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var spectrum: SpectrumView
    private lateinit var chart: ChartView
    private lateinit var sweepBtn: Button
    private lateinit var liveBtn: Button
    private lateinit var modeBtn: Button
    private lateinit var micBtn: Button
    private lateinit var deviceBtn: Button
    private lateinit var eqTypeBtn: Button
    private lateinit var smoothBtn: Button
    private lateinit var repeatsBtn: Button
    private lateinit var profileBtn: Button
    private lateinit var viewBtn: Button
    private lateinit var bandsBtn: Button
    private lateinit var strengthBtn: Button
    private lateinit var eqBtn: Button
    private lateinit var copyBtn: Button
    private lateinit var advBtn: Button
    private lateinit var advPanel: LinearLayout
    private lateinit var paramsEt: EditText
    private lateinit var calEt: EditText
    private lateinit var prefs: SharedPreferences

    @Volatile private var running = false
    @Volatile private var live = false
    @Volatile private var liveStop = false
    @Volatile private var btMode = false
    @Volatile private var micMode = 0
    @Volatile private var deviceIdx = 1
    @Volatile private var eqType = 1
    @Volatile private var smoothIdx = 0
    @Volatile private var repeatsIdx = 0
    @Volatile private var profileIdx = 1
    @Volatile private var viewIdx = 0
    @Volatile private var bandsIdx = 3
    @Volatile private var shapeMode = 0
    @Volatile private var eqOn = false
    @Volatile private var lastGraphicEq: DoubleArray? = null
    @Volatile private var usedSource = "?"
    private var pending: (() -> Unit)? = null

    private var params = Params()
    private var cal: Cal? = null
    private val effects = ArrayList<AudioEffect>()

    // results of the last parametric run
    private var curReport: Report? = null
    private var baseReport: Report? = null
    private var verifyProf: ProfileResult? = null
    private var prevRel: DoubleArray? = null
    private var valErr = DoubleArray(3)
    private var lastOutType = -1
    private var lastPeak = -120.0
    private var lastBt = false

    // results of the last graphic run
    private var prevGraphicRel: DoubleArray? = null
    private var graphicText = ""
    private var graphicExport = ""

    private fun smoothDen(): Int = if (smoothIdx == 0) 3 else 6

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("sweepeq", Context.MODE_PRIVATE)
        val d = resources.displayMetrics.density
        val pad = (12 * d).toInt()
        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Throwable) {
            ""
        }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)
        root.setBackgroundColor(BG)

        val title = TextView(this)
        title.text = "Sweep EQ v$ver"
        title.textSize = 22f
        title.setTextColor(FG)
        root.addView(title)

        val intro = TextView(this)
        intro.text = "Live spectrum shows what the microphone hears (yellow = peak hold, like Spectroid). " +
            "The sweep test plays a beep and a 20 Hz - 20 kHz sweep, measures the response and recommends an EQ.\n" +
            "Speakers: phone where you listen, quiet room, volume about 50%. BT earbuds: set Setup to BT earbuds, press " +
            "the earbud tip firmly against the phone microphone hole, media volume 70-100%.\n" +
            "EQ: Parametric gives three recommendations (Safe, Balanced, Aggressive) with free frequency, gain and Q, " +
            "weighted by how reliable each frequency was measured (signal-to-noise, repeatability between Runs, phone " +
            "microphone limits). Graphic uses fixed bands (Bands) and a Strength ratio.\n" +
            "EQ in test: after a test, switch it On and run again: the sweep is pre-filtered with the EQ and the app shows how " +
            "well the real result matches the prediction.\n" +
            "Advanced: all algorithm limits, tonal preferences (bass / mid / treble / presence) and a microphone calibration file."
        intro.setTextColor(FG)
        intro.textSize = 13f
        intro.setPadding(0, pad / 2, 0, pad / 2)
        root.addView(intro)

        liveBtn = Button(this)
        liveBtn.text = "Live spectrum"
        liveBtn.setOnClickListener { toggleLive() }
        sweepBtn = Button(this)
        sweepBtn.text = "Start sweep test"
        sweepBtn.setOnClickListener { startSweep() }
        modeBtn = cycleBtn(arrayOf("Setup: Speakers", "Setup: BT earbuds"), { if (btMode) 1 else 0 }, { btMode = it == 1 }, {})
        micBtn = cycleBtn(micNames, { micMode }, { micMode = it }, {})
        deviceBtn = cycleBtn(deviceNames, { deviceIdx }, { deviceIdx = it }, {})
        eqTypeBtn = cycleBtn(eqTypeNames, { eqType }, { eqType = it }, {})
        smoothBtn = cycleBtn(smoothNames, { smoothIdx }, { smoothIdx = it }, {})
        repeatsBtn = cycleBtn(repeatNames, { repeatsIdx }, { repeatsIdx = it }, {})
        profileBtn = cycleBtn(profileNames, { profileIdx }, { profileIdx = it }, { renderPeq() })
        viewBtn = cycleBtn(viewNames, { viewIdx }, { viewIdx = it }, { renderPeq() })
        bandsBtn = cycleBtn(bandNames, { bandsIdx }, { bandsIdx = it }, {
            // a stored graphic EQ belongs to the old band layout
            lastGraphicEq = null
        })
        strengthBtn = cycleBtn(strengthNames, { shapeMode }, { shapeMode = it }, {})
        eqBtn = Button(this)
        eqBtn.text = "EQ in test: Off"
        eqBtn.setOnClickListener {
            if (!eqOn && !hasEqToApply()) {
                status.text = "Run a sweep test first to get an EQ recommendation."
            } else {
                eqOn = !eqOn
                eqBtn.text = if (eqOn) "EQ in test: ON" else "EQ in test: Off"
            }
        }
        copyBtn = Button(this)
        copyBtn.text = "Copy EQ"
        copyBtn.setOnClickListener { copyEq() }
        advBtn = Button(this)
        advBtn.text = "Advanced"
        advBtn.setOnClickListener {
            advPanel.visibility = if (advPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        root.addView(row(liveBtn, sweepBtn))
        root.addView(row(modeBtn, micBtn))
        root.addView(row(deviceBtn, eqTypeBtn))
        root.addView(row(smoothBtn, repeatsBtn))
        root.addView(row(profileBtn, viewBtn))
        root.addView(row(bandsBtn, strengthBtn))
        root.addView(row(eqBtn, copyBtn))
        root.addView(advBtn)

        advPanel = LinearLayout(this)
        advPanel.orientation = LinearLayout.VERTICAL
        advPanel.visibility = View.GONE
        val advHelp1 = TextView(this)
        advHelp1.text = "Parameters (key=value, # = comment). Profiles derive from these: Safe is stricter, Aggressive looser."
        advHelp1.setTextColor(FG)
        advHelp1.textSize = 12f
        advPanel.addView(advHelp1)
        paramsEt = EditText(this)
        paramsEt.setTextColor(FG)
        paramsEt.typeface = Typeface.MONOSPACE
        paramsEt.textSize = 12f
        paramsEt.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        paramsEt.minLines = 8
        paramsEt.gravity = android.view.Gravity.TOP
        advPanel.addView(paramsEt)
        val advHelp2 = TextView(this)
        advHelp2.text = "Microphone calibration file: paste lines of 'frequency dB' (the microphone's deviation, which is subtracted). " +
            "Leave empty if you have none: then the phone microphone is treated as unreliable at the extremes."
        advHelp2.setTextColor(FG)
        advHelp2.textSize = 12f
        advPanel.addView(advHelp2)
        calEt = EditText(this)
        calEt.setTextColor(FG)
        calEt.typeface = Typeface.MONOSPACE
        calEt.textSize = 12f
        calEt.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        calEt.minLines = 4
        calEt.gravity = android.view.Gravity.TOP
        advPanel.addView(calEt)
        val applyBtn = Button(this)
        applyBtn.text = "Apply and save"
        applyBtn.setOnClickListener { applyAdvanced() }
        val defaultsBtn = Button(this)
        defaultsBtn.text = "Reset to defaults"
        defaultsBtn.setOnClickListener {
            paramsEt.setText(Params().toText())
            applyAdvanced()
        }
        advPanel.addView(row(applyBtn, defaultsBtn))
        root.addView(advPanel)

        status = TextView(this)
        status.setTextColor(FG)
        status.setPadding(0, pad / 2, 0, pad / 2)
        root.addView(status)
        spectrum = SpectrumView(this)
        root.addView(spectrum, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (430 * d).toInt()))
        chart = ChartView(this)
        root.addView(chart, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (300 * d).toInt()))
        result = TextView(this)
        result.typeface = Typeface.MONOSPACE
        result.setTextColor(FG)
        result.setPadding(0, pad / 2, 0, 0)
        root.addView(result)

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(BG)
        scroll.addView(root)
        setContentView(scroll)

        // saved settings
        paramsEt.setText(prefs.getString("params", null) ?: Params().toText())
        calEt.setText(prefs.getString("cal", "") ?: "")
        loadAdvanced()
    }

    private fun cycleBtn(names: Array<String>, get: () -> Int, set: (Int) -> Unit, after: () -> Unit): Button {
        val b = Button(this)
        b.text = names[get()]
        b.setOnClickListener {
            set((get() + 1) % names.size)
            b.text = names[get()]
            after()
        }
        return b
    }

    private fun row(vararg bs: Button): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        for (b in bs) r.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return r
    }

    private fun loadAdvanced() {
        val np = Params()
        np.parse(paramsEt.text.toString())
        params = np
        val txt = calEt.text.toString()
        cal = if (txt.isBlank()) null else Cal.parse(txt)
    }

    private fun applyAdvanced() {
        val np = Params()
        val problems = np.parse(paramsEt.text.toString())
        params = np
        val txt = calEt.text.toString()
        val c = if (txt.isBlank()) null else Cal.parse(txt)
        cal = c
        prefs.edit().putString("params", paramsEt.text.toString()).putString("cal", txt).apply()
        val sb = StringBuilder("Settings saved.")
        if (problems.isNotEmpty()) sb.append(" ").append(problems.joinToString("; "))
        if (txt.isNotBlank() && c == null) sb.append(" Calibration text not recognised (need at least two 'frequency dB' lines).")
        if (c != null) sb.append(" Calibration loaded (").append(c.f.size).append(" points).")
        status.text = sb.toString()
    }

    private fun hasEqToApply(): Boolean {
        if (eqType == 0) return lastGraphicEq != null
        val b = baseReport
        return b != null && b.profiles.size > profileIdx
    }

    private fun ui(block: () -> Unit) {
        runOnUiThread { block() }
    }

    private fun withMic(action: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pending = action
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            pending?.invoke()
        } else {
            status.text = "Microphone permission is required."
        }
        pending = null
    }

    override fun onStop() {
        super.onStop()
        liveStop = true
    }

    private fun lockButtons(locked: Boolean) {
        val bs = arrayOf(sweepBtn, modeBtn, micBtn, deviceBtn, eqTypeBtn, smoothBtn, repeatsBtn, bandsBtn, strengthBtn, eqBtn)
        for (b in bs) b.isEnabled = !locked
    }

    private fun startSweep() {
        withMic {
            if (running) return@withMic
            running = true
            loadAdvanced()
            lockButtons(true)
            liveBtn.isEnabled = false
            result.text = ""
            val plan = Plan(btMode)
            val eqC = EQ_PRESETS[bandsIdx]
            thread {
                try {
                    runTest(plan, eqC)
                } catch (e: Throwable) {
                    ui { status.text = "Error: ${e.message}" }
                } finally {
                    running = false
                    ui {
                        lockButtons(false)
                        liveBtn.isEnabled = true
                    }
                }
            }
        }
    }

    private fun toggleLive() {
        if (live) {
            liveStop = true
            return
        }
        withMic {
            if (running) return@withMic
            running = true
            live = true
            liveStop = false
            lockButtons(true)
            liveBtn.text = "Stop live"
            spectrum.reset()
            status.text = "Live..."
            thread {
                try {
                    runLive()
                } catch (e: Throwable) {
                    ui { status.text = "Error: ${e.message}" }
                } finally {
                    running = false
                    live = false
                    ui {
                        lockButtons(false)
                        liveBtn.text = "Live spectrum"
                    }
                }
            }
        }
    }

    private fun releaseEffects() {
        for (e in effects) {
            try {
                e.release()
            } catch (_: Throwable) {
            }
        }
        effects.clear()
    }

    private fun openRecord(): AudioRecord {
        val sr = Audio.SR
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val sources = when (micMode) {
            1 -> intArrayOf(MediaRecorder.AudioSource.MIC)
            2 -> intArrayOf(MediaRecorder.AudioSource.CAMCORDER)
            else -> intArrayOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC)
        }
        for (src in sources) {
            try {
                val a = AudioRecord(src, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, sr) * 2)
                if (a.state == AudioRecord.STATE_INITIALIZED) {
                    usedSource = when (src) {
                        MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
                        MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
                        else -> "MIC"
                    }
                    // always record with the phone's own microphone, never the earbuds' mic
                    try {
                        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        val dev = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                        if (dev != null) a.setPreferredDevice(dev)
                    } catch (_: Throwable) {
                    }
                    // switch off automatic gain control, noise suppression and echo cancellation when available
                    releaseEffects()
                    try {
                        if (AutomaticGainControl.isAvailable()) {
                            val e = AutomaticGainControl.create(a.audioSessionId)
                            if (e != null) {
                                e.setEnabled(false)
                                effects.add(e)
                            }
                        }
                    } catch (_: Throwable) {
                    }
                    try {
                        if (NoiseSuppressor.isAvailable()) {
                            val e = NoiseSuppressor.create(a.audioSessionId)
                            if (e != null) {
                                e.setEnabled(false)
                                effects.add(e)
                            }
                        }
                    } catch (_: Throwable) {
                    }
                    try {
                        if (AcousticEchoCanceler.isAvailable()) {
                            val e = AcousticEchoCanceler.create(a.audioSessionId)
                            if (e != null) {
                                e.setEnabled(false)
                                effects.add(e)
                            }
                        }
                    } catch (_: Throwable) {
                    }
                    return a
                } else {
                    a.release()
                }
            } catch (_: Throwable) {
            }
        }
        throw IllegalStateException(
            if (micMode == 2) "This phone does not offer a second mic source (CAMCORDER)." else "Cannot open the microphone"
        )
    }

    private fun devName(t: Int): String = when (t) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth (A2DP)"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth (call mode)"
        26, 27 -> "Bluetooth LE"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "phone speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headphones"
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
        -1 -> "unknown"
        else -> "other ($t)"
    }

    private fun isBtOut(t: Int): Boolean =
        t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || t == 26 || t == 27

    private fun fmtHz(f: Double): String {
        if (f >= 1000) {
            val k = f / 1000
            return if (abs(k - Math.round(k)) < 0.01) "${Math.round(k)} kHz" else String.format(Locale.US, "%.1f kHz", k)
        }
        return "${f.toInt()} Hz"
    }

    private fun pushFrame(p: DoubleArray, pcm: ShortArray, from: Int, to: Int) {
        val db = FloatArray(Audio.N / 2) { (10 * log10(p[it] + 1e-12)).toFloat() }
        var pk = 0
        for (i in max(0, from) until min(to, pcm.size)) pk = max(pk, abs(pcm[i].toInt()))
        val lvl = (20 * log10(max(pk, 1) / 32768.0)).toFloat()
        ui { spectrum.update(db, lvl) }
    }

    private fun runLive() {
        val rec = openRecord()
        val frame = ShortArray(Audio.N)
        rec.startRecording()
        try {
            while (!liveStop) {
                System.arraycopy(frame, Audio.HOP, frame, 0, Audio.N - Audio.HOP)
                var got = 0
                while (got < Audio.HOP && !liveStop) {
                    val r = rec.read(frame, Audio.N - Audio.HOP + got, Audio.HOP - got)
                    if (r < 0) {
                        liveStop = true
                        break
                    }
                    got += r
                }
                if (got < Audio.HOP) break
                fft.power(frame, 0, pw)
                pushFrame(pw, frame, Audio.N - Audio.HOP, Audio.N)
            }
        } finally {
            rec.stop()
            rec.release()
            releaseEffects()
        }
        ui { status.text = "Live stopped." }
    }

    private fun writeBack(play: ShortArray, from: Int, y: DoubleArray) {
        var pk = 0.0
        for (v in y) pk = max(pk, abs(v))
        val scale = if (pk > 0.5) 0.5 / pk else 1.0
        for (i in y.indices) play[from + i] = (y[i] * scale * 32767).toInt().toShort()
    }

    /** Pre-filters the sweep with a graphic EQ (peaking filters whose width follows the band spacing). */
    private fun applyEq(play: ShortArray, from: Int, len: Int, centers: DoubleArray, gains: DoubleArray) {
        var y = DoubleArray(len) { play[from + it] / 32768.0 }
        for (j in centers.indices) {
            if (abs(gains[j]) < 0.05) continue
            y = Biquad.process(Biquad.peaking(centers[j], gains[j], EqMath.bandQ(centers, j)), y)
        }
        writeBack(play, from, y)
    }

    /** Pre-filters the sweep with a parametric EQ. */
    private fun applyPeq(play: ShortArray, from: Int, len: Int, filters: List<PeqFilter>) {
        var y = DoubleArray(len) { play[from + it] / 32768.0 }
        for (f in filters) {
            y = Biquad.process(Biquad.peaking(f.f, f.g, f.q), y)
        }
        writeBack(play, from, y)
    }

    private fun recordOnce(plan: Plan, eqC: DoubleArray, gEq: DoubleArray?, peq: List<PeqFilter>?, runNo: Int, runs: Int): Rec {
        val sr = Audio.SR
        val play = SweepGen.make(plan)
        val s0 = SweepGen.sweepStart(plan)
        val sn = SweepGen.sweepLen()
        if (gEq != null) applyEq(play, s0, sn, eqC, gEq)
        if (peq != null) applyPeq(play, s0, sn, peq)

        val rec = openRecord()
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(play.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(play, 0, play.size)

        val recTotal = play.size + (plan.extraRec * sr).toInt()
        val buf = ShortArray(recTotal)
        var n = 0
        var started = false
        var lastPct = -1
        var nextEnd = Audio.N
        var outType = -1
        var routeRead = false
        rec.startRecording()
        while (n < recTotal) {
            val r = rec.read(buf, n, min(4096, recTotal - n))
            if (r < 0) break
            n += r
            if (!started && n >= sr * 3 / 10) {
                track.play()
                started = true
            }
            if (started && !routeRead && n > sr * 2) {
                routeRead = true
                outType = try {
                    track.routedDevice?.type ?: -1
                } catch (_: Throwable) {
                    -1
                }
            }
            while (nextEnd <= n) {
                fft.power(buf, nextEnd - Audio.N, pw)
                pushFrame(pw, buf, nextEnd - Audio.HOP, nextEnd)
                nextEnd += Audio.HOP
            }
            val pct = n * 100 / recTotal
            if (pct != lastPct) {
                lastPct = pct
                ui { status.text = "Measuring ($runNo/$runs)... $pct%" }
            }
        }
        rec.stop()
        rec.release()
        releaseEffects()
        track.stop()
        track.release()

        val res = SweepAnalyzer.analyze(buf.copyOf(n), plan, fft)
        return Rec(res, outType)
    }

    private fun runTest(plan: Plan, eqC: DoubleArray) {
        val wanted = repeatsIdx + 1
        var gApplied: DoubleArray? = null
        var verifyProfile: ProfileResult? = null
        if (eqOn) {
            if (eqType == 0) {
                val st = lastGraphicEq
                if (st != null && st.size == eqC.size) gApplied = st
            } else {
                val b = baseReport
                if (b != null && b.profiles.size > profileIdx) verifyProfile = b.profiles[profileIdx]
            }
        }
        val peqApplied: List<PeqFilter>? = if (verifyProfile != null) verifyProfile.filters else null

        val results = ArrayList<SweepResult>()
        var outType = -1
        for (run in 0 until wanted) {
            ui { spectrum.reset() }
            val one = recordOnce(plan, eqC, gApplied, peqApplied, run + 1, wanted)
            results.add(one.result)
            outType = one.outType
        }
        var best = -120.0
        for (r in results) best = max(best, r.peakDb)
        lastPeak = best
        lastOutType = outType
        lastBt = plan.bt

        ui { status.text = "Analyzing..." }
        if (eqType == 1) {
            analyzePeq(results, plan, verifyProfile)
        } else {
            analyzeGraphic(results, plan, eqC, gApplied)
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Parametric EQ
    // ------------------------------------------------------------------------------------------------------------

    private fun analyzePeq(results: List<SweepResult>, plan: Plan, vp: ProfileResult?) {
        val verify = vp != null
        val rep = Pipeline.runPeq(results, plan.bt, deviceIdx, params, cal, smoothDen(), verify)
        // check the predicted EQ responses against the real filter chains
        val errs = DoubleArray(3)
        for (i in 0 until rep.profiles.size) {
            val fl = rep.profiles[i].filters
            val fa = DoubleArray(fl.size) { fl[it].f }
            val ga = DoubleArray(fl.size) { fl[it].g }
            val qa = DoubleArray(fl.size) { fl[it].q }
            errs[i] = Validate.maxErrorDb(fa, ga, qa, rep.grid)
        }
        val oldBase = curReport
        prevRel = oldBase?.rel
        valErr = errs
        curReport = rep
        verifyProf = vp
        if (!verify) baseReport = rep
        val done: String
        if (vp != null) {
            done = if (rep.simNow.isNaN() || vp.simAfter.isNaN()) {
                "Done."
            } else {
                String.format(Locale.US, "Done. With the EQ: similarity %.0f%% (predicted %.0f%%).", rep.simNow, vp.simAfter)
            }
        } else if (rep.simNow.isNaN()) {
            done = "Done."
        } else {
            done = String.format(
                Locale.US, "Done. Similarity now %.0f%%  ->  Safe %.0f%% / Balanced %.0f%% / Aggressive %.0f%%.",
                rep.simNow, rep.profiles[0].simAfter, rep.profiles[1].simAfter, rep.profiles[2].simAfter
            )
        }
        ui {
            renderPeq()
            status.text = done
        }
    }

    private fun renderPeq() {
        val r = curReport ?: return
        val layers = ArrayList<Layer>()
        val g = r.grid
        val cyan = Color.rgb(80, 200, 255)
        val yellow = Color.rgb(255, 215, 70)
        val green = Color.rgb(90, 220, 120)
        val orange = Color.rgb(255, 140, 60)
        val grey = Color.rgb(140, 140, 150)
        val prof: ProfileResult? = if (r.profiles.size > profileIdx) r.profiles[profileIdx] else null
        if (r.verify) {
            val b = baseReport
            if (b != null) layers.add(Layer(g, b.rel, grey, 2f, "before EQ"))
            val vp = verifyProf
            if (vp != null) layers.add(Layer(g, vp.predRel, green, 2f, "predicted"))
            layers.add(Layer(g, r.target, yellow, 2f, "target"))
            layers.add(Layer(g, r.rel, cyan, 3f, "measured with EQ"))
        } else if (viewIdx == 0) {
            val pv = prevRel
            if (pv != null && pv.size == g.size) layers.add(Layer(g, pv, grey, 2f, "previous run"))
            layers.add(Layer(g, r.target, yellow, 2f, "target"))
            if (prof != null) layers.add(Layer(g, prof.predRel, green, 2f, "predicted (" + prof.name + ")"))
            layers.add(Layer(g, r.rel, cyan, 3f, "measured (smoothed)"))
            if (prof != null) {
                val fx = DoubleArray(prof.filters.size) { prof.filters[it].f }
                val fy = DoubleArray(prof.filters.size) { prof.filters[it].g }
                layers.add(Layer(fx, fy, orange, 0f, "PEQ filters", true))
            }
        } else if (viewIdx == 1) {
            layers.add(Layer(g, r.target, yellow, 2f, "target"))
            layers.add(Layer(g, r.rel, cyan, 3f, "measured (smoothed)"))
            val cols = intArrayOf(Color.rgb(150, 230, 150), Color.rgb(60, 200, 90), Color.rgb(230, 90, 230))
            for (i in 0 until r.profiles.size) {
                layers.add(Layer(g, r.profiles[i].predRel, cols[i], 2f, "predicted " + r.profiles[i].name))
            }
        } else if (viewIdx == 2) {
            if (prof != null) {
                var first = true
                for (f in prof.filters) {
                    val ys = DoubleArray(g.size) { EqMath.peakDb(g[it], f.f, f.g, f.q) }
                    layers.add(Layer(g, ys, orange, 1.5f, if (first) "single filters" else null))
                    first = false
                }
                layers.add(Layer(g, prof.eq, Color.WHITE, 3f, "EQ sum (" + prof.name + ")"))
            }
        } else {
            layers.add(Layer(r.rawF, r.rawRel, grey, 1.5f, "raw measured (first run)"))
            layers.add(Layer(g, r.rel, cyan, 3f, "measured (smoothed)"))
            layers.add(Layer(g, r.target, yellow, 2f, "target"))
        }
        chart.setLayers(layers, r.grid, r.conf)
        result.text = peqText(r)
    }

    private fun peqText(r: Report): String {
        val sb = StringBuilder()
        sb.append("Device: ").append(Targets.NAMES[deviceIdx]).append("\n")
        sb.append("Smoothing: ").append(if (smoothIdx == 0) "1/3" else "1/6").append(" octave   Runs: ").append(r.runs).append("\n")
        sb.append("Mic: ").append(micNames[micMode]).append(" (").append(usedSource).append(")   Output: ")
            .append(devName(lastOutType)).append("\n")
        if (r.calibrated) {
            sb.append("Calibration file: loaded\n")
        } else {
            sb.append("Calibration: none (phone microphone is not calibrated, so its extremes are treated as less reliable)\n")
        }
        if (r.runs < 2) sb.append("Repeatability: not measured (set Runs to 2 or 3)\n")
        sb.append(String.format(Locale.US, "Strongest band: %.0f dBFS\n", lastPeak))
        if (!r.simNow.isNaN()) {
            sb.append(String.format(Locale.US, "Similarity to target (100 Hz - 10 kHz): %.0f%%\n", r.simNow))
        }
        if (r.verify) {
            val vp = verifyProf
            sb.append("\nVERIFICATION RUN: the sweep was filtered with the ").append(vp?.name ?: "selected").append(" EQ.\n")
            if (vp != null && !vp.simAfter.isNaN() && !r.simNow.isNaN()) {
                sb.append(String.format(Locale.US, "Predicted similarity %.0f%%, measured %.0f%%.\n", vp.simAfter, r.simNow))
                var s = 0.0
                var c = 0
                for (i in r.grid.indices) {
                    val a = r.rel[i]
                    val b = vp.predRel[i]
                    if (r.grid[i] >= 100.0 && r.grid[i] <= 10000.0 && !a.isNaN() && !b.isNaN()) {
                        s += (a - b) * (a - b)
                        c++
                    }
                }
                if (c > 0) sb.append(String.format(Locale.US, "RMS difference between prediction and measurement: %.1f dB.\n", sqrt(s / c)))
            }
            sb.append("The recommendation list below belongs to the previous (pre-EQ) run; switch EQ in test off to measure again.\n")
        } else {
            sb.append("\nProfiles (similarity after EQ):\n")
            for (i in 0 until r.profiles.size) {
                val p = r.profiles[i]
                val mark = if (i == profileIdx) ">" else " "
                sb.append(String.format(Locale.US, "%s %-10s %3.0f%%  %d filters  preamp %.1f dB\n", mark, p.name, p.simAfter, p.filters.size, p.preamp))
            }
        }
        val base = baseReport
        val shown: Report? = if (r.verify) base else r
        val prof: ProfileResult? = if (shown != null && shown.profiles.size > profileIdx) shown.profiles[profileIdx] else null
        if (prof != null) {
            sb.append("\n").append(prof.name).append(" EQ:\n")
            sb.append(String.format(Locale.US, "Preamp: %.1f dB\n", prof.preamp))
            if (prof.filters.isEmpty()) sb.append("No filter is worth adding: the response is already close to the target.\n")
            for (i in 0 until prof.filters.size) {
                val f = prof.filters[i]
                sb.append(String.format(Locale.US, "Filter %d: ON PK Fc %s Gain %+.1f dB Q %.2f\n", i + 1, fmtHz(f.f), f.g, f.q))
            }
            sb.append(String.format(Locale.US, "Check: the predicted curve matches the real filter chain within %.3f dB.\n", valErr[profileIdx]))
        }
        if (shown != null) {
            val feats = shown.features
            if (feats.isNotEmpty()) {
                sb.append("\nPeaks and dips found (largest first):\n")
                var shownN = 0
                for (ft in feats) {
                    if (shownN >= 8) break
                    sb.append(
                        String.format(
                            Locale.US, "%8s  %s%.1f dB  %.1f oct  conf %.1f  %s\n",
                            fmtHz(ft.f), if (ft.isPeak) "+" else "-", ft.prom, ft.widthOct, ft.w, ft.cls
                        )
                    )
                    shownN++
                }
            }
            if (shown.unreliable.isNotEmpty()) {
                sb.append("\nUnreliable regions (low confidence, corrections there are suppressed):\n")
                sb.append(shown.unreliable.joinToString("; ")).append("\n")
            }
        }
        sb.append("\nPredicted curves are calculated, not measured. The similarity uses your phone microphone, so compare runs with each other rather than trusting the absolute score.")
        if (lastBt && !isBtOut(lastOutType)) {
            sb.append("\n\nWARNING: audio was played through ").append(devName(lastOutType)).append(", not the Bluetooth earbuds. Check the connection and test again.")
        }
        if (lastPeak < -55) {
            sb.append("\n\nThe signal is very weak. Press the earbud tip against the phone mic hole and raise the volume.")
        }
        return sb.toString()
    }

    private fun exportText(): String {
        if (eqType == 1) {
            val b = baseReport ?: return ""
            if (b.profiles.size <= profileIdx) return ""
            val p = b.profiles[profileIdx]
            val sb = StringBuilder()
            sb.append(String.format(Locale.US, "Preamp: %.1f dB\n", p.preamp))
            for (i in 0 until p.filters.size) {
                val f = p.filters[i]
                sb.append(String.format(Locale.US, "Filter %d: ON PK Fc %.0f Hz Gain %.1f dB Q %.2f\n", i + 1, f.f, f.g, f.q))
            }
            return sb.toString()
        }
        return graphicExport
    }

    private fun copyEq() {
        val text = exportText()
        if (text.isEmpty()) {
            status.text = "Nothing to copy yet. Run a sweep test first."
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("EQ", text))
        status.text = "EQ copied to the clipboard."
    }

    // ------------------------------------------------------------------------------------------------------------
    // Graphic EQ (fixed bands, strength ratio)
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Finds EQ gains that bring the deviation from the target towards zero. Every measured band is assigned to its
     * nearest EQ band. The filters overlap, so the gains are refined a few times using the predicted response of the
     * whole filter chain.
     */
    private fun fitEq(dev: DoubleArray, cent: DoubleArray, usable: BooleanArray, eqC: DoubleArray, asg: IntArray): DoubleArray {
        val g = DoubleArray(eqC.size)
        for (iter in 0 until 6) {
            val after = DoubleArray(cent.size) {
                if (usable[it]) dev[it] + EqMath.responseDb(cent[it], eqC, g) else Double.NaN
            }
            for (j in eqC.indices) {
                var s = 0.0
                var c = 0
                for (k in cent.indices) {
                    if (usable[k] && asg[k] == j) {
                        s += after[k]
                        c++
                    }
                }
                if (c > 0) g[j] = (g[j] - 0.7 * (s / c)).coerceIn(-24.0, 24.0)
            }
        }
        return g
    }

    private fun analyzeGraphic(results: List<SweepResult>, plan: Plan, eqC: DoubleArray, appliedEq: DoubleArray?) {
        val cArr = DoubleArray(28) { 1000.0 * 2.0.pow((it - 15) / 3.0) }
        val snrMin = if (plan.bt) 6.0 else 10.0
        val m = Pipeline.measure(results, cArr, 1.0 / 6, cal, snrMin)
        val rel = m.level
        val tgt = DoubleArray(cArr.size) { Targets.at(deviceIdx, cArr[it], params) }
        val dev = DoubleArray(cArr.size) { if (rel[it].isNaN()) Double.NaN else rel[it] - tgt[it] }
        // with earbuds, a band more than 15 dB under the target is almost always sealing leakage, not the earbud
        val usable = BooleanArray(cArr.size) { !dev[it].isNaN() && (!plan.bt || dev[it] > -15.0) }

        val mm = eqC.size
        val asg = IntArray(cArr.size) { k ->
            var best = 0
            var bd = Double.MAX_VALUE
            for (j in 0 until mm) {
                val dd = abs(ln(cArr[k] / eqC[j]))
                if (dd < bd) {
                    bd = dd
                    best = j
                }
            }
            best
        }
        val resid = fitEq(dev, cArr, usable, eqC, asg)
        val has = BooleanArray(mm) { j -> cArr.indices.any { asg[it] == j && usable[it] } }
        val dropped = (0 until mm).any { j -> !has[j] && cArr.indices.any { asg[it] == j && !dev[it].isNaN() } }

        // total EQ = what the sweep was already filtered with (EQ in test) + the new residual correction
        val base = appliedEq ?: DoubleArray(mm)
        val rawTotal = DoubleArray(mm) { if (has[it]) base[it] + resid[it] else Double.NaN }
        var rawMax = -1e9
        var rawMin = 1e9
        for (v in rawTotal) {
            if (!v.isNaN()) {
                rawMax = max(rawMax, v)
                rawMin = min(rawMin, v)
            }
        }
        val rawRange = if (rawMax < rawMin) 0.0 else rawMax - rawMin

        // total gains for a strength ratio r: all gains are multiplied by r; boosts are capped at +6 dB by a global shift
        fun build(r: Double): DoubleArray {
            var top = -1e9
            for (v in rawTotal) if (!v.isNaN()) top = max(top, v * r)
            val shift = if (top > 6.0) top - 6.0 else 0.0
            return DoubleArray(mm) {
                if (rawTotal[it].isNaN()) Double.NaN else Math.round((rawTotal[it] * r - shift) * 2) / 2.0
            }
        }

        fun effective(tot: DoubleArray): DoubleArray = DoubleArray(mm) { if (tot[it].isNaN()) 0.0 else tot[it] - base[it] }

        val refIdx = cArr.indices.filter { cArr[it] in 780.0..1300.0 }
        // predicted response after applying an EQ change, normalised at 1 kHz like the measurement itself
        fun predictRel(rEff: DoubleArray): DoubleArray {
            var refShift = 0.0
            for (k in refIdx) refShift += EqMath.responseDb(cArr[k], eqC, rEff)
            refShift /= refIdx.size
            return DoubleArray(cArr.size) {
                if (rel[it].isNaN()) Double.NaN else rel[it] + EqMath.responseDb(cArr[it], eqC, rEff) - refShift
            }
        }

        fun devOf(pr: DoubleArray): DoubleArray = DoubleArray(cArr.size) { if (pr[it].isNaN()) Double.NaN else pr[it] - tgt[it] }

        fun simOf(tot: DoubleArray): Double = Pipeline.similarity(cArr, devOf(predictRel(effective(tot))))

        // strength ratio: fixed by the user, or automatic (keep the whole EQ within the range limit and pick the
        // gentlest ratio whose predicted similarity is within 1 point of the best one)
        val ratio: Double = when (shapeMode) {
            0, 1 -> {
                val limit = if (shapeMode == 0) 18.0 else 12.0
                val rMax = if (rawRange > limit) limit / rawRange else 1.0
                val steps = ceil(rMax / 0.05).toInt()
                val rs = DoubleArray(steps + 1) { max(rMax - 0.05 * it, 0.0) }
                val ss = DoubleArray(rs.size) {
                    val v = simOf(build(rs[it]))
                    if (v.isNaN()) -1.0 else v
                }
                var best = -1.0
                for (v in ss) best = max(best, v)
                if (best < 0) {
                    rMax
                } else {
                    var chosen = rMax
                    for (i in rs.indices) if (ss[i] >= best - 1.0) chosen = rs[i]
                    chosen
                }
            }
            2 -> 1.0
            3 -> 0.75
            4 -> 0.5
            else -> 0.25
        }

        val total = build(ratio)
        val rEff = effective(total)
        lastGraphicEq = DoubleArray(mm) { if (total[it].isNaN()) base[it] else total[it] }
        val predRel = predictRel(rEff)
        val predDev = devOf(predRel)
        val simNow = Pipeline.similarity(cArr, dev)
        val simAfter = Pipeline.similarity(cArr, predDev)

        var finMax = -1e9
        var finMin = 1e9
        for (v in total) {
            if (!v.isNaN()) {
                finMax = max(finMax, v)
                finMin = min(finMin, v)
            }
        }
        val finRange = if (finMax < finMin) 0.0 else finMax - finMin
        val outName = devName(lastOutType)

        val sb = StringBuilder()
        val ex = StringBuilder()
        sb.append("Output: $outName   ${micNames[micMode]} ($usedSource)\n")
        sb.append(String.format(Locale.US, "Strongest band: %.0f dBFS   Runs: %d\n", lastPeak, results.size))
        sb.append("Target: ").append(Targets.NAMES[deviceIdx]).append("   EQ bands: $mm\n")
        if (!simNow.isNaN() && !simAfter.isNaN()) {
            sb.append(String.format(Locale.US, "Similarity to target (100 Hz - 10 kHz): %.0f%%  ->  %.0f%% with the suggested EQ\n", simNow, simAfter))
        }
        sb.append(String.format(Locale.US, "Strength ratio: x%.2f   (EQ range %.0f dB -> %.0f dB)\n", ratio, rawRange, finRange))
        if (ratio < 0.001) sb.append("The EQ would not improve the similarity, so all gains are 0.\n")
        if (appliedEq != null) sb.append("EQ in test: ON (the sweep was pre-filtered with the previous EQ; the list below is the new total)\n")
        sb.append("\nSuggested EQ ($mm bands):\n")
        for (j in 0 until mm) {
            val label = fmtHz(eqC[j])
            if (total[j].isNaN()) {
                sb.append(String.format(Locale.US, "%8s     n/a\n", label))
            } else if (appliedEq != null) {
                sb.append(String.format(Locale.US, "%8s  %+5.1f dB  (new %+4.1f)\n", label, total[j], rEff[j]))
                ex.append(String.format(Locale.US, "%s: %+.1f dB\n", label, total[j]))
            } else {
                sb.append(String.format(Locale.US, "%8s  %+5.1f dB\n", label, total[j]))
                ex.append(String.format(Locale.US, "%s: %+.1f dB\n", label, total[j]))
            }
        }
        sb.append("\nIf your equalizer has a preamp or a lower overall volume, remember that the largest boost is kept at +6 dB or less by shifting all gains down together.")
        sb.append("\nn/a = no clear signal around that band, so no correction is suggested.")
        sb.append("\nThe similarity is measured with your phone microphone, not an ear simulator, so treat it as a comparison between runs rather than an absolute score.")
        if (plan.bt && !isBtOut(lastOutType)) {
            sb.append("\n\nWARNING: audio was played through $outName, not the Bluetooth earbuds. Check the connection and test again.")
        }
        if (lastPeak < -55) {
            sb.append("\n\nThe signal is very weak. Press the earbud tip against the phone mic hole and raise the volume.")
        }
        if (plan.bt && dropped) {
            sb.append("\n\nSome bands were more than 15 dB under the target and were left out of the EQ: the earbud is probably not sealed against the mic.")
        }
        val doneText = if (!simNow.isNaN() && !simAfter.isNaN()) {
            String.format(Locale.US, "Done. Similarity %.0f%% -> %.0f%% with EQ (ratio x%.2f).", simNow, simAfter, ratio)
        } else {
            "Done."
        }

        val layers = ArrayList<Layer>()
        val pv = prevGraphicRel
        if (pv != null && pv.size == cArr.size) layers.add(Layer(cArr, pv, Color.rgb(140, 140, 150), 2f, "previous run"))
        if (deviceIdx != 0) layers.add(Layer(cArr, tgt, Color.rgb(255, 215, 70), 2f, "target"))
        layers.add(Layer(cArr, predRel, Color.rgb(90, 220, 120), 2f, "predicted with EQ"))
        layers.add(Layer(cArr, rel, Color.rgb(80, 200, 255), 3f, "measured (rel. to 1 kHz)"))
        layers.add(Layer(eqC, total, Color.rgb(255, 140, 60), 3f, "suggested EQ", true))
        prevGraphicRel = rel
        graphicText = sb.toString()
        graphicExport = ex.toString()
        ui {
            chart.setLayers(layers, DoubleArray(0), DoubleArray(0))
            result.text = graphicText
            status.text = doneText
        }
    }
}
