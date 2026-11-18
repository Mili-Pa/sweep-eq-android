package com.example.sweepeq

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** Keeps the connection to the Debian server alive (foreground service, microphone type). */
class RemoteService : Service(), RemoteClient.Listener {

    companion object {
        const val ACTION_START = "com.example.sweepeq.REMOTE_START"
        const val ACTION_STOP = "com.example.sweepeq.REMOTE_STOP"
        private const val CHANNEL = "remote"
        private const val NOTIF_ID = 77

        @Volatile var running = false
        @Volatile var lastState = "Not connected"
        val lines = CopyOnWriteArrayList<String>()
        val observers = CopyOnWriteArrayList<() -> Unit>()

        fun notifyObservers() {
            for (o in observers) {
                try {
                    o()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private var client: RemoteClient? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        startFg("Starting ...")
        val c = client ?: RemoteClient(this, this).also { client = it }
        running = true
        acquireLocks()
        c.connect(RemotePrefs.host(this), RemotePrefs.port(this), RemotePrefs.token(this))
        notifyObservers()
        return START_NOT_STICKY
    }

    private fun startFg(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "SweepCal Remote", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n = buildNotif(text)
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotif(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, RemoteActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return b.setContentTitle("SweepCal Remote")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onState(text: String) {
        lastState = text
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotif(text))
        } catch (_: Throwable) {
        }
        notifyObservers()
    }

    override fun onLog(line: String) {
        lines.add(fmt.format(Date()) + "  " + line)
        while (lines.size > 200) lines.removeAt(0)
        notifyObservers()
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wake == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sweepcal:remote").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (wifi == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "sweepcal:remote").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        try {
            wake?.release()
        } catch (_: Throwable) {
        }
        try {
            wifi?.release()
        } catch (_: Throwable) {
        }
        wake = null
        wifi = null
    }

    @Suppress("DEPRECATION")
    private fun shutdown() {
        client?.disconnect()
        client = null
        releaseLocks()
        running = false
        lastState = "Not connected"
        stopForeground(true)
        stopSelf()
        notifyObservers()
    }

    override fun onDestroy() {
        client?.disconnect()
        client = null
        releaseLocks()
        running = false
        super.onDestroy()
    }
}
