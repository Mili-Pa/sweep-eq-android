package com.example.sweepeq

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Every tunable number of the recommendation engine. Edited as key=value lines in the Advanced panel. */
internal class Params {
    var filters = 8
    var maxBoost = 4.0
    var maxCut = -6.0
    var qMin = 0.5
    var qMax = 5.0
    /** Largest Q used when the microphone is not calibrated (narrow filters are not trustworthy then). */
    var qMaxUncal = 2.5
    var fMin = 30.0
    var fMax = 16000.0
    var snrLow = 6.0
    var snrHigh = 20.0
    var sigma2 = 18.0
    var lam1 = 1.0
    var lam2 = 0.5
    var lam3 = 0.4
    var lam4 = 1.0
    /** Weight of points above the target (they need a cut) relative to points below it. */
    var peakBias = 1.4
    var nullDepth = 6.0
    var nullWidth = 0.5
    var bass = 0.0
    var mid = 0.0
    var treble = 0.0
    var presence = 0.0

    fun copy(): Params {
        val c = Params()
        c.filters = filters
        c.maxBoost = maxBoost
        c.maxCut = maxCut
        c.qMin = qMin
        c.qMax = qMax
        c.qMaxUncal = qMaxUncal
        c.fMin = fMin
        c.fMax = fMax
        c.snrLow = snrLow
        c.snrHigh = snrHigh
        c.sigma2 = sigma2
        c.lam1 = lam1
        c.lam2 = lam2
        c.lam3 = lam3
        c.lam4 = lam4
        c.peakBias = peakBias
        c.nullDepth = nullDepth
        c.nullWidth = nullWidth
        c.bass = bass
        c.mid = mid
        c.treble = treble
        c.presence = presence
        return c
    }

    fun toText(): String {
        fun f(v: Double): String = String.format(Locale.US, "%.2f", v)
        val sb = StringBuilder()
        sb.append("# EQ limits\n")
        sb.append("filters=").append(filters).append("\n")
        sb.append("maxBoost=").append(f(maxBoost)).append("\n")
        sb.append("maxCut=").append(f(maxCut)).append("\n")
        sb.append("qMin=").append(f(qMin)).append("\n")
        sb.append("qMax=").append(f(qMax)).append("\n")
        sb.append("qMaxUncal=").append(f(qMaxUncal)).append("\n")
        sb.append("fMin=").append(f(fMin)).append("\n")
        sb.append("fMax=").append(f(fMax)).append("\n")
        sb.append("# measurement confidence\n")
        sb.append("snrLow=").append(f(snrLow)).append("\n")
        sb.append("snrHigh=").append(f(snrHigh)).append("\n")
        sb.append("sigma2=").append(f(sigma2)).append("\n")
        sb.append("# cost weights: target, boost, filter count, uncertainty\n")
        sb.append("lam1=").append(f(lam1)).append("\n")
        sb.append("lam2=").append(f(lam2)).append("\n")
        sb.append("lam3=").append(f(lam3)).append("\n")
        sb.append("lam4=").append(f(lam4)).append("\n")
        sb.append("# peaks / dips\n")
        sb.append("peakBias=").append(f(peakBias)).append("\n")
        sb.append("nullDepth=").append(f(nullDepth)).append("\n")
        sb.append("nullWidth=").append(f(nullWidth)).append("\n")
        sb.append("# tonal preference in dB\n")
        sb.append("bass=").append(f(bass)).append("\n")
        sb.append("mid=").append(f(mid)).append("\n")
        sb.append("treble=").append(f(treble)).append("\n")
        sb.append("presence=").append(f(presence)).append("\n")
        return sb.toString()
    }

