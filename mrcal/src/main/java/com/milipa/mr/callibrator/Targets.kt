package com.milipa.mr.callibrator

import android.content.Context
import org.json.JSONObject

data class Target(
    val id: String,
    val name: String,
    val category: String,
    val application: String,
    val limitations: List<String>,
    val needsMeasurement: Boolean,
    val bandsDb: DoubleArray?,
    val confidence: String,
    val sourceNote: String,
)

object Targets {
    val categoryLabels = linkedMapOf(
        "home_speakers" to "اسپیکر خانگی",
        "car_audio" to "خودرو",
        "home_theater" to "سینمای خانگی",
        "cinema" to "سینما",
        "headphones" to "هدفون",
    )

    fun load(ctx: Context): List<Target> {
        val text = ctx.assets.open("targets.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(text).getJSONArray("targets")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val b = o.optJSONArray("bands_db")
            val lim = o.getJSONArray("limitations")
            Target(
                id = o.getString("id"),
                name = o.getString("name"),
                category = o.getString("category"),
                application = o.getString("application"),
                limitations = (0 until lim.length()).map { lim.getString(it) },
                needsMeasurement = o.optBoolean("needs_measurement"),
                bandsDb = b?.let { a -> DoubleArray(a.length()) { a.getDouble(it) } },
                confidence = o.optString("confidence"),
                sourceNote = o.optString("source_note"),
            )
        }
    }

    fun confidenceLabel(c: String) = when (c) {
        "exact" -> "دقیق"
        "high" -> "قابل‌اتکا"
        "medium" -> "متوسط"
        "low" -> "تقریبی"
        "unverified" -> "تأییدنشده"
        "user" -> "سفارشی"
        else -> "نامشخص"
    }
}
