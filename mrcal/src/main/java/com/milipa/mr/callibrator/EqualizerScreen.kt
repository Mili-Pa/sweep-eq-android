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

/** How strongly each band follows a sound group (bass, vocals, instruments, other sounds). */
private class SoundGroup(val nameRes: Int, val w: DoubleArray)

private val GROUPS = listOf(
    SoundGroup(R.string.grp_bass, doubleArrayOf(1.0, 0.8, 0.3, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)),
    SoundGroup(R.string.grp_vocals, doubleArrayOf(0.0, 0.0, 0.4, 0.8, 1.0, 0.8, 0.3, 0.0, 0.0)),
    SoundGroup(R.string.grp_instruments, doubleArrayOf(0.0, 0.2, 0.4, 0.0, 0.0, 0.4, 1.0, 0.8, 0.0)),
    SoundGroup(R.string.grp_other, doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.5, 1.0)),
)

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
fun EqualizerScreen(bands: List<Double>, onBands: (List<Double>) -> Unit) {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf("") }
    var presets by remember { mutableStateOf(Store.loadPresets(ctx)) }
    var saveDialog by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<EqPreset?>(null) }
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
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.groups_title), style = MaterialTheme.typography.titleMedium)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Column {
                        GROUPS.forEach { grp ->
                            var sw = 0.0
                            var sv = 0.0
                            for (i in 0 until 9) {
                                sw += grp.w[i]
                                sv += grp.w[i] * bands[i]
                            }
                            val avg = if (sw > 0) sv / sw else 0.0
                            Text(stringResource(grp.nameRes), style = MaterialTheme.typography.labelLarge)
                            Slider(
                                value = avg.toFloat().coerceIn(-10f, 10f),
                                onValueChange = { v ->
                                    val delta = v.toDouble() - avg
                                    onBands(List(9) { i -> (bands[i] + delta * grp.w[i]).coerceIn(-10.0, 10.0) })
                                },
                                valueRange = -10f..10f,
                            )
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
                persist(presets + EqPreset("p" + System.currentTimeMillis(), nm, levels.toList()))
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
                persist(presets.map { if (it.id == rn.id) EqPreset(it.id, nm, it.levels) else it })
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
}