    /** Reads key=value lines (# starts a comment). Returns messages about lines that could not be used. */
    fun parse(text: String): List<String> {
        val problems = ArrayList<String>()
        for (raw in text.lines()) {
            var line = raw
            val hash = line.indexOf('#')
            if (hash >= 0) line = line.substring(0, hash)
            line = line.trim()
            if (line.isEmpty()) continue
            val sep = line.indexOfFirst { it == '=' || it == ':' }
            if (sep < 0) {
                problems.add("Ignored line: $line")
                continue
            }
            val key = line.substring(0, sep).trim().lowercase(Locale.US)
            val v = line.substring(sep + 1).trim().toDoubleOrNull()
            if (v == null) {
                problems.add("Not a number: $line")
                continue
            }
            when (key) {
                "filters" -> filters = v.toInt().coerceIn(1, 15)
                "maxboost" -> maxBoost = v.coerceIn(0.0, 18.0)
                "maxcut" -> maxCut = v.coerceIn(-18.0, 0.0)
                "qmin" -> qMin = v.coerceIn(0.2, 10.0)
                "qmax" -> qMax = v.coerceIn(0.2, 10.0)
                "qmaxuncal" -> qMaxUncal = v.coerceIn(0.2, 10.0)
                "fmin" -> fMin = v.coerceIn(20.0, 20000.0)
                "fmax" -> fMax = v.coerceIn(20.0, 20000.0)
                "snrlow" -> snrLow = v.coerceIn(-10.0, 40.0)
                "snrhigh" -> snrHigh = v.coerceIn(-10.0, 60.0)
                "sigma2" -> sigma2 = v.coerceIn(0.5, 200.0)
                "lam1" -> lam1 = v.coerceIn(0.0, 100.0)
                "lam2" -> lam2 = v.coerceIn(0.0, 100.0)
                "lam3" -> lam3 = v.coerceIn(0.0, 100.0)
                "lam4" -> lam4 = v.coerceIn(0.0, 100.0)
                "peakbias" -> peakBias = v.coerceIn(0.1, 10.0)
                "nulldepth" -> nullDepth = v.coerceIn(1.0, 40.0)
                "nullwidth" -> nullWidth = v.coerceIn(0.05, 3.0)
                "bass" -> bass = v.coerceIn(-12.0, 12.0)
                "mid" -> mid = v.coerceIn(-12.0, 12.0)
                "treble" -> treble = v.coerceIn(-12.0, 12.0)
                "presence" -> presence = v.coerceIn(-12.0, 12.0)
                else -> problems.add("Unknown key: $key")
            }
        }
        if (qMin > qMax) {
            val t = qMin
            qMin = qMax
            qMax = t
        }
        if (fMin > fMax) {
            val t = fMin
            fMin = fMax
            fMax = t
        }
        if (snrHigh < snrLow + 1.0) snrHigh = snrLow + 1.0
        return problems
    }
}

/** Target curves in dB relative to 1 kHz, by device type, plus the user's bass / mid / treble / presence preference. */
internal object Targets {
    val NAMES = arrayOf(
        "flat (reference)",
        "Harman in-ear 2019v2",
        "Harman in-ear 2019v2 (stand-in for headphones)",
        "room curve (gentle downward tilt)",
        "car curve (strong bass, downward tilt)"
    )

    private val HF = doubleArrayOf(
        20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0, 250.0, 300.0, 400.0, 500.0, 630.0,
        800.0, 1000.0, 1250.0, 1400.0, 1600.0, 1800.0, 2000.0, 2500.0, 2800.0, 3000.0, 3150.0, 3500.0, 4000.0,
        4500.0, 5000.0, 5600.0, 6300.0, 7000.0, 8000.0, 9000.0, 10000.0, 11200.0, 12500.0, 14000.0, 16000.0,
        18000.0, 19750.0
    )
    private val HV = doubleArrayOf(
        7.620, 7.719, 7.699, 7.415, 6.662, 5.550, 4.188, 2.820, 1.440, 0.112, -0.962, -1.576, -1.710, -1.599,
        -1.402, -1.102, -0.637, 0.001, 1.153, 2.019, 3.145, 4.436, 5.803, 8.299, 9.065, 9.301, 9.400, 9.330,
        8.884, 8.319, 7.729, 7.128, 6.382, 5.397, 3.580, 1.504, -0.736, -3.092, -5.352, -7.494, -10.195,
        -14.494, -19.320
    )

