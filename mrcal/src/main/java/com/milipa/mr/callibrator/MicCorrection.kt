package com.milipa.mr.callibrator

import com.example.sweepeq.Cal
import kotlin.math.log10
import kotlin.math.pow

/**
 * Typical response of a phone's MEMS capsule microphone, in dB relative to 1 kHz.
 *
 * Low end: a first-order roll-off with the -3 dB point at 75 Hz (datasheets and MEMS literature give 50-100 Hz,
 * typically 75 Hz), which is about -4 dB at 63 Hz and -1.3 dB at 125 Hz.
 * High end: the flank of the package resonance (peaks of +8 to +9 dB near 20 kHz in datasheets, response at 10 kHz
 * between -5 and +5 dB depending on the model), modelled as +4 dB at 16 kHz growing with the square of frequency.
 * Unit-to-unit spread is about +-1 dB in the mid band and +-3 dB at the ends, which this cannot remove.
 */
internal fun micModelDb(f: Double): Double {
    val hp = -10.0 * log10(1.0 + (75.0 / f).pow(2))
    val hf = 4.0 * (f / 16000.0).pow(2)
    return hp + hf
}

internal fun approxMicCal(): Cal {
    val f = doubleArrayOf(
        20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0, 250.0, 315.0, 400.0, 500.0, 630.0,
        800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0, 10000.0, 12500.0,
        16000.0, 20000.0,
    )
    val ref = micModelDb(1000.0)
    return Cal(f, DoubleArray(f.size) { micModelDb(f[it]) - ref })
}
