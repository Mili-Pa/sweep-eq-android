package com.milipa.mr.callibrator

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** The audio output in use right now. `headphones` is true for Bluetooth, wired and USB headsets. */
class OutputInfo(val headphones: Boolean, val label: String)

fun detectOutput(ctx: Context): OutputInfo {
    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val devs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

    fun pick(vararg types: Int): AudioDeviceInfo? = devs.firstOrNull { it.type in types }

    fun label(res: Int, d: AudioDeviceInfo?, withName: Boolean): String {
        val base = ctx.getString(res)
        val n = if (withName) d?.productName?.toString().orEmpty() else ""
        return if (n.isBlank()) base else "$base: $n"
    }

    val bt = pick(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, 26, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
    if (bt != null) return OutputInfo(true, label(R.string.out_bluetooth, bt, true))
    val wired = pick(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET)
    if (wired != null) return OutputInfo(true, label(R.string.out_wired, wired, false))
    val usb = pick(AudioDeviceInfo.TYPE_USB_HEADSET)
    if (usb != null) return OutputInfo(true, label(R.string.out_usb, usb, true))
    val spk = pick(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
    if (spk != null) return OutputInfo(false, ctx.getString(R.string.out_speaker))
    return OutputInfo(false, ctx.getString(R.string.out_other))
}

/** Re-detects the output whenever an audio device is added or removed. */
@Composable
fun rememberOutput(): State<OutputInfo> {
    val ctx = LocalContext.current
    val state = remember { mutableStateOf(detectOutput(ctx)) }
    DisposableEffect(Unit) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                state.value = detectOutput(ctx)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                state.value = detectOutput(ctx)
            }
        }
        am.registerAudioDeviceCallback(cb, null)
        onDispose { am.unregisterAudioDeviceCallback(cb) }
    }
    return state
}
