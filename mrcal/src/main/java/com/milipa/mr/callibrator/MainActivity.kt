package com.milipa.mr.callibrator

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Typeface as ComposeTypeface

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val ctx = LocalContext.current
            val dark = isSystemInDarkTheme()
            val colors = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            val typo = remember { persianTypography(ctx) ?: Typography() }
            MaterialTheme(colorScheme = colors, typography = typo) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

/** Vazirmatn (bundled as an asset at build time) is used only when the UI language is Persian. */
private fun persianTypography(ctx: Context): Typography? {
    if (ctx.resources.configuration.locales[0].language != "fa") return null
    return try {
        fun family(path: String): FontFamily =
            FontFamily(ComposeTypeface(android.graphics.Typeface.createFromAsset(ctx.assets, path)))

        val regular = family("fonts/Vazirmatn-Regular.ttf")
        val medium = family("fonts/Vazirmatn-Medium.ttf")
        val bold = family("fonts/Vazirmatn-Bold.ttf")

        fun TextStyle.fa(): TextStyle {
            val w = fontWeight?.weight ?: 400
            val f = if (w >= 600) bold else if (w >= 500) medium else regular
            return copy(fontFamily = f)
        }

        val t = Typography()
        Typography(
            displayLarge = t.displayLarge.fa(),
            displayMedium = t.displayMedium.fa(),
            displaySmall = t.displaySmall.fa(),
            headlineLarge = t.headlineLarge.fa(),
            headlineMedium = t.headlineMedium.fa(),
            headlineSmall = t.headlineSmall.fa(),
            titleLarge = t.titleLarge.fa(),
            titleMedium = t.titleMedium.fa(),
            titleSmall = t.titleSmall.fa(),
            bodyLarge = t.bodyLarge.fa(),
            bodyMedium = t.bodyMedium.fa(),
            bodySmall = t.bodySmall.fa(),
            labelLarge = t.labelLarge.fa(),
            labelMedium = t.labelMedium.fa(),
            labelSmall = t.labelSmall.fa(),
        )
    } catch (e: Throwable) {
        null
    }
}

private fun licenseText(ctx: Context): String {
    val sb = StringBuilder()
    try {
        sb.append(ctx.assets.open("licenses.txt").bufferedReader().use { it.readText() })
    } catch (_: Throwable) {
    }
    try {
        val ofl = ctx.assets.open("licenses/Vazirmatn-OFL.txt").bufferedReader().use { it.readText() }
        sb.append("\n\n----- Vazirmatn OFL -----\n\n")
        sb.append(ofl)
    } catch (_: Throwable) {
    }
    return sb.toString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val ctx = LocalContext.current
    var reloadKey by remember { mutableIntStateOf(0) }
    val targets = remember(reloadKey) { Targets.load(ctx) }
    var tab by remember { mutableIntStateOf(0) }
    var selectedId by remember { mutableStateOf("bk_in_room") }
    var eqBands by remember { mutableStateOf(List(9) { 0.0 }) }
    var showAbout by remember { mutableStateOf(false) }

    // keep the selection valid if the selected user curve was deleted
    val effectiveId = if (targets.any { it.id == selectedId }) selectedId else (targets.firstOrNull()?.id ?: "")

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { showAbout = true }) {
                        Icon(Icons.Filled.Info, contentDescription = stringResource(R.string.about_open))
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_test)) },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.List, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_library)) },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_eq)) },
                )
            }
        },
    ) { pad ->
        Surface(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> TestScreen(
                    targets = targets,
                    selectedId = effectiveId,
                    onSelect = { selectedId = it },
                    onOpenLibrary = { tab = 1 },
                    onSendToEq = { v -> eqBands = v; tab = 2 },
                )
                1 -> LibraryScreen(
                    targets = targets,
                    selectedId = effectiveId,
                    onUse = { selectedId = it; tab = 0 },
                    onChanged = { reloadKey++ },
                )
                else -> EqualizerScreen(eqBands) { eqBands = it }
            }
        }
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text(stringResource(R.string.about_title)) },
            text = {
                Text(
                    licenseText(ctx),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text(stringResource(R.string.ok_btn)) } },
        )
    }
}
