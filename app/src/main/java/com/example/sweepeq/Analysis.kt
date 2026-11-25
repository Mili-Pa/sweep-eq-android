package com.example.sweepeq

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** One analysis frame per hop while the sweep passes: peak frequency, band power around it and its SNR. */
internal class Frames(val fp: DoubleArray, val sig: DoubleArray, val snr: DoubleArray)

internal class SweepResult(val frames: Frames, val peakDb: Double)

/** Turns a recording of the sweep into frames: DC removal, latency from the marker burst, noise floor, peak tracking. */
internal object SweepAnalyzer {
    private fun bandpass(x: DoubleArray, from: Int, to: Int, fc: Double, q: Double): DoubleArray {
        val w0 = 2 * PI * fc / Audio.SR
        val alpha = sin(w0) / (2 * q)
        val a0 = 1 + alpha
        val b0 = alpha / a0
        val b2 = -alpha / a0
        val a1 = -2 * cos(w0) / a0
        val a2 = (1 - alpha) / a0
        val y = DoubleArray(to - from)
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        for (i in from until to) {
            val xi = x[i]
            val yi = b0 * xi + b2 * x2 - a1 * y1 - a2 * y2
            y[i - from] = yi
            x2 = x1
            x1 = xi
            y2 = y1
            y1 = yi
        }
        return y
    }

    fun analyze(raw: ShortArray, plan: Plan, fft: Fft): SweepResult {
        val sr = Audio.SR
        val bigN = Audio.N
        val hop = Audio.HOP
        val sweepSec = Audio.SWEEP_SEC
        val n = raw.size
        if (n < ((plan.sweepAt + sweepSec) * sr).toInt()) throw IllegalStateException("Recording too short, try again.")

        var peakRaw = 0
        var sum = 0.0
        for (s in raw) {
            peakRaw = max(peakRaw, abs(s.toInt()))
            sum += s.toDouble()
        }
        if (peakRaw >= 32000) throw IllegalStateException("The recording is clipping. Lower the media volume and try again.")

        // DC offset removal
        val dc = sum / n
        val pcm = ShortArray(n)
        for (i in 0 until n) {
            pcm[i] = Math.round(raw[i] - dc).toInt().coerceIn(-32768, 32767).toShort()
        }

        // find the marker burst (latency)
        val lim = min(n, ((plan.burstAt + 4.0) * sr).toInt())
        val x = DoubleArray(lim) { pcm[it] / 32768.0 }
        val yb = bandpass(x, 0, lim, 1000.0, 5.0)
        val win = 240
        val env = DoubleArray(lim)
        var acc = 0.0
        for (i in 0 until lim) {
            acc += abs(yb[i])
            if (i >= win) acc -= abs(yb[i - win])
            env[i] = acc / win
        }
        var mx = 0.0
        for (v in env) mx = max(mx, v)
        val minTone = if (plan.bt) 0.0005 else 0.002
        if (mx < minTone) {
            throw IllegalStateException("Test tone not heard. Raise the media volume, press the earbud closer to the mic, or reduce noise.")
        }
        val thr = mx * 0.5
        var first = 0
        for (i in 0 until lim) {
            if (env[i] > thr) {
                first = i
                break
            }
        }
        val onset = max(0, first - win / 2)

        val half = bigN / 2
        val binHz = sr.toDouble() / bigN
        val sw0 = onset + ((plan.sweepAt - plan.burstAt) * sr).toInt()
        val pw = DoubleArray(half)

        // noise spectrum: after the sweep if possible, else the gap between beep and sweep
        val noise = DoubleArray(half)
        fun accumulate(from: Int, to: Int): Int {
            var cnt = 0
            var s = max(0, from)
            while (s + bigN <= min(n, to)) {
                fft.power(pcm, s, pw)
                for (k in 0 until half) noise[k] = noise[k] + pw[k]
                cnt++
                s += hop
            }
            return cnt
        }
        var nf = accumulate(sw0 + ((sweepSec + 0.15) * sr).toInt(), n)
        if (nf == 0) nf = accumulate(onset + ((plan.burst + 0.1) * sr).toInt(), sw0 - (0.02 * sr).toInt())
        if (nf == 0) throw IllegalStateException("Could not estimate the noise floor, try again.")
        val nfd = nf.toDouble()
        for (k in 0 until half) noise[k] = noise[k] / nfd

        // follow the sweep frame by frame: find the strongest peak near the expected frequency
        val lnr = ln(Audio.F1 / Audio.F0)
        val fpL = ArrayList<Double>()
        val sigL = ArrayList<Double>()
        val snrL = ArrayList<Double>()
        var start = sw0 - bigN / 2
        while (start + bigN <= n) {
            val tc = (start + bigN / 2 - sw0).toDouble() / sr
            if (tc > sweepSec) break
            if (tc >= 0) {
                fft.power(pcm, start, pw)
                val fe = Audio.F0 * exp(tc / sweepSec * lnr)
                val kLo = (fe / 1.5 / binHz).toInt().coerceIn(1, half - 3)
                val kHi = ceil(fe * 1.5 / binHz).toInt().coerceIn(kLo + 1, half - 1)
                var kp = kLo
                for (k in kLo..kHi) if (pw[k] > pw[kp]) kp = k
                val w = max(3, ceil(kp * (2.0.pow(0.2) - 1)).toInt())
                val k1 = max(1, kp - w)
                val k2 = min(half - 1, kp + w)
                var sig = 0.0
                var nz = 0.0
                for (k in k1..k2) {
                    sig += pw[k]
                    nz += noise[k]
                }
                if (sig > 0 && nz > 0) {
                    fpL.add(kp * binHz)
                    sigL.add(max(sig - nz, sig * 0.05))
                    snrL.add(10 * log10(sig / nz))
                }
            }
            start += hop
        }
        var best = 0.0
        for (v in sigL) best = max(best, v)
        val peakDb = if (best <= 0.0) -120.0 else 10 * log10(best)
        return SweepResult(Frames(fpL.toDoubleArray(), sigL.toDoubleArray(), snrL.toDoubleArray()), peakDb)
    }
}

