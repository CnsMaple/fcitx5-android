package org.fcitx.fcitx5.android.plugin.cloudvoice

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fetches the model list from an OpenAI-compatible `GET {base}/models` endpoint.
 * Used for Aliyun DashScope (compatible mode) and later for SiliconFlow etc.
 */
object ModelsFetcher {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Returns model ids, or throws with a readable message. */
    fun fetchOpenAiModels(baseUrl: String, apiKey: String): List<String> {
        val url = baseUrl.trimEnd('/') + "/models"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        http.newCall(request).execute().use { resp ->
            val body = runCatching { resp.body.string() }.getOrDefault("")
            if (!resp.isSuccessful) {
                error("HTTP ${resp.code}: ${body.take(200)}")
            }
            val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
            return (0 until data.length()).mapNotNull {
                data.optJSONObject(it)?.optString("id")
            }
        }
    }
}
