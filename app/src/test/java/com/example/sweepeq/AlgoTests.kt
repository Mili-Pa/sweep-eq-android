package com.example.sweepeq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

private fun bump(f: Double, f0: Double, db: Double, w: Double): Double {
    val x = ln(f / f0) / ln(2.0)
    return db * exp(-0.5 * (x / w) * (x / w))
}

private fun weights(resid: DoubleArray, conf: DoubleArray, p: Params): DoubleArray =
    DoubleArray(resid.size) { if (resid[it].isNaN()) 0.0 else conf[it] * (if (resid[it] > 0) p.peakBias else 1.0) }

class BiquadTest {
    @Test
    fun analyticResponseMatchesRealFilterChain() {
        val f = doubleArrayOf(80.0, 400.0, 2500.0, 9000.0)
        val g = doubleArrayOf(4.0, -6.0, -3.5, 3.0)
        val q = doubleArrayOf(0.7, 1.4, 2.5, 1.0)
        val freqs = DoubleArray(40) { 30.0 * 2.0.pow(it / 4.0) }.filter { it < 16000.0 }.toDoubleArray()
        val err = Validate.maxErrorDb(f, g, q, freqs)
        assertTrue("max error $err dB", err < 0.05)
    }

    @Test
    fun peakGainAtCentreEqualsRequestedGain() {
        assertEquals(5.0, EqMath.peakDb(1000.0, 1000.0, 5.0, 1.2), 0.01)
        assertEquals(-4.0, EqMath.peakDb(250.0, 250.0, -4.0, 2.0), 0.01)
    }
}

class AnalyzerTest {
    private fun simulate(plan: Plan, f: DoubleArray, g: DoubleArray, q: DoubleArray, noiseDb: Double): ShortArray {
        val play = SweepGen.make(plan)
        var x = DoubleArray(play.size) { play[it] / 32768.0 }
        for (j in f.indices) x = Biquad.process(Biquad.peaking(f[j], g[j], q[j]), x)
        val delay = (0.35 * Audio.SR).toInt() + (0.3 * Audio.SR).toInt()
        val total = x.size + delay + Audio.SR
        val rnd = java.util.Random(1)
        val amp = 10.0.pow(noiseDb / 20)
        return ShortArray(total) { i ->
            val s = if (i >= delay && i - delay < x.size) x[i - delay] else 0.0
            ((s + rnd.nextGaussian() * amp) * 32767).roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }

    @Test
    fun recoversKnownResponseWithinTwoDb() {
        val plan = Plan(false)
        val f = doubleArrayOf(100.0, 1000.0, 4000.0)
        val g = doubleArrayOf(3.0, -4.0, 3.0)
        val q = doubleArrayOf(1.0, 1.0, 1.4)
        val rec = simulate(plan, f, g, q, -90.0)
        val res = SweepAnalyzer.analyze(rec, plan, Fft(Audio.N))
        val grid = Pipeline.grid()
        val m = Pipeline.measure(listOf(res), grid, 1.0 / 6, null, -1e9)
        var refTruth = 0.0
        var c = 0
        for (i in grid.indices) {
            if (grid[i] >= 780.0 && grid[i] <= 1300.0) {
                refTruth += EqMath.peqDb(grid[i], f, g, q)
                c++
            }
        }
        refTruth /= c
        var worst = 0.0
        var checked = 0
        for (i in grid.indices) {
            if (grid[i] < 150.0 || grid[i] > 9000.0 || m.level[i].isNaN()) continue
            val truth = EqMath.peqDb(grid[i], f, g, q) - refTruth
            worst = maxOf(worst, abs(m.level[i] - truth))
            checked++
        }
        assertTrue("too few points checked: $checked", checked > 40)
        assertTrue("worst error $worst dB", worst < 2.0)
    }

    @Test
    fun dcOffsetDoesNotChangeTheResult() {
        val plan = Plan(false)
        val f = doubleArrayOf(1000.0)
        val g = doubleArrayOf(0.0)
        val q = doubleArrayOf(1.0)
        val rec = simulate(plan, f, g, q, -90.0)
        val shifted = ShortArray(rec.size) { (rec[it] + 800).coerceIn(-32768, 32767).toShort() }
        val a = SweepAnalyzer.analyze(rec, plan, Fft(Audio.N))
        val b = SweepAnalyzer.analyze(shifted, plan, Fft(Audio.N))
        assertEquals(a.peakDb, b.peakDb, 0.5)
    }
}

class PeqTest {
    private val grid = Pipeline.grid()
    private val ones = DoubleArray(grid.size) { 1.0 }

