package com.milipa.mr.callibrator

import kotlin.math.ln

/** Parses "freq,spl" text (CSV / tab / space) and reduces it to the 9 Samsung bands, normalised at 1 kHz. */
object Measurement {
    fun parse(text: String): List<Pair<Double, Double>> =
        text.lineSequence().mapNotNull { line ->
            val p = line.trim().split(Regex("[,;\\t ]+"))
            val f = p.getOrNull(0)?.toDoubleOrNull()
            val v = p.getOrNull(1)?.toDoubleOrNull()
            if (f != null && v != null && f > 0) f to v else null
        }.sortedBy { it.first }.toList()

    private fun at(points: List<Pair<Double, Double>>, f: Double): Double? {
        if (points.size < 2 || f < points.first().first || f > points.last().first) return null
        val i = points.indexOfFirst { it.first >= f }.coerceAtLeast(1)
        val (f0, v0) = points[i - 1]
        val (f1, v1) = points[i]
        if (f1 == f0) return v1
        val t = (ln(f) - ln(f0)) / (ln(f1) - ln(f0))
        return v0 + t * (v1 - v0)
    }

    fun toBands(points: List<Pair<Double, Double>>): DoubleArray? {
        val raw = SamsungEq.BANDS_HZ.map { at(points, it.toDouble()) ?: return null }
        val ref = raw[4]
        return DoubleArray(9) { raw[it] - ref }
    }
}
