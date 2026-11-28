package com.milipa.mr.callibrator

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class UserCurve(val id: String, val name: String, val bands: List<Double>, val ptsF: DoubleArray?, val ptsDb: DoubleArray?)

/** A saved equalizer setting; `output` is the audio output that was in use when it was saved. */
class EqPreset(val id: String, val name: String, val levels: List<Int>, val output: String = "")

/** Small persistent store (SharedPreferences, JSON) for the user's own curves and saved equalizer settings. */
object Store {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("mrcal", Context.MODE_PRIVATE)

    fun loadCurves(ctx: Context): List<UserCurve> {
        val txt = sp(ctx).getString("curves", null) ?: return emptyList()
        return try {
            val a = JSONArray(txt)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val b = o.getJSONArray("bands")
                val f = o.optJSONArray("f")
                val d = o.optJSONArray("d")
                UserCurve(
                    o.getString("id"),
                    o.getString("name"),
                    (0 until b.length()).map { b.getDouble(it) },
                    f?.let { x -> DoubleArray(x.length()) { x.getDouble(it) } },
                    d?.let { x -> DoubleArray(x.length()) { x.getDouble(it) } },
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveCurves(ctx: Context, list: List<UserCurve>) {
        val a = JSONArray()
        for (c in list) {
            val o = JSONObject()
            o.put("id", c.id)
            o.put("name", c.name)
            o.put("bands", JSONArray(c.bands))
            if (c.ptsF != null && c.ptsDb != null) {
                o.put("f", JSONArray(c.ptsF.toList()))
                o.put("d", JSONArray(c.ptsDb.toList()))
            }
            a.put(o)
        }
        sp(ctx).edit().putString("curves", a.toString()).apply()
    }

    fun loadPresets(ctx: Context): List<EqPreset> {
        val txt = sp(ctx).getString("presets", null) ?: return emptyList()
        return try {
            val a = JSONArray(txt)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val l = o.getJSONArray("levels")
                EqPreset(o.getString("id"), o.getString("name"), (0 until l.length()).map { l.getInt(it) }, o.optString("out", ""))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun savePresets(ctx: Context, list: List<EqPreset>) {
        val a = JSONArray()
        for (p in list) {
            val o = JSONObject()
            o.put("id", p.id)
            o.put("name", p.name)
            o.put("levels", JSONArray(p.levels))
            o.put("out", p.output)
            a.put(o)
        }
        sp(ctx).edit().putString("presets", a.toString()).apply()
    }
}
