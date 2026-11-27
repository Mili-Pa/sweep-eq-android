package com.example.sweepeq

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * The curve shaping below follows the approach of AutoEq (https://github.com/jaakkopasanen/AutoEq,
 * frequency_response.py and peq.py), which is distributed under the MIT License,
 * Copyright (c) 2018-2022 Jaakko Pasanen. See THIRD_PARTY_NOTICES.md for the full licence text.
 * This is a re-implementation in Kotlin for a noisy phone-microphone measurement, not a copy of that code.
 */

internal class GraphicFit(val levels: IntArray, val gains: DoubleArray, val response: DoubleArray)

internal object AutoEq {
    private const val MAX_SLOPE = 18.0

    /** 0 below ~6 kHz, 1 above ~8 kHz (log-frequency sigmoid like AutoEq's treble transition). */
    fun trebleK(f: Double): Double {
        val lo = 6000.0
        val hi = 8000.0
        val center = sqrt(hi / lo) * lo
        val half = log10(hi) - log10(center)
        val a = (log10(f) - log10(center)) / (half / 4)
        return 1.0 / (1.0 + exp(-a))
    }

    /** Moving average on the 1/12-octave grid. Window in octaves. */
    private fun smooth(v: DoubleArray, octaves: Double): DoubleArray {
        val n = max(1, Math.round(octaves * 12).toInt())
        val h = n / 2
        return DoubleArray(v.size) { i ->
            var s = 0.0
            var c = 0
            for (j in max(0, i - h)..min(v.size - 1, i + h)) {
                if (!v[j].isNaN()) {
                    s += v[j]
                    c++
                }
            }
            if (c == 0) 0.0 else s / c
        }
    }

    private fun smoothVar(grid: DoubleArray, v: DoubleArray, normalOct: Double, trebleOct: Double): DoubleArray {
        val a = smooth(v, normalOct)
        val b = smooth(v, trebleOct)
        return DoubleArray(v.size) {
            val k = trebleK(grid[it])
            a[it] * (1 - k) + b[it] * k
        }
    }

    /** Local maxima whose prominence is at least minProm. */
    private fun peaks(y: DoubleArray, minProm: Double): IntArray {
        val out = ArrayList<Int>()
        for (i in 1 until y.size - 1) {
            if (y[i] > y[i - 1] && y[i] >= y[i + 1]) {
                var leftMin = y[i]
                var j = i - 1
                while (j >= 0 && y[j] <= y[i]) {
                    leftMin = min(leftMin, y[j])
                    j--
                }
                var rightMin = y[i]
                var k = i + 1
                while (k < y.size && y[k] <= y[i]) {
                    rightMin = min(rightMin, y[k])
                    k++
                }
                if (y[i] - max(leftMin, rightMin) >= minProm) out.add(i)
            }
        }
        return out.toIntArray()
    }

    /** Regions around dips where the slope limit must not apply (so a boost can follow a real dip). */
    private fun protectionMask(y: DoubleArray, peaks: IntArray, dipsIn: IntArray): BooleanArray {
        val n = y.size
        val mask = BooleanArray(n)
        val dips = ArrayList<Int>()
        for (d in dipsIn) dips.add(d)
        val levels = ArrayList<Double>()
        if (peaks.isNotEmpty() && (dips.isEmpty() || peaks.last() > dips.last())) {
            var m = peaks.last()
            for (i in peaks.last() until n) if (y[i] < y[m]) m = i
            dips.add(m)
            for (d in dips) levels.add(y[d])
        } else {
            dips.add(n - 1)
            for (d in dips) levels.add(y[d])
            levels[levels.size - 1] = y.minOrNull() ?: 0.0
        }
        if (dips.size < 3) return mask
        for (i in 1 until dips.size - 1) {
            val dip = dips[i]
            val tl = levels[i - 1]
            val tr = levels[i + 1]
            var left = -1
            for (j in dip - 1 downTo 0) {
                if (y[j] >= tl) {
                    left = j
                    break
                }
            }
            left += 1
            var right = n - 1
            for (j in dip until n) {
                if (y[j] >= tr) {
                    right = j - 1
                    break
                }
            }
            if (left < 0) left = 0
            if (right >= left) for (j in left..right) mask[j] = true
        }
        return mask
    }