    private fun interp(f: Double, xs: DoubleArray, ys: DoubleArray): Double {
        if (f <= xs[0]) return ys[0]
        if (f >= xs[xs.size - 1]) return ys[ys.size - 1]
        var i = 0
        while (i < xs.size - 2 && f > xs[i + 1]) i++
        val t = ln(f / xs[i]) / ln(xs[i + 1] / xs[i])
        return ys[i] + t * (ys[i + 1] - ys[i])
    }

    fun base(type: Int, f: Double): Double {
        return when (type) {
            1, 2 -> interp(f, HF, HV)
            3 -> -1.0 * log2(max(f, 200.0) / 1000.0) + 3.0 / (1.0 + (f / 80.0).pow(2))
            4 -> -1.3 * log2(max(f, 200.0) / 1000.0) + 5.0 / (1.0 + (f / 80.0).pow(2))
            else -> 0.0
        }
    }

    fun prefs(f: Double, p: Params): Double {
        val x = ln(f / 1000.0) / ln(2.0)
        val bassS = 1.0 / (1.0 + (f / 150.0).pow(2))
        val trebS = (f / 5000.0).pow(2) / (1.0 + (f / 5000.0).pow(2))
        val midB = exp(-0.5 * (x / 0.75).pow(2))
        val presB = exp(-0.5 * ((ln(f / 3000.0) / ln(2.0)) / 0.5).pow(2))
        return p.bass * bassS + p.treble * trebS + p.mid * midB + p.presence * presB
    }

    /** Target at frequency f, normalised to 0 dB at 1 kHz. */
    fun at(type: Int, f: Double, p: Params): Double {
        return base(type, f) + prefs(f, p) - (base(type, 1000.0) + prefs(1000.0, p))
    }
}

/** Microphone calibration: list of (frequency, deviation in dB). The deviation is subtracted from the measurement. */
internal class Cal(val f: DoubleArray, val db: DoubleArray) {
    fun at(freq: Double): Double {
        if (freq <= f[0]) return db[0]
        if (freq >= f[f.size - 1]) return db[db.size - 1]
        var i = 0
        while (i < f.size - 2 && freq > f[i + 1]) i++
        val t = ln(freq / f[i]) / ln(f[i + 1] / f[i])
        return db[i] + t * (db[i + 1] - db[i])
    }

    companion object {
        /** Reads lines with "frequency dB" (separated by space, tab, comma or semicolon). Other lines are skipped. */
        fun parse(text: String): Cal? {
            val pts = ArrayList<DoubleArray>()
            for (raw in text.lines()) {
                val toks = raw.trim().split(Regex("[\\s,;]+"))
                if (toks.size < 2) continue
                val a = toks[0].toDoubleOrNull()
                val b = toks[1].toDoubleOrNull()
                if (a == null || b == null || a <= 0.0) continue
                pts.add(doubleArrayOf(a, b))
            }
            if (pts.size < 2) return null
            pts.sortBy { it[0] }
            return Cal(DoubleArray(pts.size) { pts[it][0] }, DoubleArray(pts.size) { pts[it][1] })
        }
    }
}

/** Confidence weight W(f) in [0, 1] of one grid point. */
internal object Confidence {
    private fun lerp(f: Double, f1: Double, v1: Double, f2: Double, v2: Double): Double {
        val t = (ln(f / f1) / ln(f2 / f1)).coerceIn(0.0, 1.0)
        return v1 + t * (v2 - v1)
    }

