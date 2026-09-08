package org.fcitx.fcitx5.android.plugin.cloudvoice

import android.content.Context
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.fcitx.fcitx5.android.common.ipc.IVoiceInputCallback
import org.fcitx.fcitx5.android.common.ipc.IVoiceInputProvider
import org.fcitx.fcitx5.android.common.ipc.VoiceInputIpc
import org.fcitx.fcitx5.android.plugin.cloudvoice.asr.DashscopeEngine
import org.fcitx.fcitx5.android.plugin.cloudvoice.asr.FileAsrEngine
import org.fcitx.fcitx5.android.plugin.cloudvoice.asr.SonioxStreamEngine
import org.fcitx.fcitx5.android.plugin.cloudvoice.asr.StreamingAsrEngine
import org.fcitx.fcitx5.android.plugin.cloudvoice.asr.VolcEngine
import org.fcitx.fcitx5.android.plugin.cloudvoice.asr.VendorApis
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * In-app cloud ASR provider (merged from the cloudvoice plugin). Implements the same
 * [IVoiceInputProvider] contract the host driver expects, but runs in-process.
 * Audio is captured by the host (IME) and pushed via feedAudio.
 */
class LocalVoiceProvider(private val context: Context) : IVoiceInputProvider.Stub() {

    private val conf = Settings(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var engine: StreamingAsrEngine? = null
    private var cb: IVoiceInputCallback? = null

    private fun chinese() = context.resources.configuration.locales[0].language == "zh"

    override fun isAvailable(): Boolean = conf.isConfigured()

    override fun getPreferredConfig(): Bundle = Bundle().apply {
        putString("vendor", conf.vendor)
        putString("dash_model", conf.dashModel)
    }

    override fun configure(config: Bundle?) { /* host config goes through setSettings */ }

    override fun startSession(callback: IVoiceInputCallback?) {
        val cb = callback ?: return
        this.cb = cb
        SessionLog.log("svc", "startSession vendor=${conf.vendor} configured=${conf.isConfigured()}")
        if (!isAvailable()) {
            cb.onError(VoiceInputIpc.ERR_NOT_CONFIGURED, "Vendor not configured (missing key)")
            return
        }
        engine?.cancel()
        val listener = object : StreamingAsrEngine.Listener {
            override fun onFinal(text: String) { cb.onSegmentFinal(text); cb.onSessionEnded() }
            override fun onError(message: String) { cb.onError(VoiceInputIpc.ERR_GENERIC, message); cb.onSessionEnded() }
            override fun onPartial(text: String) = cb.onPartialResult(text)
            override fun onStopped() {}
            override fun onAmplitude(amplitude: Float) {}
        }
        engine = createEngine(conf.vendor, listener)
        cb.onReady()
        engine?.start()
    }

    private fun createEngine(vendorId: String, listener: StreamingAsrEngine.Listener): StreamingAsrEngine =
        when (VendorRegistry.byId(vendorId)?.kind) {
            "dashscope" -> DashscopeEngine(context, scope, conf, listener)
            "soniox" -> SonioxStreamEngine(scope, conf, listener)
            "file" -> FileAsrEngine(scope, conf, listener, VendorApis.recognizerFor(vendorId))
            else -> VolcEngine(context, scope, conf, listener)
        }

    override fun feedAudio(pcm: ByteArray?, offset: Int, len: Int, ptsMs: Long) {
        val src = pcm ?: return
        engine?.feedPcm(src.copyOfRange(offset, (offset + len).coerceAtMost(src.size)))
    }

    override fun endStream() { engine?.stop() }

    override fun cancelSession() { engine?.cancel(); engine = null }

    override fun stopSession() {
        val e = engine
        if (e == null) { cb?.onSessionEnded(); return }
        e.stop()
    }

    override fun getSettings(): Bundle = Bundle().apply {
        putString("vendor", conf.vendor)
        conf.allStrings().forEach { (k, v) -> putString(k, v) }
    }

    override fun setSettings(s: Bundle?) {
        s ?: return
        for (key in s.keySet()) {
            val value = s.getString(key) ?: continue
            if (key == "vendor") conf.vendor = value else conf.set(key, value)
        }
    }

    override fun getVendorList(): String = VendorRegistry.vendorListJson(chinese())

    override fun getVendorConfig(vendorId: String?): String =
        VendorRegistry.byId(vendorId.orEmpty())
            ?.let { VendorRegistry.configJson(it, chinese()) } ?: "{}"

    override fun fetchModelsAt(baseUrl: String?, apiKey: String?): Bundle = try {
        val models = ModelsFetcher.fetchOpenAiModels(baseUrl.orEmpty(), apiKey.orEmpty())
        Bundle().apply { putStringArray("models", models.toTypedArray()) }
    } catch (e: Exception) {
        Bundle().apply { putString("error", e.message ?: "fetch failed") }
    }

    override fun getModelCatalog(): Bundle {
        val chinese = chinese()
        val models = Settings.VOLC_STREAM_MODELS
        return Bundle().apply {
            putStringArray("ids", models.map { it.resourceId }.toTypedArray())
            putStringArray("labels", models.map { m -> if (chinese) m.zhName else m.enName }.toTypedArray())
        }
    }

    override fun probeResources(): Bundle {
        val out = Bundle()
        for (m in Settings.VOLC_STREAM_MODELS) {
            val latch = CountDownLatch(1)
            var result = "failed"
            VolcResourceProbe.probe(conf.volcApiKey, m.resourceId) { status, _ ->
                result = when (status) {
                    VolcResourceProbe.Status.Granted -> "granted"
                    VolcResourceProbe.Status.NotGranted -> "not_granted"
                    VolcResourceProbe.Status.Failed -> "failed"
                }
                latch.countDown()
            }
            latch.await(15, TimeUnit.SECONDS)
            out.putString(m.resourceId, result)
        }
        return out
    }

    override fun fetchModels(): Bundle = try {
        val base = if (conf.dashRegion == "intl")
            "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
        else
            "https://dashscope.aliyuncs.com/compatible-mode/v1"
        val models = ModelsFetcher.fetchOpenAiModels(base, conf.dashApiKey)
        Bundle().apply { putStringArray("models", models.toTypedArray()) }
    } catch (e: Exception) {
        Bundle().apply { putString("error", e.message ?: "fetch failed") }
    }

    override fun hasMicPermission(): Boolean = true
    override fun micPermissionActivity(): String? = null
    override fun getDiagnostics(): String = SessionLog.dump()

    /** Stop any running engine (call from the host on teardown). */
    fun shutdown() {
        runCatching { engine?.cancel() }
        engine = null
    }
}
