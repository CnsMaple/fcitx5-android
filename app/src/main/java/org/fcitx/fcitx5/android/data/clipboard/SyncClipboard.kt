package org.fcitx.fcitx5.android.data.clipboard

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * SyncClipboard WebDAV protocol client (v3.1.1+).
 * Endpoints: {base}/SyncClipboard.json and {base}/file/{dataName}.
 * Protocol per https://github.com/Jeric-X/SyncClipboard (README + docs/Hash.md).
 */
class SyncClipboard(baseUrl: String, private val user: String, private val pass: String) {

    data class Meta(
        val type: String, val hash: String, val text: String,
        val hasData: Boolean, val dataName: String?, val size: Long,
    )

    private val base = baseUrl.trim().trimEnd('/')

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile private var activeCall: okhttp3.Call? = null

    /** Abort the in-flight request (pull or push). */
    fun cancel() { runCatching { activeCall?.cancel() } }

    private fun exec(request: okhttp3.Request): okhttp3.Response {
        val call = http.newCall(request)
        activeCall = call
        return call.execute()
    }

    private fun url(rel: String) =
        "$base/" + rel.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun req(u: String) = Request.Builder()
        .url(u).addHeader("Authorization", Credentials.basic(user, pass, Charsets.UTF_8))

    /** GET SyncClipboard.json. Returns null on 404 (empty clipboard); throws on error. */
    fun pullMeta(): Meta? {
        val request = req(url("SyncClipboard.json")).get().build()
        exec(request).use { resp ->
            when {
                resp.code == 404 -> return null
                !resp.isSuccessful -> error("pullMeta HTTP ${resp.code}")
            }
            val body = resp.body.string().removePrefix("\uFEFF")
            val o = JSONObject(body)
            // SyncClipboard.json has no "hasData" field: an attached file is signalled by a
            // non-null dataName (+size). Derive hasData from dataName so real desktop pushes
            // (Image/File) are recognized instead of falling back to the text/path branch.
            val dataName = if (o.isNull("dataName")) null
                else o.optString("dataName", "").takeIf { it.isNotEmpty() }
            return Meta(
                type = o.optString("type", "Text"),
                hash = o.optString("hash", ""),
                text = o.optString("text", ""),
                hasData = dataName != null,
                dataName = dataName,
                size = o.optLong("size", 0),
            )
        }
    }

    /** GET file/{dataName}. */
    fun pullData(dataName: String): ByteArray {
        val request = req(url("file/$dataName")).get().build()
        exec(request).use { resp ->
            if (!resp.isSuccessful) error("pullData HTTP ${resp.code} (file/$dataName)")
            return resp.body.bytes()
        }
    }

    /**
     * Stream GET file/{dataName} into [out], reporting (done,total) via [onProgress].
     * Returning false from onProgress aborts (cancellation). total == -1 if unknown.
     */
    fun pullDataStream(dataName: String, out: java.io.File, onProgress: (Long, Long) -> Boolean) {
        val request = req(url("file/$dataName")).get().build()
        exec(request).use { resp ->
            if (!resp.isSuccessful) error("pullData HTTP ${resp.code} (file/$dataName)")
            val total = resp.body.contentLength()
            var done = 0L
            val buf = ByteArray(64 * 1024)
            java.io.FileOutputStream(out).use { os ->
                resp.body.byteStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        done += n
                        if (!onProgress(done, total)) { out.delete(); error("cancelled") }
                    }
                }
            }
        }
    }

    /** Upload an item: file first (if any), then the json. */
    fun push(type: String, text: String, dataName: String?, data: ByteArray?, hash: String) {
        val hasData = data != null && dataName != null
        if (hasData) {
            mkcol("file")
            val put = req(url("file/$dataName"))
                .put(data!!.toRequestBody("application/octet-stream".toMediaType()))
                .build()
            exec(put).use { resp ->
                if (!resp.isSuccessful && resp.code != 204) error("PUT file HTTP ${resp.code}")
            }
        }
        val json = JSONObject().apply {
            put("type", type)
            put("hash", hash)
            put("text", text)
            put("hasData", hasData)
            if (hasData) put("dataName", dataName) else put("dataName", JSONObject.NULL)
            put("size", if (type == "Text") text.length else (data?.size?.toLong() ?: 0L))
        }
        val put = req(url("SyncClipboard.json"))
            .put(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        http.newCall(put).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 204) error("PUT json HTTP ${resp.code}")
        }
    }

    private fun mkcol(rel: String) {
        runCatching {
            http.newCall(req(url(rel)).method("MKCOL", null).build())
                .execute().use { /* 201 ok, 405 exists */ }
        }
    }

    companion object {
        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02X".format(it) }

        /**
         * SyncClipboard hash (uppercase hex):
         *  - Text: sha256(utf8 fullText)
         *  - File/Image: sha256(utf8 "dataName|sha256Hex(content)")
         */
        fun computeHash(type: String, text: String, dataName: String?, data: ByteArray?): String =
            when (type) {
                "Text" -> sha256Hex(
                    (if (data != null) String(data, Charsets.UTF_8) else text).toByteArray(Charsets.UTF_8)
                )
                else -> {
                    require(data != null && dataName != null) { "file/image needs data+dataName" }
                    sha256Hex("$dataName|${sha256Hex(data)}".toByteArray(Charsets.UTF_8))
                }
            }
    }
}
