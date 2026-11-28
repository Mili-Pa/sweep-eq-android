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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private val BAND_C = intArrayOf(63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

/**
 * A sound group is a frequency range (or two). Each of the nine bands (one octave wide) gets a weight
 * from how much of its octave lies inside the range, so the groups follow the real frequency ranges.
 */
private class SoundGroup(val nameRes: Int, val rangeText: String, ranges: List<Pair<Double, Double>>) {
    val w: DoubleArray = weights(ranges)

    companion object {
        fun weights(ranges: List<Pair<Double, Double>>): DoubleArray {
            val s2 = sqrt(2.0)
            val raw = DoubleArray(9) { i ->
                val c = BAND_C[i].toDouble()
                val bl = c / s2
                val bh = c * s2
                var s = 0.0
                for ((lo, hi) in ranges) {
                    val a = max(lo, bl)
                    val b = min(hi, bh)
                    if (b > a) s += ln(b / a) / ln(2.0)
                }
                min(s, 1.0)
            }
            val m = raw.max()
            return if (m > 0.0) DoubleArray(9) { raw[it] / m } else raw
        }
    }
}

// The four main groups
private val MAIN_GROUPS = listOf(
    SoundGroup(R.string.grp_bass, "20–250 Hz", listOf(20.0 to 250.0)),
    SoundGroup(R.string.grp_vocals, "250 Hz–4 kHz", listOf(250.0 to 4000.0)),
    SoundGroup(R.string.grp_instruments, "125–500 Hz + 2–8 kHz", listOf(125.0 to 500.0, 2000.0 to 8000.0)),
    SoundGroup(R.string.grp_other, "6–20 kHz", listOf(6000.0 to 20000.0)),
)

// Hidden advanced groups: the standard audio-engineering band names, plus the ITU-T G.712 speech band
private val ADVANCED_GROUPS = listOf(
    SoundGroup(R.string.adv_sub_bass, "20–60 Hz", listOf(20.0 to 60.0)),
    SoundGroup(R.string.adv_bass, "60–250 Hz", listOf(60.0 to 250.0)),
    SoundGroup(R.string.adv_low_mid, "250–500 Hz", listOf(250.0 to 500.0)),
    SoundGroup(R.string.adv_mid, "500 Hz–2 kHz", listOf(500.0 to 2000.0)),
    SoundGroup(R.string.adv_upper_mid, "2–4 kHz", listOf(2000.0 to 4000.0)),
    SoundGroup(R.string.adv_presence, "4–6 kHz", listOf(4000.0 to 6000.0)),
    SoundGroup(R.string.adv_brilliance, "6–20 kHz", listOf(6000.0 to 20000.0)),
    SoundGroup(R.string.adv_speech, "300–3400 Hz", listOf(300.0 to 3400.0)),
)

@Composable
private fun GroupSlider(group: SoundGroup, bands: List<Double>, onBands: (List<Double>) -> Unit) {
    var sw = 0.0
    var sw2 = 0.0
    var sv = 0.0
    for (i in 0 until 9) {
        sw += group.w[i]
        sw2 += group.w[i] * group.w[i]
        sv += group.w[i] * bands[i]
    }
    val avg = if (sw > 0) sv / sw else 0.0
    val k = if (sw2 > 0) sw / sw2 else 0.0
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(group.nameRes), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Text(group.rangeText, style = MaterialTheme.typography.labelSmall)
    }
    Slider(
        value = avg.toFloat().coerceIn(-10f, 10f),
        onValueChange = { v ->
            val delta = v.toDouble() - avg
            onBands(List(9) { i -> (bands[i] + delta * group.w[i] * k).coerceIn(-10.0, 10.0) })
        },
        valueRange = -10f..10f,
    )
}

@Composable
internal fun NameDialog(title: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.preset_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.library_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel)) } },
    )
}

