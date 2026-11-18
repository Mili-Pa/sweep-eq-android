package com.example.sweepeq

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * Remote microphone for the Debian sweepcal server (protocol: PROTOCOL.md in the sweepcal project).
 * The server sends `arm`; this records mono PCM16 for the requested time and uploads it as a WAV.
 */
class RemoteClient(private val ctx: Context, private val listener: Listener) {

    interface Listener {
        fun onState(text: String)
        fun onLog(line: String)
    }

    private val wsClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val upClient = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var ws: WebSocket? = null
    @Volatile private var wanted = false
    @Volatile private var host = ""
    @Volatile private var port = 8765
    @Volatile private var token = ""
    @Volatile private var micId = "src:UNPROCESSED"
    @Volatile private var abortRec = false
    private val busy = AtomicBoolean(false)
    private var reconnect: Runnable? = null
    private var backoff = 1000L

    fun connect(host: String, port: Int, token: String) {
        this.host = host
        this.port = port
        this.token = token
        wanted = true
        backoff = 1000L
        reconnect?.let { main.removeCallbacks(it) }
        ws?.cancel()
        ws = null
        open()
    }

    fun disconnect() {
        wanted = false
        reconnect?.let { main.removeCallbacks(it) }
        abortRec = true
        try {
            ws?.close(1000, "bye")
        } catch (_: Throwable) {
        }
        ws = null
        listener.onState("Disconnected")
    }