    /** How far a phone microphone can be trusted at this frequency (without a calibration file it is a rough guess). */
    fun micPrior(f: Double, bt: Boolean, calibrated: Boolean): Double {
        var w: Double
        if (calibrated) {
            w = if (f < 40.0 || f > 16000.0) 0.6 else 1.0
        } else if (f < 60.0) {
            w = 0.2
        } else if (f < 120.0) {
            w = lerp(f, 60.0, 0.2, 120.0, 0.6)
        } else if (f < 250.0) {
            w = lerp(f, 120.0, 0.6, 250.0, 1.0)
        } else if (f <= 8000.0) {
            w = 1.0
        } else if (f <= 14000.0) {
            w = lerp(f, 8000.0, 1.0, 14000.0, 0.6)
        } else {
            w = 0.3
        }
        // earbuds pressed against a microphone leak a lot of bass
        if (bt && f < 300.0) w *= 0.6
        return w
    }

    fun weight(f: Double, snr: Double, sigma: Double, bt: Boolean, calibrated: Boolean, devDb: Double, p: Params): Double {
        if (snr.isNaN() || devDb.isNaN()) return 0.0
        val ws = ((snr - p.snrLow) / (p.snrHigh - p.snrLow)).coerceIn(0.0, 1.0)
        val wr = if (sigma.isNaN()) 1.0 else exp(-sigma * sigma / p.sigma2)
        var w = ws * wr * micPrior(f, bt, calibrated)
        // more than 15 dB under the target with earbuds is almost always sealing leakage, not the earbud
        if (bt && devDb < -15.0) w = 0.0
        return w
    }
}

internal class Feature(
    val f: Double,
    val prom: Double,
    val widthOct: Double,
    val isPeak: Boolean,
    val cls: String,
    val w: Double,
    val ignored: Boolean
)

/** Finds peaks and dips in the deviation from the target and classifies them. */
internal object Features {
    fun detect(grid: DoubleArray, r: DoubleArray, w: DoubleArray, p: Params): List<Feature> {
        val n = grid.size
        val out = ArrayList<Feature>()
        val signs = intArrayOf(1, -1)
        for (sign in signs) {
            val v = DoubleArray(n) { if (r[it].isNaN()) Double.NaN else sign * r[it] }
            for (i in 2 until n - 2) {
                val c = v[i]
                if (c.isNaN()) continue
                var isMax = true
                for (d in -2..2) {
                    if (d == 0) continue
                    val o = v[i + d]
                    if (o.isNaN() || o > c || (d < 0 && o == c)) {
                        isMax = false
                        break
                    }
                }
                if (!isMax) continue
                var leftMin = c
                var j = i - 1
                while (j >= 0 && !v[j].isNaN() && v[j] <= c) {
                    leftMin = min(leftMin, v[j])
                    j--
                }
                var rightMin = c
                var k = i + 1
                while (k < n && !v[k].isNaN() && v[k] <= c) {
                    rightMin = min(rightMin, v[k])
                    k++
                }
                val prom = c - max(leftMin, rightMin)
                if (prom < 2.0) continue
                val halfLevel = c - prom / 2
                var a = i
                while (a > 0 && !v[a - 1].isNaN() && v[a - 1] >= halfLevel) a--
                var b = i
                while (b < n - 1 && !v[b + 1].isNaN() && v[b + 1] >= halfLevel) b++
                val width = (b - a + 1) / 12.0
                val conf = w[i]
                val isPeak = sign == 1
                val f = grid[i]
                var ignored = false
                val cls: String
                if (conf < 0.3) {
                    cls = "unreliable region (ignored)"
                    ignored = true
                } else if (isPeak) {
                    cls = if (width < 0.33 && prom >= 4.0 && f < 600.0 && conf >= 0.5) {
                        "resonance (narrow peak)"
                    } else if (width < 0.33) {
                        "narrow peak"
                    } else if (width >= 1.0) {
                        "broad peak"
                    } else {
                        "peak"
                    }
                } else if (width < p.nullWidth && prom >= p.nullDepth) {
                    cls = "possible null / cancellation (ignored)"
                    ignored = true
                } else if (prom < p.nullDepth) {
                    cls = if (width >= 1.0) "broad shallow dip" else "shallow dip"
                } else {
                    cls = "broad deep dip (boost limited)"
                }
                out.add(Feature(f, prom, width, isPeak, cls, conf, ignored))
            }
        }
        out.sortByDescending { it.prom }
        return out
    }

