package com.milipa.mr.callibrator

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import com.example.sweepeq.Audio
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** Spectrum frames fed from the audio thread; Compose redraws when db/hold/wfVersion change. */
internal class SpectrumData {
    var db by mutableStateOf(FloatArray(0))
    var hold by mutableStateOf(FloatArray(0))
    var level by mutableFloatStateOf(-120f)
    var wfVersion by mutableIntStateOf(0)

    val wfW = 400
    val wfH = 160
    private val px = IntArray(wfW * wfH)
    val bitmap: Bitmap = Bitmap.createBitmap(wfW, wfH, Bitmap.Config.ARGB_8888)
    private val pal = IntArray(256)
    private val binHz = Audio.SR.toDouble() / Audio.N
    private val ratio = Audio.F1 / Audio.F0

    init {
        setPalette(0xFF101010.toInt(), 0xFF4CAF50.toInt(), 0xFFFFC107.toInt())
        clearWaterfall()
    }

    /** Waterfall colours come from the app's colour palette (dark -> primary -> tertiary). */
    fun setPalette(c0: Int, c1: Int, c2: Int) {
        for (i in 0 until 256) {
            val t = i / 255f
            pal[i] = if (t < 0.6f) ColorUtils.blendARGB(c0, c1, t / 0.6f) else ColorUtils.blendARGB(c1, c2, (t - 0.6f) / 0.4f)
        }
    }

    private fun clearWaterfall() {
        px.fill(pal[0])
        bitmap.setPixels(px, 0, wfW, 0, 0, wfW, wfH)
        wfVersion++
    }

    fun reset() {
        db = FloatArray(0)
        hold = FloatArray(0)
        level = -120f
        clearWaterfall()
    }

    private fun maxIn(a: FloatArray, fa: Double, fb: Double): Float {
        val k1 = (fa / binHz).toInt().coerceIn(0, a.size - 1)
        val k2 = ceil(fb / binHz).toInt().coerceIn(k1, a.size - 1)
        var m = a[k1]
        for (k in k1..k2) if (a[k] > m) m = a[k]
        return m
    }

    /** decay = dB per frame the hold line falls (0 = true peak hold, kept after the test). */
    fun push(pw: DoubleArray, lvl: Float, decay: Float) {
        val d = FloatArray(pw.size) { (10 * log10(pw[it] + 1e-12)).toFloat() }
        val h = hold
        val nh = FloatArray(pw.size) { if (it < h.size) max(d[it], h[it] - decay) else d[it] }
        System.arraycopy(px, 0, px, wfW, wfW * (wfH - 1))
        for (x in 0 until wfW) {
            val fa = Audio.F0 * ratio.pow(x.toDouble() / wfW)
            val fb = Audio.F0 * ratio.pow((x + 1).toDouble() / wfW)
            val v = maxIn(d, fa, fb)
            val idx = (((v + 100f) / 80f).coerceIn(0f, 1f) * 255f).toInt()
            px[x] = pal[idx]
        }
        bitmap.setPixels(px, 0, wfW, 0, 0, wfW, wfH)
        db = d
        hold = nh
        level = lvl
        wfVersion++
    }
}

@Composable
internal fun LegendItem(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(12.dp).background(color))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun SpectrumChart(data: SpectrumData) {
    val fillC = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
    val lineC = MaterialTheme.colorScheme.primary
    val holdC = MaterialTheme.colorScheme.tertiary
    val gridC = MaterialTheme.colorScheme.outlineVariant
    val c0 = MaterialTheme.colorScheme.surfaceContainerLowest.toArgb()
    val c1 = lineC.toArgb()
    val c2 = holdC.toArgb()
    SideEffect { data.setPalette(c0, c1, c2) }

    val db = data.db
    val hold = data.hold
    val binHz = Audio.SR.toDouble() / Audio.N
    val f0 = Audio.F0
    val ratio = Audio.F1 / Audio.F0

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (db.isNotEmpty()) {
                var kp = (f0 / binHz).toInt().coerceIn(0, db.size - 1)
                for (k in kp until db.size) if (db[k] > db[kp]) kp = k
                Text(
                    stringResource(R.string.spectrum_peak, kp * binHz, db[kp].toDouble(), data.level.toDouble()),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Canvas(Modifier.fillMaxWidth().height(200.dp)) {
                val w = size.width
                val h = size.height
                fun yd(v: Float): Float = h * (-(v.coerceIn(-100f, 0f))) / 100f
                for (g in 0..5) {
                    drawLine(gridC, Offset(0f, yd(-20f * g)), Offset(w, yd(-20f * g)), strokeWidth = 1f)
                }
                for (f in listOf(100.0, 1000.0, 10000.0)) {
                    val x = (w * (log10(f / f0) / log10(ratio))).toFloat()
                    drawLine(gridC, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
                }
                if (db.isNotEmpty()) {
                    fun maxIn(a: FloatArray, fa: Double, fb: Double): Float {
                        val k1 = (fa / binHz).toInt().coerceIn(0, a.size - 1)
                        val k2 = ceil(fb / binHz).toInt().coerceIn(k1, a.size - 1)
                        var m = a[k1]
                        for (k in k1..k2) if (a[k] > m) m = a[k]
                        return m
                    }
                    val fillP = Path()
                    val lineP = Path()
                    val holdP = Path()
                    val wi = w.toInt().coerceAtLeast(1)
                    fillP.moveTo(0f, h)
                    var xp = 0
                    var first = true
                    while (xp <= wi) {
                        val fa = f0 * ratio.pow(xp.toDouble() / wi)
                        val fb = f0 * ratio.pow((xp + 2).toDouble() / wi)
                        val y1 = yd(maxIn(db, fa, fb))
                        val y2 = yd(if (hold.size == db.size) maxIn(hold, fa, fb) else maxIn(db, fa, fb))
                        fillP.lineTo(xp.toFloat(), y1)
                        if (first) {
                            lineP.moveTo(xp.toFloat(), y1)
                            holdP.moveTo(xp.toFloat(), y2)
                            first = false
                        } else {
                            lineP.lineTo(xp.toFloat(), y1)
                            holdP.lineTo(xp.toFloat(), y2)
                        }
                        xp += 2
                    }
                    fillP.lineTo(w, h)
                    fillP.close()
                    drawPath(fillP, fillC)
                    drawPath(lineP, lineC, style = Stroke(width = 3f))
                    drawPath(holdP, holdC, style = Stroke(width = 3f))
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().height(18.dp)) {
                val wDp = maxWidth
                listOf("20" to 20.0, "100" to 100.0, "1k" to 1000.0, "10k" to 10000.0, "20k" to 20000.0).forEach { (s, f) ->
                    val frac = (log10(f / f0) / log10(ratio)).toFloat()
                    Text(s, style = MaterialTheme.typography.labelSmall, modifier = Modifier.offset(x = (wDp * frac - 10.dp).coerceAtLeast(0.dp)))
                }
            }
            Text(stringResource(R.string.wf_label), style = MaterialTheme.typography.labelMedium)
            Canvas(Modifier.fillMaxWidth().height(110.dp)) {
                @Suppress("UNUSED_VARIABLE")
                val version = data.wfVersion
                drawImage(
                    image = data.bitmap.asImageBitmap(),
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(data.wfW, data.wfH),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendItem(lineC, stringResource(R.string.legend_live))
                LegendItem(holdC, stringResource(R.string.legend_hold))
            }
        }
    }
}
