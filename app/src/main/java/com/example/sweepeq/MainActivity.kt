package com.example.sweepeq

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.media.AudioAttributes
import android.media.AudioFormat
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
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

class MainActivity : Activity() {

    companion object {
        const val SR = 48000
        const val F0 = 20.0
        const val F1 = 20000.0
        const val SWEEP_SEC = 12.0
        const val BURST_SEC = 0.25
        const val SWEEP_START = 0.8
        const val TAIL = 0.6
    }

    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var graph: ResponseView
    private lateinit var btn: Button
    @Volatile private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Sweep EQ"
            textSize = 24f
            setTextColor(Color.BLACK)
        })
        root.addView(TextView(this).apply {
            text = "Place the phone where you listen, microphone facing the speakers, in a quiet room. " +
                "Set the media volume to a comfortable level (about 50%), then start. The test plays a short beep " +
                "followed by a 20 Hz - 20 kHz sweep (about 14 s). The result includes the phone microphone's own response."
            setPadding(0, pad / 2, 0, pad / 2)
        })
        btn = Button(this).apply {
            text = "Start sweep test"
            setOnClickListener { start() }
        }
        root.addView(btn)
        status = TextView(this).apply { setPadding(0, pad / 2, 0, pad / 2) }
        root.addView(status)
        graph = ResponseView(this)
        root.addView(graph, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (280 * d).toInt()))
        result = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, pad / 2, 0, 0)
        }
        root.addView(result)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun ui(block: () -> Unit) = runOnUiThread(block)

    private fun start() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (running) return
        running = true
        btn.isEnabled = false
        result.text = ""
        thread {
            try {
                runTest()
            } catch (e: Throwable) {
                ui { status.text = "Error: ${e.message}" }
            } finally {
                running = false
                ui { btn.isEnabled = true }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) start()
        else status.text = "Microphone permission is required."
    }

    private fun runTest() {
        val total = ((SWEEP_START + SWEEP_SEC + TAIL) * SR).toInt()
        val play = ShortArray(total)

        // 1 kHz marker burst at t = 0 (used to find the playback/recording latency)
        val bn = (BURST_SEC * SR).toInt()
        for (i in 0 until bn) {
            val env = min(1.0, min(i, bn - 1 - i).toDouble() / (0.01 * SR))
            play[i] = (sin(2 * PI * 1000.0 * i / SR) * 0.5 * env * 32767).toInt().toShort()
        }
        // logarithmic sweep
        val s0 = (SWEEP_START * SR).toInt()
        val sn = (SWEEP_SEC * SR).toInt()
        val lnr = ln(F1 / F0)
        val fade = (0.05 * SR).toInt()
        for (i in 0 until sn) {
            val t = i.toDouble() / SR
            val phase = 2 * PI * F0 * SWEEP_SEC / lnr * (exp(t / SWEEP_SEC * lnr) - 1)
            val env = min(1.0, min(i, sn - 1 - i).toDouble() / fade)
            play[s0 + i] = (sin(phase) * 0.5 * env * 32767).toInt().toShort()
        }

        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        var ar: AudioRecord? = null
        for (src in intArrayOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC)) {
            try {
                val a = AudioRecord(src, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, SR) * 2)
                if (a.state == AudioRecord.STATE_INITIALIZED) { ar = a; break } else a.release()
            } catch (_: Throwable) {
            }
        }
        val rec = ar ?: throw IllegalStateException("Cannot open the microphone")

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

        val recTotal = total + SR
        val buf = ShortArray(recTotal)
        var n = 0
        var started = false
        var lastPct = -1
        rec.startRecording()
        while (n < recTotal) {
            val r = rec.read(buf, n, min(4096, recTotal - n))
            if (r < 0) break
            n += r
            if (!started && n >= SR * 3 / 10) {
                track.play()
                started = true
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
        analyze(DoubleArray(n) { buf[it] / 32768.0 })
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

    private fun analyze(x: DoubleArray) {
        val n = x.size
        // find the marker burst
        val lim = min(n, 4 * SR)
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
        if (mx < 0.002) throw IllegalStateException("Test tone not heard. Raise the media volume and reduce noise.")
        val thr = mx * 0.5
        val onset = max(0, env.indexOfFirst { it > thr } - win / 2)

        val lnr = ln(F1 / F0)
        val centers = (-15..12).map { 1000.0 * 2.0.pow(it / 3.0) }
        val levels = DoubleArray(centers.size)
        val warm = (0.25 * SR).toInt()
        for ((k, fc) in centers.withIndex()) {
            val lo = fc / 2.0.pow(1.0 / 6)
            val hi = fc * 2.0.pow(1.0 / 6)
            val tLo = SWEEP_START + SWEEP_SEC * ln(lo / F0) / lnr
            val tHi = SWEEP_START + SWEEP_SEC * ln(hi / F0) / lnr
            val a = onset + (tLo * SR).toInt()
            val b = min(n, onset + (tHi * SR).toInt())
            if (b - a < SR / 20) throw IllegalStateException("Recording too short, try again.")
            val from = max(0, a - warm)
            val y = bandpass(x, from, b, fc, 4.318)
            var s = 0.0
            var c = 0
            for (i in (a - from) until (b - from)) { s += y[i] * y[i]; c++ }
            levels[k] = 10 * log10(s / c + 1e-12)
        }

        val refIdx = centers.indices.filter { centers[it] in 250.0..4000.0 }
        val ref = refIdx.map { levels[it] }.average()
        val rel = DoubleArray(levels.size) { levels[it] - ref }

        val eqF = doubleArrayOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
        val eqG = DoubleArray(eqF.size)
        for ((j, f) in eqF.withIndex()) {
            val idx = centers.indices.filter { abs(ln(centers[it] / f) / ln(2.0)) <= 0.34 }
            val avg = idx.map { rel[it] }.average()
            val g = (-avg).coerceIn(-12.0, 6.0)
            eqG[j] = Math.round(g * 2) / 2.0
        }

        val sb = StringBuilder("Suggested EQ (10 bands):\n")
        for (j in eqF.indices) {
            val label = if (eqF[j] >= 1000) "${(eqF[j] / 1000).toInt()} kHz" else "${eqF[j].toInt()} Hz"
            sb.append(String.format(Locale.US, "%7s  %+5.1f dB\n", label, eqG[j]))
        }
        sb.append("\nBoosts are limited to +6 dB and cuts to -12 dB. Be careful with large boosts at the lowest bands.")
        ui {
            graph.setData(centers.toDoubleArray(), rel, eqF, eqG)
            result.text = sb.toString()
            status.text = "Done."
        }
    }
}

class ResponseView(ctx: Context) : View(ctx) {
    private var fc = DoubleArray(0)
    private var rel = DoubleArray(0)
    private var eqF = DoubleArray(0)
    private var eqG = DoubleArray(0)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density

    fun setData(fc: DoubleArray, rel: DoubleArray, eqF: DoubleArray, eqG: DoubleArray) {
        this.fc = fc; this.rel = rel; this.eqF = eqF; this.eqG = eqG
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val l = 40 * d
        val t = 10 * d
        val r = width - 10 * d
        val bt = height - 24 * d
        val pw = r - l
        val ph = bt - t
        val minDb = -24.0
        val maxDb = 24.0
        fun x(f: Double) = (l + (log10(f) - log10(20.0)) / (log10(20000.0) - log10(20.0)) * pw).toFloat()
        fun y(db: Double) = (t + (maxDb - db.coerceIn(minDb, maxDb)) / (maxDb - minDb) * ph).toFloat()

        p.textSize = 10 * d
        p.strokeWidth = 1f
        var db = minDb
        while (db <= maxDb) {
            p.color = if (db == 0.0) Color.DKGRAY else Color.LTGRAY
            c.drawLine(l, y(db), r, y(db), p)
            p.color = Color.DKGRAY
            c.drawText(String.format(Locale.US, "%+.0f", db), 4 * d, y(db) + 4 * d, p)
            db += 12.0
        }
        for ((f, s) in listOf(20.0 to "20", 100.0 to "100", 1000.0 to "1k", 10000.0 to "10k", 20000.0 to "20k")) {
            p.color = Color.LTGRAY
            c.drawLine(x(f), t, x(f), bt, p)
            p.color = Color.DKGRAY
            c.drawText(s, x(f) - 8 * d, height - 8 * d, p)
        }
        if (fc.isEmpty()) return

        // measured response
        val path = Path()
        for (i in fc.indices) {
            if (i == 0) path.moveTo(x(fc[i]), y(rel[i])) else path.lineTo(x(fc[i]), y(rel[i]))
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3 * d
        p.color = Color.rgb(30, 100, 220)
        c.drawPath(path, p)

        // suggested EQ
        val ep = Path()
        for (i in eqF.indices) {
            if (i == 0) ep.moveTo(x(eqF[i]), y(eqG[i])) else ep.lineTo(x(eqF[i]), y(eqG[i]))
        }
        p.color = Color.rgb(220, 60, 40)
        c.drawPath(ep, p)
        p.style = Paint.Style.FILL
        for (i in eqF.indices) c.drawCircle(x(eqF[i]), y(eqG[i]), 4 * d, p)

        p.color = Color.rgb(30, 100, 220)
        c.drawText("measured (relative)", l + 6 * d, t + 12 * d, p)
        p.color = Color.rgb(220, 60, 40)
        c.drawText("suggested EQ", l + 130 * d, t + 12 * d, p)
    }
}
