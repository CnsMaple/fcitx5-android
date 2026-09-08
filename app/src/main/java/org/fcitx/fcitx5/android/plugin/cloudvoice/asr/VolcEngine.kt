package org.fcitx.fcitx5.android.plugin.cloudvoice.asr

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.fcitx.fcitx5.android.plugin.cloudvoice.SessionLog
import org.fcitx.fcitx5.android.plugin.cloudvoice.Settings
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Doubao / Volcengine streaming ASR over the sauc bigmodel_async binary protocol.
 * Protocol ported from BiBi-Keyboard (Apache-2.0, Copyright BryceWG).
 */
class VolcEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: Settings,
    private val listener: StreamingAsrEngine.Listener,
) : StreamingAsrEngine {

    companion object {
        private const val TAG = "VolcEngine"
        private const val WS_URL = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"

        private const val PROTOCOL_VERSION = 0x1
        private const val HEADER_SIZE_UNITS = 0x1

        private const val MSG_FULL_CLIENT_REQ = 0x1
        private const val MSG_AUDIO_CLIENT_REQ = 0x2
        private const val MSG_FULL_SERVER_RESP = 0x9
        private const val MSG_ERROR = 0xF

        private const val SERIALIZE_JSON = 0x1
        private const val COMPRESS_GZIP = 0x1
        private const val FLAG_AUDIO_LAST = 0x2
        private const val FLAG_SERVER_FINAL = 0x3
    }

    private val running = AtomicBoolean(false)
    private val wsReady = AtomicBoolean(false)
    private val awaitingFinal = AtomicBoolean(false)
    private val audioLastSent = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private var audioFramesSent = 0
    @Volatile private var lastPartial = ""
    private var ws: WebSocket? = null
    private val prebuffer = ArrayDeque<ByteArray>()
    private val prebufferLock = Any()

    private val http = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    override val isRunning: Boolean get() = running.get()

    override fun start() {
        if (running.get()) return
        running.set(true)
        cancelled.set(false)
        synchronized(prebufferLock) { prebuffer.clear() }
        audioLastSent.set(false)

        val connectId = UUID.randomUUID().toString()
        val req = Request.Builder()
            .url(WS_URL)
            .apply {
                addHeader("X-Api-Key", settings.volcApiKey)
                addHeader("X-Api-Resource-Id", settings.volcResourceId)
                addHeader("X-Api-Connect-Id", connectId)
            }
            .build()
        ws = http.newWebSocket(req, listener0)
        SessionLog.log("volc", "connecting resource=${settings.volcResourceId} keyLen=${settings.volcApiKey.length}")
        // audio arrives externally via feedPcm (captured by the host IME process)
    }

    /** External PCM push from the host app (100 ms chunks). */
    override fun feedPcm(pcm: ByteArray) {
        if (!running.get()) return
        if (!wsReady.get()) {
            synchronized(prebufferLock) {
                prebuffer.addLast(pcm)
                while (prebuffer.size > 20) prebuffer.removeFirst()
            }
        } else {
            flushPrebuffer()
            runCatching { sendAudioFrame(pcm, last = false) }
        }
    }

    override fun stop() {
        if (!running.get()) return
        running.set(false)
        awaitingFinal.set(true)
        scope.launch(Dispatchers.IO) {
            var waited = 0
            while (!wsReady.get() && waited < 500) { delay(50); waited += 50 }
            if (!wsReady.get()) {
                // never connected (e.g. instant release): end quietly, empty result
                runCatching { ws?.close(1000, "stop") }
                ws = null
                awaitingFinal.set(false)
                listener.onStopped()
                listener.onFinal("")
                return@launch
            }
            flushPrebufferAndSendLast()
            var left = 3_000L
            while (awaitingFinal.get() && left > 0) { delay(50); left -= 50 }
            runCatching { ws?.close(1000, "stop") }
            ws = null
            wsReady.set(false)
            if (awaitingFinal.get()) {
                // no final packet arrived: deliver the latest partial as final
                awaitingFinal.set(false)
                listener.onFinal(lastPartial)
            }
        }
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

    private fun flushPrebuffer() {
        var flushed: Array<ByteArray>
        synchronized(prebufferLock) {
            flushed = prebuffer.toTypedArray()
            prebuffer.clear()
        }
        flushed.forEach { runCatching { sendAudioFrame(it, last = false) } }
    }

    private fun flushPrebufferAndSendLast() {
        if (!wsReady.get()) return
        flushPrebuffer()
        if (!audioLastSent.get()) {
            runCatching { sendAudioFrame(byteArrayOf(), last = true) }
            audioLastSent.set(true)
        }
    }

    private fun fail(msg: String) {
        running.set(false)
        awaitingFinal.set(false)
        runCatching { ws?.close(1000, "err") }
        ws = null
        wsReady.set(false)
        if (!cancelled.get()) listener.onError(msg)
    }

    private val listener0 = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            try {
                val payload = gzip(buildFullRequestJson().toByteArray())
                webSocket.send(
                    ByteString.of(*buildClientFrame(MSG_FULL_CLIENT_REQ, 0, SERIALIZE_JSON, COMPRESS_GZIP, payload))
                )
                SessionLog.log("volc", "ws open, full request sent (${payload.size}B gz)")
                wsReady.set(true)
                if (!running.get() && awaitingFinal.get()) flushPrebufferAndSendLast()
            } catch (t: Throwable) {
                SessionLog.log("volc", "send full request failed: ${t.message}")
                fail("send full request failed: ${t.message}")
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            try {
                handleServerMessage(bytes.toByteArray())
            } catch (t: Throwable) {
                SessionLog.log("volc", "handle message failed: ${t.message}")
                Log.e(TAG, "handle message failed", t)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            SessionLog.log("volc", "ws failure: ${t.javaClass.simpleName}: ${t.message} http=${response?.code}")
            Log.e(TAG, "ws failure: ${t.message}", t)
            if (running.get() || awaitingFinal.get()) fail("WS failed: ${t.message}")
        }
    }

    private fun buildFullRequestJson(): String {
        val user = JSONObject().put("uid", settings.volcApiKey)
        val audio = JSONObject().apply {
            put("format", "pcm")
            put("rate", 16000)
            put("bits", 16)
            put("channel", 1)
            if (settings.volcLanguage.isNotBlank()) put("language", settings.volcLanguage)
        }
        val request = JSONObject().apply {
            put("model_name", "bigmodel")
            put("enable_itn", settings.flag("volc_itn", true))
            put("enable_punc", settings.flag("volc_punc", true))
            if (settings.flag("volc_ddc", false)) put("enable_ddc", true)
            val hot = settings.csv("volc_hotwords")
            if (hot.isNotEmpty()) {
                val words = JSONArray()
                hot.take(100).forEach { words.put(JSONObject().put("word", it)) }
                put("corpus", JSONObject().put("context", JSONObject().put("hotwords", words).toString()))
            }
        }
        return JSONObject().put("user", user).put("audio", audio).put("request", request).toString()
    }

    private fun sendAudioFrame(pcm: ByteArray, last: Boolean) {
        val socket = ws ?: return
        if (!wsReady.get()) return
        // NOTE: the new-auth (X-Api-Key) gateway fails to ungzip audio frames for
        // seedasr resources ("unable to ungzip payload: EOF"); raw PCM works and
        // saves CPU. Full-client-request stays gzip'd (accepted).
        val frame = buildClientFrame(
            messageType = MSG_AUDIO_CLIENT_REQ,
            flags = if (last) FLAG_AUDIO_LAST else 0,
            serialization = 0,
            compression = 0,
            payload = pcm,
        )
        socket.send(ByteString.of(*frame))
        if (++audioFramesSent == 1) SessionLog.log("volc", "first audio frame sent (${pcm.size}B, last=$last)")
    }

    private fun handleServerMessage(arr: ByteArray) {
        if (arr.size < 8) return
        val headerSize = (arr[0].toInt() and 0x0F) * 4
        val msgType = (arr[1].toInt() ushr 4) and 0x0F
        val flags = arr[1].toInt() and 0x0F
        var offset = headerSize
        when (msgType) {
            MSG_FULL_SERVER_RESP -> {
                if (arr.size < offset + 8) return
                offset += 4 // sequence
                val payloadSize = readUInt32BE(arr, offset)
                offset += 4
                if (arr.size < offset + payloadSize) return
                var payload = arr.copyOfRange(offset, offset + payloadSize)
                if ((arr[2].toInt() and 0x0F) == COMPRESS_GZIP) payload = gunzip(payload)
                val text = parseText(String(payload))
                val isFinal = (flags and FLAG_SERVER_FINAL) == FLAG_SERVER_FINAL
                SessionLog.log("volc", "server resp final=$isFinal running=${running.get()} textLen=${text.length}")
                if (!running.get() && !isFinal) return
                // A definite packet while still recording is VAD segmentation, not
                // the end of the session: surface it as a partial update.
                if (isFinal && !running.get()) {
                    awaitingFinal.set(false)
                    listener.onFinal(text)
                    scope.launch(Dispatchers.IO) {
                        runCatching { ws?.close(1000, "final") }
                        ws = null
                        wsReady.set(false)
                    }
                } else if (text.isNotBlank()) {
                    lastPartial = text
                    listener.onPartial(text)
                }
            }
            MSG_ERROR -> {
                if (arr.size < offset + 8) return
                val code = readUInt32BE(arr, offset)
                val size = readUInt32BE(arr, offset + 4)
                val msg = String(arr.copyOfRange(offset + 8, (offset + 8 + size).coerceAtMost(arr.size)))
                SessionLog.log("volc", "server error $code: $msg")
                fail("ASR error $code: $msg")
            }
        }
    }

    private fun parseText(json: String): String = runCatching {
        val o = JSONObject(json)
        if (o.has("result")) o.getJSONObject("result").optString("text", "") else ""
    }.getOrDefault("")

    private fun buildClientFrame(messageType: Int, flags: Int, serialization: Int, compression: Int, payload: ByteArray): ByteArray {
        val header = byteArrayOf(
            ((((PROTOCOL_VERSION and 0x0F) shl 4) or (HEADER_SIZE_UNITS and 0x0F)).toByte()),
            ((((messageType and 0x0F) shl 4) or (flags and 0x0F)).toByte()),
            ((((serialization and 0x0F) shl 4) or (compression and 0x0F)).toByte()),
            0,
        )
        val size = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(payload.size).array()
        return header + size + payload
    }

    private fun readUInt32BE(a: ByteArray, o: Int): Int =
        ((a[o].toInt() and 0xFF) shl 24) or ((a[o + 1].toInt() and 0xFF) shl 16) or
                ((a[o + 2].toInt() and 0xFF) shl 8) or (a[o + 3].toInt() and 0xFF)

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun gunzip(data: ByteArray): ByteArray =
        runCatching { GZIPInputStream(data.inputStream()).readBytes() }.getOrDefault(data)
}
