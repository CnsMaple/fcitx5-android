package org.fcitx.fcitx5.android.ui.main.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.addTextChangedListener
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.common.ipc.IVoiceInputProvider
import org.fcitx.fcitx5.android.common.ipc.VoiceInputIpc
import org.fcitx.fcitx5.android.plugin.cloudvoice.LocalVoiceProvider
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native "cloud voice" settings page rendered by the main app. The vendor list
 * and every form field come from the plugin's descriptor AIDL, so adding a
 * vendor never requires touching this file.
 */
class CloudVoiceSettingsFragment : Fragment() {

    private var provider: IVoiceInputProvider? = null

    private lateinit var form: LinearLayout
    private lateinit var vendorSpinner: Spinner
    private lateinit var fieldsBox: LinearLayout
    private lateinit var micStatus: TextView

    /** field key -> widget, for collecting values */
    private val fieldViews = LinkedHashMap<String, View>()

    // volc special case widgets
    private var volcModelSpinner: Spinner? = null
    private var volcModelIds: List<String> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        form = LinearLayout(requireContext())
        return ScrollView(requireContext()).apply {
            addView(form, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val pad = (resources.displayMetrics.density * 16).toInt()
        form.setPadding(pad, pad, pad, pad)
        form.orientation = LinearLayout.VERTICAL
        bind()
    }

    private fun bind() {
        val p = LocalVoiceProvider(requireContext())
        provider = p
        lifecycleScope.launch {
            val settings = runCatching { p.settings }.getOrNull() ?: Bundle()
            val vendors = runCatching { JSONArray(p.vendorList) }
                .onFailure { android.util.Log.w("CloudVoiceUI", "vendorList failed", it) }
                .getOrElse { JSONArray() }
            buildForm(vendors, settings)
        }
    }

    private fun dp(v: Int) = (resources.displayMetrics.density * v).toInt()

    private fun label(text: String) = TextView(requireContext()).apply {
        this.text = text
        setPadding(0, dp(16), 0, dp(4))
    }

    private fun input(value: String, password: Boolean = false) = EditText(requireContext()).apply {
        setText(value)
        isSingleLine = true
        if (password) inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    private fun choice(value: String, choices: List<String>) = Spinner(requireContext()).apply {
        adapter = ArrayAdapter(requireContext(),
            android.R.layout.simple_spinner_dropdown_item, choices)
        setSelection(choices.indexOf(value).coerceAtLeast(0))
    }

    private fun buildForm(vendors: JSONArray, settings: Bundle) {
        vendorTag = vendors
        if (vendors.length() == 0) {
            form.addView(TextView(requireContext()).apply {
                text = getString(R.string.cloud_voice_plugin_outdated)
            })
            return
        }
        val names = (0 until vendors.length()).map { vendors.getJSONObject(it).getString("name") }
        val current = settings.getString("vendor").orEmpty()
        vendorSpinner = Spinner(requireContext()).apply {
            adapter = ArrayAdapter(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, names)
            setSelection((0 until vendors.length())
                .firstOrNull { vendors.getJSONObject(it).getString("id") == current } ?: 0)
        }
        form.addView(label(getString(R.string.cloud_voice_vendor)))
        form.addView(vendorSpinner)

        fieldsBox = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        form.addView(fieldsBox)

        micStatus = TextView(requireContext())
        form.addView(label(getString(R.string.cloud_voice_permission)))
        form.addView(micStatus.apply { setOnClickListener { updateMicStatus(autoAsk = true) } })
        updateMicStatus(autoAsk = true)

        vendorSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                renderFields(vendors.getJSONObject(pos).getString("id"), settings)
                saveNow()
            }

            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        renderFields(
            vendors.getJSONObject(vendorSpinner.selectedItemPosition).getString("id"), settings)
    }

    private fun renderFields(vendorId: String, settings: Bundle) {
        val p = provider ?: return
        loading = true
        fieldsBox.removeAllViews()
        fieldViews.clear()
        volcModelSpinner = null
        val config = runCatching { JSONObject(p.getVendorConfig(vendorId)) }.getOrNull() ?: return
        val fields = config.optJSONArray("fields") ?: JSONArray()

        if (config.optString("kind") == "volc") renderVolcModel(settings)

        for (i in 0 until fields.length()) {
            val f = fields.getJSONObject(i)
            val key = f.getString("key")
            val value = settings.getString(key) ?: f.optString("default")
            fieldsBox.addView(label(f.getString("label")))
            val view = when (f.getString("type")) {
                "choice" -> choice(value, (0 until f.getJSONArray("choices").length())
                    .map { f.getJSONArray("choices").getString(it) })
                "password" -> input(value, password = true)
                else -> input(value)
            }
            fieldViews[key] = view
            attachAutoSave(view)
            fieldsBox.addView(view)
        }
        loading = false

        // "fetch models online" for vendors exposing an OpenAI-compatible base
        val baseField = config.optString("modelsBaseField")
        if (baseField.isNotEmpty() && baseField != "null") {
            fieldsBox.addView(Button(requireContext()).apply {
                text = getString(R.string.cloud_voice_fetch_models)
                setOnClickListener { fetchModels(vendorId, baseField, config.optString("modelsKeyField")) }
            })
        }
    }

    private var loading = false

    /** Persist the current form to the plugin on any field change. */
    private fun saveNow() {
        val p = provider ?: return
        runCatching { p.setSettings(currentSettings()) }
    }

    private fun attachAutoSave(view: View) {
        when (view) {
            is EditText -> view.doAfterTextChanged { if (!loading) saveNow() }
            is Spinner -> view.onItemSelectedListener =
                object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long
                    ) { if (!loading) saveNow() }

                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                }
        }
    }

    /** volc: model == resource id, discovered via the online probe (special case). */
    private fun renderVolcModel(settings: Bundle) {
        val p = provider ?: return
        val catalog = runCatching { p.modelCatalog }.getOrNull() ?: return
        val ids = catalog.getStringArray("ids")?.toList().orEmpty()
        val labels = catalog.getStringArray("labels")?.toList().orEmpty()
        var pairs = ids.zip(labels).toMutableList()
        val saved = settings.getString("volc_resource_id").orEmpty()
        if (saved.isNotEmpty() && pairs.none { it.first == saved })
            pairs.add(Pair(saved, getString(R.string.cloud_voice_custom, saved)))
        fieldsBox.addView(label(getString(R.string.cloud_voice_model)))
        volcModelSpinner = Spinner(requireContext()).apply {
            adapter = ArrayAdapter(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, pairs.map { it.second })
            setSelection(pairs.indexOfFirst { it.first == saved }.coerceAtLeast(0))
        }
        volcModelIds = pairs.map { it.first }
        attachAutoSave(volcModelSpinner!!)
        fieldsBox.addView(volcModelSpinner)
        fieldsBox.addView(Button(requireContext()).apply {
            text = getString(R.string.cloud_voice_probe)
            setOnClickListener { probe() }
        })
    }

    private fun fieldValue(key: String): String? = when (val v = fieldViews[key]) {
        is EditText -> v.text.toString().trim()
        is Spinner -> v.selectedItem?.toString()
        else -> null
    }

    /** current form state as a settings bundle (for save/probe/fetch) */
    private fun currentSettings(): Bundle = Bundle().apply {
        val id = currentVendorId()
        if (id != null) putString("vendor", id)
        fieldViews.forEach { (key, view) ->
            when (view) {
                is EditText -> putString(key, view.text.toString().trim())
                is Spinner -> (view.selectedItemPosition.takeIf { it >= 0 })?.let {
                    putString(key, (view.adapter?.getItem(it) ?: "").toString())
                }
            }
        }
        volcModelSpinner?.let { sp ->
            volcModelIds.getOrNull(sp.selectedItemPosition)
                ?.let { putString("volc_resource_id", it) }
        }
    }

    private fun currentVendorId(): String? {
        if (!::vendorSpinner.isInitialized) return null
        // vendor id is stored on the spinner tag via adapter position -> vendors array
        return vendorTag?.getJSONObject(vendorSpinner.selectedItemPosition)?.getString("id")
    }

    private var vendorTag: JSONArray? = null

    private fun updateMicStatus(autoAsk: Boolean = false): Boolean {
        // recording happens in the main app (IME) process, so its own permission matters
        val granted = requireContext().checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        micStatus.text = getString(
            if (granted) R.string.cloud_voice_mic_granted else R.string.cloud_voice_mic_denied)
        if (!granted && autoAsk) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
        return granted
    }

    override fun onResume() {
        super.onResume()
        if (::micStatus.isInitialized) updateMicStatus()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC && ::micStatus.isInitialized) updateMicStatus()
    }

    private fun save() {
        val p = provider ?: return
        runCatching { p.setSettings(currentSettings()) }
        Toast.makeText(requireContext(), R.string.cloud_voice_saved, Toast.LENGTH_SHORT).show()
    }

    private fun probe() {
        val p = provider ?: return
        runCatching { p.setSettings(currentSettings()) }   // persist form first
        Toast.makeText(requireContext(), R.string.cloud_voice_probing, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { p.probeResources() }.getOrNull()
            withContext(Dispatchers.Main) {
                if (result == null) {
                    Toast.makeText(requireContext(), R.string.cloud_voice_probe_failed,
                        Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                val lines = volcModelIds.map { id ->
                    val status = when (result.getString(id)) {
                        "granted" -> getString(R.string.cloud_voice_granted)
                        "not_granted" -> getString(R.string.cloud_voice_not_granted)
                        else -> getString(R.string.cloud_voice_probe_failed)
                    }
                    "$id: $status"
                }
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.cloud_voice_probe)
                    .setMessage(lines.joinToString("\n"))
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        volcModelIds.indexOfFirst { result.getString(it) == "granted" }
                            .takeIf { it >= 0 }
                            ?.let { volcModelSpinner?.setSelection(it) }
                    }
                    .show()
            }
        }
    }

    /** OpenAI-compatible model fetch; dashscope base derives from its region field. */
    private fun fetchModels(vendorId: String, baseField: String, keyField: String) {
        val p = provider ?: return
        runCatching { p.setSettings(currentSettings()) }
        val base = if (vendorId == "dashscope") {
            val region = fieldValue("dash_region") ?: "cn"
            (if (region == "intl") "https://dashscope-intl.aliyuncs.com"
            else "https://dashscope.aliyuncs.com") + "/compatible-mode/v1"
        } else {
            fieldValue(baseField)?.trimEnd('/')?.plus("") ?: run {
                Toast.makeText(requireContext(), R.string.cloud_voice_fetch_empty,
                    Toast.LENGTH_SHORT).show()
                return
            }
        }
        val key = fieldValue(keyField).orEmpty()
        if (key.isBlank()) {
            Toast.makeText(requireContext(), R.string.cloud_voice_fetch_need_key,
                Toast.LENGTH_SHORT).show()
            return
        }
        val target = if (vendorId == "dashscope") "dash_model"
        else fieldViews.keys.firstOrNull { it.endsWith("_model") }
        Toast.makeText(requireContext(), R.string.cloud_voice_probing, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            val bundle = runCatching { p.fetchModelsAt(base, key) }.getOrNull()
            withContext(Dispatchers.Main) {
                val err = bundle?.getString("error")
                val models = bundle?.getStringArray("models")?.toList().orEmpty()
                if (err != null || models.isEmpty()) {
                    Toast.makeText(requireContext(),
                        err ?: getString(R.string.cloud_voice_fetch_empty),
                        Toast.LENGTH_LONG).show()
                    return@withContext
                }
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.cloud_voice_fetch_models)
                    .setItems(models.toTypedArray()) { _, i ->
                        (fieldViews[target] as? EditText)?.setText(models[i])
                        Toast.makeText(requireContext(), models[i], Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    companion object {
        private const val REQ_MIC = 1
    }
}