    private fun limitedLtr(x: DoubleArray, y: DoubleArray, start: Int, peaks: IntArray, free: BooleanArray): DoubleArray {
        val n = y.size
        val limited = DoubleArray(n)
        val clipped = BooleanArray(n)
        var regionStart = -1
        for (i in 0 until n) {
            if (i <= start) {
                limited[i] = y[i]
                continue
            }
            val octaves = ln(x[i] / x[i - 1]) / ln(2.0)
            val slope = (y[i] - limited[i - 1]) / octaves
            if (slope > MAX_SLOPE && !free[i]) {
                if (!clipped[i - 1]) regionStart = i
                clipped[i] = true
                limited[i] = limited[i - 1] + MAX_SLOPE * octaves
            } else {
                limited[i] = y[i]
                if (clipped[i - 1]) {
                    val rs = regionStart
                    val touches = peaks.any { it >= rs && it < i }
                    if (!touches) {
                        for (j in rs until i) {
                            limited[j] = y[j]
                            clipped[j] = false
                        }
                    }
                }
                clipped[i] = false
            }
        }
        return limited
    }

    private fun limitedRtl(x: DoubleArray, y: DoubleArray, start: Int, peaks: IntArray, free: BooleanArray): DoubleArray {
        val n = y.size
        val fy = DoubleArray(n) { y[n - 1 - it] }
        val ff = BooleanArray(n) { free[n - 1 - it] }
        val fp = IntArray(peaks.size) { n - 1 - peaks[it] }
        val r = limitedLtr(x, fy, n - 1 - start, fp, ff)
        return DoubleArray(n) { r[n - 1 - it] }
    }

    private fun rtlStart(y: DoubleArray, peaks: IntArray, dips: IntArray): Int {
        val n = y.size
        if (peaks.isNotEmpty() && (dips.isEmpty() || peaks.last() > dips.last())) {
            val thr = if (dips.isNotEmpty()) y[dips.last()] else max(y[0], y[n - 1])
            for (j in peaks.last() until n) if (y[j] <= thr) return j
            return n - 1
        }
        return if (dips.isNotEmpty()) dips.last() else n - 1
    }

    /**
     * Desired EQ curve in dB (positive = boost) for a residual (measurement minus target).
     * Steps: remove the overall level offset, fade out low-confidence points, smooth (wide in the treble),
     * limit how fast the EQ may rise (18 dB/octave, with peak/dip protection), treble gain factor, clip, smooth.
     */
    fun shape(
        grid: DoubleArray,
        resid: DoubleArray,
        conf: DoubleArray,
        mask: BooleanArray?,
        maxBoost: Double,
        maxCut: Double,
        trebleGainK: Double,
    ): DoubleArray {
        val n = grid.size
        var sw = 0.0
        var sv = 0.0
        for (i in 0 until n) {
            if (grid[i] >= 100.0 && grid[i] <= 10000.0 && !resid[i].isNaN()) {
                sw += conf[i]
                sv += conf[i] * resid[i]
            }
        }
        val delta = if (sw > 1.0) sv / sw else 0.0
        val y = DoubleArray(n) {
            val r = resid[it]
            if (r.isNaN() || (mask != null && mask[it])) {
                0.0
            } else {
                val gate = ((conf[it] - 0.2) / 0.5).coerceIn(0.0, 1.0)
                -(r - delta) * gate
            }
        }
        val ys = smoothVar(grid, y, 1.0 / 6, 2.0)
        val pk = peaks(ys, 1.0)
        val dp = peaks(DoubleArray(n) { -ys[it] }, 1.0)
        val eq: DoubleArray
        if (pk.isEmpty() && dp.isEmpty()) {
            eq = ys.copyOf()
        } else {
            val free = protectionMask(ys, pk, dp)
            val rs = rtlStart(ys, pk, dp)
            val l = limitedLtr(grid, ys, 0, pk, free)
            val r = limitedRtl(grid, ys, rs, pk, free)
            eq = DoubleArray(n) { min(l[it], r[it]) }
        }
        for (i in 0 until n) {
            val k = 1.0 + (trebleGainK - 1.0) * trebleK(grid[i])
            eq[i] = min(max(eq[i] * k, maxCut), maxBoost)
        }
        return smooth(eq, 0.2)
    }

    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size
        for (c in 0 until n) {
            var piv = c
            for (r in c + 1 until n) if (abs(a[r][c]) > abs(a[piv][c])) piv = r
            val tmp = a[c]
            a[c] = a[piv]
            a[piv] = tmp
            val tb = b[c]
            b[c] = b[piv]
            b[piv] = tb
            val d = a[c][c]
            if (abs(d) < 1e-12) continue
            for (r in c + 1 until n) {
                val f = a[r][c] / d
                for (k in c until n) a[r][k] -= f * a[c][k]
                b[r] -= f * b[c]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = b[r]
            for (k in r + 1 until n) s -= a[r][k] * x[k]
            x[r] = if (abs(a[r][r]) < 1e-12) 0.0 else s / a[r][r]
        }
        return x
    }

