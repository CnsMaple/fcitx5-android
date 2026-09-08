package org.fcitx.fcitx5.android.plugin.cloudvoice

import org.json.JSONArray
import org.json.JSONObject

/**
 * Descriptor-driven registry of cloud ASR vendors. The host app renders the
 * settings form generically from [vendorListJson] / [configJson]; engines are
 * chosen by [Vendor.kind].
 *
 * Protocol details ported from BiBi-Keyboard's engine suite (Apache-2.0).
 */
object VendorRegistry {

    data class Field(
        val key: String,
        val zh: String,
        val en: String,
        val type: String,               // text | password | choice
        val default: String = "",
        val required: Boolean = false,
        val choices: List<String> = emptyList(),
    )

    data class Vendor(
        val id: String,
        val zh: String,
        val en: String,
        val kind: String,               // volc | dashscope | soniox | file
        val fields: List<Field>,
        val modelsBaseField: String? = null,
        val modelsKeyField: String? = null,
    )

    val all = listOf(
        Vendor("volc", "豆包（火山引擎）", "Doubao (Volcengine)", "volc", listOf(
            Field("volc_api_key", "API Key（X-Api-Key）", "API Key (X-Api-Key)", "password", required = true),
            Field("volc_language", "语言（留空=自动）", "Language (blank = auto)", "text"),
            Field("volc_punc", "标点", "Punctuation", "choice", "开", choices = listOf("开", "关")),
            Field("volc_itn", "数字规整(ITN)", "ITN (numbers)", "choice", "开", choices = listOf("开", "关")),
            Field("volc_ddc", "语义顺滑(去语气词)", "Disfluency removal", "choice", "关", choices = listOf("开", "关")),
            Field("volc_hotwords", "热词（逗号分隔，≤100）", "Hotwords (comma separated, ≤100)", "text"),
        )),
        Vendor("dashscope", "阿里云（DashScope）", "Aliyun DashScope", "dashscope", listOf(
            Field("dash_api_key", "API Key", "API Key", "password", required = true),
            Field("dash_model", "模型", "Model", "text", "fun-asr-realtime"),
            Field("dash_region", "区域", "Region", "choice", "cn", choices = listOf("cn", "intl")),
            Field("dash_punc", "标点", "Punctuation", "choice", "开", choices = listOf("开", "关")),
            Field("dash_itn", "数字规整(ITN)", "ITN (numbers)", "choice", "开", choices = listOf("开", "关")),
            Field("dash_disfluency", "去语气词", "Disfluency removal", "choice", "关", choices = listOf("开", "关")),
            Field("dash_langs", "语言提示（逗号，如 zh,en,yue）", "Language hints (comma)", "text"),
            Field("dash_vocabulary", "热词表ID(vocabulary_id)", "Vocabulary id", "text"),
            Field("dash_silence", "断句静音阈值ms(200-6000)", "Max sentence silence ms (200-6000)", "text"),
        )),
        Vendor("siliconflow", "硅基流动（免费）", "SiliconFlow (free)", "file", listOf(
            Field("sf_api_key", "API Key", "API Key", "password", required = true),
            Field("sf_endpoint", "Endpoint", "Endpoint", "text", "https://api.siliconflow.cn/v1"),
            Field("sf_model", "模型", "Model", "text", "FunAudioLLM/SenseVoiceSmall"),
        ), modelsBaseField = "sf_endpoint", modelsKeyField = "sf_api_key"),
        Vendor("openai", "OpenAI / 兼容端点", "OpenAI / compatible", "file", listOf(
            Field("oai_endpoint", "Endpoint", "Endpoint", "text", "https://api.openai.com/v1"),
            Field("oai_api_key", "API Key", "API Key", "password", required = true),
            Field("oai_model", "模型", "Model", "text", "gpt-4o-mini-transcribe"),
            Field("oai_language", "语言（留空=自动，ISO如 zh/en）", "Language (blank=auto, ISO)", "text"),
            Field("oai_prompt", "上下文提示（≤224字）", "Prompt / context (≤224 chars)", "text"),
        ), modelsBaseField = "oai_endpoint", modelsKeyField = "oai_api_key"),
        Vendor("zhipu", "智谱 GLM-ASR", "Zhipu GLM-ASR", "file", listOf(
            Field("zp_api_key", "API Key", "API Key", "password", required = true),
        )),
        Vendor("cohere", "Cohere Transcribe", "Cohere Transcribe", "file", listOf(
            Field("ch_api_key", "API Key", "API Key", "password", required = true),
            Field("ch_model", "模型", "Model", "text", "cohere-transcribe-03-2026"),
            Field("ch_language", "语言", "Language", "text", "zh"),
        )),
        Vendor("openrouter", "OpenRouter", "OpenRouter", "file", listOf(
            Field("or_endpoint", "Endpoint", "Endpoint", "text", "https://openrouter.ai/api/v1"),
            Field("or_api_key", "API Key", "API Key", "password", required = true),
            Field("or_model", "模型", "Model", "text", "qwen/qwen3-asr-flash-2026-02-10"),
            Field("or_language", "语言（留空=自动，ISO）", "Language (blank=auto, ISO)", "text"),
        ), modelsBaseField = "or_endpoint", modelsKeyField = "or_api_key"),
        Vendor("mimo", "小米 MiMo-ASR", "Xiaomi MiMo-ASR", "file", listOf(
            Field("mm_endpoint", "Endpoint", "Endpoint", "text", "https://api.xiaomimimo.com/v1"),
            Field("mm_api_key", "API Key", "API Key", "password", required = true),
            Field("mm_model", "模型", "Model", "text", "mimo-v2.5-asr"),
            Field("mm_language", "语言（auto=自动）", "Language (auto)", "text", "auto"),
        )),
        Vendor("stepaudio", "阶跃星辰 StepAudio", "StepFun StepAudio", "file", listOf(
            Field("sa_endpoint", "Endpoint", "Endpoint", "text", "https://api.stepfun.com/v1"),
            Field("sa_api_key", "API Key", "API Key", "password", required = true),
            Field("sa_model", "模型", "Model", "text", "stepaudio-2.5-asr"),
            Field("sa_language", "语言", "Language", "text", "zh"),
            Field("sa_itn", "数字规整(ITN)", "ITN (numbers)", "choice", "开", choices = listOf("开", "关")),
            Field("sa_hotwords", "热词（逗号分隔）", "Hotwords (comma separated)", "text"),
        )),
        Vendor("gemini", "Google Gemini", "Google Gemini", "file", listOf(
            Field("gm_api_key", "API Key", "API Key", "password", required = true),
            Field("gm_endpoint", "Endpoint", "Endpoint", "text", "https://generativelanguage.googleapis.com/v1beta"),
            Field("gm_model", "模型", "Model", "text", "gemini-2.5-flash"),
        )),
        Vendor("elevenlabs", "ElevenLabs Scribe", "ElevenLabs Scribe", "file", listOf(
            Field("el_api_key", "API Key", "API Key", "password", required = true),
            Field("el_model", "模型", "Model", "choice", "scribe_v2", choices = listOf("scribe_v1", "scribe_v2")),
            Field("el_language", "语言（留空=自动）", "Language (blank = auto)", "text"),
            Field("el_hotwords", "关键词(keyterms，逗号，仅v2)", "Keyterms (comma, v2 only)", "text"),
            Field("el_disfluency", "去语气词/口误(仅v2)", "No verbatim (v2 only)", "choice", "关", choices = listOf("开", "关")),
        )),
        Vendor("soniox", "Soniox（流式）", "Soniox (streaming)", "soniox", listOf(
            Field("sx_api_key", "API Key", "API Key", "password", required = true),
            Field("sx_languages", "语言提示（逗号分隔）", "Language hints (comma separated)", "text", "zh-CN,en-US"),
            Field("sx_hotwords", "关键词(context.terms，逗号)", "Keyterms (comma separated)", "text"),
            Field("sx_endpoint_ms", "判停延迟ms(500-3000)", "Endpoint delay ms (500-3000)", "text"),
        )),
    )

    fun byId(id: String): Vendor? = all.firstOrNull { it.id == id }

    fun vendorListJson(zh: Boolean): String = JSONArray().apply {
        all.forEach { put(JSONObject().put("id", it.id).put("name", if (zh) it.zh else it.en)) }
    }.toString()

    fun configJson(v: Vendor, zh: Boolean): String = JSONObject().apply {
        put("kind", v.kind)
        put("fields", JSONArray().apply {
            v.fields.forEach { f ->
                put(JSONObject().apply {
                    put("key", f.key)
                    put("label", if (zh) f.zh else f.en)
                    put("type", f.type)
                    put("default", f.default)
                    put("required", f.required)
                    put("choices", JSONArray(f.choices))
                })
            }
        })
        v.modelsBaseField?.let { put("modelsBaseField", it); put("modelsKeyField", v.modelsKeyField) }
    }.toString()

    fun isConfigured(s: Settings): Boolean {
        val v = byId(s.vendor) ?: return false
        return v.fields.none { it.required && s.get(it.key).isBlank() }
    }
}
