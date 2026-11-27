package com.milipa.mr.callibrator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val ctx = LocalContext.current
    val targets = remember { Targets.load(ctx) }
    var tab by remember { mutableIntStateOf(0) }
    var eqBands by remember { mutableStateOf(List(9) { 0.0 }) }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("Mr. calibrator") }) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                    label = { Text("تست") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("اکولایزر") },
                )
            }
        },
    ) { pad ->
        Surface(Modifier.padding(pad).fillMaxSize()) {
            if (tab == 0) {
                TestScreen(targets) { v -> eqBands = v; tab = 1 }
            } else {
                EqualizerScreen(eqBands) { eqBands = it }
            }
        }
    }
}
