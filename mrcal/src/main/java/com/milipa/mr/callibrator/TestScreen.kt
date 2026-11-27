package com.milipa.mr.callibrator

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.core.content.ContextCompat
import com.example.sweepeq.Cal
import com.example.sweepeq.Params
import com.example.sweepeq.Pipeline
import com.example.sweepeq.Plan
import com.example.sweepeq.Report
import com.example.sweepeq.SweepResult
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.ln
import com.example.sweepeq.Targets as EngineTargets

/** Grid indices (25 Hz * 2^(i/12)) closest to the nine Samsung bands. */
private val IDX = intArrayOf(16, 28, 40, 52, 64, 76, 88, 100, 112)

private class BandPlan(val levels: IntArray, val lowConf: BooleanArray, val need: DoubleArray)

private fun planBands(rep: Report, strength: Double): BandPlan {
    val need = DoubleArray(9)
    val low = BooleanArray(9)
    val lv = IntArray(9)
    for (k in 0 until 9) {
        var s = 0.0
        var c = 0
        var cf = 0.0
        for (i in (IDX[k] - 2)..(IDX[k] + 2)) {
            if (i < 0 || i >= rep.grid.size) continue
            val r = rep.resid[i]
            if (r.isNaN()) continue
            s += r
            cf += rep.conf[i]
            c++
        }
        if (c == 0 || cf / c < 0.3) {
            low[k] = true
            need[k] = 0.0
        } else {
            need[k] = -s / c
        }
        lv[k] = SamsungEq.clamp(need[k] * strength)
    }
    return BandPlan(lv, low, need)
}

private fun peqText(rep: Report): String {
    val p = rep.profiles.getOrNull(1) ?: return ""
    val sb = StringBuilder()
    sb.append(String.format(Locale.US, "Preamp: %.1f dB\n", p.preamp))
    p.filters.forEachIndexed { i, f ->
        sb.append(String.format(Locale.US, "Filter %d: ON PK Fc %.0f Hz Gain %.1f dB Q %.2f\n", i + 1, f.f, f.g, f.q))
    }
    return sb.toString()
}

@Composable
private fun ResponseChart(rep: Report) {
    val cT = MaterialTheme.colorScheme.primary
    val cM = MaterialTheme.colorScheme.tertiary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(180.dp)) {
        val w = size.width
        val h = size.height
        val f0 = ln(25.0)
        val f1 = ln(16000.0)
        fun x(f: Double): Float = w * ((ln(f) - f0) / (f1 - f0)).toFloat()
        fun y(v: Double): Float = h / 2f - (v.toFloat().coerceIn(-15f, 15f) / 15f) * (h / 2f)
        for (g in listOf(-10, -5, 0, 5, 10)) {
            drawLine(grid, Offset(0f, y(g.toDouble())), Offset(w, y(g.toDouble())), strokeWidth = if (g == 0) 3f else 1f)
        }
        fun line(v: DoubleArray, color: Color) {
            val p = Path()
            var open = false
            for (i in v.indices) {
                if (v[i].isNaN()) {
                    open = false
                    continue
                }
                if (!open) {
                    p.moveTo(x(rep.grid[i]), y(v[i]))
                    open = true
                } else {
                    p.lineTo(x(rep.grid[i]), y(v[i]))
                }
            }
            drawPath(p, color, style = Stroke(width = 4f))
        }
        line(rep.target, cT)
        line(rep.rel, cM)
    }
}

