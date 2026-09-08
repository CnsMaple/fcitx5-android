package org.fcitx.fcitx5.android.plugin.cloudvoice

import android.content.Context
import android.content.SharedPreferences

/** Plugin-side configuration, edited via SettingsActivity. */
class Settings(context: Context) {

    /**
     * Doubao streaming ASR model catalog. The "model" IS the resource id
     * (model_name is fixed to "bigmodel"); official doc lists exactly these:
     * https://www.volcengine.com/docs/6561/1354869
     */
    data class VolcModel(val resourceId: String, val zhName: String, val enName: String)

    companion object {
        const val VENDOR_VOLC = "volc"
        const val VENDOR_DASHSCOPE = "dashscope"

        val VOLC_STREAM_MODELS = listOf(
            VolcModel("volc.seedasr.sauc.duration", "流式识别 2.0（小时版）", "Streaming ASR 2.0 (duration)"),
            VolcModel("volc.seedasr.sauc.concurrent", "流式识别 2.0（并发版）", "Streaming ASR 2.0 (concurrent)"),
            VolcModel("volc.bigasr.sauc.duration", "流式识别 1.0（小时版）", "Streaming ASR 1.0 (duration)"),
            VolcModel("volc.bigasr.sauc.concurrent", "流式识别 1.0（并发版）", "Streaming ASR 1.0 (concurrent)"),
        )

        const val DEFAULT_VOLC_RESOURCE_ID = "volc.seedasr.sauc.duration"
        const val DEFAULT_DASH_MODEL = "fun-asr-realtime"
    }

    private val sp: SharedPreferences =
        context.getSharedPreferences("cloudvoice", Context.MODE_PRIVATE)

    var vendor: String
        get() = sp.getString("vendor", VENDOR_VOLC)!!
        set(v) = sp.edit().putString("vendor", v).apply()

    // ---- Volcengine (Doubao), new auth only (X-Api-Key) ----
    var volcApiKey: String
        get() = sp.getString("volc_api_key", "")!!
        set(v) = sp.edit().putString("volc_api_key", v).apply()

    var volcResourceId: String
        get() = sp.getString("volc_resource_id", DEFAULT_VOLC_RESOURCE_ID)!!
        set(v) = sp.edit().putString("volc_resource_id", v).apply()

    var volcLanguage: String
        get() = sp.getString("volc_language", "")!!
        set(v) = sp.edit().putString("volc_language", v).apply()

    // ---- DashScope (Aliyun) ----
    var dashApiKey: String
        get() = sp.getString("dash_api_key", "")!!
        set(v) = sp.edit().putString("dash_api_key", v).apply()

    var dashModel: String
        get() = sp.getString("dash_model", DEFAULT_DASH_MODEL)!!
        set(v) = sp.edit().putString("dash_model", v).apply()

    var dashRegion: String
        get() = sp.getString("dash_region", "cn")!!
        set(v) = sp.edit().putString("dash_region", v).apply()

    fun isConfigured(): Boolean = VendorRegistry.isConfigured(this)

    /** generic accessors for descriptor-driven vendor fields */
    fun get(key: String, def: String = ""): String = sp.getString(key, def)!!
    fun set(key: String, value: String) { sp.edit().putString(key, value).apply() }
    fun allStrings(): Map<String, String> =
        sp.all.filterValues { it is String }.mapValues { it.value as String }

    /** choice field "开"/"关" -> Boolean (blank falls back to [def]) */
    fun flag(key: String, def: Boolean): Boolean = when (get(key).trim()) {
        "开" -> true
        "关" -> false
        else -> def
    }

    /** comma-separated text field -> trimmed non-empty list (accepts ，,) */
    fun csv(key: String): List<String> =
        get(key).split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
}