    /** Grid points belonging to ignored features (nulls, unreliable regions): the optimizer must not chase them. */
    fun ignoreMask(grid: DoubleArray, feats: List<Feature>): BooleanArray {
        val m = BooleanArray(grid.size)
        for (ft in feats) {
            if (!ft.ignored) continue
            val hw = max(ft.widthOct, 0.34) / 2
            for (i in grid.indices) {
                if (abs(ln(grid[i] / ft.f) / ln(2.0)) <= hw) m[i] = true
            }
        }
        return m
    }
}

internal class PeqFilter(val f: Double, val g: Double, val q: Double)

internal class PeqResult(val filters: List<PeqFilter>, val eq: DoubleArray, val cost: Double, val preamp: Double)

private class Trial(val j: Double, val g: Double, val r: DoubleArray)

/**
 * Greedy parametric-EQ optimizer. Cost J = lam1*E_target + lam2*E_boost + lam3*E_complexity + lam4*E_uncertainty.
 * Filters are added one at a time where they reduce J most (gain by weighted least squares, then exact cost),
 * then every filter is re-optimised in turn and filters that no longer pay for themselves are removed.
 */
internal object PeqOptimizer {
    private val QS = doubleArrayOf(0.5, 0.7, 1.0, 1.4, 2.0, 3.0, 5.0)

    private fun cost(resid: DoubleArray, wt: DoubleArray, wAll: DoubleArray, eq: DoubleArray, nFilters: Int, p: Params): Double {
        var sw = 0.0
        var se = 0.0
        var cnt = 0
        for (i in resid.indices) {
            if (resid[i].isNaN()) continue
            cnt++
            sw += wt[i]
            se += wt[i] * (resid[i] + eq[i])
        }
        // only the shape matters, a constant level offset is free (it is just the preamp)
        val off = if (sw > 0) se / sw else 0.0
        var et = 0.0
        var eb = 0.0
        var eu = 0.0
        for (i in resid.indices) {
            if (resid[i].isNaN()) continue
            if (wt[i] > 0) {
                val d = resid[i] + eq[i] - off
                et += wt[i] * d * d
            }
            val pos = max(eq[i], 0.0)
            eb += pos * pos
            eu += (1.0 - wAll[i]) * eq[i] * eq[i]
        }
        if (sw > 0) et /= sw
        if (cnt > 0) {
            eb /= cnt
            eu /= cnt
        }
        return p.lam1 * et + p.lam2 * eb + p.lam3 * nFilters + p.lam4 * eu
    }

