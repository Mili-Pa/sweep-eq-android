package com.example.sweepeq

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh

/** Constants shared by the signal generator, the analyser and the views. */
internal object Audio {
    const val SR = 48000
    const val N = 8192
    const val HOP = 2048
    const val F0 = 20.0
    const val F1 = 20000.0
    const val SWEEP_SEC = 12.0
}

/** Radix-2 FFT with a Hann window. Output power is amplitude^2 (a full-scale sine = 1.0 = 0 dBFS). */
class Fft(private val n: Int) {
    private val cosT = DoubleArray(n / 2) { cos(2 * PI * it / n) }
    private val sinT = DoubleArray(n / 2) { -sin(2 * PI * it / n) }
    private val rev = IntArray(n)
    private val win = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }
    private val norm: Double
    private val re = DoubleArray(n)
    private val im = DoubleArray(n)

    init {
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) rev[i] = Integer.reverse(i) ushr (32 - bits)
        norm = win.sum() / 2
    }

    fun power(pcm: ShortArray, start: Int, out: DoubleArray) {
        for (i in 0 until n) {
            val j = start + i
            val v = if (j >= 0 && j < pcm.size) pcm[j] / 32768.0 else 0.0
            re[rev[i]] = v * win[i]
            im[rev[i]] = 0.0
        }
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + half) {
                    val wr = cosT[k]
                    val wi = sinT[k]
                    val xr = re[j + half] * wr - im[j + half] * wi
                    val xi = re[j + half] * wi + im[j + half] * wr
                    re[j + half] = re[j] - xr
                    im[j + half] = im[j] - xi
                    re[j] = re[j] + xr
                    im[j] = im[j] + xi
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
        for (k in 0 until n / 2) out[k] = (re[k] * re[k] + im[k] * im[k]) / (norm * norm)
    }
}

/** Timeline of the test signal. Bluetooth earbuds need a wake-up tone and more slack for latency. */
internal class Plan(val bt: Boolean) {
    val wake = if (bt) 1.5 else 0.0
    val burst = if (bt) 0.4 else 0.25
    val gap = if (bt) 1.0 else 0.55
    val burstAt = wake
    val sweepAt = wake + burst + gap
    val tail = if (bt) 1.2 else 0.6
    val extraRec = if (bt) 2.0 else 1.0
}

/** Generator of the test signal: wake-up tone (earbuds only), 1 kHz marker burst and a logarithmic sweep. */
internal object SweepGen {
    fun sweepStart(plan: Plan): Int = (plan.sweepAt * Audio.SR).toInt()

    fun sweepLen(): Int = (Audio.SWEEP_SEC * Audio.SR).toInt()

    fun total(plan: Plan): Int = ((plan.sweepAt + Audio.SWEEP_SEC + plan.tail) * Audio.SR).toInt()

    fun make(plan: Plan): ShortArray {
        val sr = Audio.SR
        val play = ShortArray(total(plan))
        // quiet 1 kHz wake-up tone: Bluetooth links often swallow the first moments of audio
        val wn = (plan.wake * sr).toInt()
        for (i in 0 until wn) {
            play[i] = (sin(2 * PI * 1000.0 * i / sr) * 0.04 * 32767).toInt().toShort()
        }
        // 1 kHz marker burst (used to find the playback/recording latency)
        val bn = (plan.burst * sr).toInt()
        for (i in 0 until bn) {
            val env = min(1.0, min(i, bn - 1 - i).toDouble() / (0.01 * sr))
            play[wn + i] = (sin(2 * PI * 1000.0 * i / sr) * 0.5 * env * 32767).toInt().toShort()
        }
        // logarithmic sweep
        val s0 = sweepStart(plan)
        val sn = sweepLen()
        val lnr = ln(Audio.F1 / Audio.F0)
        val fade = (0.05 * sr).toInt()
        for (i in 0 until sn) {
            val t = i.toDouble() / sr
            val phase = 2 * PI * Audio.F0 * Audio.SWEEP_SEC / lnr * (exp(t / Audio.SWEEP_SEC * lnr) - 1)
            val env = min(1.0, min(i, sn - 1 - i).toDouble() / fade)
            play[s0 + i] = (sin(phase) * 0.5 * env * 32767).toInt().toShort()
        }
        return play
    }
}

