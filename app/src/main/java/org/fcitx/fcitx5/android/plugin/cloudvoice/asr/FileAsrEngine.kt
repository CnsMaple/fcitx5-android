package org.fcitx.fcitx5.android.plugin.cloudvoice.asr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.fcitx.fcitx5.android.plugin.cloudvoice.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * File-mode engine for push-to-talk: PCM accumulates while the key is held;
 * on release the audio is uploaded in one request and the final text delivered.
 */
class FileAsrEngine(
    private val scope: CoroutineScope,
    private val settings: Settings,
    private val listener: StreamingAsrEngine.Listener,
    private val recognize: suspend (Settings, ByteArray, ByteArray) -> String,
) : StreamingAsrEngine {

    private val buf = ByteArrayOutputStream()
    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    override val isRunning: Boolean get() = running.get()

    override fun start() {
        running.set(true)
        cancelled.set(false)
        synchronized(buf) { buf.reset() }
    }

    override fun feedPcm(pcm: ByteArray) {
        if (running.get()) synchronized(buf) { buf.write(pcm) }
    }

    override fun stop() {
        if (!running.getAndSet(false)) return
        listener.onStopped()
        val pcm = synchronized(buf) { buf.toByteArray() }
        synchronized(buf) { buf.reset() }
        if (pcm.size < 3200) {   // < 100 ms: nothing meaningful to send
            listener.onFinal("")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val text = recognize(settings, pcm, wav(pcm))
                if (!cancelled.get()) listener.onFinal(text)
            } catch (t: Throwable) {
                if (!cancelled.get()) listener.onError(t.message ?: "recognize failed")
            }
        }
    }

    override fun cancel() {
        running.set(false)
        cancelled.set(true)
        listener.onStopped()
    }

    companion object {
        /** Wrap raw s16le mono PCM in a 44-byte RIFF/WAVE header. */
        fun wav(pcm: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(44 + pcm.size)
            fun le32(v: Int) {
                out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
                out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
            }
            fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
            out.write("RIFF".toByteArray()); le32(36 + pcm.size); out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray()); le32(16); le16(1); le16(1)
            le32(16000); le32(32000); le16(2); le16(16)
            out.write("data".toByteArray()); le32(pcm.size)
            out.write(pcm)
            return out.toByteArray()
        }
    }
}

/** Per-vendor HTTP recognizers; (settings, pcm, wav) -> text. */
object VendorApis {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun String.trimEnd2() = trimEnd('/', ' ')