    fun optimize(grid: DoubleArray, resid: DoubleArray, wt: DoubleArray, wAll: DoubleArray, p: Params, qMaxEff: Double): PeqResult {
        val n = grid.size
        val qList = ArrayList<Double>()
        for (q in QS) {
            if (q >= p.qMin - 1e-9 && q <= qMaxEff + 1e-9) qList.add(q)
        }
        if (qList.isEmpty()) qList.add(min(max(1.0, p.qMin), qMaxEff))
        val freqs = ArrayList<Double>()
        var ff = p.fMin
        while (ff <= p.fMax * 1.0001) {
            freqs.add(ff)
            ff *= 2.0.pow(1.0 / 6)
        }
        val nc = freqs.size * qList.size
        val cf = DoubleArray(nc)
        val cq = DoubleArray(nc)
        val unit = Array(nc) { DoubleArray(0) }
        var idx = 0
        for (f in freqs) {
            for (q in qList) {
                cf[idx] = f
                cq[idx] = q
                unit[idx] = DoubleArray(n) { EqMath.peakDb(grid[it], f, 6.0, q) / 6.0 }
                idx++
            }
        }

        val fi = ArrayList<Int>()
        val fg = ArrayList<Double>()
        val resp = ArrayList<DoubleArray>()
        var eq = DoubleArray(n)
        var cur = cost(resid, wt, wAll, eq, 0, p)

        fun bestGain(eqBase: DoubleArray, c: Int): Double {
            var sw = 0.0
            var se = 0.0
            var su = 0.0
            for (i in 0 until n) {
                if (resid[i].isNaN()) continue
                sw += wt[i]
                se += wt[i] * (resid[i] + eqBase[i])
                su += wt[i] * unit[c][i]
            }
            if (sw <= 0) return 0.0
            val eb = se / sw
            val ub = su / sw
            var num = 0.0
            var den = 0.0
            for (i in 0 until n) {
                if (resid[i].isNaN()) continue
                val du = unit[c][i] - ub
                num += wt[i] * (resid[i] + eqBase[i] - eb) * du
                den += wt[i] * du * du
            }
            if (den < 1e-9) return 0.0
            val g = (-num / den).coerceIn(p.maxCut, p.maxBoost)
            return Math.round(g * 10) / 10.0
        }

        fun trial(eqBase: DoubleArray, k: Int, c: Int): Trial? {
            val g = bestGain(eqBase, c)
            if (abs(g) < 0.3) return null
            val r = DoubleArray(n) { EqMath.peakDb(grid[it], cf[c], g, cq[c]) }
            val e = DoubleArray(n) { eqBase[it] + r[it] }
            return Trial(cost(resid, wt, wAll, e, k, p), g, r)
        }

        fun tooClose(c: Int, skip: Int): Boolean {
            for (j in fi.indices) {
                if (j == skip) continue
                if (abs(ln(cf[c] / cf[fi[j]]) / ln(2.0)) < 0.25) return true
            }
            return false
        }

        // greedy growth
        for (step in 0 until p.filters) {
            var bestT: Trial? = null
            var bestC = -1
            for (c in 0 until nc) {
                if (tooClose(c, -1)) continue
                val t = trial(eq, fi.size + 1, c)
                if (t != null) {
                    val b = bestT
                    if (b == null || t.j < b.j) {
                        bestT = t
                        bestC = c
                    }
                }
            }
            val bt = bestT
            if (bt == null || bt.j >= cur - 0.02) break
            fi.add(bestC)
            fg.add(bt.g)
            resp.add(bt.r)
            eq = DoubleArray(n) { eq[it] + bt.r[it] }
            cur = bt.j
        }

        // refinement: re-optimise each filter with the others fixed
        for (pass in 0 until 2) {
            for (j in fi.indices) {
                val eqWo = DoubleArray(n) { eq[it] - resp[j][it] }
                var bestT: Trial? = null
                var bestC = -1
                for (c in 0 until nc) {
                    if (tooClose(c, j)) continue
                    val t = trial(eqWo, fi.size, c)
                    if (t != null) {
                        val b = bestT
                        if (b == null || t.j < b.j) {
                            bestT = t
                            bestC = c
                        }
                    }
                }
                val bt = bestT
                if (bt != null && bt.j < cur - 1e-6) {
                    fi[j] = bestC
                    fg[j] = bt.g
                    resp[j] = bt.r
                    eq = DoubleArray(n) { eqWo[it] + bt.r[it] }
                    cur = bt.j
                }
            }
        }

        // prune filters that are tiny or do not pay for their complexity
        var changed = true
        while (changed && fi.isNotEmpty()) {
            changed = false
            for (j in fi.indices) {
                val eqWo = DoubleArray(n) { eq[it] - resp[j][it] }
                val jWo = cost(resid, wt, wAll, eqWo, fi.size - 1, p)
                if (jWo <= cur + 1e-9 || abs(fg[j]) < 0.5) {
                    eq = eqWo
                    fi.removeAt(j)
                    fg.removeAt(j)
                    resp.removeAt(j)
                    cur = jWo
                    changed = true
                    break
                }
            }
        }

        // final filters with tidy numbers; the response is recomputed from exactly these numbers
        val list = ArrayList<PeqFilter>()
        for (j in fi.indices) {
            val f = Math.round(cf[fi[j]]).toDouble()
            list.add(PeqFilter(f, fg[j], Math.round(cq[fi[j]] * 100) / 100.0))
        }
        list.sortBy { it.f }
        val fa = DoubleArray(list.size) { list[it].f }
        val ga = DoubleArray(list.size) { list[it].g }
        val qa = DoubleArray(list.size) { list[it].q }
        val finalEq = DoubleArray(n) { EqMath.peqDb(grid[it], fa, ga, qa) }

        // headroom: lower everything by the highest boost anywhere in the audible range so that nothing clips
        var top = 0.0
        var fd = 20.0
        while (fd <= 20000.0) {
            top = max(top, EqMath.peqDb(fd, fa, ga, qa))
            fd *= 2.0.pow(1.0 / 24)
        }
        val preamp = if (top > 0.0) -ceil(top * 10) / 10.0 else 0.0
        return PeqResult(list, finalEq, cur, preamp)
    }
}

