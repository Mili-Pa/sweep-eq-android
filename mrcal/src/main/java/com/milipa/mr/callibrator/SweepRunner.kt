package com.milipa.mr.callibrator

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import com.example.sweepeq.Audio
import com.example.sweepeq.Fft
import com.example.sweepeq.Plan
import com.example.sweepeq.SweepAnalyzer
import com.example.sweepeq.SweepGen
import com.example.sweepeq.SweepResult
import kotlin.math.max
import kotlin.math.min

/** Plays the log sweep and records it with the phone's own microphone (ported from Sweep EQ). */
internal class SweepRunner(private val ctx: Context) {
    var outType = -1
    private val effects = ArrayList<AudioEffect>()

    private fun releaseEffects() {
        for (e in effects) {
            try {
                e.release()
            } catch (_: Throwable) {
            }
        }
        effects.clear()
    }

    private fun addEffect(e: AudioEffect?) {
        if (e != null) {
            e.setEnabled(false)
            effects.add(e)
        }
    }

    private fun openRecord(micMode: Int): AudioRecord {
        val sr = Audio.SR
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val sources = when (micMode) {
            1 -> intArrayOf(MediaRecorder.AudioSource.MIC)
            2 -> intArrayOf(MediaRecorder.AudioSource.CAMCORDER)
            else -> intArrayOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC)
        }
        for (src in sources) {
            try {
                val a = AudioRecord(src, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, sr) * 2)
                if (a.state == AudioRecord.STATE_INITIALIZED) {
                    try {
                        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        val dev = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                        if (dev != null) a.setPreferredDevice(dev)
                    } catch (_: Throwable) {
                    }
                    releaseEffects()
                    try {
                        if (AutomaticGainControl.isAvailable()) addEffect(AutomaticGainControl.create(a.audioSessionId))
                    } catch (_: Throwable) {
                    }
                    try {
                        if (NoiseSuppressor.isAvailable()) addEffect(NoiseSuppressor.create(a.audioSessionId))
                    } catch (_: Throwable) {
                    }
                    try {
                        if (AcousticEchoCanceler.isAvailable()) addEffect(AcousticEchoCanceler.create(a.audioSessionId))
                    } catch (_: Throwable) {
                    }
                    return a
                } else {
                    a.release()
                }
            } catch (_: Throwable) {
            }
        }
        throw IllegalStateException(
            if (micMode == 2) "این گوشی میکروفون دوم (CAMCORDER) ندارد." else "میکروفون باز نشد"
        )
    }

    fun run(plan: Plan, micMode: Int, onProgress: (Int) -> Unit): SweepResult {
        val sr = Audio.SR
        val play = SweepGen.make(plan)
        val rec = openRecord(micMode)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(play.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(play, 0, play.size)

        val recTotal = play.size + (plan.extraRec * sr).toInt()
        val buf = ShortArray(recTotal)
        var n = 0
        var started = false
        var lastPct = -1
        var routeRead = false
        try {
            rec.startRecording()
            while (n < recTotal) {
                val r = rec.read(buf, n, min(4096, recTotal - n))
                if (r < 0) break
                n += r
                if (!started && n >= sr * 3 / 10) {
                    track.play()
                    started = true
                }
                if (started && !routeRead && n > sr * 2) {
                    routeRead = true
                    outType = try {
                        track.routedDevice?.type ?: -1
                    } catch (_: Throwable) {
                        -1
                    }
                }
                val pct = n * 100 / recTotal
                if (pct != lastPct) {
                    lastPct = pct
                    onProgress(pct)
                }
            }
        } finally {
            try {
                rec.stop()
            } catch (_: Throwable) {
            }
            rec.release()
            releaseEffects()
            try {
                track.stop()
            } catch (_: Throwable) {
            }
            track.release()
        }
        return SweepAnalyzer.analyze(buf.copyOf(n), plan, Fft(Audio.N))
    }

    companion object {
        fun isBluetooth(t: Int): Boolean =
            t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || t == 26 || t == 27
    }
}