    private suspend fun exec(request: Request): String =
        withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { resp ->
                val body = runCatching { resp.body.string() }.getOrDefault("")
                if (!resp.isSuccessful) {
                    error("HTTP ${resp.code}: ${body.take(200)}")
                }
                body
            }
        }

    private fun wavPart(wav: ByteArray) =
        MultipartBody.Part.createFormData("file", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))

    private fun bearer(key: String) = okhttp3.Headers.Builder().add("Authorization", "Bearer $key").build()

    fun recognizerFor(kind: String): (suspend (Settings, ByteArray, ByteArray) -> String) = when (kind) {
        "siliconflow" -> { s, _, wav ->
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", s.get("sf_model", "FunAudioLLM/SenseVoiceSmall"))
                .addPart(wavPart(wav)).build()
            val req = Request.Builder().url(s.get("sf_endpoint").trimEnd2() + "/audio/transcriptions")
                .headers(bearer(s.get("sf_api_key"))).post(body).build()
            JSONObject(exec(req)).optString("text")
        }
        "openai" -> { s, _, wav ->
            val mb = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", s.get("oai_model", "gpt-4o-mini-transcribe"))
                .addFormDataPart("response_format", "json")
            s.get("oai_language").trim().takeIf { it.isNotEmpty() }
                ?.let { mb.addFormDataPart("language", it) }
            s.get("oai_prompt").trim().takeIf { it.isNotEmpty() }
                ?.let { mb.addFormDataPart("prompt", it) }
            mb.addPart(wavPart(wav))
            val req = Request.Builder().url(s.get("oai_endpoint").trimEnd2() + "/audio/transcriptions")
                .headers(bearer(s.get("oai_api_key"))).post(mb.build()).build()
            JSONObject(exec(req)).optString("text")
        }
        "zhipu" -> { s, _, wav ->
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", "glm-asr-2512")
                .addFormDataPart("stream", "false")
                .addPart(wavPart(wav)).build()
            val req = Request.Builder()
                .url("https://open.bigmodel.cn/api/paas/v4/audio/transcriptions")
                .headers(bearer(s.get("zp_api_key"))).post(body).build()
            JSONObject(exec(req)).optString("text")
        }
        "cohere" -> { s, _, wav ->
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", s.get("ch_model", "cohere-transcribe-03-2026"))
                .addFormDataPart("language", s.get("ch_language", "zh"))
                .addPart(wavPart(wav)).build()
            val req = Request.Builder().url("https://api.cohere.com/v2/audio/transcriptions")
                .headers(bearer(s.get("ch_api_key"))).post(body).build()
            JSONObject(exec(req)).optString("text")
        }
        "openrouter" -> { s, _, wav ->
            val payload = JSONObject()
                .put("model", s.get("or_model", "qwen/qwen3-asr-flash-2026-02-10"))
                .put("input_audio", JSONObject()
                    .put("data", b64(wav)).put("format", "wav"))
            s.get("or_language").trim().takeIf { it.isNotEmpty() }?.let { payload.put("language", it) }
            val req = Request.Builder().url(s.get("or_endpoint").trimEnd2() + "/audio/transcriptions")
                .headers(bearer(s.get("or_api_key")))
                .post(payload.toString().toRequestBody(JSON)).build()
            val o = JSONObject(exec(req))
            o.optString("text").ifBlank { o.optString("output_text") }
        }
        "mimo" -> { s, _, wav ->
            val payload = JSONObject()
                .put("model", s.get("mm_model", "mimo-v2.5-asr"))
                .put("messages", JSONArray().put(JSONObject()
                    .put("role", "user")
                    .put("content", JSONArray().put(JSONObject()
                        .put("type", "input_audio")
                        .put("input_audio", JSONObject()
                            .put("data", "data:audio/wav;base64," + b64(wav)))))))
                .put("asr_options", JSONObject().put("language", s.get("mm_language", "auto")))
            val req = Request.Builder().url(s.get("mm_endpoint").trimEnd2() + "/chat/completions")
                .addHeader("api-key", s.get("mm_api_key"))
                .post(payload.toString().toRequestBody(JSON)).build()
            JSONObject(exec(req)).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content")
        }
        "stepaudio" -> { s, pcm, _ ->
            val transcription = JSONObject()
                .put("model", s.get("sa_model", "stepaudio-2.5-asr"))
                .put("language", s.get("sa_language", "zh"))
                .put("enable_itn", s.flag("sa_itn", true))
            s.csv("sa_hotwords").takeIf { it.isNotEmpty() }?.let { transcription.put("hotwords", JSONArray(it)) }
            val payload = JSONObject().put("audio", JSONObject()
                .put("data", b64(pcm))
                .put("input", JSONObject()
                    .put("transcription", transcription)
                    .put("format", JSONObject()
                        .put("type", "pcm").put("codec", "pcm_s16le")
                        .put("rate", 16000).put("bits", 16).put("channel", 1))))
            val req = Request.Builder().url(s.get("sa_endpoint").trimEnd2() + "/audio/asr/sse")
                .headers(bearer(s.get("sa_api_key")))
                .addHeader("Accept", "text/event-stream")
                .post(payload.toString().toRequestBody(JSON)).build()
            aggregateSse(exec(req))
        }
        "gemini" -> { s, _, wav ->
            val payload = JSONObject()
                .put("contents", JSONArray().put(JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(JSONObject()
                        .put("inline_data", JSONObject()
                            .put("mime_type", "audio/wav")
                            .put("data", b64(wav)))))))
                .put("generationConfig", JSONObject().put("temperature", 0))
            val url = s.get("gm_endpoint").trimEnd2() +
                    "/models/" + s.get("gm_model", "gemini-2.5-flash") +
                    ":generateContent?key=" + java.net.URLEncoder.encode(s.get("gm_api_key"), "UTF-8")
            val req = Request.Builder().url(url).post(payload.toString().toRequestBody(JSON)).build()
            val parts = JSONObject(exec(req)).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            StringBuilder().apply {
                for (i in 0 until parts.length()) {
                    parts.getJSONObject(i).optString("text").let { if (it.isNotBlank()) append(it) }
                }
            }.toString().trim()
        }
        "elevenlabs" -> { s, _, wav ->
            val model = s.get("el_model", "scribe_v2")
            val mb = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model_id", model)
                .addFormDataPart("tag_audio_events", "false")
                .addFormDataPart("num_speakers", "1")
            s.get("el_language").takeIf { it.isNotBlank() }
                ?.let { mb.addFormDataPart("language_code", it) }
            if (model == "scribe_v2") {
                s.csv("el_hotwords").forEach { mb.addFormDataPart("keyterms", it) }
                if (s.flag("el_disfluency", false)) mb.addFormDataPart("no_verbatim", "true")
            }
            mb.addPart(wavPart(wav))
            val req = Request.Builder().url("https://api.elevenlabs.io/v1/speech-to-text")
                .addHeader("xi-api-key", s.get("el_api_key")).post(mb.build()).build()
            val o = JSONObject(exec(req))
            o.optString("text").ifBlank {
                o.optJSONArray("transcripts")?.let { t ->
                    (0 until t.length()).joinToString("\n") { t.getJSONObject(it).optString("text") }
                } ?: ""
            }
        }
        else -> { _, _, _ -> "" }
    }

    /** StepAudio SSE aggregation: transcript.text.delta chunks + done fallback. */
    private fun aggregateSse(sse: String): String {
        val sb = StringBuilder()
        var lastDone = ""
        for (line in sse.lineSequence()) {
            if (!line.startsWith("data:")) continue
            val data = line.substring(5).trim()
            if (data.isEmpty() || data == "[DONE]") continue
            val o = runCatching { JSONObject(data) }.getOrNull() ?: continue
            when {
                o.has("delta") -> sb.append(
                    if (o.opt("delta") is String) o.getString("delta")
                    else o.getJSONObject("delta").optString("text")
                )
                o.has("text") && o.optString("text").isNotEmpty() -> lastDone = o.getString("text")
            }
        }
        return sb.toString().ifBlank { lastDone }
    }
}
