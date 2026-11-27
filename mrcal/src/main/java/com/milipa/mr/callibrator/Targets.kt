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
    val builtIn: Boolean = true,
    val curveF: DoubleArray? = null,
    val curveDb: DoubleArray? = null,
)

object Targets {
    val categoryRes = linkedMapOf(
        "user" to R.string.cat_user,
        "home_speakers" to R.string.cat_home_speakers,
        "car_audio" to R.string.cat_car_audio,
        "home_theater" to R.string.cat_home_theater,
        "cinema" to R.string.cat_cinema,
        "headphones" to R.string.cat_headphones,
    )

    // Only curves with a trustworthy source are shown; approximate / unverified ones were thrown away.
    private val KEEP = setOf("exact", "high", "medium")
    private val DROP_IDS = setOf("neutral_studio")

    private fun read(ctx: Context, name: String): String =
        ctx.assets.open(name).bufferedReader().use { it.readText() }

    /** Persian texts come from the catalog file itself, English texts from targets_i18n.json. */
    fun load(ctx: Context): List<Target> {
        val fa = ctx.resources.configuration.locales[0].language == "fa"
        val arr = JSONObject(read(ctx, "targets.json")).getJSONArray("targets")
        val ov = JSONObject(read(ctx, "targets_i18n.json"))
        val cv = JSONObject(read(ctx, "curves.json")).optJSONObject("override") ?: JSONObject()
        val list = ArrayList<Target>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.getString("id")
            if (id in DROP_IDS) continue
            val c = cv.optJSONObject(id)
            val conf = if (c != null) c.optString("conf") else o.optString("confidence")
            if (conf !in KEEP) continue
            val x = ov.optJSONObject(id)
            val b = if (c != null) c.optJSONArray("bands") else o.optJSONArray("bands_db")
            val limArr = if (fa || x == null) o.getJSONArray("limitations") else x.getJSONArray("el")
            val note = when {
                c != null -> if (fa) c.optString("srcFa") else c.optString("src")
                fa && x != null -> x.optString("fn")
                else -> o.optString("source_note")
            }
            list.add(
                Target(
                    id = id,
                    name = o.getString("name"),
                    category = o.getString("category"),
                    application = if (fa || x == null) o.getString("application") else x.getString("ea"),
                    limitations = (0 until limArr.length()).map { limArr.getString(it) },
                    needsMeasurement = o.optBoolean("needs_measurement"),
                    bandsDb = b?.let { a -> DoubleArray(a.length()) { a.getDouble(it) } },
                    confidence = conf,
                    sourceNote = note,
                )
            )
        }
        for (u in Store.loadCurves(ctx)) {
            list.add(
                Target(
                    id = u.id,
                    name = u.name,
                    category = "user",
                    application = "",
                    limitations = emptyList(),
                    needsMeasurement = false,
                    bandsDb = u.bands.toDoubleArray(),
                    confidence = "user",
                    sourceNote = "",
                    builtIn = false,
                    curveF = u.ptsF,
                    curveDb = u.ptsDb,
                )
            )
        }
        return list
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
