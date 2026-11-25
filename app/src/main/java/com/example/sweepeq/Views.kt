package com.example.sweepeq

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

internal class Layer(
    val xs: DoubleArray,
    val ys: DoubleArray,
    val color: Int,
    val width: Float,
    val label: String?,
    val dots: Boolean = false
)

/** Frequency-response chart with any number of layers and an optional confidence strip along the bottom. */
internal class ChartView(ctx: Context) : View(ctx) {
    private var layers: List<Layer> = ArrayList()
    private var cx = DoubleArray(0)
    private var cw = DoubleArray(0)
    private var lim = 24.0
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density

    fun setLayers(layers: List<Layer>, confX: DoubleArray, conf: DoubleArray) {
        this.layers = layers
        cx = confX
        cw = conf
        var m = 0.0
        for (l in layers) {
            for (v in l.ys) if (!v.isNaN()) m = max(m, abs(v))
        }
        lim = (ceil(m / 6.0) * 6.0).coerceIn(12.0, 48.0)
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
        val step = if (lim <= 24.0) 6.0 else 12.0
        var db = minDb
        while (db <= maxDb + 0.01) {
            p.style = Paint.Style.STROKE
            p.color = if (abs(db) < 0.01) Color.GRAY else Color.rgb(50, 50, 70)
            c.drawLine(l, y(db), r, y(db), p)
            p.style = Paint.Style.FILL
            p.color = Color.LTGRAY
            c.drawText(String.format(Locale.US, "%+.0f", db), 4 * d, y(db) + 4 * d, p)
            db += step
        }
        for ((f, s) in listOf(20.0 to "20", 100.0 to "100", 1000.0 to "1k", 10000.0 to "10k", 20000.0 to "20k")) {
            p.style = Paint.Style.STROKE
            p.color = Color.rgb(50, 50, 70)
            c.drawLine(x(f), t, x(f), bt, p)
            p.style = Paint.Style.FILL
            p.color = Color.LTGRAY
            c.drawText(s, x(f) - 8 * d, height - 8 * d, p)
        }

        // confidence strip: green = reliable, red = unreliable
        if (cx.size > 1 && cw.size == cx.size) {
            for (i in 0 until cx.size - 1) {
                val w = cw[i].coerceIn(0.0, 1.0)
                p.style = Paint.Style.FILL
                p.color = Color.argb(200, (255 * (1 - w)).toInt(), (200 * w).toInt(), 60)
                c.drawRect(x(cx[i]), bt - 8 * d, x(cx[i + 1]), bt, p)
            }
        }

        for (lay in layers) {
            val path = Path()
            var pen = false
            for (i in lay.xs.indices) {
                val v = lay.ys[i]
                if (v.isNaN()) {
                    pen = false
                    continue
                }
                if (!pen) {
                    path.moveTo(x(lay.xs[i]), y(v))
                    pen = true
                } else {
                    path.lineTo(x(lay.xs[i]), y(v))
                }
            }
            p.style = Paint.Style.STROKE
            p.strokeWidth = (if (lay.width > 0f) lay.width else 2f) * d
            p.color = lay.color
            c.drawPath(path, p)
            if (lay.dots) {
                p.style = Paint.Style.FILL
                for (i in lay.xs.indices) {
                    if (!lay.ys[i].isNaN()) c.drawCircle(x(lay.xs[i]), y(lay.ys[i]), 4 * d, p)
                }
            }
        }

        // legend in two columns
        p.style = Paint.Style.FILL
        p.textSize = 10 * d
        var n = 0
        for (lay in layers) {
            val lb = lay.label ?: continue
            p.color = lay.color
            val col = n % 2
            val rowN = n / 2
            c.drawText(lb, l + 6 * d + col * (pw.toFloat() / 2f), t + (12 + 12 * rowN) * d, p)
            n++
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
    private val binHz = Audio.SR.toDouble() / Audio.N
    private val f0 = Audio.F0
    private val ratio = Audio.F1 / Audio.F0

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
