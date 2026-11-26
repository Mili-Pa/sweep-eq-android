package com.milipa.mr.callibrator

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val ctx = LocalContext.current
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

private fun readText(ctx: Context, uri: Uri): String =
    ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val ctx = LocalContext.current
    val targets = remember { Targets.load(ctx) }
    var category by remember { mutableStateOf("home_speakers") }
    var selected by remember { mutableStateOf<Target?>(null) }
    var bands by remember { mutableStateOf(List(9) { 0.0 }) }
    var measured by remember { mutableStateOf<List<Double>?>(null) }
    var status by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val b = Measurement.toBands(Measurement.parse(readText(ctx, uri)))
            if (b == null) status = "فایل معتبر نیست: باید ستون‌های فرکانس و SPL داشته باشد و ۶۳ تا ۱۶۰۰۰ هرتز را پوشش دهد."
            else { measured = b.toList(); status = "اندازه‌گیری وارد شد؛ مقدار اعمال‌شده = هدف − اندازه‌گیری." }
        }
    }

    val levels = IntArray(9) { SamsungEq.clamp(bands[it] - (measured?.get(it) ?: 0.0)) }

    Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text("Mr. calibrator") }) }) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Targets.categoryLabels.entries.toList()) { e ->
                        FilterChip(selected = category == e.key, onClick = { category = e.key }, label = { Text(e.value) })
                    }
                }
            }
            items(targets.filter { it.category == category }) { t ->
                Card(
                    onClick = {
                        selected = t
                        bands = t.bandsDb?.toList() ?: List(9) { 0.0 }
                        measured = null
                        status = ""
                    },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected?.id == t.id) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(t.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            AssistChip(onClick = {}, label = { Text(Targets.confidenceLabel(t.confidence)) })
                        }
                        Text(t.application, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            val t = selected
            if (t != null) {
                item {
                    ElevatedCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(t.name, style = MaterialTheme.typography.titleLarge)
                            Text(t.sourceNote, style = MaterialTheme.typography.bodySmall)
                            t.limitations.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                            if (t.needsMeasurement && measured == null)
                                Text("این منحنی مرجع هدفون است؛ بدون وارد کردن اندازه‌گیری واقعی معنای کالیبراسیون ندارد.",
                                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            if (t.bandsDb == null)
                                Text("مقدار قابل‌تأییدی برای این منحنی پیدا نشد؛ مقادیر را دستی تنظیم کنید.",
                                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)

                            Chart(bands, measured, levels)

                            SamsungEq.BANDS_HZ.forEachIndexed { i, hz ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (hz >= 1000) "${hz / 1000}k" else "$hz", Modifier.width(44.dp))
                                    Slider(
                                        value = bands[i].toFloat().coerceIn(-10f, 10f),
                                        onValueChange = { v -> bands = bands.toMutableList().also { it[i] = v.toDouble() } },
                                        valueRange = -10f..10f, steps = 19, modifier = Modifier.weight(1f),
                                    )
                                    Text("%+.1f".format(bands[i]), Modifier.width(48.dp))
                                }
                            }
                            Text("مقادیر ارسالی به سامسونگ: " + levels.joinToString(", "), style = MaterialTheme.typography.bodyMedium)

                            Button(onClick = {
                                status = SamsungEq.apply(ctx, levels).fold(
                                    { ok -> if (ok) "اعمال و تأیید شد ✓" else "ارسال شد، اما مقدار خوانده‌شده با ارسالی یکی نیست." },
                                    { e -> "خطا: ${e.message}" })
                            }, modifier = Modifier.fillMaxWidth()) { Text("اعمال روی اکولایزر سامسونگ") }

                            FilledTonalButton(onClick = {
                                status = SamsungEq.read(ctx).fold(
                                    { v -> bands = v.map { it.toDouble() }; measured = null; "مقادیر فعلی: " + v.joinToString(", ") },
                                    { e -> "خطا: ${e.message}" })
                            }, modifier = Modifier.fillMaxWidth()) { Text("خواندن مقادیر فعلی سامسونگ") }

                            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                                Text("وارد کردن اندازه‌گیری (CSV: فرکانس، SPL)")
                            }
                            if (measured != null) TextButton(onClick = { measured = null; status = "" }) { Text("حذف اندازه‌گیری") }
                            TextButton(onClick = {
                                SamsungEq.openEqualizer(ctx).onFailure { status = "خطا: ${it.message}" }
                            }) { Text("باز کردن صفحه اکولایزر سامسونگ") }
                            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Chart(target: List<Double>, measured: List<Double>?, result: IntArray) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val c1 = MaterialTheme.colorScheme.primary
    val c2 = MaterialTheme.colorScheme.tertiary
    val c3 = MaterialTheme.colorScheme.secondary
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val w = size.width
        val h = size.height
        fun x(i: Int) = w * (i + 0.5f) / 9f
        fun y(v: Double) = (h / 2f - (v.toFloat().coerceIn(-12f, 12f) / 12f) * (h / 2f))
        for (g in listOf(-10, -5, 0, 5, 10))
            drawLine(grid, Offset(0f, y(g.toDouble())), Offset(w, y(g.toDouble())), strokeWidth = if (g == 0) 3f else 1f)
        fun line(v: List<Double>, color: Color) {
            val p = Path()
            v.forEachIndexed { i, d -> if (i == 0) p.moveTo(x(i), y(d)) else p.lineTo(x(i), y(d)) }
            drawPath(p, color, style = Stroke(width = 5f))
            v.forEachIndexed { i, d -> drawCircle(color, 7f, Offset(x(i), y(d))) }
        }
        line(target, c1)
        if (measured != null) line(measured, c2)
        line(result.map { it.toDouble() }, c3)
    }
}