/** Band levels from the frames: all frames whose peak frequency lies within +-halfOct of a centre are averaged (power). */
internal class BandStats(val level: DoubleArray, val snr: DoubleArray, val count: IntArray)

internal object Bands {
    fun levels(fr: Frames, centers: DoubleArray, halfOct: Double): BandStats {
        val binHz = Audio.SR.toDouble() / Audio.N
        val level = DoubleArray(centers.size) { Double.NaN }
        val snr = DoubleArray(centers.size) { Double.NaN }
        val count = IntArray(centers.size)
        for (k in centers.indices) {
            val fc = centers[k]
            var s = 0.0
            var sn = 0.0
            var c = 0
            for (i in fr.fp.indices) {
                val f = fr.fp[i]
                if (abs(ln(f / fc) / ln(2.0)) <= halfOct || abs(f - fc) <= binHz / 2) {
                    s += fr.sig[i]
                    sn += fr.snr[i]
                    c++
                }
            }
            count[k] = c
            if (c > 0) {
                level[k] = 10 * log10(s / c)
                snr[k] = sn / c
            }
        }
        return BandStats(level, snr, count)
    }
}

/** Several runs combined: median level per band, standard deviation between runs (NaN with a single run). */
internal class Combined(val level: DoubleArray, val snr: DoubleArray, val sigma: DoubleArray, val runs: Int)

internal object Combine {
    fun of(list: List<BandStats>): Combined {
        val n = list[0].level.size
        val level = DoubleArray(n) { Double.NaN }
        val snr = DoubleArray(n) { Double.NaN }
        val sigma = DoubleArray(n) { Double.NaN }
        for (k in 0 until n) {
            val v = ArrayList<Double>()
            var sn = 0.0
            var cs = 0
            for (st in list) {
                if (!st.level[k].isNaN()) {
                    v.add(st.level[k])
                    sn += st.snr[k]
                    cs++
                }
            }
            if (v.isEmpty()) continue
            v.sort()
            val m = v.size
            level[k] = if (m % 2 == 1) v[m / 2] else (v[m / 2 - 1] + v[m / 2]) / 2
            snr[k] = sn / cs
            if (m >= 2) {
                var mean = 0.0
                for (x in v) mean += x
                mean /= m
                var ss = 0.0
                for (x in v) ss += (x - mean) * (x - mean)
                sigma[k] = sqrt(ss / (m - 1))
            }
        }
        return Combined(level, snr, sigma, list.size)
    }
}