/** The test environment: sweep measurement + the target-curve menu + result applied to Samsung's EQ. */
@Composable
internal fun TestScreen(targets: List<Target>, onSendToEq: (List<Double>) -> Unit) {
    val ctx = LocalContext.current
    var target by remember { mutableStateOf(targets.firstOrNull { it.id == "harman_in_room" }) }
    var menuOpen by remember { mutableStateOf(false) }
    var bt by remember { mutableStateOf(false) }
    var runsN by remember { mutableIntStateOf(1) }
    var micMode by remember { mutableIntStateOf(0) }
    var strength by remember { mutableFloatStateOf(0.75f) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<Report?>(null) }

    fun startSweep() {
        val t = target
        val bands = t?.bandsDb
        if (t == null || bands == null) {
            status = "برای این کرو مقدار قابل‌استفاده‌ای وجود ندارد؛ کرو دیگری انتخاب کنید."
            return
        }
        if (running) return
        running = true
        progress = 0
        report = null
        EngineTargets.custom = Cal(DoubleArray(9) { SamsungEq.BANDS_HZ[it].toDouble() }, bands)
        val useBt = bt
        val n = runsN
        val mic = micMode
        thread {
            try {
                val runner = SweepRunner(ctx)
                val plan = Plan(useBt)
                val results = ArrayList<SweepResult>()
                for (i in 1..n) {
                    status = "در حال اندازه‌گیری ($i از $n)... صدا را بلند کنید و سکوت نگه دارید"
                    results.add(runner.run(plan, mic) { p -> progress = p })
                }
                status = "در حال تحلیل..."
                report = Pipeline.runPeq(results, useBt, 5, Params(), null, 3, false)
                status = "تمام شد."
            } catch (e: Throwable) {
                status = "خطا: ${e.message}"
            } finally {
                running = false
            }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startSweep() else status = "بدون اجازه میکروفون اندازه‌گیری ممکن نیست."
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ElevatedCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("منحنی هدف (Curve)", style = MaterialTheme.typography.titleMedium)
                Box {
                    OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth(), enabled = !running) {
                        Text(target?.let { it.name + " · " + Targets.confidenceLabel(it.confidence) } ?: "انتخاب کرو")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        Targets.categoryLabels.forEach { (cat, label) ->
                            DropdownMenuItem(
                                text = { Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) },
                                onClick = {},
                                enabled = false,
                            )
                            targets.filter { it.category == cat }.forEach { t ->
                                DropdownMenuItem(
                                    text = { Text(t.name + " · " + Targets.confidenceLabel(t.confidence)) },
                                    onClick = { target = t; menuOpen = false; report = null },
                                )
                            }
                        }
                    }
                }
                target?.let { t ->
                    Text(t.application, style = MaterialTheme.typography.bodyMedium)
                    Text(t.sourceNote, style = MaterialTheme.typography.bodySmall)
                    t.limitations.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        ElevatedCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("تنظیمات تست", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("حالت ایرباد بلوتوثی", Modifier.weight(1f))
                    Switch(checked = bt, onCheckedChange = { bt = it }, enabled = !running)
                }
                Text("تعداد اجرا", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (i in 1..3) FilterChip(selected = runsN == i, onClick = { runsN = i }, label = { Text("$i") }, enabled = !running)
                }
                Text("میکروفون", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("خودکار", "MIC", "دوم").forEachIndexed { i, s ->
                        FilterChip(selected = micMode == i, onClick = { micMode = i }, label = { Text(s) }, enabled = !running)
                    }
                }
                Text("شدت اعمال روی اکولایزر: ${(strength * 100).toInt()}٪", style = MaterialTheme.typography.labelLarge)
                Slider(value = strength, onValueChange = { strength = it }, valueRange = 0.25f..1f)
                Button(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startSweep()
                        else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (running) "در حال اجرا..." else "شروع تست سوییپ") }
                if (running) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodyMedium)
            }
        }

        val rep = report
        if (rep != null) {
            val bp = planBands(rep, strength.toDouble())
            ElevatedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("نتیجه", style = MaterialTheme.typography.titleMedium)
                    if (!rep.simNow.isNaN())
                        Text("شباهت فعلی به هدف: %.0f٪ (بیشتر برای مقایسه دو اجرا مناسب است)".format(Locale.US, rep.simNow))
                    ResponseChart(rep)
                    Text("آبی: هدف — سبز: اندازه‌گیری", style = MaterialTheme.typography.bodySmall)
                    if (rep.unreliable.isNotEmpty())
                        Text("نواحی کم‌اعتماد: " + rep.unreliable.joinToString(", "), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    SamsungEq.BANDS_HZ.forEachIndexed { i, hz ->
                        val lbl = if (hz >= 1000) "${hz / 1000}k" else "$hz"
                        Text("$lbl Hz: %+.1f dB → ${bp.levels[i]}".format(Locale.US, bp.need[i]) + (if (bp.lowConf[i]) "  (اطمینان کم، صفر)" else ""),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = {
                        status = SamsungEq.apply(ctx, bp.levels).fold(
                            { ok -> if (ok) "روی اکولایزر سامسونگ اعمال و تأیید شد ✓" else "ارسال شد، اما مقدار خوانده‌شده یکی نیست." },
                            { e -> "خطا: ${e.message}" })
                    }, modifier = Modifier.fillMaxWidth()) { Text("اعمال روی اکولایزر سامسونگ") }
                    FilledTonalButton(onClick = { onSendToEq(bp.levels.map { it.toDouble() }) }, modifier = Modifier.fillMaxWidth()) {
                        Text("انتقال به تب اکولایزر برای ویرایش")
                    }
                    OutlinedButton(onClick = {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("EQ", peqText(rep)))
                        status = "EQ پارامتریک (Balanced) کپی شد."
                    }, modifier = Modifier.fillMaxWidth()) { Text("کپی EQ پارامتریک (Filter n: ON PK ...)") }
                }
            }
        }
    }
}