    @Test
    fun cutsABroadPeak() {
        val p = Params()
        val resid = DoubleArray(grid.size) { bump(grid[it], 3000.0, 6.0, 0.7) }
        val res = PeqOptimizer.optimize(grid, resid, weights(resid, ones, p), ones, p, 5.0)
        assertTrue(res.filters.isNotEmpty())
        val near = res.filters.filter { it.f >= 2000.0 && it.f <= 4500.0 }
        assertTrue("no filter near 3 kHz", near.isNotEmpty())
        assertTrue("expected a cut", near.any { it.g < -3.0 })
    }

    @Test
    fun doesNotBoostADeepNarrowDip() {
        val p = Params()
        val resid = DoubleArray(grid.size) {
            bump(grid[it], 3000.0, 6.0, 0.7) + (if (abs(ln(grid[it] / 200.0) / ln(2.0)) < 0.08) -15.0 else 0.0)
        }
        val res = PeqOptimizer.optimize(grid, resid, weights(resid, ones, p), ones, p, 5.0)
        for (f in res.filters) {
            val near = abs(ln(f.f / 200.0) / ln(2.0)) < 0.5
            assertFalse("boost near the null: $f.f Hz ${f.g} dB", near && f.g > 0)
        }
    }

    @Test
    fun ignoresUnreliableRegion() {
        val p = Params()
        val conf = DoubleArray(grid.size) { if (grid[it] < 120.0) 0.0 else 1.0 }
        val resid = DoubleArray(grid.size) { (if (grid[it] < 120.0) -12.0 else 0.0) + bump(grid[it], 4000.0, 5.0, 0.5) }
        val res = PeqOptimizer.optimize(grid, resid, weights(resid, conf, p), conf, p, 5.0)
        for (f in res.filters) assertTrue("filter at ${f.f} Hz in the unreliable region", f.f >= 120.0)
    }

    @Test
    fun flatResponseNeedsNoFilters() {
        val p = Params()
        val resid = DoubleArray(grid.size)
        val res = PeqOptimizer.optimize(grid, resid, weights(resid, ones, p), ones, p, 5.0)
        assertTrue(res.filters.isEmpty())
    }

    @Test
    fun respectsAllConstraints() {
        val p = Params()
        p.maxBoost = 3.0
        p.maxCut = -5.0
        p.filters = 6
        val resid = DoubleArray(grid.size) {
            val x = ln(grid[it] / 1000.0) / ln(2.0)
            if (x < 0) 20.0 * x / 5.0 else 12.0 * x / 3.0
        }
        val res = PeqOptimizer.optimize(grid, resid, weights(resid, ones, p), ones, p, 2.5)
        assertTrue(res.filters.size <= p.filters)
        for (f in res.filters) {
            assertTrue("gain ${f.g}", f.g <= p.maxBoost + 1e-9 && f.g >= p.maxCut - 1e-9)
            assertTrue("Q ${f.q}", f.q >= p.qMin - 1e-9 && f.q <= 2.5 + 1e-9)
            assertTrue("freq ${f.f}", f.f >= p.fMin - 1.0 && f.f <= p.fMax + 1.0)
        }
        for (i in res.filters.indices) {
            for (j in i + 1 until res.filters.size) {
                val sep = abs(ln(res.filters[i].f / res.filters[j].f) / ln(2.0))
                assertTrue("filters too close: $sep octaves", sep >= 0.24)
            }
        }
    }

