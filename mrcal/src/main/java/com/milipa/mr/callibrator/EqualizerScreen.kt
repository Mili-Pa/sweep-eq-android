package com.milipa.mr.callibrator

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Manual 9-band equalizer talking to Samsung's Sound quality and effects app. */
@Composable
fun EqualizerScreen(bands: List<Double>, onBands: (List<Double>) -> Unit) {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf("") }
    val levels = IntArray(9) { SamsungEq.clamp(bands[it]) }
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ElevatedCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("اکولایزر ۹ باندی سامسونگ", style = MaterialTheme.typography.titleMedium)
                Canvas(Modifier.fillMaxWidth().height(150.dp)) {
                    val w = size.width
                    val h = size.height
                    fun x(i: Int): Float = w * (i + 0.5f) / 9f
                    fun y(v: Double): Float = h / 2f - (v.toFloat().coerceIn(-12f, 12f) / 12f) * (h / 2f)
                    for (g in listOf(-10, -5, 0, 5, 10)) {
                        drawLine(gridColor, Offset(0f, y(g.toDouble())), Offset(w, y(g.toDouble())), strokeWidth = if (g == 0) 3f else 1f)
                    }
                    val p = Path()
                    levels.forEachIndexed { i, d -> if (i == 0) p.moveTo(x(i), y(d.toDouble())) else p.lineTo(x(i), y(d.toDouble())) }
                    drawPath(p, lineColor, style = Stroke(width = 5f))
                    levels.forEachIndexed { i, d -> drawCircle(lineColor, 7f, Offset(x(i), y(d.toDouble()))) }
                }
                SamsungEq.BANDS_HZ.forEachIndexed { i, hz ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (hz >= 1000) "${hz / 1000}k" else "$hz", Modifier.width(44.dp))
                        Slider(
                            value = bands[i].toFloat().coerceIn(-10f, 10f),
                            onValueChange = { v -> onBands(bands.toMutableList().also { it[i] = Math.round(v).toDouble() }) },
                            valueRange = -10f..10f,
                            steps = 19,
                            modifier = Modifier.weight(1f),
                        )
                        Text("${levels[i]}", Modifier.width(36.dp))
                    }
                }
                Button(onClick = {
                    status = SamsungEq.apply(ctx, levels).fold(
                        { ok -> if (ok) "اعمال و تأیید شد ✓" else "ارسال شد، اما مقدار خوانده‌شده یکی نیست." },
                        { e -> "خطا: ${e.message}" })
                }, modifier = Modifier.fillMaxWidth()) { Text("اعمال روی اکولایزر سامسونگ") }
                FilledTonalButton(onClick = {
                    status = SamsungEq.read(ctx).fold(
                        { v -> onBands(v.map { it.toDouble() }); "مقادیر فعلی: " + v.joinToString(", ") },
                        { e -> "خطا: ${e.message}" })
                }, modifier = Modifier.fillMaxWidth()) { Text("خواندن مقادیر فعلی سامسونگ") }
                OutlinedButton(onClick = { onBands(List(9) { 0.0 }) }, modifier = Modifier.fillMaxWidth()) { Text("صفر کردن اسلایدرها") }
                TextButton(onClick = { SamsungEq.openEqualizer(ctx).onFailure { status = "خطا: ${it.message}" } }) {
                    Text("باز کردن صفحه اکولایزر سامسونگ")
                }
                if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
