package com.milipa.mr.callibrator

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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.sweepeq.Audio
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** Spectrum frames fed from the audio thread; Compose redraws when db/hold change. */
internal class SpectrumData {
    var db by mutableStateOf(FloatArray(0))
    var hold by mutableStateOf(FloatArray(0))
    var level by mutableFloatStateOf(-120f)

    fun reset() {
        db = FloatArray(0)
        hold = FloatArray(0)
        level = -120f
    }

    /** decay = dB per frame the hold line falls (0 = true peak hold, kept after the test). */
    fun push(pw: DoubleArray, lvl: Float, decay: Float) {
        val d = FloatArray(pw.size) { (10 * log10(pw[it] + 1e-12)).toFloat() }
        val h = hold
        val nh = FloatArray(pw.size) { if (it < h.size) max(d[it], h[it] - decay) else d[it] }
        db = d
        hold = nh
        level = lvl
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
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendItem(lineC, stringResource(R.string.legend_live))
                LegendItem(holdC, stringResource(R.string.legend_hold))
            }
        }
    }
}
