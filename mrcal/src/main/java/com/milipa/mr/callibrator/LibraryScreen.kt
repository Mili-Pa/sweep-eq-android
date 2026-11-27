package com.milipa.mr.callibrator

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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.ln

/** Value of a (frequency, dB) list at 1 kHz, interpolated on a log-frequency axis. */
private fun valueAt1k(points: List<Pair<Double, Double>>): Double? {
    if (points.size < 2 || points.first().first > 1000.0 || points.last().first < 1000.0) return null
    val i = points.indexOfFirst { it.first >= 1000.0 }.coerceAtLeast(1)
    val (f0, v0) = points[i - 1]
    val (f1, v1) = points[i]
    if (f1 == f0) return v1
    val t = (ln(1000.0) - ln(f0)) / (ln(f1) - ln(f0))
    return v0 + t * (v1 - v0)
}

@Composable
private fun MiniCurve(bands: DoubleArray) {
    val c = MaterialTheme.colorScheme.primary
    val g = MaterialTheme.colorScheme.outlineVariant
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Canvas(Modifier.fillMaxWidth().height(56.dp)) {
            val w = size.width
            val h = size.height
            fun x(i: Int): Float = w * (i + 0.5f) / bands.size
            fun y(v: Double): Float = h / 2f - (v.toFloat().coerceIn(-12f, 12f) / 12f) * (h / 2f)
            drawLine(g, Offset(0f, y(0.0)), Offset(w, y(0.0)), strokeWidth = 2f)
            val p = Path()
            bands.forEachIndexed { i, d -> if (i == 0) p.moveTo(x(i), y(d)) else p.lineTo(x(i), y(d)) }
            drawPath(p, c, style = Stroke(width = 4f))
        }
    }
}

@Composable
private fun CurveEditor(
    initial: Target?,
    onDismiss: () -> Unit,
    onSave: (String, List<Double>, DoubleArray?, DoubleArray?) -> Unit,
) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var bands by remember { mutableStateOf(initial?.bandsDb?.toList() ?: List(9) { 0.0 }) }
    var ptsF by remember { mutableStateOf(initial?.curveF) }
    var ptsDb by remember { mutableStateOf(initial?.curveDb) }
    var err by remember { mutableStateOf("") }
    val badCsv = stringResource(R.string.library_csv_bad)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val pts = Measurement.parse(text)
            val b = Measurement.toBands(pts)
            val ref = valueAt1k(pts)
            if (b == null || ref == null) {
                err = badCsv
            } else {
                err = ""
                bands = b.map { Math.round(it * 10) / 10.0 }
                ptsF = DoubleArray(pts.size) { pts[it].first }
                ptsDb = DoubleArray(pts.size) { pts[it].second - ref }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.library_new else R.string.library_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.library_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.library_import_csv))
                }
                if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Column {
                        SamsungEq.BANDS_HZ.forEachIndexed { i, hz ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (hz >= 1000) "${hz / 1000}k" else "$hz", Modifier.width(40.dp), style = MaterialTheme.typography.bodySmall)
                                Slider(
                                    value = bands[i].toFloat().coerceIn(-12f, 12f),
                                    onValueChange = { v ->
                                        bands = bands.toMutableList().also { it[i] = Math.round(v * 2) / 2.0 }
                                        ptsF = null
                                        ptsDb = null
                                    },
                                    valueRange = -12f..12f,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(String.format(java.util.Locale.US, "%+.1f", bands[i]), Modifier.width(44.dp), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim(), bands, ptsF, ptsDb) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.library_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel)) } },
    )
}

/** Curve library: built-in curves with a trustworthy source plus the user's own curves (add, edit, delete). */
@Composable
internal fun LibraryScreen(
    targets: List<Target>,
    selectedId: String,
    onUse: (String) -> Unit,
    onChanged: () -> Unit,
) {
    val ctx = LocalContext.current
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Target?>(null) }
    var deleting by remember { mutableStateOf<Target?>(null) }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.library_title), style = MaterialTheme.typography.titleLarge)
        Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.library_new)) }
        Targets.categoryRes.forEach { (cat, labelRes) ->
            val items = targets.filter { it.category == cat }
            if (items.isNotEmpty()) {
                Text(stringResource(labelRes), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                items.forEach { t ->
                    val selected = t.id == selectedId
                    ElevatedCard(
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(t.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                AssistChip(onClick = {}, label = { Text(stringResource(Targets.confidenceRes(t.confidence))) })
                            }
                            if (t.application.isNotEmpty()) Text(t.application, style = MaterialTheme.typography.bodyMedium)
                            if (t.sourceNote.isNotEmpty()) Text(t.sourceNote, style = MaterialTheme.typography.bodySmall)
                            t.limitations.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                            t.bandsDb?.let { MiniCurve(it) }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (selected) {
                                    Text(stringResource(R.string.library_selected), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                                } else {
                                    FilledTonalButton(onClick = { onUse(t.id) }) { Text(stringResource(R.string.library_use)) }
                                    Spacer(Modifier.weight(1f))
                                }
                                if (!t.builtIn) {
                                    TextButton(onClick = { editing = t }) { Text(stringResource(R.string.library_edit)) }
                                    TextButton(onClick = { deleting = t }) { Text(stringResource(R.string.library_delete)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        CurveEditor(
            initial = null,
            onDismiss = { creating = false },
            onSave = { name, bands, f, d ->
                val list = Store.loadCurves(ctx).toMutableList()
                list.add(UserCurve("u" + System.currentTimeMillis(), name, bands, f, d))
                Store.saveCurves(ctx, list)
                creating = false
                onChanged()
            },
        )
    }
    val ed = editing
    if (ed != null) {
        CurveEditor(
            initial = ed,
            onDismiss = { editing = null },
            onSave = { name, bands, f, d ->
                val list = Store.loadCurves(ctx).map { if (it.id == ed.id) UserCurve(it.id, name, bands, f, d) else it }
                Store.saveCurves(ctx, list)
                editing = null
                onChanged()
            },
        )
    }
    val del = deleting
    if (del != null) {
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.library_delete_confirm, del.name)) },
            confirmButton = {
                TextButton(onClick = {
                    Store.saveCurves(ctx, Store.loadCurves(ctx).filter { it.id != del.id })
                    deleting = null
                    onChanged()
                }) { Text(stringResource(R.string.library_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.library_cancel)) } },
        )
    }
}
