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
    val categoryRes = linkedMapOf(
        "home_speakers" to R.string.cat_home_speakers,
        "car_audio" to R.string.cat_car_audio,
        "home_theater" to R.string.cat_home_theater,
        "cinema" to R.string.cat_cinema,
        "headphones" to R.string.cat_headphones,
    )

    private fun read(ctx: Context, name: String): String =
        ctx.assets.open(name).bufferedReader().use { it.readText() }

    /** Persian texts come from the catalog file itself, English texts from targets_i18n.json. */
    fun load(ctx: Context): List<Target> {
        val fa = ctx.resources.configuration.locales[0].language == "fa"
        val arr = JSONObject(read(ctx, "targets.json")).getJSONArray("targets")
        val ov = JSONObject(read(ctx, "targets_i18n.json"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val id = o.getString("id")
            val x = ov.optJSONObject(id)
            val b = o.optJSONArray("bands_db")
            val limArr = if (fa || x == null) o.getJSONArray("limitations") else x.getJSONArray("el")
            Target(
                id = id,
                name = o.getString("name"),
                category = o.getString("category"),
                application = if (fa || x == null) o.getString("application") else x.getString("ea"),
                limitations = (0 until limArr.length()).map { limArr.getString(it) },
                needsMeasurement = o.optBoolean("needs_measurement"),
                bandsDb = b?.let { a -> DoubleArray(a.length()) { a.getDouble(it) } },
                confidence = o.optString("confidence"),
                sourceNote = if (fa && x != null) x.optString("fn") else o.optString("source_note"),
            )
        }
    }

    fun confidenceRes(c: String): Int = when (c) {
        "exact" -> R.string.conf_exact
        "high" -> R.string.conf_high
        "medium" -> R.string.conf_medium
        "low" -> R.string.conf_low
        "unverified" -> R.string.conf_unverified
        "user" -> R.string.conf_user
        else -> R.string.conf_unknown
    }
}