internal class Measured(val level: DoubleArray, val snr: DoubleArray, val sigma: DoubleArray, val ref: Double)

internal class ProfileResult(
    val name: String,
    val filters: List<PeqFilter>,
    val preamp: Double,
    val eq: DoubleArray,
    val predRel: DoubleArray,
    val simAfter: Double
)

internal class Report(
    val grid: DoubleArray,
    val rel: DoubleArray,
    val target: DoubleArray,
    val resid: DoubleArray,
    val conf: DoubleArray,
    val sigma: DoubleArray,
    val rawF: DoubleArray,
    val rawRel: DoubleArray,
    val features: List<Feature>,
    val profiles: List<ProfileResult>,
    val simNow: Double,
    val runs: Int,
    val calibrated: Boolean,
    val unreliable: List<String>,
    val verify: Boolean
)

/** The whole chain from sweep frames to three PEQ recommendations. */
internal object Pipeline {
    val PROFILE_NAMES = arrayOf("Safe", "Balanced", "Aggressive")

    fun grid(): DoubleArray = DoubleArray(113) { 25.0 * 2.0.pow(it / 12.0) }

    /**
     * Levels in dB relative to 1 kHz on the given centres. Bands whose SNR is below snrMin are dropped, the
     * calibration file (if any) is subtracted.
     */
    fun measure(runs: List<SweepResult>, centers: DoubleArray, halfOct: Double, cal: Cal?, snrMin: Double): Measured {
        val stats = ArrayList<BandStats>()
        for (r in runs) stats.add(Bands.levels(r.frames, centers, halfOct))
        val comb = Combine.of(stats)
        val lvl = DoubleArray(centers.size) {
            val v = comb.level[it]
            if (v.isNaN() || comb.snr[it] < snrMin) {
                Double.NaN
            } else if (cal != null) {
                v - cal.at(centers[it])
            } else {
                v
            }
        }
        var s = 0.0
        var c = 0
        for (k in centers.indices) {
            if (centers[k] >= 780.0 && centers[k] <= 1300.0 && !lvl[k].isNaN()) {
                s += lvl[k]
                c++
            }
        }
        if (c < 2) throw IllegalStateException("Signal too weak or too noisy around 1 kHz. Raise the volume or reduce noise and try again.")
        val ref = s / c
        val rel = DoubleArray(centers.size) { if (lvl[it].isNaN()) Double.NaN else lvl[it] - ref }
        return Measured(rel, comb.snr, comb.sigma, ref)
    }

