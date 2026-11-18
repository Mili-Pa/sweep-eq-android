package com.example.sweepeq

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Pairing screen for Remote mode: the phone becomes the microphone of the Debian sweepcal server. */
class RemoteActivity : Activity() {

    private lateinit var hostEt: EditText
    private lateinit var portEt: EditText
    private lateinit var tokenEt: EditText
    private lateinit var status: TextView
    private lateinit var logTv: TextView
    private lateinit var connectBtn: Button
    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var pending: (() -> Unit)? = null
    private val refresh: () -> Unit = { runOnUiThread { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (12 * d).toInt()
        val fg = MainActivity.FG
        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Throwable) {
            ""
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(MainActivity.BG)
        }
        root.addView(TextView(this).apply {
            text = "SweepCal Remote v$ver"
            textSize = 22f
            setTextColor(fg)
        })
        root.addView(TextView(this).apply {
            text = "The phone becomes the microphone of the SweepCal server on your Debian PC. " +
                "On the PC run `sweepcal pair`, then scan its QR code with the camera (it opens this screen and connects), " +
                "or press Find PC and type the token. Put the phone where you listen, then start the calibration " +
                "from the PC page (button below). Keep the phone still and the room quiet while it measures."
            setTextColor(fg)
            textSize = 13f
            setPadding(0, pad / 2, 0, pad / 2)
        })

        fun field(hint: String, value: String, type: Int): EditText = EditText(this).apply {
            this.hint = hint
            setText(value)
            inputType = type
            setSingleLine()
            setTextColor(fg)
            setHintTextColor(Color.GRAY)
        }
        hostEt = field("PC address, e.g. 192.168.0.10", RemotePrefs.host(this),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        portEt = field("Port", RemotePrefs.port(this).toString(), InputType.TYPE_CLASS_NUMBER)
        tokenEt = field("Token (from sweepcal pair)", RemotePrefs.token(this), InputType.TYPE_CLASS_TEXT)

        val discoverBtn = Button(this).apply {
            text = "Find PC on LAN"
            setOnClickListener { discover() }
        }
        connectBtn = Button(this).apply {
            text = "Connect"
            setOnClickListener { if (RemoteService.running) stopRemote() else startRemote() }
        }
        val webBtn = Button(this).apply {
            text = "Open calibration page"
            setOnClickListener { openWeb() }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(discoverBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(connectBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        status = TextView(this).apply {
            setTextColor(fg)
            setPadding(0, pad / 2, 0, pad / 2)
        }
        logTv = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(fg)
        }
        root.addView(hostEt)
        root.addView(portEt)
        root.addView(tokenEt)
        root.addView(row)
        root.addView(webBtn)
        root.addView(status)
        root.addView(logTv)
        setContentView(ScrollView(this).apply {
            setBackgroundColor(MainActivity.BG)
            addView(root)
        })
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** sweepcal://pair?host=..&port=..&token=.. (QR code from `sweepcal pair`) */
    private fun handleIntent(i: Intent?) {
        val u = i?.data ?: return
        if (u.scheme != "sweepcal") return
        val h = u.getQueryParameter("host") ?: return
        val p = u.getQueryParameter("port")?.toIntOrNull() ?: 8765
        val t = u.getQueryParameter("token") ?: return
        hostEt.setText(h)
        portEt.setText(p.toString())
        tokenEt.setText(t)
        RemotePrefs.save(this, h, p, t)
        setIntent(Intent())
        startRemote()
    }

    override fun onStart() {
        super.onStart()
        RemoteService.observers.add(refresh)
        render()
    }

    override fun onStop() {
        RemoteService.observers.remove(refresh)
        stopDiscovery()
        super.onStop()
    }

    private fun render() {
        connectBtn.text = if (RemoteService.running) "Disconnect" else "Connect"
        status.text = RemoteService.lastState
        logTv.text = RemoteService.lines.takeLast(40).joinToString("\n")
    }

    private fun withPerms(action: () -> Unit) {
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isEmpty()) {
            action()
        } else {
            pending = action
            requestPermissions(need.toTypedArray(), 2)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending?.invoke()
        } else {
            status.text = "Microphone permission is required."
        }
        pending = null
    }

    private fun startRemote() {
        val h = hostEt.text.toString().trim()
        val p = portEt.text.toString().trim().toIntOrNull() ?: 8765
        val t = tokenEt.text.toString().trim()
        if (h.isEmpty() || t.isEmpty()) {
            status.text = "Enter the PC address and the token (or scan the QR code from `sweepcal pair`)."
            return
        }
        RemotePrefs.save(this, h, p, t)
        withPerms {
            val i = Intent(this, RemoteService::class.java).setAction(RemoteService.ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        }
    }

    private fun stopRemote() {
        startService(Intent(this, RemoteService::class.java).setAction(RemoteService.ACTION_STOP))
    }

    private fun openWeb() {
        val h = hostEt.text.toString().trim()
        val p = portEt.text.toString().trim().toIntOrNull() ?: 8765
        val t = tokenEt.text.toString().trim()
        if (h.isEmpty()) {
            status.text = "Enter the PC address first."
            return
        }
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://$h:$p/?token=$t")))
    }

    @Suppress("DEPRECATION")
    private fun discover() {
        stopDiscovery()
        val m = getSystemService(Context.NSD_SERVICE) as NsdManager
        nsd = m
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                runOnUiThread { status.text = "Searching the LAN ..." }
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                m.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {}

                    override fun onServiceResolved(si: NsdServiceInfo) {
                        val addr = si.host?.hostAddress ?: return
                        runOnUiThread {
                            hostEt.setText(addr)
                            portEt.setText(si.port.toString())
                            status.text = "Found SweepCal at $addr:${si.port}. Enter the token and press Connect."
                        }
                    }
                })
            }

            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                runOnUiThread { status.text = "Discovery failed ($errorCode)" }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discovery = l
        m.discoverServices("_sweepcal._tcp", NsdManager.PROTOCOL_DNS_SD, l)
    }

    private fun stopDiscovery() {
        val l = discovery ?: return
        try {
            nsd?.stopServiceDiscovery(l)
        } catch (_: Throwable) {
        }
        discovery = null
    }
}