/** Peaking biquad filter (RBJ cookbook): coefficients, analytic magnitude and a real time-domain implementation. */
internal object Biquad {
    /** Normalised coefficients [b0, b1, b2, a1, a2]. */
    fun peaking(fc: Double, gDb: Double, q: Double): DoubleArray {
        val a = 10.0.pow(gDb / 40)
        val w0 = 2 * PI * fc / Audio.SR
        val al = sin(w0) / (2 * q)
        val cs = cos(w0)
        val a0 = 1 + al / a
        return doubleArrayOf(
            (1 + al * a) / a0,
            (-2 * cs) / a0,
            (1 - al * a) / a0,
            (-2 * cs) / a0,
            (1 - al / a) / a0
        )
    }

    /** Analytic magnitude response in dB at the given frequency. */
    fun magDb(c: DoubleArray, freq: Double): Double {
        val w = 2 * PI * freq / Audio.SR
        val c1 = cos(w)
        val s1 = sin(w)
        val c2 = cos(2 * w)
        val s2 = sin(2 * w)
        val nr = c[0] + c[1] * c1 + c[2] * c2
        val ni = -(c[1] * s1 + c[2] * s2)
        val dr = 1 + c[3] * c1 + c[4] * c2
        val di = -(c[3] * s1 + c[4] * s2)
        return 10 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
    }

    /** Runs a signal through the filter (direct form I). */
    fun process(c: DoubleArray, x: DoubleArray): DoubleArray {
        val y = DoubleArray(x.size)
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        for (i in x.indices) {
            val xi = x[i]
            val yi = c[0] * xi + c[1] * x1 + c[2] * x2 - c[3] * y1 - c[4] * y2
            x2 = x1
            x1 = xi
            y2 = y1
            y1 = yi
            y[i] = yi
        }
        return y
    }
}

/** Response of EQs made of peaking filters. */
internal object EqMath {
    /** Q for a graphic-EQ band, chosen so that its bandwidth matches the spacing to its neighbours. */
    fun bandQ(c: DoubleArray, j: Int): Double {
        var bw = 0.0
        var cnt = 0
        if (j > 0) {
            bw += ln(c[j] / c[j - 1]) / ln(2.0)
            cnt++
        }
        if (j < c.size - 1) {
            bw += ln(c[j + 1] / c[j]) / ln(2.0)
            cnt++
        }
        val b = if (cnt == 0) 1.0 else bw / cnt
        return 1.0 / (2 * sinh(ln(2.0) / 2 * b))
    }

    fun peakDb(freq: Double, fc: Double, g: Double, q: Double): Double = Biquad.magDb(Biquad.peaking(fc, g, q), freq)

    /** Graphic EQ: gains at fixed centres, Q from the band spacing. */
    fun responseDb(freq: Double, centers: DoubleArray, gains: DoubleArray): Double {
        var s = 0.0
        for (j in centers.indices) {
            if (abs(gains[j]) > 1e-9) s += peakDb(freq, centers[j], gains[j], bandQ(centers, j))
        }
        return s
    }

    /** Parametric EQ: every filter has its own centre, gain and Q. */
    fun peqDb(freq: Double, f: DoubleArray, g: DoubleArray, q: DoubleArray): Double {
        var s = 0.0
        for (j in f.indices) {
            if (abs(g[j]) > 1e-9) s += peakDb(freq, f[j], g[j], q[j])
        }
        return s
    }
}

/** Checks the analytic EQ response against the discrete Fourier transform of the real filter chain's impulse response. */
internal object Validate {
    fun maxErrorDb(f: DoubleArray, g: DoubleArray, q: DoubleArray, freqs: DoubleArray): Double {
        val len = 16384
        var h = DoubleArray(len)
        h[0] = 1.0
        for (j in f.indices) h = Biquad.process(Biquad.peaking(f[j], g[j], q[j]), h)
        var worst = 0.0
        for (fr in freqs) {
            val w = 2 * PI * fr / Audio.SR
            var re = 0.0
            var im = 0.0
            for (n in 0 until len) {
                re += h[n] * cos(w * n)
                im -= h[n] * sin(w * n)
            }
            val mag = 10 * log10(re * re + im * im + 1e-30)
            val ana = EqMath.peqDb(fr, f, g, q)
            worst = max(worst, abs(mag - ana))
        }
        return worst
    }
}