    fun similarity(grid: DoubleArray, d: DoubleArray): Double {
        var s = 0.0
        var c = 0
        for (i in grid.indices) {
            if (grid[i] >= 100.0 && grid[i] <= 10000.0 && !d[i].isNaN()) {
                s += d[i] * d[i]
                c++
            }
        }
        if (c < 8) return Double.NaN
        return max(0.0, 100.0 - sqrt(s / c) * 100.0 / 12.0)
    }

    fun profileParams(p: Params, idx: Int): Params {
        val c = p.copy()
        if (idx == 0) {
            c.filters = min(5, p.filters)
            c.maxBoost = min(p.maxBoost, 3.0)
            c.maxCut = max(p.maxCut, -4.0)
            c.lam3 = p.lam3 * 2
        } else if (idx == 2) {
            c.filters = min(10, p.filters + 2)
            c.maxBoost = p.maxBoost + 2.0
            c.maxCut = p.maxCut - 3.0
            c.lam2 = p.lam2 * 0.5
            c.lam3 = p.lam3 * 0.5
        }
        return c
    }

    private fun ranges(grid: DoubleArray, conf: DoubleArray): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < grid.size) {
            if (conf[i] < 0.3) {
                var j = i
                while (j + 1 < grid.size && conf[j + 1] < 0.3) j++
                out.add(fmtF(grid[i]) + " - " + fmtF(grid[j]))
                i = j + 1
            } else {
                i++
            }
        }
        return out
    }

    fun fmtF(f: Double): String {
        return if (f >= 1000.0) String.format(Locale.US, "%.1f kHz", f / 1000.0) else String.format(Locale.US, "%.0f Hz", f)
    }

    fun runPeq(runs: List<SweepResult>, bt: Boolean, device: Int, p: Params, cal: Cal?, smoothDen: Int, verify: Boolean): Report {
        val g = grid()
        val m = measure(runs, g, 0.5 / smoothDen, cal, -1e9)
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

        val simNow = similarity(g, resid)
        val profiles = ArrayList<ProfileResult>()
        if (!verify) {
            val qCap = if (calibrated) p.qMax else min(p.qMax, p.qMaxUncal)
            val refIdx = ArrayList<Int>()
            for (i in g.indices) if (g[i] >= 780.0 && g[i] <= 1300.0) refIdx.add(i)
            for (pi in 0 until 3) {
                val cfg = profileParams(p, pi)
                val confMin = if (pi == 0) 0.7 else if (pi == 1) 0.4 else 0.15
                val qEff = if (pi == 0) min(qCap, 2.0) else qCap
                val wt = DoubleArray(g.size) {
                    if (resid[it].isNaN() || mask[it] || conf[it] < confMin) {
                        0.0
                    } else {
                        conf[it] * (if (resid[it] > 0) cfg.peakBias else 1.0)
                    }
                }
                val res = PeqOptimizer.optimize(g, resid, wt, conf, cfg, qEff)
                var eq1k = 0.0
                for (i in refIdx) eq1k += res.eq[i]
                eq1k /= refIdx.size
                val predRel = DoubleArray(g.size) {
                    if (m.level[it].isNaN()) Double.NaN else m.level[it] + res.eq[it] - eq1k
                }
                val predDev = DoubleArray(g.size) { if (predRel[it].isNaN()) Double.NaN else predRel[it] - target[it] }
                profiles.add(ProfileResult(PROFILE_NAMES[pi], res.filters, res.preamp, res.eq, predRel, similarity(g, predDev)))
            }
        }
        return Report(
            g, m.level, target, resid, conf, m.sigma, fr.fp, rawRel, feats, profiles,
            simNow, runs.size, calibrated, ranges(g, conf), verify
        )
    }
}
