package org.fcitx.fcitx5.android.plugin.cloudvoice.asr

import android.content.Context
import android.util.Log
import com.alibaba.dashscope.audio.asr.recognition.Recognition
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam
import com.alibaba.dashscope.audio.asr.recognition.RecognitionResult
import com.alibaba.dashscope.common.ResultCallback
import com.alibaba.dashscope.utils.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.plugin.cloudvoice.Settings
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Aliyun DashScope streaming ASR via the official SDK (Recognition / fun-asr-realtime).
 * Approach ported from BiBi-Keyboard (Apache-2.0, Copyright BryceWG).
 */
class DashscopeEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: Settings,
    private val listener: StreamingAsrEngine.Listener,
) : StreamingAsrEngine {

    companion object {
        private const val TAG = "DashscopeEngine"
        private const val WS_INFER_CN = "wss://dashscope.aliyuncs.com/api-ws/v1/inference"
        private const val WS_INFER_INTL = "wss://dashscope-intl.aliyuncs.com/api-ws/v1/inference"
    }

    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private var recognizer: Recognition? = null
    private var committed = false

    private var confirmedText = ""
    private var stashText = ""

    private val prebuffer = ArrayDeque<ByteArray>()
    private val prebufferLock = Any()
    @Volatile private var ready = false

    override val isRunning: Boolean get() = running.get()

    override fun start() {
        if (running.get()) return
        if (settings.dashApiKey.isBlank()) {
            listener.onError("DashScope API key not configured")
            return
        }
        running.set(true)
        cancelled.set(false)
        committed = false
        confirmedText = ""
        stashText = ""
        ready = false
        synchronized(prebufferLock) { prebuffer.clear() }

        val wsUrl = if (settings.dashRegion.equals("intl", true)) WS_INFER_INTL else WS_INFER_CN
        try {
            Constants.baseWebsocketApiUrl = wsUrl
        } catch (t: Throwable) {
            Log.w(TAG, "set baseWebsocketApiUrl failed", t)
        }
        val param = RecognitionParam.builder()
            .model(settings.dashModel)
            .apiKey(settings.dashApiKey)
            .format("pcm")
            .sampleRate(16000)
            .apply {
                if (settings.flag("dash_disfluency", false)) disfluencyRemovalEnabled(true)
                settings.get("dash_vocabulary").takeIf { it.isNotBlank() }?.let { vocabularyId(it.trim()) }
                parameter("punctuation_prediction_enabled", settings.flag("dash_punc", true))
                parameter("inverse_text_normalization_enabled", settings.flag("dash_itn", true))
                settings.csv("dash_langs").takeIf { it.isNotEmpty() }
                    ?.let { parameter("language_hints", it) }
                settings.get("dash_silence").trim().toIntOrNull()?.let {
                    parameter("max_sentence_silence", it.coerceIn(200, 6000))
                }
            }
            .build()
        val rec = Recognition()
        recognizer = rec
        rec.call(param, object : ResultCallback<RecognitionResult>() {
            override fun onEvent(result: RecognitionResult) {
                val text = result.getSentence()?.text.orEmpty()
                if (text.isBlank()) return
                if (result.isSentenceEnd) {
                    confirmedText = join(confirmedText, text)
                    stashText = ""
                } else {
                    stashText = text
                }
                if (!running.get()) return
                val preview = join(confirmedText, stashText)
                if (preview.isNotEmpty()) listener.onPartial(preview)
            }

            override fun onComplete() {
                deliverFinal(join(confirmedText, stashText))
            }

            override fun onError(e: Exception) {
                Log.e(TAG, "recognition error", e)
                fail(e.message ?: "Recognition error")
            }
        })
        ready = true
        flushPrebuffer()
        // audio arrives externally via feedPcm (captured by the host IME process)
    }

    /** External PCM push from the host app (100 ms chunks). */
    override fun feedPcm(pcm: ByteArray) {
        if (!running.get()) return
        if (!ready) {
            synchronized(prebufferLock) {
                prebuffer.addLast(pcm)
                while (prebuffer.size > 20) prebuffer.removeFirst()
            }
        } else {
            flushPrebuffer()
            runCatching { recognizer?.sendAudioFrame(ByteBuffer.wrap(pcm)) }
        }
    }

    override fun stop() {
        if (!running.get()) return
        running.set(false)
        listener.onStopped()
        scope.launch(Dispatchers.IO) {
            flushPrebuffer()
            try {
                recognizer?.stop() // triggers onComplete with the final text
            } catch (t: Throwable) {
                Log.w(TAG, "recognizer.stop() failed", t)
                deliverFinal(join(confirmedText, stashText))
            }
            // safety net: if onComplete never arrives, deliver what we have
            delay(3_000)
            if (!committed) deliverFinal(join(confirmedText, stashText))
        }
    }

    override fun cancel() {
        running.set(false)
        cancelled.set(true)
        runCatching { recognizer?.stop() }
        recognizer = null
        listener.onStopped()
    }

    private fun flushPrebuffer() {
        var flushed: Array<ByteArray>
        synchronized(prebufferLock) {
            flushed = prebuffer.toTypedArray()
            prebuffer.clear()
        }
        flushed.forEach { b ->
            runCatching { recognizer?.sendAudioFrame(ByteBuffer.wrap(b)) }
        }
    }

    private fun deliverFinal(text: String) {
        if (committed) return
        committed = true
        recognizer = null
        if (!cancelled.get()) listener.onFinal(text.trim())
    }

    private fun fail(msg: String) {
        running.set(false)
        committed = true
        recognizer = null
        if (!cancelled.get()) listener.onError(msg)
    }

    private fun join(a: String, b: String): String {
        val s = b.trim()
        if (s.isEmpty()) return a.trim()
        val cur = a.trim()
        if (cur.isEmpty()) return s
        val needsSpace =
            cur.last().isLetterOrDigit() && cur.last().code < 128 &&
                    s.first().isLetterOrDigit() && s.first().code < 128
        return if (needsSpace) "$cur $s" else cur + s
    }
}