    private fun open() {
        if (!wanted) return
        listener.onState("Connecting to $host:$port ...")
        val req = Request.Builder()
            .url("http://$host:$port/ws/phone")
            .header("Authorization", "Bearer $token")
            .build()
        ws = wsClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                backoff = 1000L
                webSocket.send(hello().toString())
                listener.onState("Connected to $host:$port")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handle(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                lost(webSocket, "closed ($code)")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (response != null && (response.code == 401 || response.code == 403)) {
                    if (webSocket === ws) {
                        ws = null
                        wanted = false
                        listener.onState("Rejected by the server: wrong token. Run `sweepcal pair` again.")
                    }
                    return
                }
                lost(webSocket, t.message ?: "connection failed")
            }
        })
    }

    private fun lost(w: WebSocket, why: String) {
        if (w !== ws) return
        ws = null
        listener.onState("Disconnected: $why (retrying)")
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (!wanted) return
        val r = Runnable { open() }
        reconnect = r
        main.postDelayed(r, backoff)
        backoff = min(backoff * 2, 15000L)
    }

    private fun send(o: JSONObject) {
        ws?.send(o.toString())
    }

    private fun ack(cid: String, ok: Boolean, err: String?) {
        send(JSONObject().put("type", "ack").put("cmd_id", cid).put("ok", ok).put("error", err ?: JSONObject.NULL))
    }

    private fun battery(): Int = try {
        (ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (_: Throwable) {
        -1
    }

    private fun status(st: String, extra: JSONObject? = null) {
        val o = JSONObject().put("type", "status").put("state", st).put("mic_id", micId).put("battery", battery())
        if (extra != null) {
            val keys = extra.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                o.put(k, extra.get(k))
            }
        }
        send(o)
        listener.onLog("state: $st")
        listener.onState("Connected to $host:$port - $st")
    }

    private fun typeName(t: Int): String = when (t) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "built-in"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
        else -> "type $t"
    }

    private fun mics(): JSONArray {
        val a = JSONArray()
        fun add(id: String, name: String, type: String) {
            a.put(JSONObject().put("id", id).put("name", name).put("type", type))
        }
        add("src:UNPROCESSED", "Unprocessed (default)", "source")
        add("src:MIC", "Main mic (MIC)", "source")
        add("src:CAMCORDER", "Second mic (CAMCORDER)", "source")
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            for (d in am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                val t = d.type
                if (t == AudioDeviceInfo.TYPE_BUILTIN_MIC || t == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    t == AudioDeviceInfo.TYPE_USB_HEADSET || t == AudioDeviceInfo.TYPE_WIRED_HEADSET
                ) {
                    add("dev:${d.id}", "${d.productName} (${typeName(t)})", "device")
                }
            }
        } catch (_: Throwable) {
        }
        return a
    }

    private fun hello(): JSONObject {
        val ver = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
        } catch (_: Throwable) {
            ""
        }
        return JSONObject()
            .put("type", "hello")
            .put("device_id", Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown")
            .put("name", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("app_version", ver)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("sample_rates", JSONArray().put(44100).put(48000))
            .put("mics", mics())
    }

    private fun handle(text: String) {
        val m = try {
            JSONObject(text)
        } catch (_: Throwable) {
            return
        }
        val cid = m.optString("cmd_id")
        when (m.optString("cmd")) {
            "welcome" -> listener.onLog("server ${m.optString("server_version")}")
            "set_mic" -> {
                micId = m.optString("mic_id", micId)
                listener.onLog("mic -> $micId")
                ack(cid, true, null)
            }
            "arm" -> arm(cid, m)
            "abort" -> {
                abortRec = true
                ack(cid, true, null)
            }
            "ping" -> ack(cid, true, null)
        }
    }

    private fun disableEffects(session: Int, keep: MutableList<AudioEffect>) {
        try {
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl.create(session)?.let { it.setEnabled(false); keep.add(it) }
            }
        } catch (_: Throwable) {
        }
        try {
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(session)?.let { it.setEnabled(false); keep.add(it) }
            }
        } catch (_: Throwable) {
        }
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(session)?.let { it.setEnabled(false); keep.add(it) }
            }
        } catch (_: Throwable) {
        }
    }

    private fun openRecord(sr: Int, effects: MutableList<AudioEffect>): AudioRecord {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val inputs: List<AudioDeviceInfo> = try {
            am.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        } catch (_: Throwable) {
            emptyList()
        }
        val id = micId
        var sources = intArrayOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC)
        var device: AudioDeviceInfo? = null
        if (id == "src:MIC") {
            sources = intArrayOf(MediaRecorder.AudioSource.MIC)
        } else if (id == "src:CAMCORDER") {
            sources = intArrayOf(MediaRecorder.AudioSource.CAMCORDER)
        } else if (id.startsWith("dev:")) {
            device = inputs.firstOrNull { "dev:${it.id}" == id }
        }
        if (!id.startsWith("dev:")) {
            // like the Sweep EQ app: always the phone's own microphone, never a Bluetooth headset mic
            device = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        }
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        for (src in sources) {
            try {
                val a = AudioRecord(
                    src, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    max(minBuf, sr) * 2
                )
                if (a.state == AudioRecord.STATE_INITIALIZED) {
                    if (device != null) {
                        try {
                            a.setPreferredDevice(device)
                        } catch (_: Throwable) {
                        }
                    }
                    disableEffects(a.audioSessionId, effects)
                    return a
                }
                a.release()
            } catch (_: Throwable) {
            }
        }
        throw IllegalStateException("Cannot open the microphone ($id) at $sr Hz")
    }

    private fun arm(cid: String, m: JSONObject) {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ack(cid, false, "RECORD_AUDIO permission not granted on the phone")
            return
        }
        if (!busy.compareAndSet(false, true)) {
            ack(cid, false, "already recording")
            return
        }
        val recId = m.optString("rec_id")
        val dur = m.optDouble("duration_s", 10.0)
        val sr = m.optInt("sample_rate", 48000)
        abortRec = false
        thread(name = "sweepcal-rec") {
            var rec: AudioRecord? = null
            val effects = ArrayList<AudioEffect>()
            var acked = false
            try {
                rec = openRecord(sr, effects)
                val total = (dur * sr).toInt()
                val buf = ShortArray(total)
                rec.startRecording()
                if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw IllegalStateException("AudioRecord did not start")
                }
                ack(cid, true, null)
                acked = true
                status("recording")
                var n = 0
                while (n < total && !abortRec) {
                    val r = rec.read(buf, n, min(4096, total - n))
                    if (r < 0) throw IllegalStateException("read error $r")
                    n += r
                }
                rec.stop()
                if (abortRec) {
                    status("idle")
                } else {
                    var pk = 0
                    for (s in buf) pk = max(pk, abs(s.toInt()))
                    val peak = 20 * log10(max(pk, 1) / 32768.0)
                    status("uploading", JSONObject().put("peak_dbfs", peak))
                    upload(recId, wav(buf, sr))
                    status("idle", JSONObject().put("peak_dbfs", peak))
                }
            } catch (e: Throwable) {
                if (!acked) ack(cid, false, e.message ?: e.javaClass.simpleName)
                val msg = "recording failed: ${e.message}"
                listener.onLog(msg)
                send(JSONObject().put("type", "log").put("line", msg))
                status("idle")
            } finally {
                try {
                    rec?.release()
                } catch (_: Throwable) {
                }
                for (e in effects) {
                    try {
                        e.release()
                    } catch (_: Throwable) {
                    }
                }
                busy.set(false)
            }
        }
    }

    private fun wav(pcm: ShortArray, sr: Int): ByteArray {
        val dataLen = pcm.size * 2
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt(36 + dataLen).put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sr).putInt(sr * 2).putShort(2).putShort(16)
        bb.put("data".toByteArray()).putInt(dataLen)
        for (s in pcm) bb.putShort(s)
        return bb.array()
    }

    private fun upload(recId: String, bytes: ByteArray) {
        val req = Request.Builder()
            .url("http://$host:$port/api/upload/$recId")
            .header("Authorization", "Bearer $token")
            .post(bytes.toRequestBody("audio/wav".toMediaType()))
            .build()
        upClient.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("upload failed: HTTP ${r.code}")
        }
    }
}
