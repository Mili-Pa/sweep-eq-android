package com.example.sweepeq

import android.content.Context

/** Pairing data for the Debian sweepcal server (address, port, token). */
object RemotePrefs {
    private fun sp(c: Context) = c.getSharedPreferences("remote", Context.MODE_PRIVATE)

    fun host(c: Context): String = sp(c).getString("host", "") ?: ""
    fun port(c: Context): Int = sp(c).getInt("port", 8765)
    fun token(c: Context): String = sp(c).getString("token", "") ?: ""

    fun save(c: Context, host: String, port: Int, token: String) {
        sp(c).edit().putString("host", host).putInt("port", port).putString("token", token).apply()
    }
}