    /** Fits the gains of a fixed-band graphic EQ (integer steps within +-lim) to a desired curve, with overlapping bands accounted for. */
    fun fitGraphic(grid: DoubleArray, target: DoubleArray, wt: DoubleArray, centers: DoubleArray, lim: Int): GraphicFit {
        val n = grid.size
        val m = centers.size
        val qs = DoubleArray(m) { EqMath.bandQ(centers, it) }
        val g = DoubleArray(m)

        fun resp(gains: DoubleArray): DoubleArray = DoubleArray(n) { i ->
            var s = 0.0
            for (k in 0 until m) if (abs(gains[k]) > 1e-9) s += EqMath.peakDb(grid[i], centers[k], gains[k], qs[k])
            s
        }

        for (iter in 0 until 4) {
            val cur = resp(g)
            val jac = Array(m) { DoubleArray(n) }
            for (k in 0 until m) {
                for (i in 0 until n) {
                    val a = EqMath.peakDb(grid[i], centers[k], g[k] + 0.25, qs[k])
                    val b = EqMath.peakDb(grid[i], centers[k], g[k], qs[k])
                    jac[k][i] = (a - b) / 0.25
                }
            }
            val a = Array(m) { DoubleArray(m) }
            val b = DoubleArray(m)
            for (k in 0 until m) {
                for (l in 0 until m) {
                    var s = 0.0
                    for (i in 0 until n) s += wt[i] * jac[k][i] * jac[l][i]
                    a[k][l] = s
                }
                a[k][k] += 0.05
                var s = 0.0
                for (i in 0 until n) s += wt[i] * jac[k][i] * (target[i] - cur[i])
                b[k] = s
            }
            val d = solve(a, b)
            for (k in 0 until m) g[k] = (g[k] + d[k]).coerceIn(-12.0, 12.0)
        }

        val lv = IntArray(m) { Math.round(g[it]).toInt().coerceIn(-lim, lim) }
        fun cost(l: IntArray): Double {
            val r = resp(DoubleArray(m) { l[it].toDouble() })
            var s = 0.0
            for (i in 0 until n) {
                val e = target[i] - r[i]
                s += wt[i] * e * e
            }
            return s
        }
        var best = cost(lv)
        for (pass in 0 until 3) {
            var improved = false
            for (k in 0 until m) {
                for (d in intArrayOf(-1, 1)) {
                    val nv = lv[k] + d
                    if (nv < -lim || nv > lim) continue
                    lv[k] = nv
                    val c = cost(lv)
                    if (c < best - 1e-9) {
                        best = c
                        improved = true
                    } else {
                        lv[k] -= d
                    }
                }
            }
            if (!improved) break
        }
        return GraphicFit(lv, g, resp(DoubleArray(m) { lv[it].toDouble() }))
    }
}

