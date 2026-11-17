package com.example.sweepeq

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Radix-2 FFT with a Hann window. Output power is amplitude^2 (a full-scale sine = 1.0 = 0 dBFS). */
class Fft(private val n: Int) {
    private val cosT = DoubleArray(n / 2) { cos(2 * PI * it / n) }
    private val sinT = DoubleArray(n / 2) { -sin(2 * PI * it / n) }
    private val rev = IntArray(n)
    private val win = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }
    private val norm: Double
    private val re = DoubleArray(n)
    private val im = DoubleArray(n)

    init {
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) rev[i] = Integer.reverse(i) ushr (32 - bits)
        norm = win.sum() / 2
    }

    fun power(pcm: ShortArray, start: Int, out: DoubleArray) {
        for (i in 0 until n) {
            val j = start + i
            val v = if (j >= 0 && j < pcm.size) pcm[j] / 32768.0 else 0.0
            re[rev[i]] = v * win[i]
            im[rev[i]] = 0.0
        }
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + half) {
                    val wr = cosT[k]
                    val wi = sinT[k]
                    val xr = re[j + half] * wr - im[j + half] * wi
                    val xi = re[j + half] * wi + im[j + half] * wr
                    re[j + half] = re[j] - xr
                    im[j + half] = im[j] - xi
                    re[j] = re[j] + xr
                    im[j] = im[j] + xi
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
        for (k in 0 until n / 2) out[k] = (re[k] * re[k] + im[k] * im[k]) / (norm * norm)
    }
}

/** Timeline of the test signal. Bluetooth earbuds need a wake-up tone and more slack for latency. */
internal class Plan(val bt: Boolean) {
    val wake = if (bt) 1.5 else 0.0
    val burst = if (bt) 0.4 else 0.25
    val gap = if (bt) 1.0 else 0.55
    val burstAt = wake
    val sweepAt = wake + burst + gap
    val tail = if (bt) 1.2 else 0.6
    val extraRec = if (bt) 2.0 else 1.0
}

/** Magnitude response (dB) of the 10-band peaking EQ, used to predict the effect of a suggestion. */
internal object EqMath {
    fun peakDb(freq: Double, fc: Double, g: Double): Double {
        val a = 10.0.pow(g / 40)
        val w0 = 2 * PI * fc / MainActivity.SR
        val al = sin(w0) / (2 * MainActivity.EQ_Q)
        val cs = cos(w0)
        val b0 = 1 + al * a
        val b1 = -2 * cs
        val b2 = 1 - al * a
        val a0 = 1 + al / a
        val a1 = -2 * cs
        val a2 = 1 - al / a
        val w = 2 * PI * freq / MainActivity.SR
        val nr = b0 + b1 * cos(w) + b2 * cos(2 * w)
        val ni = b1 * sin(w) + b2 * sin(2 * w)
        val dr = a0 + a1 * cos(w) + a2 * cos(2 * w)
        val di = a1 * sin(w) + a2 * sin(2 * w)
        return 10 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
    }

    fun responseDb(freq: Double, gains: DoubleArray): Double {
        var s = 0.0
        for (j in MainActivity.EQ_CENTERS.indices) {
            if (abs(gains[j]) > 1e-9) s += peakDb(freq, MainActivity.EQ_CENTERS[j], gains[j])
        }
        return s
    }
}

class MainActivity : Activity() {

    companion object {
        const val SR = 48000
        const val N = 8192
        const val HOP = 2048
        const val F0 = 20.0
        const val F1 = 20000.0
        const val SWEEP_SEC = 12.0
        const val EQ_Q = 1.41
        val BG = Color.rgb(16, 16, 24)
        val FG = Color.rgb(220, 220, 230)
        val EQ_CENTERS = doubleArrayOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)

