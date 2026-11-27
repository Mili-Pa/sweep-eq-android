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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

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
        val names = listOf("fonts/Vazirmatn-Regular.ttf", "fonts/Vazirmatn-Medium.ttf", "fonts/Vazirmatn-Bold.ttf")
        for (n in names) ctx.assets.open(n).close()
        val am = ctx.assets
        val fam = FontFamily(
            Font(am, names[0], FontWeight.Normal),
            Font(am, names[1], FontWeight.Medium),
            Font(am, names[2], FontWeight.Bold),
        )
        val t = Typography()
        Typography(
            displayLarge = t.displayLarge.copy(fontFamily = fam),
            displayMedium = t.displayMedium.copy(fontFamily = fam),
            displaySmall = t.displaySmall.copy(fontFamily = fam),
            headlineLarge = t.headlineLarge.copy(fontFamily = fam),
            headlineMedium = t.headlineMedium.copy(fontFamily = fam),
            headlineSmall = t.headlineSmall.copy(fontFamily = fam),
            titleLarge = t.titleLarge.copy(fontFamily = fam),
            titleMedium = t.titleMedium.copy(fontFamily = fam),
            titleSmall = t.titleSmall.copy(fontFamily = fam),
            bodyLarge = t.bodyLarge.copy(fontFamily = fam),
            bodyMedium = t.bodyMedium.copy(fontFamily = fam),
            bodySmall = t.bodySmall.copy(fontFamily = fam),
            labelLarge = t.labelLarge.copy(fontFamily = fam),
            labelMedium = t.labelMedium.copy(fontFamily = fam),
            labelSmall = t.labelSmall.copy(fontFamily = fam),
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
        sb.append("\n\n----- Vazirmatn OFL -----\n\n")
        sb.append(ctx.assets.open("licenses/Vazirmatn-OFL.txt").bufferedReader().use { it.readText() })
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
