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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.sweepeq.Cal
import com.example.sweepeq.Params
import com.example.sweepeq.Pipeline
import com.example.sweepeq.Plan
import com.example.sweepeq.Report
import com.example.sweepeq.SweepResult
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
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
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendItem(cT, stringResource(R.string.legend_target))
                LegendItem(cM, stringResource(R.string.legend_measured))
            }
        }
    }
}

/** The test environment: curve menu, sweep measurement, optional Spectroid-style spectrum, result applied to Samsung's EQ. */
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
    val spectrum = remember { SpectrumData() }
    var showSpectrum by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf(false) }
    var pendingLive by remember { mutableStateOf(false) }
    val stopFlag = remember { AtomicBoolean(false) }

    DisposableEffect(Unit) { onDispose { stopFlag.set(true) } }

    fun startSweep() {
        val t = target
        val bands = t?.bandsDb
        if (t == null || bands == null) {
            status = ctx.getString(R.string.st_no_data)
            return
        }
        if (running || live) return
        running = true
        progress = 0
        report = null
        EngineTargets.custom = Cal(DoubleArray(9) { SamsungEq.BANDS_HZ[it].toDouble() }, bands)
        val useBt = bt
        val n = runsN
        val mic = micMode
        val feed = showSpectrum
        if (feed) spectrum.reset()
        thread {
            try {
                val runner = SweepRunner(ctx)
                val plan = Plan(useBt)
                val results = ArrayList<SweepResult>()
                for (i in 1..n) {
                    status = ctx.getString(R.string.st_measuring, i, n)
                    val cb: ((DoubleArray, Float) -> Unit)? = if (feed) { pw, lvl -> spectrum.push(pw, lvl, 0f) } else null
                    results.add(runner.run(plan, mic, cb) { p -> progress = p })
                }
                status = ctx.getString(R.string.st_analyzing)
                report = Pipeline.runPeq(results, useBt, 5, Params(), null, 3, false)
                status = ctx.getString(R.string.st_done)
            } catch (e: Throwable) {
                status = ctx.getString(R.string.st_error, e.message ?: "")
            } finally {
                running = false
            }
        }
    }

    fun startLive() {
        if (running || live) return
        live = true
        stopFlag.set(false)
        spectrum.reset()
        status = ctx.getString(R.string.st_live)
        val mic = micMode
        thread {
            try {
                SweepRunner(ctx).live(mic, { stopFlag.get() }) { pw, lvl -> spectrum.push(pw, lvl, 0.4f) }
                status = ctx.getString(R.string.st_live_stopped)
            } catch (e: Throwable) {
                status = ctx.getString(R.string.st_error, e.message ?: "")
            } finally {
                live = false
            }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            if (pendingLive) startLive() else startSweep()
        } else {
            status = ctx.getString(R.string.st_no_perm)
        }
    }

    fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ElevatedCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.curve_title), style = MaterialTheme.typography.titleMedium)
                Box {
                    OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth(), enabled = !running) {
                        Text(
                            target?.let { it.name + " · " + stringResource(Targets.confidenceRes(it.confidence)) }
                                ?: stringResource(R.string.curve_pick)
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        Targets.categoryRes.forEach { (cat, labelRes) ->
                            DropdownMenuItem(
                                text = { Text(stringResource(labelRes), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) },
                                onClick = {},
                                enabled = false,
                            )
                            targets.filter { it.category == cat }.forEach { t ->
                                DropdownMenuItem(
                                    text = { Text(t.name + " · " + stringResource(Targets.confidenceRes(t.confidence))) },
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
                Text(stringResource(R.string.test_settings), style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.bt_mode), Modifier.weight(1f))
                    Switch(checked = bt, onCheckedChange = { bt = it }, enabled = !running)
                }
                Text(stringResource(R.string.runs_count), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (i in 1..3) FilterChip(selected = runsN == i, onClick = { runsN = i }, label = { Text("$i") }, enabled = !running)
                }
                Text(stringResource(R.string.mic_title), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(R.string.mic_auto, R.string.mic_mic, R.string.mic_second).forEachIndexed { i, res ->
                        FilterChip(selected = micMode == i, onClick = { micMode = i }, label = { Text(stringResource(res)) }, enabled = !running && !live)
                    }
                }
                Text(stringResource(R.string.strength_label, (strength * 100).toInt()), style = MaterialTheme.typography.labelLarge)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Slider(value = strength, onValueChange = { strength = it }, valueRange = 0.25f..1f)
                }
                Button(
                    onClick = {
                        pendingLive = false
                        if (hasMic()) startSweep() else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    enabled = !running && !live,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(if (running) R.string.test_running else R.string.start_test)) }
                if (running) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodyMedium)
            }
        }

        ElevatedCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.spectrum_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Switch(checked = showSpectrum, onCheckedChange = { showSpectrum = it })
                }
                if (showSpectrum) {
                    Text(stringResource(R.string.spectrum_hint), style = MaterialTheme.typography.bodySmall)
                    SpectrumChart(spectrum)
                    OutlinedButton(
                        onClick = {
                            if (live) {
                                stopFlag.set(true)
                            } else {
                                pendingLive = true
                                if (hasMic()) startLive() else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(if (live) R.string.live_stop else R.string.live_start)) }
                }
            }
        }

        val rep = report
        if (rep != null) {
            val bp = planBands(rep, strength.toDouble())
            val lowSuffix = stringResource(R.string.band_low_conf)
            val okMsg = stringResource(R.string.applied_ok)
            val badMsg = stringResource(R.string.applied_unverified)
            ElevatedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.result_title), style = MaterialTheme.typography.titleMedium)
                    if (!rep.simNow.isNaN()) Text(stringResource(R.string.similarity, Math.round(rep.simNow).toInt()))
                    ResponseChart(rep)
                    if (rep.unreliable.isNotEmpty()) {
                        Text(
                            stringResource(R.string.low_conf_areas, rep.unreliable.joinToString(", ")),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Column {
                            SamsungEq.BANDS_HZ.forEachIndexed { i, hz ->
                                val lbl = if (hz >= 1000) "${hz / 1000}k" else "$hz"
                                Text(
                                    String.format(Locale.US, "%s Hz: %+.1f dB → %d", lbl, bp.need[i], bp.levels[i]) + (if (bp.lowConf[i]) lowSuffix else ""),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    Button(onClick = {
                        status = SamsungEq.apply(ctx, bp.levels).fold(
                            { ok -> if (ok) okMsg else badMsg },
                            { e -> ctx.getString(R.string.st_error, e.message ?: "") })
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.apply_samsung)) }
                    FilledTonalButton(onClick = { onSendToEq(bp.levels.map { it.toDouble() }) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.send_to_eq))
                    }
                    OutlinedButton(onClick = {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("EQ", peqText(rep)))
                        status = ctx.getString(R.string.peq_copied)
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.copy_peq)) }
                }
            }
        }
    }
}