        /** Harman in-ear 2019v2 target in dB relative to 1 kHz, at the 28 third-octave centres 31.5 Hz ... 16 kHz. */
        val HARMAN_IE = doubleArrayOf(
            7.7, 7.44, 6.69, 5.59, 4.28, 2.87, 1.44, 0.19, -0.93, -1.58, -1.71, -1.6, -1.4, -1.1,
            -0.66, 0.0, 1.2, 3.07, 5.8, 8.36, 9.4, 8.88, 7.69, 6.32, 3.58, -0.9, -5.66, -10.2
        )
    }

    private val fft = Fft(N)
    private val pw = DoubleArray(N / 2)
    private val micNames = arrayOf("Mic: Auto", "Mic: Main (MIC)", "Mic: Second (CAMCORDER)")
    private val targetNames = arrayOf("Target: Flat", "Target: Harman in-ear")

    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var spectrum: SpectrumView
    private lateinit var graph: ResponseView
    private lateinit var sweepBtn: Button
    private lateinit var liveBtn: Button
    private lateinit var modeBtn: Button
    private lateinit var micBtn: Button
    private lateinit var eqBtn: Button
    private lateinit var targetBtn: Button
    @Volatile private var running = false
    @Volatile private var live = false
    @Volatile private var liveStop = false
    @Volatile private var btMode = false
    @Volatile private var micMode = 0
    @Volatile private var eqOn = false
    @Volatile private var targetMode = 0
    @Volatile private var lastEq: DoubleArray? = null
    private var pending: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val d = resources.displayMetrics.density
        val pad = (12 * d).toInt()
        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Throwable) {
            ""
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(BG)
        }
        root.addView(TextView(this).apply {
            text = "Sweep EQ v$ver"
            textSize = 22f
            setTextColor(FG)
        })
        root.addView(TextView(this).apply {
            text = "Live spectrum shows what the microphone hears (yellow = peak hold, like Spectroid). " +
                "The sweep test plays a beep and a 20 Hz - 20 kHz sweep, then measures the response and suggests an EQ.\n" +
                "Speakers: put the phone where you listen, quiet room, volume about 50%.\n" +
                "BT earbuds: connect them, set Setup to BT earbuds, press the earbud tip firmly against the " +
                "phone microphone hole (seal it with your fingers), media volume 70-100%.\n" +
                "Target: Flat aims for equal level at all frequencies; Harman in-ear aims for the Harman 2019v2 " +
                "in-ear target (both relative to 1 kHz). The similarity % shows how close the response is to the target " +
                "(100% = identical, each 1 dB of RMS error costs about 8%).\n" +
                "EQ in test: after a sweep, switch it On and run again. The sweep is pre-filtered with the suggested " +
                "EQ, so the grey previous curve and the blue new curve show whether the EQ really helps."
            setTextColor(FG)
            textSize = 13f
            setPadding(0, pad / 2, 0, pad / 2)
        })
        liveBtn = Button(this).apply {
            text = "Live spectrum"
            setOnClickListener { toggleLive() }
        }
        sweepBtn = Button(this).apply {
            text = "Start sweep test"
            setOnClickListener { startSweep() }
        }
        modeBtn = Button(this).apply {
            text = "Setup: Speakers"
            setOnClickListener {
                btMode = !btMode
                text = if (btMode) "Setup: BT earbuds" else "Setup: Speakers"
            }
        }
        micBtn = Button(this).apply {
            text = micNames[0]
            setOnClickListener {
                micMode = (micMode + 1) % micNames.size
                text = micNames[micMode]
            }
        }
        targetBtn = Button(this).apply {
            text = targetNames[0]
            setOnClickListener {
                targetMode = (targetMode + 1) % targetNames.size
                text = targetNames[targetMode]
            }
        }
        eqBtn = Button(this).apply {
            text = "EQ in test: Off"
            setOnClickListener {
                if (!eqOn && lastEq == null) {
                    status.text = "Run a sweep test first to get an EQ suggestion."
                } else {
                    eqOn = !eqOn
                    text = if (eqOn) "EQ in test: ON" else "EQ in test: Off"
                }
            }
        }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(liveBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(sweepBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row1)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(modeBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row2.addView(micBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row2)
        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row3.addView(targetBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row3.addView(eqBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row3)
        status = TextView(this).apply {
            setTextColor(FG)
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(status)
        spectrum = SpectrumView(this)
        root.addView(spectrum, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (430 * d).toInt()))
        graph = ResponseView(this)
        root.addView(graph, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (280 * d).toInt()))
        result = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(FG)
            setPadding(0, pad / 2, 0, 0)
        }
        root.addView(result)
        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            addView(root)
        })
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
        sweepBtn.isEnabled = !locked
        modeBtn.isEnabled = !locked
        micBtn.isEnabled = !locked
        eqBtn.isEnabled = !locked
        targetBtn.isEnabled = !locked
    }

    private fun startSweep() {
        withMic {
            if (running) return@withMic
            running = true
            lockButtons(true)
            liveBtn.isEnabled = false
            result.text = ""
            spectrum.reset()
            val plan = Plan(btMode)
            thread {
                try {
                    runTest(plan)
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

    private fun openRecord(): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val sources = when (micMode) {
            1 -> intArrayOf(MediaRecorder.AudioSource.MIC)
            2 -> intArrayOf(MediaRecorder.AudioSource.CAMCORDER)
            else -> intArrayOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC)
        }
        for (src in sources) {
            try {
                val a = AudioRecord(src, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, SR) * 2)
                if (a.state == AudioRecord.STATE_INITIALIZED) {
                    // always record with the phone's own microphone, never the earbuds' mic
                    try {
                        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        val dev = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                        if (dev != null) a.setPreferredDevice(dev)
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

    private fun pushFrame(p: DoubleArray, pcm: ShortArray, from: Int, to: Int) {
        val db = FloatArray(N / 2) { (10 * log10(p[it] + 1e-12)).toFloat() }
        var pk = 0
        for (i in max(0, from) until min(to, pcm.size)) pk = max(pk, abs(pcm[i].toInt()))
        val lvl = (20 * log10(max(pk, 1) / 32768.0)).toFloat()
        ui { spectrum.update(db, lvl) }
    }

    private fun runLive() {
        val rec = openRecord()
        val frame = ShortArray(N)
        rec.startRecording()
        try {
            while (!liveStop) {
                System.arraycopy(frame, HOP, frame, 0, N - HOP)
                var got = 0
                while (got < HOP && !liveStop) {
                    val r = rec.read(frame, N - HOP + got, HOP - got)
                    if (r < 0) {
                        liveStop = true
                        break
                    }
                    got += r
                }
                if (got < HOP) break
                fft.power(frame, 0, pw)
                pushFrame(pw, frame, N - HOP, N)
            }
        } finally {
            rec.stop()
            rec.release()
        }
        ui { status.text = "Live stopped." }
    }

    /** Pre-filters the sweep with the suggested 10-band EQ (peaking filters, about one octave wide). */
    private fun applyEq(play: ShortArray, from: Int, len: Int, gains: DoubleArray) {
        val y = DoubleArray(len) { play[from + it] / 32768.0 }
        for (j in EQ_CENTERS.indices) {
            val g = gains[j]
            if (abs(g) < 0.05) continue
            val a = 10.0.pow(g / 40)
            val w0 = 2 * PI * EQ_CENTERS[j] / SR
            val alpha = sin(w0) / (2 * EQ_Q)
            val cs = cos(w0)
            val b0 = 1 + alpha * a
            val b1 = -2 * cs
            val b2 = 1 - alpha * a
            val a0 = 1 + alpha / a
            val a1 = -2 * cs
            val a2 = 1 - alpha / a
            var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
            for (i in 0 until len) {
                val xi = y[i]
                val yi = (b0 * xi + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2) / a0
                x2 = x1; x1 = xi; y2 = y1; y1 = yi
                y[i] = yi
            }
        }
        var pk = 0.0
        for (v in y) pk = max(pk, abs(v))
        val scale = if (pk > 0.5) 0.5 / pk else 1.0
        for (i in 0 until len) play[from + i] = (y[i] * scale * 32767).toInt().toShort()
    }

    private fun runTest(plan: Plan) {
        val total = ((plan.sweepAt + SWEEP_SEC + plan.tail) * SR).toInt()
        val play = ShortArray(total)

        // quiet 1 kHz wake-up tone (earbuds only): Bluetooth links often swallow the first moments of audio
        val wn = (plan.wake * SR).toInt()
        for (i in 0 until wn) {
            play[i] = (sin(2 * PI * 1000.0 * i / SR) * 0.04 * 32767).toInt().toShort()
        }
        // 1 kHz marker burst (used to find the playback/recording latency)
        val bn = (plan.burst * SR).toInt()
        for (i in 0 until bn) {
            val env = min(1.0, min(i, bn - 1 - i).toDouble() / (0.01 * SR))
            play[wn + i] = (sin(2 * PI * 1000.0 * i / SR) * 0.5 * env * 32767).toInt().toShort()
        }
        // logarithmic sweep
        val s0 = (plan.sweepAt * SR).toInt()
        val sn = (SWEEP_SEC * SR).toInt()
        val lnr = ln(F1 / F0)
        val fade = (0.05 * SR).toInt()
        for (i in 0 until sn) {
            val t = i.toDouble() / SR
            val phase = 2 * PI * F0 * SWEEP_SEC / lnr * (exp(t / SWEEP_SEC * lnr) - 1)
            val env = min(1.0, min(i, sn - 1 - i).toDouble() / fade)
            play[s0 + i] = (sin(phase) * 0.5 * env * 32767).toInt().toShort()
        }

        val eq = if (eqOn) lastEq else null
        if (eq != null) applyEq(play, s0, sn, eq)

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
                    .setSampleRate(SR)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(play.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(play, 0, play.size)

        val recTotal = total + (plan.extraRec * SR).toInt()
        val buf = ShortArray(recTotal)
        var n = 0
        var started = false
        var lastPct = -1
        var nextEnd = N
        var outType = -1
        var routeRead = false
        rec.startRecording()
        while (n < recTotal) {
            val r = rec.read(buf, n, min(4096, recTotal - n))
            if (r < 0) break
            n += r
            if (!started && n >= SR * 3 / 10) {
                track.play()
                started = true
            }
            if (started && !routeRead && n > SR * 2) {
                routeRead = true
                outType = try {
                    track.routedDevice?.type ?: -1
                } catch (_: Throwable) {
                    -1
                }
            }
            while (nextEnd <= n) {
                fft.power(buf, nextEnd - N, pw)
                pushFrame(pw, buf, nextEnd - HOP, nextEnd)
                nextEnd += HOP
            }
            val pct = n * 100 / recTotal
            if (pct != lastPct) {
                lastPct = pct
                ui { status.text = "Measuring... $pct%" }
            }
        }
        rec.stop(); rec.release()
        track.stop(); track.release()

        ui { status.text = "Analyzing..." }
        analyze(buf.copyOf(n), plan, outType, eq)
    }

    private fun bandpass(x: DoubleArray, from: Int, to: Int, fc: Double, q: Double): DoubleArray {
        val w0 = 2 * PI * fc / SR
        val alpha = sin(w0) / (2 * q)
        val a0 = 1 + alpha
        val b0 = alpha / a0
        val b2 = -alpha / a0
        val a1 = -2 * cos(w0) / a0
        val a2 = (1 - alpha) / a0
        val y = DoubleArray(to - from)
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (i in from until to) {
            val xi = x[i]
            val yi = b0 * xi + b2 * x2 - a1 * y1 - a2 * y2
            y[i - from] = yi
            x2 = x1; x1 = xi; y2 = y1; y1 = yi
        }
        return y
    }

    /**
     * Finds the 10 EQ gains that bring the deviation from the target towards zero. The filters overlap,
     * so the gains are refined a few times using the predicted response of the whole filter chain.
     */
    private fun fitEq(dev: DoubleArray, cent: DoubleArray, usable: BooleanArray): DoubleArray {
        val g = DoubleArray(EQ_CENTERS.size)
        for (iter in 0 until 4) {
            val after = DoubleArray(cent.size) {
                if (usable[it]) dev[it] + EqMath.responseDb(cent[it], g) else Double.NaN
            }
            for (j in EQ_CENTERS.indices) {
                var s = 0.0
                var c = 0
                for (k in cent.indices) {
                    if (usable[k] && abs(ln(cent[k] / EQ_CENTERS[j]) / ln(2.0)) <= 0.34) {
                        s += after[k]
                        c++
                    }
                }
                if (c > 0) g[j] = (g[j] - 0.8 * (s / c)).coerceIn(-12.0, 6.0)
            }
        }
        return g
    }

    private fun analyze(pcm: ShortArray, plan: Plan, outType: Int, appliedEq: DoubleArray?) {
        val n = pcm.size
        if (n < ((plan.sweepAt + SWEEP_SEC) * SR).toInt()) throw IllegalStateException("Recording too short, try again.")

        var peakAbs = 0
        for (s in pcm) peakAbs = max(peakAbs, abs(s.toInt()))
        if (peakAbs >= 32000) throw IllegalStateException("The recording is clipping. Lower the media volume and try again.")

        // find the marker burst (latency)
        val lim = min(n, ((plan.burstAt + 4.0) * SR).toInt())
        val x = DoubleArray(lim) { pcm[it] / 32768.0 }
        val yb = bandpass(x, 0, lim, 1000.0, 5.0)
        val win = 240
        val env = DoubleArray(lim)
        var acc = 0.0
        for (i in 0 until lim) {
            acc += abs(yb[i])
            if (i >= win) acc -= abs(yb[i - win])
            env[i] = acc / win
        }
        val mx = env.max()
        val minTone = if (plan.bt) 0.0005 else 0.002
        if (mx < minTone) throw IllegalStateException("Test tone not heard. Raise the media volume, press the earbud closer to the mic, or reduce noise.")
        val thr = mx * 0.5
        val onset = max(0, env.indexOfFirst { it > thr } - win / 2)

        val half = N / 2
        val binHz = SR.toDouble() / N
        val sw0 = onset + ((plan.sweepAt - plan.burstAt) * SR).toInt()

        // noise spectrum: after the sweep if possible, else the gap between beep and sweep
        val noise = DoubleArray(half)
        fun accumulate(from: Int, to: Int): Int {
            var cnt = 0
            var s = max(0, from)
            while (s + N <= min(n, to)) {
                fft.power(pcm, s, pw)
                for (k in 0 until half) noise[k] = noise[k] + pw[k]
                cnt++
                s += HOP
            }
            return cnt
        }
        var nf = accumulate(sw0 + ((SWEEP_SEC + 0.15) * SR).toInt(), n)
        if (nf == 0) nf = accumulate(onset + ((plan.burst + 0.1) * SR).toInt(), sw0 - (0.02 * SR).toInt())
        if (nf == 0) throw IllegalStateException("Could not estimate the noise floor, try again.")
        val nfd = nf.toDouble()
        for (k in 0 until half) noise[k] = noise[k] / nfd

        // follow the sweep frame by frame: find the strongest peak near the expected frequency
        val lnr = ln(F1 / F0)
        val fpL = ArrayList<Double>()
        val sigL = ArrayList<Double>()
        val snrL = ArrayList<Double>()
        var start = sw0 - N / 2
        while (start + N <= n) {
            val tc = (start + N / 2 - sw0).toDouble() / SR
            if (tc > SWEEP_SEC) break
            if (tc >= 0) {
                fft.power(pcm, start, pw)
                val fe = F0 * exp(tc / SWEEP_SEC * lnr)
                val kLo = (fe / 1.5 / binHz).toInt().coerceIn(1, half - 3)
                val kHi = ceil(fe * 1.5 / binHz).toInt().coerceIn(kLo + 1, half - 1)
                var kp = kLo
                for (k in kLo..kHi) if (pw[k] > pw[kp]) kp = k
                val w = max(3, ceil(kp * (2.0.pow(0.2) - 1)).toInt())
                val k1 = max(1, kp - w)
                val k2 = min(half - 1, kp + w)
                var sig = 0.0
                var nz = 0.0
                for (k in k1..k2) {
                    sig += pw[k]
                    nz += noise[k]
                }
                if (sig > 0 && nz > 0) {
                    fpL.add(kp * binHz)
                    sigL.add(max(sig - nz, sig * 0.05))
                    snrL.add(10 * log10(sig / nz))
                }
            }
            start += HOP
        }

        val snrMin = if (plan.bt) 6.0 else 10.0
        val centers = (-15..12).map { 1000.0 * 2.0.pow(it / 3.0) }
        val cArr = centers.toDoubleArray()
        val lv = DoubleArray(cArr.size) { Double.NaN }
        for ((k, fc) in centers.withIndex()) {
            var s = 0.0
            var snrSum = 0.0
            var c = 0
            for (i in fpL.indices) {
                val f = fpL[i]
                val inBand = abs(ln(f / fc) / ln(2.0)) <= 1.0 / 6 || abs(f - fc) <= binHz / 2
                if (inBand) {
                    s += sigL[i]
                    snrSum += snrL[i]
                    c++
                }
            }
            if (c > 0 && snrSum / c >= snrMin) lv[k] = 10 * log10(s / c)
        }

        // everything is plotted relative to 1 kHz (the usual convention, and what Spectroid users compare against)
        val refVals = centers.indices.filter { centers[it] in 780.0..1300.0 && !lv[it].isNaN() }.map { lv[it] }
        if (refVals.size < 2) throw IllegalStateException("Signal too weak or too noisy around 1 kHz. Raise the volume or reduce noise and try again.")
        val ref = refVals.average()
        val rel = DoubleArray(lv.size) { if (lv[it].isNaN()) Double.NaN else lv[it] - ref }

        // deviation from the chosen target (flat = 0 dB everywhere, or Harman in-ear 2019v2)
        val tgt = if (targetMode == 1) HARMAN_IE.copyOf() else DoubleArray(cArr.size)
        val dev = DoubleArray(cArr.size) { if (rel[it].isNaN()) Double.NaN else rel[it] - tgt[it] }
        // with earbuds, a band more than 15 dB under the target is almost always sealing leakage, not the earbud
        val usable = BooleanArray(cArr.size) { !dev[it].isNaN() && (!plan.bt || dev[it] > -15.0) }

        val resid = fitEq(dev, cArr, usable)
        val has = BooleanArray(EQ_CENTERS.size) { j ->
            cArr.indices.any { usable[it] && abs(ln(cArr[it] / EQ_CENTERS[j]) / ln(2.0)) <= 0.34 }
        }
        val dropped = EQ_CENTERS.indices.any { j ->
            !has[j] && cArr.indices.any { !dev[it].isNaN() && abs(ln(cArr[it] / EQ_CENTERS[j]) / ln(2.0)) <= 0.34 }
        }
        // total EQ = what the sweep was already filtered with (EQ in test) + the new residual correction
        val base = appliedEq ?: DoubleArray(EQ_CENTERS.size)
        val total = DoubleArray(EQ_CENTERS.size) {
            if (has[it]) Math.round((base[it] + resid[it]).coerceIn(-12.0, 6.0) * 2) / 2.0 else Double.NaN
        }
        val rEff = DoubleArray(EQ_CENTERS.size) { if (total[it].isNaN()) 0.0 else total[it] - base[it] }
        lastEq = DoubleArray(EQ_CENTERS.size) { if (total[it].isNaN()) base[it] else total[it] }

        val predRel = DoubleArray(cArr.size) {
            if (rel[it].isNaN()) Double.NaN else rel[it] + EqMath.responseDb(cArr[it], rEff)
        }
        val predDev = DoubleArray(cArr.size) { if (predRel[it].isNaN()) Double.NaN else predRel[it] - tgt[it] }

        fun similarity(d: DoubleArray): Double {
            var s = 0.0
            var c = 0
            for (k in cArr.indices) {
                if (cArr[k] in 100.0..10000.0 && !d[k].isNaN()) {
                    s += d[k] * d[k]
                    c++
                }
            }
            if (c < 8) return Double.NaN
            return max(0.0, 100.0 - sqrt(s / c) * 100.0 / 12.0)
        }
        val simNow = similarity(dev)
        val simAfter = similarity(predDev)

        val peakDb = if (sigL.isEmpty()) -120.0 else 10 * log10(sigL.max())
        val outName = devName(outType)
        val tName = if (targetMode == 1) "Harman in-ear 2019v2" else "flat (0 dB)"

        val sb = StringBuilder()
        sb.append("Output: $outName   ${micNames[micMode]}\n")
        sb.append(String.format(Locale.US, "Strongest band: %.0f dBFS\n", peakDb))
        sb.append("Target: $tName\n")
        if (!simNow.isNaN() && !simAfter.isNaN()) {
            sb.append(String.format(Locale.US, "Similarity to target (100 Hz - 10 kHz): %.0f%%  ->  %.0f%% with the suggested EQ\n", simNow, simAfter))
        }
        if (appliedEq != null) sb.append("EQ in test: ON (the sweep was pre-filtered with the previous EQ; the list below is the new total)\n")
        sb.append("\nSuggested EQ (10 bands):\n")
        for (j in EQ_CENTERS.indices) {
            val label = if (EQ_CENTERS[j] >= 1000) "${(EQ_CENTERS[j] / 1000).toInt()} kHz" else "${EQ_CENTERS[j].toInt()} Hz"
            if (total[j].isNaN()) {
                sb.append(String.format(Locale.US, "%7s     n/a\n", label))
            } else if (appliedEq != null) {
                sb.append(String.format(Locale.US, "%7s  %+5.1f dB  (new %+4.1f)\n", label, total[j], rEff[j]))
            } else {
                sb.append(String.format(Locale.US, "%7s  %+5.1f dB\n", label, total[j]))
            }
        }
        sb.append("\nn/a = no clear signal in that band, so no correction is suggested.")
        sb.append("\nBoosts are limited to +6 dB and cuts to -12 dB. Narrow dips are often cancellations: do not boost those.")
        sb.append("\nThe similarity is measured with your phone microphone, not an ear simulator, so treat it as a comparison between runs rather than an absolute score.")
        if (plan.bt && !isBtOut(outType)) {
            sb.append("\n\nWARNING: audio was played through $outName, not the Bluetooth earbuds. Check the connection and test again.")
        }
        if (peakDb < -55) {
            sb.append("\n\nThe signal is very weak. Press the earbud tip against the phone mic hole and raise the volume.")
        }
        if (plan.bt && dropped) {
            sb.append("\n\nSome bands were more than 15 dB under the target and were left out of the EQ: the earbud is probably not sealed against the mic.")
        }
        val doneText = if (!simNow.isNaN() && !simAfter.isNaN()) {
            String.format(Locale.US, "Done. Similarity %.0f%% -> %.0f%% with EQ.", simNow, simAfter)
        } else {
            "Done."
        }
        ui {
            graph.setData(cArr, rel, tgt, predRel, EQ_CENTERS, total, targetMode == 1)
            result.text = sb.toString()
            status.text = doneText
        }
    }
}

/** Spectroid-style view: live spectrum with peak hold on top, scrolling waterfall below. */
class SpectrumView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val wfW = 600
    private val wfH = 360
    private val px = IntArray(wfW * wfH)
    private val bm = Bitmap.createBitmap(wfW, wfH, Bitmap.Config.ARGB_8888)
    private var db = FloatArray(0)
    private var hold = FloatArray(0)
    private var level = -120f
    private val binHz = MainActivity.SR.toDouble() / MainActivity.N
    private val f0 = MainActivity.F0
    private val ratio = MainActivity.F1 / MainActivity.F0

    init {
        reset()
    }

    fun reset() {
        px.fill(Color.BLACK)
        bm.setPixels(px, 0, wfW, 0, 0, wfW, wfH)
        db = FloatArray(0)
        hold = FloatArray(0)
        level = -120f
        invalidate()
    }

    private fun maxIn(a: FloatArray, fa: Double, fb: Double): Float {
        val k1 = (fa / binHz).toInt().coerceIn(0, a.size - 1)
        val k2 = ceil(fb / binHz).toInt().coerceIn(k1, a.size - 1)
        var m = a[k1]
        for (k in k1..k2) if (a[k] > m) m = a[k]
        return m
    }

    private fun color(v: Float): Int {
        val t = ((v + 100f) / 80f).coerceIn(0f, 1f)
        if (t < 0.05f) return Color.BLACK
        return Color.HSVToColor(floatArrayOf(250f * (1f - t), 1f, min(1f, 0.3f + t)))
    }

    fun update(newDb: FloatArray, lvl: Float) {
        if (hold.size != newDb.size) hold = FloatArray(newDb.size) { -140f }
        for (i in newDb.indices) hold[i] = max(newDb[i], hold[i] - 0.4f)
        db = newDb
        level = lvl
        System.arraycopy(px, 0, px, wfW, wfW * (wfH - 1))
        for (x in 0 until wfW) {
            val fa = f0 * ratio.pow(x.toDouble() / wfW)
            val fb = f0 * ratio.pow((x + 1).toDouble() / wfW)
            px[x] = color(maxIn(newDb, fa, fb))
        }
        bm.setPixels(px, 0, wfW, 0, 0, wfW, wfH)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.rgb(8, 8, 14))
        val l = 38 * d
        val r = width - 8 * d
        val t = 8 * d
        val pb = t + 200 * d
        val pw = (r - l).toDouble()
        val ph = pb - t

        fun xf(f: Double) = (l + log10(f / f0) / log10(ratio) * pw).toFloat()
        fun yd(v: Float) = (t + (-(v.coerceIn(-100f, 0f))) / 100f * ph).toFloat()

        p.textSize = 10 * d
        p.strokeWidth = 1f
        for (g in 0..5) {
            val v = -20f * g
            p.style = Paint.Style.STROKE
            p.color = Color.rgb(50, 50, 70)
            c.drawLine(l, yd(v), r, yd(v), p)
            p.style = Paint.Style.FILL
            p.color = Color.LTGRAY
            c.drawText(String.format(Locale.US, "%.0f", v), 4 * d, yd(v) + 4 * d, p)
        }
        val marks = listOf(
            20.0 to "20", 50.0 to "50", 100.0 to "100", 200.0 to "200", 500.0 to "500",
            1000.0 to "1k", 2000.0 to "2k", 5000.0 to "5k", 10000.0 to "10k", 20000.0 to "20k"
        )
        for ((f, s) in marks) {
            p.style = Paint.Style.STROKE
            p.color = Color.rgb(50, 50, 70)
            c.drawLine(xf(f), t, xf(f), pb, p)
            p.style = Paint.Style.FILL
            p.color = Color.LTGRAY
            c.drawText(s, xf(f) - 8 * d, pb + 12 * d, p)
        }

        if (db.isNotEmpty()) {
            val fill = Path()
            val line = Path()
            val holdLine = Path()
            fill.moveTo(l, pb)
            var xp = l.toInt()
            var first = true
            while (xp <= r.toInt()) {
                val fa = f0 * ratio.pow((xp - l) / pw)
                val fb = f0 * ratio.pow((xp + 2 - l) / pw)
                val y1 = yd(maxIn(db, fa, fb))
                val y2 = yd(maxIn(hold, fa, fb))
                fill.lineTo(xp.toFloat(), y1)
                if (first) {
                    line.moveTo(xp.toFloat(), y1)
                    holdLine.moveTo(xp.toFloat(), y2)
                    first = false
                } else {
                    line.lineTo(xp.toFloat(), y1)
                    holdLine.lineTo(xp.toFloat(), y2)
                }
                xp += 2
            }
            fill.lineTo(r, pb)
            fill.close()
            p.style = Paint.Style.FILL
            p.color = Color.argb(70, 60, 220, 90)
            c.drawPath(fill, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.5f * d
            p.color = Color.rgb(80, 240, 110)
            c.drawPath(line, p)
            p.strokeWidth = 1f * d
            p.color = Color.rgb(255, 210, 60)
            c.drawPath(holdLine, p)

            var kp = (f0 / binHz).toInt().coerceIn(0, db.size - 1)
            for (k in kp until db.size) if (db[k] > db[kp]) kp = k
            p.style = Paint.Style.FILL
            p.color = Color.WHITE
            p.textSize = 11 * d
            c.drawText(
                String.format(Locale.US, "Peak %.0f Hz  %.1f dB    Input peak %.1f dBFS", kp * binHz, db[kp], level),
                l + 6 * d, t + 14 * d, p
            )
        }

        p.style = Paint.Style.FILL
        p.color = Color.LTGRAY
        p.textSize = 10 * d
        c.drawText("Waterfall (newest at top)", l, pb + 28 * d, p)
        val rect = RectF(l, pb + 34 * d, r, height - 6 * d)
        c.drawBitmap(bm, null, rect, bmpPaint)
    }
}

class ResponseView(ctx: Context) : View(ctx) {
    private var fc = DoubleArray(0)
    private var rel = DoubleArray(0)
    private var prev = DoubleArray(0)
    private var tgt = DoubleArray(0)
    private var pred = DoubleArray(0)
    private var eqF = DoubleArray(0)
    private var eqG = DoubleArray(0)
    private var showTarget = false
    private var lim = 24.0
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density

    fun setData(
        fc: DoubleArray, rel: DoubleArray, tgt: DoubleArray, pred: DoubleArray,
        eqF: DoubleArray, eqG: DoubleArray, showTarget: Boolean
    ) {
        val old = this.rel
        prev = if (old.size == rel.size) old else DoubleArray(0)
        this.fc = fc; this.rel = rel; this.tgt = tgt; this.pred = pred
        this.eqF = eqF; this.eqG = eqG; this.showTarget = showTarget
        var m = 0.0
        for (v in rel) if (!v.isNaN()) m = max(m, abs(v))
        for (v in prev) if (!v.isNaN()) m = max(m, abs(v))
        if (showTarget) for (v in tgt) m = max(m, abs(v))
        lim = (ceil(m / 12.0) * 12.0).coerceIn(24.0, 48.0)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.rgb(8, 8, 14))
        val l = 40 * d
        val t = 10 * d
        val r = width - 10 * d
        val bt = height - 24 * d
        val pw = r - l
        val ph = bt - t
        val minDb = -lim
        val maxDb = lim
        fun x(f: Double) = (l + (log10(f) - log10(20.0)) / (log10(20000.0) - log10(20.0)) * pw).toFloat()
        fun y(db: Double) = (t + (maxDb - db.coerceIn(minDb, maxDb)) / (maxDb - minDb) * ph).toFloat()

        p.textSize = 10 * d
        p.strokeWidth = 1f
        var db = minDb
        while (db <= maxDb + 0.01) {
            p.style = Paint.Style.STROKE
            p.color = if (abs(db) < 0.01) Color.GRAY else Color.rgb(50, 50, 70)
            c.drawLine(l, y(db), r, y(db), p)
            p.style = Paint.Style.FILL
            p.color = Color.LTGRAY
            c.drawText(String.format(Locale.US, "%+.0f", db), 4 * d, y(db) + 4 * d, p)
            db += 12.0
        }
        for ((f, s) in listOf(20.0 to "20", 100.0 to "100", 1000.0 to "1k", 10000.0 to "10k", 20000.0 to "20k")) {
            p.style = Paint.Style.STROKE
            p.color = Color.rgb(50, 50, 70)
            c.drawLine(x(f), t, x(f), bt, p)
            p.style = Paint.Style.FILL
            p.color = Color.LTGRAY
            c.drawText(s, x(f) - 8 * d, height - 8 * d, p)
        }
        if (fc.isEmpty()) return

        fun curve(xs: DoubleArray, ys: DoubleArray): Path {
            val path = Path()
            var pen = false
            for (i in xs.indices) {
                if (ys[i].isNaN()) {
                    pen = false
                    continue
                }
                if (!pen) {
                    path.moveTo(x(xs[i]), y(ys[i]))
                    pen = true
                } else {
                    path.lineTo(x(xs[i]), y(ys[i]))
                }
            }
            return path
        }

        // previous run (for comparing before / after the EQ)
        if (prev.isNotEmpty()) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2 * d
            p.color = Color.rgb(140, 140, 150)
            c.drawPath(curve(fc, prev), p)
        }

        // target curve
        if (showTarget) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2 * d
            p.color = Color.rgb(255, 215, 70)
            c.drawPath(curve(fc, tgt), p)
        }

        // predicted response after applying the suggested EQ
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2 * d
        p.color = Color.rgb(90, 220, 120)
        c.drawPath(curve(fc, pred), p)

        // measured response (gaps where the signal was too weak)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3 * d
        p.color = Color.rgb(80, 200, 255)
        c.drawPath(curve(fc, rel), p)
        p.style = Paint.Style.FILL
        for (i in fc.indices) {
            if (rel[i].isNaN()) {
                p.color = Color.GRAY
                c.drawCircle(x(fc[i]), y(0.0), 2.5f * d, p)
            }
        }

        // suggested EQ
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3 * d
        p.color = Color.rgb(255, 140, 60)
        c.drawPath(curve(eqF, eqG), p)
        p.style = Paint.Style.FILL
        for (i in eqF.indices) if (!eqG[i].isNaN()) c.drawCircle(x(eqF[i]), y(eqG[i]), 4 * d, p)

        p.color = Color.rgb(80, 200, 255)
        c.drawText("measured (rel. to 1 kHz)", l + 6 * d, t + 12 * d, p)
        p.color = Color.rgb(255, 140, 60)
        c.drawText("suggested EQ", l + 150 * d, t + 12 * d, p)
        p.color = Color.rgb(90, 220, 120)
        c.drawText("predicted with EQ", l + 6 * d, t + 24 * d, p)
        if (showTarget) {
            p.color = Color.rgb(255, 215, 70)
            c.drawText("target", l + 150 * d, t + 24 * d, p)
        }
        p.color = Color.rgb(140, 140, 150)
        c.drawText("previous run", l + 6 * d, t + 36 * d, p)
        p.color = Color.GRAY
        c.drawText("grey dot = no signal", l + 150 * d, t + 36 * d, p)
    }
}
