package org.fcitx.fcitx5.android.plugin.cloudvoice

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Online probe for Doubao (Volcengine) streaming ASR resources: attempts the
 * WebSocket handshake for a resource id and reports whether it is granted.
 * 101 Switching Protocols -> Granted; 403 -> Not granted.
 */
object VolcResourceProbe {

    enum class Status { Granted, NotGranted, Failed }

    private const val WS_URL = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun probe(apiKey: String, resourceId: String, onResult: (Status, String?) -> Unit) {
        val request = Request.Builder()
            .url(WS_URL)
            .header("X-Api-Key", apiKey)
            .header("X-Api-Resource-Id", resourceId)
            .header("X-Api-Connect-Id", UUID.randomUUID().toString())
            .build()
        var settled = false
        http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (settled) return
                settled = true
                runCatching { webSocket.cancel() }
                onResult(Status.Granted, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (settled) return
                settled = true
                onResult(
                    when (response?.code) {
                        403 -> Status.NotGranted
                        null -> Status.Failed
                        else -> Status.Failed
                    },
                    response?.code?.toString() ?: t.message,
                )
            }
        })
    }
}