    @Test
    fun preampLeavesHeadroomForBoosts() {
        val p = Params()
        val resid = DoubleArray(grid.size) { bump(grid[it], 100.0, -5.0, 0.6) }
        val res = PeqOptimizer.optimize(grid, resid, weights(resid, ones, p), ones, p, 5.0)
        if (res.filters.any { it.g > 0 }) assertTrue("preamp ${res.preamp}", res.preamp < 0.0)
        assertTrue(res.preamp <= 0.0)
    }
}

class FeaturesTest {
    private val grid = Pipeline.grid()
    private val ones = DoubleArray(grid.size) { 1.0 }

    @Test
    fun broadPeakIsClassifiedAsBroad() {
        val resid = DoubleArray(grid.size) { bump(grid[it], 3000.0, 6.0, 0.7) }
        val feats = Features.detect(grid, resid, ones, Params())
        assertTrue(feats.any { it.isPeak && it.cls == "broad peak" && it.f > 2000.0 && it.f < 4500.0 })
    }

    @Test
    fun narrowDeepDipIsTreatedAsNull() {
        val resid = DoubleArray(grid.size) { if (abs(ln(grid[it] / 200.0) / ln(2.0)) < 0.08) -15.0 else 0.0 }
        val feats = Features.detect(grid, resid, ones, Params())
        assertTrue(feats.any { !it.isPeak && it.ignored })
        val mask = Features.ignoreMask(grid, feats)
        assertTrue(mask.any { it })
    }
}

class ParamsCalTest {
    @Test
    fun parsesKeyValueLinesAndReportsProblems() {
        val p = Params()
        val problems = p.parse("maxBoost=3\nqmax: 2.5 # note\nfoo=1\nbad=abc\n")
        assertEquals(3.0, p.maxBoost, 1e-9)
        assertEquals(2.5, p.qMax, 1e-9)
        assertTrue(problems.any { it.contains("Unknown key") })
        assertTrue(problems.any { it.contains("Not a number") })
    }

    @Test
    fun parameterTextRoundTrips() {
        val a = Params()
        a.bass = 2.0
        a.filters = 6
        val b = Params()
        b.parse(a.toText())
        assertEquals(2.0, b.bass, 1e-9)
        assertEquals(6, b.filters)
    }

    @Test
    fun calibrationFileIsParsedAndInterpolated() {
        val cal = Cal.parse("Freq(Hz) SPL(dB)\n20 -2.0\n1000 0.0\n10000 4.0")
        assertNotNull(cal)
        assertEquals(0.0, cal!!.at(1000.0), 1e-9)
        assertEquals(-1.18, cal.at(100.0), 0.05)
        assertEquals(4.0, cal.at(50000.0), 1e-9)
    }

    @Test
    fun targetsAreNormalisedAtOneKilohertz() {
        for (t in 0 until 5) assertEquals(0.0, Targets.at(t, 1000.0, Params()), 1e-9)
        val h = Targets.at(1, 3000.0, Params())
        assertTrue("Harman at 3 kHz: $h", h > 9.0 && h < 9.6)
    }

    @Test
    fun confidenceRisesWithSnrAndDropsForLeakage() {
        val p = Params()
        val low = Confidence.weight(1000.0, 8.0, Double.NaN, false, false, 0.0, p)
        val high = Confidence.weight(1000.0, 25.0, Double.NaN, false, false, 0.0, p)
        assertTrue(high > low)
        assertEquals(0.0, Confidence.weight(1000.0, 25.0, Double.NaN, true, false, -20.0, p), 1e-9)
        val unstable = Confidence.weight(1000.0, 25.0, 4.0, false, false, 0.0, p)
        assertTrue(unstable < high)
    }
}
