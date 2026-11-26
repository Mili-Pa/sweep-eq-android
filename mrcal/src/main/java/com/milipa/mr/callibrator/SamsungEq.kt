package com.milipa.mr.callibrator

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Talks to Samsung's "Sound quality and effects" system app (com.sec.android.app.soundalive).
 * Found by reverse-engineering v13.0.43: its exported SAContentProvider (no permission) accepts
 * an insert() with EQ_BAR_POSITION = "l1,...,l9" (9 integer band levels) and returns the
 * current custom levels from query() when the projection contains EQ_BAR_POSITION.
 * Verified on a SM-T875 (Android 13): insert then query returned the same values.
 */
object SamsungEq {
    const val PKG = "com.sec.android.app.soundalive"
    const val EQ_ACTIVITY = "com.sec.android.app.soundalive.activity.EqualizerActivity"
    const val KEY_BANDS = "EQ_BAR_POSITION"
    const val MIN = -10
    const val MAX = 10
    val BANDS_HZ = intArrayOf(63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    private val URI: Uri = Uri.parse("content://com.sec.android.app.soundalive.compatibility.SAContentProvider")

    fun clamp(v: Double): Int = Math.round(v).toInt().coerceIn(MIN, MAX)

    fun read(ctx: Context): Result<IntArray> = runCatching {
        ctx.contentResolver.query(URI, arrayOf(KEY_BANDS), null, null, null)?.use { c ->
            if (!c.moveToFirst()) error("empty result")
            val parts = c.getString(0).orEmpty().split(",").map { it.trim().toInt() }
            if (parts.size != 9) error("unexpected answer: ${c.getString(0)}")
            parts.toIntArray()
        } ?: error("provider not reachable (is the Samsung app present and enabled?)")
    }

    /** Sends the levels, then reads them back to confirm. */
    fun apply(ctx: Context, levels: IntArray): Result<Boolean> = runCatching {
        require(levels.size == 9)
        val cv = ContentValues().apply { put(KEY_BANDS, levels.joinToString(",")) }
        ctx.contentResolver.insert(URI, cv)
        val back = read(ctx).getOrNull()
        back != null && back.contentEquals(levels)
    }

    fun openEqualizer(ctx: Context): Result<Unit> = runCatching {
        ctx.startActivity(Intent().setClassName(PKG, EQ_ACTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