/** Manual 9-band equalizer talking to Samsung's Sound quality and effects app, with sound groups and saved settings. */
@Composable
fun EqualizerScreen(bands: List<Double>, output: OutputInfo, onBands: (List<Double>) -> Unit) {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf("") }
    var presets by remember { mutableStateOf(Store.loadPresets(ctx)) }
    var saveDialog by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<EqPreset?>(null) }
    var showAdvanced by remember { mutableStateOf(false) }
    val levels = IntArray(9) { SamsungEq.clamp(bands[it]) }
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val okMsg = stringResource(R.string.applied_ok)
    val badMsg = stringResource(R.string.applied_unverified)

    fun persist(list: List<EqPreset>) {
        presets = list
        Store.savePresets(ctx, list)
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ElevatedCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.groups_title), style = MaterialTheme.typography.titleMedium)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Column {
                        MAIN_GROUPS.forEach { grp -> GroupSlider(grp, bands, onBands) }
                    }
                }
                TextButton(onClick = { showAdvanced = !showAdvanced }) {
                    Text(stringResource(if (showAdvanced) R.string.adv_hide else R.string.adv_show))
                }
                if (showAdvanced) {
                    Text(stringResource(R.string.adv_groups_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.adv_groups_hint), style = MaterialTheme.typography.bodySmall)
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Column {
                            ADVANCED_GROUPS.forEach { grp -> GroupSlider(grp, bands, onBands) }
                        }
                    }
                }
            }
        }

        ElevatedCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.eq_title), style = MaterialTheme.typography.titleMedium)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                    }
                }
                Button(onClick = {
                    status = SamsungEq.apply(ctx, levels).fold(
                        { ok -> if (ok) okMsg else badMsg },
                        { e -> ctx.getString(R.string.st_error, e.message ?: "") })
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.apply_samsung)) }
                FilledTonalButton(onClick = {
                    status = SamsungEq.read(ctx).fold(
                        { v -> onBands(v.map { it.toDouble() }); ctx.getString(R.string.current_values, v.joinToString(", ")) },
                        { e -> ctx.getString(R.string.st_error, e.message ?: "") })
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.read_current)) }
                OutlinedButton(onClick = { onBands(List(9) { 0.0 }) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.zero_sliders))
                }
                TextButton(onClick = {
                    SamsungEq.openEqualizer(ctx).onFailure { status = ctx.getString(R.string.st_error, it.message ?: "") }
                }) { Text(stringResource(R.string.open_samsung)) }
                if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodyMedium)
            }
        }

        ElevatedCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.presets_title), style = MaterialTheme.typography.titleMedium)
                Button(onClick = { saveDialog = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.preset_save)) }
                if (presets.isEmpty()) Text(stringResource(R.string.preset_empty), style = MaterialTheme.typography.bodySmall)
                presets.forEach { pr ->
                    OutlinedCard {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(pr.name, style = MaterialTheme.typography.titleSmall)
                            if (pr.output.isNotEmpty()) {
                                Text(stringResource(R.string.preset_output, pr.output), style = MaterialTheme.typography.bodySmall)
                            }
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Text(pr.levels.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { onBands(pr.levels.map { it.toDouble() }) }) { Text(stringResource(R.string.preset_load)) }
                                TextButton(onClick = { renaming = pr }) { Text(stringResource(R.string.preset_rename)) }
                                TextButton(onClick = { persist(presets.filter { it.id != pr.id }) }) { Text(stringResource(R.string.preset_delete)) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (saveDialog) {
        NameDialog(
            title = stringResource(R.string.preset_save),
            initial = stringResource(R.string.preset_default_name, presets.size + 1),
            onConfirm = { nm ->
                persist(presets + EqPreset("p" + System.currentTimeMillis(), nm, levels.toList(), output.label))
                saveDialog = false
            },
            onDismiss = { saveDialog = false },
        )
    }
    val rn = renaming
    if (rn != null) {
        NameDialog(
            title = stringResource(R.string.preset_rename),
            initial = rn.name,
            onConfirm = { nm ->
                persist(presets.map { if (it.id == rn.id) EqPreset(it.id, nm, it.levels, it.output) else it })
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
}