/** Same chain as Pipeline.runPeq, but the parametric EQ is fitted to the shaped (slope-limited, smoothed) curve. */
internal object AutoEqPipeline {
    private fun ranges(grid: DoubleArray, conf: DoubleArray): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < grid.size) {
            if (conf[i] < 0.3) {
                var j = i
                while (j + 1 < grid.size && conf[j + 1] < 0.3) j++
                out.add(Pipeline.fmtF(grid[i]) + " - " + Pipeline.fmtF(grid[j]))
                i = j + 1
            } else {
                i++
            }
        }
        return out
    }

    fun runPeq(runs: List<SweepResult>, bt: Boolean, device: Int, p: Params, cal: Cal?, smoothDen: Int, verify: Boolean): Report {
        val g = Pipeline.grid()
        val m = Pipeline.measure(runs, g, 0.5 / smoothDen, cal, -1e9)
        val calibrated = cal != null
        val target = DoubleArray(g.size) { Targets.at(device, g[it], p) }
        val resid = DoubleArray(g.size) { if (m.level[it].isNaN()) Double.NaN else m.level[it] - target[it] }
        val conf = DoubleArray(g.size) { Confidence.weight(g[it], m.snr[it], m.sigma[it], bt, calibrated, resid[it], p) }
        val feats = Features.detect(g, resid, conf, p)
        val mask = Features.ignoreMask(g, feats)

        val fr = runs[0].frames
        val rawRel = DoubleArray(fr.fp.size) {
            10 * log10(fr.sig[it]) - (if (cal != null) cal.at(fr.fp[it]) else 0.0) - m.ref
        }

        val simNow = Pipeline.similarity(g, resid)
        val profiles = ArrayList<ProfileResult>()
        if (!verify) {
            val qCap = if (calibrated) p.qMax else min(p.qMax, p.qMaxUncal)
            val refIdx = ArrayList<Int>()
            for (i in g.indices) if (g[i] >= 780.0 && g[i] <= 1300.0) refIdx.add(i)
            for (pi in 0 until 3) {
                val cfg = Pipeline.profileParams(p, pi)
                val confMin = if (pi == 0) 0.7 else if (pi == 1) 0.4 else 0.15
                val qEff = if (pi == 0) min(qCap, 2.0) else qCap
                val wt = DoubleArray(g.size) {
                    if (resid[it].isNaN() || mask[it] || conf[it] < confMin) {
                        0.0
                    } else {
                        conf[it] * (if (resid[it] > 0) cfg.peakBias else 1.0)
                    }
                }
                val shaped = AutoEq.shape(g, resid, conf, mask, cfg.maxBoost, cfg.maxCut, 0.6)
                val fitTarget = DoubleArray(g.size) { -shaped[it] }
                val res = PeqOptimizer.optimize(g, fitTarget, wt, conf, cfg, qEff)

                // AutoEq's sharpness rule: a narrow filter may not have a big gain (keeps slopes below ~18 dB/octave)
                var filters: List<PeqFilter> = res.filters
                var eq = res.eq
                var preamp = res.preamp
                var changed = false
                val fixed = ArrayList<PeqFilter>()
                for (f in res.filters) {
                    val qMax = 20.575 / (abs(f.g) + 0.095)
                    if (f.q > qMax) {
                        fixed.add(PeqFilter(f.f, f.g, max(qMax, cfg.qMin)))
                        changed = true
                    } else {
                        fixed.add(f)
                    }
                }
                if (changed) {
                    val fa = DoubleArray(fixed.size) { fixed[it].f }
                    val ga = DoubleArray(fixed.size) { fixed[it].g }
                    val qa = DoubleArray(fixed.size) { fixed[it].q }
                    eq = DoubleArray(g.size) { EqMath.peqDb(g[it], fa, ga, qa) }
                    var top = 0.0
                    var fd = 20.0
                    while (fd <= 20000.0) {
                        top = max(top, EqMath.peqDb(fd, fa, ga, qa))
                        fd *= 2.0.pow(1.0 / 24)
                    }
                    preamp = if (top > 0.0) -ceil(top * 10) / 10.0 else 0.0
                    filters = fixed
                }

                var eq1k = 0.0
                for (i in refIdx) eq1k += eq[i]
                eq1k /= refIdx.size
                val predRel = DoubleArray(g.size) {
                    if (m.level[it].isNaN()) Double.NaN else m.level[it] + eq[it] - eq1k
                }
                val predDev = DoubleArray(g.size) { if (predRel[it].isNaN()) Double.NaN else predRel[it] - target[it] }
                profiles.add(
                    ProfileResult(Pipeline.PROFILE_NAMES[pi], filters, preamp, eq, predRel, Pipeline.similarity(g, predDev))
                )
            }
        }
        return Report(
            g, m.level, target, resid, conf, m.sigma, fr.fp, rawRel, feats, profiles,
            simNow, runs.size, calibrated, ranges(g, conf), verify
        )
    }
}
