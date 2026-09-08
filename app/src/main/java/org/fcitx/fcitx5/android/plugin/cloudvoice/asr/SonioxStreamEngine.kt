package org.fcitx.fcitx5.android.plugin.cloudvoice.asr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.fcitx.fcitx5.android.plugin.cloudvoice.SessionLog
import org.fcitx.fcitx5.android.plugin.cloudvoice.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Soniox realtime streaming: WS with a JSON config first message (api key in
 * body), raw binary PCM frames, token events with is_final segmentation.
 * Protocol per BiBi-Keyboard SonioxStreamAsrEngine (Apache-2.0).
 */
class SonioxStreamEngine(
    private val scope: CoroutineScope,
    private val settings: Settings,
    private val listener: StreamingAsrEngine.Listener,
) : StreamingAsrEngine {

    companion object {
        private const val WS_URL = "wss://stt-rt.soniox.com/transcribe-websocket"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val wsReady = AtomicBoolean(false)
    private val awaitingFinal = AtomicBoolean(false)
    private var ws: WebSocket? = null

    private val stable = StringBuilder()
    private var tentative = ""

    override val isRunning: Boolean get() = running.get()

    override fun start() {
        if (running.get()) return
        running.set(true)
        cancelled.set(false)
        stable.setLength(0)
        tentative = ""
        val req = Request.Builder().url(WS_URL).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val config = JSONObject()
                    .put("api_key", settings.get("sx_api_key"))
                    .put("model", "stt-rt-v5")
                    .put("audio_format", "pcm_s16le")
                    .put("sample_rate", 16000)
                    .put("num_channels", 1)
                    .put("enable_endpoint_detection", true)
                settings.get("sx_languages").split(',', '，')
                    .map { it.trim() }.filter { it.isNotEmpty() }
                    .takeIf { it.isNotEmpty() }
                    ?.let { config.put("language_hints", JSONArray(it)) }
                settings.csv("sx_hotwords").takeIf { it.isNotEmpty() }?.let { terms ->
                    config.put("context", JSONObject().put("terms", JSONArray(terms)))
                }
                settings.get("sx_endpoint_ms").trim().toIntOrNull()?.let {
                    config.put("max_endpoint_delay_ms", it.coerceIn(500, 3000))
                }
                webSocket.send(config.toString())
                wsReady.set(true)
                SessionLog.log("soniox", "ws open, config sent")
                if (!running.get() && awaitingFinal.get()) finalize()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val o = runCatching { JSONObject(text) }.getOrNull() ?: return
                if (o.has("error_code") && o.getInt("error_code") != 0) {
                    fail("Soniox error ${o.optInt("error_code")}: ${o.optString("error_message")}")
                    return
                }
                val tokens = o.optJSONArray("tokens")
                if (tokens != null) {
                    var incTentative = StringBuilder()
                    for (i in 0 until tokens.length()) {
                        val t = tokens.getJSONObject(i)
                        val txt = t.optString("text")
                        if (t.optBoolean("is_final")) stable.append(txt) else incTentative.append(txt)
                    }
                    if (incTentative.isNotEmpty()) tentative = incTentative.toString()
                    val preview = stable.toString() + tentative
                    if (running.get() && preview.isNotBlank()) listener.onPartial(preview)
                }
                if (o.optBoolean("finished")) {
                    deliverFinal(stable.toString() + tentative)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                SessionLog.log("soniox", "ws failure: ${t.message}")
                if (running.get() || awaitingFinal.get()) fail("WS failed: ${t.message}")
            }
        })
    }

    override fun feedPcm(pcm: ByteArray) {
        if (running.get() && wsReady.get()) {
            runCatching { ws?.send(ByteString.of(*pcm)) }
        }
    }

    override fun stop() {
        if (!running.getAndSet(false)) return
        awaitingFinal.set(true)
        listener.onStopped()
        if (wsReady.get()) finalize()
        scope.launch(Dispatchers.IO) {
            var left = 8_000L
            while (awaitingFinal.get() && left > 0) { delay(50); left -= 50 }
            if (awaitingFinal.getAndSet(false)) deliverFinal(stable.toString() + tentative)
        }
    }

    private fun finalize() {
        runCatching {
            ws?.send("""{"type":"finalize"}""")
        }
    }

    private fun deliverFinal(text: String) {
        awaitingFinal.set(false)
        runCatching { ws?.close(1000, "final") }
        ws = null
        wsReady.set(false)
        if (!cancelled.get()) listener.onFinal(text.trim())
    }

    override fun cancel() {
        running.set(false)
        cancelled.set(true)
        awaitingFinal.set(false)
        runCatching { ws?.close(1000, "cancel") }
        ws = null
        wsReady.set(false)
        listener.onStopped()
    }

    private fun fail(msg: String) {
        running.set(false)
        awaitingFinal.set(false)
        runCatching { ws?.close(1000, "err") }
        ws = null
        wsReady.set(false)
        if (!cancelled.get()) listener.onError(msg)
    }
}
