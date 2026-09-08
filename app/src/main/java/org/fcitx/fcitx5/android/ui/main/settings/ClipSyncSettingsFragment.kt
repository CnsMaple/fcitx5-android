package org.fcitx.fcitx5.android.ui.main.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.addTextChangedListener
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.common.ipc.IClipSyncProgress
import org.fcitx.fcitx5.android.common.ipc.IClipSyncProvider
import org.fcitx.fcitx5.android.data.clipboard.ClipSyncClient
import org.fcitx.fcitx5.android.data.clipboard.LocalClipSyncProvider
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.utils.clipboardManager

/** Native settings page for the clipboard-sync plugin (WebDAV + auto/interval). */
class ClipSyncSettingsFragment : Fragment() {

    private var provider: IClipSyncProvider? = null
    private lateinit var form: LinearLayout
    private lateinit var url: EditText
    private lateinit var user: EditText
    private lateinit var pass: EditText
    private lateinit var autoSwitch: androidx.appcompat.widget.SwitchCompat
    private lateinit var interval: EditText
    private lateinit var status: TextView
    private lateinit var downloadDirText: TextView
    private lateinit var downloadCancelBtn: Button

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        form = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        return ScrollView(requireContext()).apply {
            addView(form, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val pad = (resources.displayMetrics.density * 16).toInt()
        form.setPadding(pad, pad, pad, pad)

        fun label(t: String) = TextView(requireContext()).apply {
            text = t; setPadding(0, dp(14), 0, dp(4))
        }

        form.addView(label(getString(R.string.clip_sync_server_url)))
        url = et(false)
        form.addView(url)
        form.addView(label(getString(R.string.clip_sync_user)))
        user = et(false)
        form.addView(user)
        form.addView(label(getString(R.string.clip_sync_password)))
        pass = et(true)
        form.addView(pass)

        val autoRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, dp(8))
        }
        autoRow.addView(TextView(requireContext()).apply {
            text = getString(R.string.clip_sync_auto)
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
            // tapping the label toggles the switch too
            setOnClickListener { autoSwitch.toggle() }
        })
        autoSwitch = androidx.appcompat.widget.SwitchCompat(requireContext())
        autoRow.addView(autoSwitch)
        form.addView(autoRow)
        form.addView(label(getString(R.string.clip_sync_interval)))
        interval = et(false).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        form.addView(interval)

        form.addView(label(getString(R.string.clip_sync_download_dir)))
        val dirRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        downloadDirText = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            textSize = 12f
            text = dirLabel()
        }
        dirRow.addView(downloadDirText)
        dirRow.addView(Button(requireContext()).apply {
            text = getString(R.string.clip_sync_choose_dir)
            setOnClickListener { pickDir() }
        })
        form.addView(dirRow)

        status = TextView(requireContext()).apply { setPadding(0, dp(16), 0, 0) }
        form.addView(status)

        form.addView(Button(requireContext()).apply {
            text = getString(R.string.clip_sync_now)
            setOnClickListener { manualSync() }
        })
        form.addView(Button(requireContext()).apply {
            text = getString(R.string.clip_sync_upload_clipboard)
            setOnClickListener { uploadClipboard() }
        })
        form.addView(Button(requireContext()).apply {
            text = getString(R.string.clip_sync_upload_file)
            setOnClickListener { filePicker.launch("*/*") }
        })
        form.addView(Button(requireContext()).apply {
            text = getString(R.string.clip_sync_upload_image)
            setOnClickListener { imagePicker.launch("image/*") }
        })
        form.addView(Button(requireContext()).apply {
            text = getString(R.string.clip_sync_download)
            setOnClickListener { downloadCloud() }
        })
        downloadCancelBtn = Button(requireContext()).apply {
            text = getString(R.string.clip_sync_cancel)
            visibility = View.GONE
            setOnClickListener { runCatching { provider?.cancelDownload() } }
        }
        form.addView(downloadCancelBtn)

        // config applies immediately on change
        listOf(url, user, pass).forEach { it.doAfterTextChanged { if (!loading) pushSettings() } }
        autoSwitch.setOnCheckedChangeListener { _, _ -> if (!loading) pushAuto() }
        interval.doAfterTextChanged { if (!loading) pushAuto() }

        bindAndLoad()
    }

    private var loading = false

    private fun pushSettings() {
        val p = provider ?: run {
            status.text = getString(R.string.clip_sync_bg_restricted)
            return
        }
        runCatching {
            p.setSettings(Bundle().apply {
                putString("url", url.text.toString().trim().trimEnd('/'))
                putString("user", user.text.toString().trim())
                putString("pass", pass.text.toString())
            })
        }
    }

    private fun pushAuto() {
        val prefs = AppPrefs.getInstance().internal
        prefs.clipSyncAuto.setValue(autoSwitch.isChecked)
        prefs.clipSyncInterval.setValue(interval.text.toString().toIntOrNull() ?: 30)
        ClipSyncClient.current?.applyAuto(prefs.clipSyncAuto.getValue(), prefs.clipSyncInterval.getValue())
    }

    private val filePicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { uploadUri(it, "File") } }

    private val imagePicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { uploadUri(it, "Image") } }

    private val dirPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val real = org.fcitx.fcitx5.android.utils.treeUriToRealPath(uri)
        if (real == null) {
            Toast.makeText(requireContext(), R.string.clip_sync_dir_unsupported, Toast.LENGTH_SHORT).show()
        } else {
            runCatching {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            AppPrefs.getInstance().internal.clipSyncDownloadDir.setValue(real)
            downloadDirText.text = dirLabel()
        }
    }

    private fun dirLabel(): String {
        val d = AppPrefs.getInstance().internal.clipSyncDownloadDir.getValue()
        return if (d.isEmpty()) getString(R.string.clip_sync_dir_default) else d
    }

    private val storagePermLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { if (android.os.Environment.isExternalStorageManager()) dirPicker.launch(null) }

    private fun pickDir() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            Toast.makeText(requireContext(), R.string.clip_sync_dir_need_android_11, Toast.LENGTH_SHORT).show()
            return
        }
        if (android.os.Environment.isExternalStorageManager()) {
            dirPicker.launch(null)
        } else {
            Toast.makeText(requireContext(), R.string.clip_sync_dir_need_permission, Toast.LENGTH_LONG).show()
            storagePermLauncher.launch(
                Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    .setData(Uri.parse("package:${requireContext().packageName}"))
            )
        }
    }

    private fun dp(v: Int) = (resources.displayMetrics.density * v).toInt()

    private fun et(pw: Boolean) = EditText(requireContext()).apply {
        isSingleLine = true
        if (pw) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    private fun bindAndLoad() {
        provider = LocalClipSyncProvider(requireContext())
        val s = runCatching { provider?.settings }.getOrNull()
        val prefs = AppPrefs.getInstance().internal
        loading = true
        url.setText(s?.getString("url").orEmpty())
        user.setText(s?.getString("user").orEmpty())
        pass.setText(s?.getString("pass").orEmpty())
        autoSwitch.isChecked = prefs.clipSyncAuto.getValue()
        interval.setText(prefs.clipSyncInterval.getValue().toString())
        loading = false
        status.text = getString(R.string.clip_sync_connected)
    }

    private fun save() {
        val p = provider
        if (p != null) {
            runCatching {
                p.setSettings(Bundle().apply {
                    putString("url", url.text.toString().trim().trimEnd('/'))
                    putString("user", user.text.toString().trim())
                    putString("pass", pass.text.toString())
                })
            }
        }
        val prefs = AppPrefs.getInstance().internal
        prefs.clipSyncAuto.setValue(autoSwitch.isChecked)
        prefs.clipSyncInterval.setValue(interval.text.toString().toIntOrNull() ?: 30)
        // live-apply to the running IME client
        ClipSyncClient.current?.applyAuto(prefs.clipSyncAuto.getValue(), prefs.clipSyncInterval.getValue())
        Toast.makeText(requireContext(), R.string.cloud_voice_saved, Toast.LENGTH_SHORT).show()
    }

    private fun manualSync() {
        if (url.text.isNullOrBlank()) {
            Toast.makeText(requireContext(), R.string.clip_sync_need_url, Toast.LENGTH_SHORT).show()
            return
        }
        status.text = getString(R.string.cloud_voice_probing)
        ClipSyncClient.current?.pullOnce(force = true)
        status.text = getString(R.string.clip_sync_connected)
    }

    private fun ensureSaved() {
        provider?.let {
            runCatching {
                it.setSettings(Bundle().apply {
                    putString("url", url.text.toString().trim().trimEnd('/'))
                    putString("user", user.text.toString().trim())
                    putString("pass", pass.text.toString())
                })
            }
        }
    }

    /** Upload the current clipboard text to the server. */
    private fun uploadClipboard() {
        val p = provider ?: return
        ensureSaved()
        val clip = requireContext().clipboardManager.primaryClip ?: return
        val text = clip.getItemAt(0)?.coerceToText(requireContext())?.toString().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(requireContext(), R.string.clip_sync_empty_clipboard, Toast.LENGTH_SHORT).show()
            return
        }
        status.text = getString(R.string.clip_sync_uploading)
        downloadCancelBtn.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val res = runCatching { p.push("Text", text, null, null, "") }
            val ok = res.getOrNull()?.getBoolean("ok") == true
            val err = res.getOrNull()?.getString("error") ?: res.exceptionOrNull()?.message
            withContext(Dispatchers.Main) {
                downloadCancelBtn.visibility = View.GONE
                status.text = if (ok) getString(R.string.clip_sync_uploaded)
                    else getString(R.string.clip_sync_failed, err ?: "?")
            }
        }
    }

    /** Upload a picked file/image to the server. */
    private fun uploadUri(uri: Uri, type: String) {
        val p = provider ?: return
        ensureSaved()
        status.text = getString(R.string.clip_sync_uploading)
        downloadCancelBtn.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val res = runCatching {
                val name = queryName(uri) ?: uri.lastPathSegment ?: "upload.bin"
                val bytes = requireContext().contentResolver.openInputStream(uri)
                    ?.use { it.readBytes() } ?: error("cannot read")
                p.push(type, name, name, bytes, "")
            }
            val ok = res.getOrNull()?.getBoolean("ok") == true
            val err = res.getOrNull()?.getString("error") ?: res.exceptionOrNull()?.message
            withContext(Dispatchers.Main) {
                downloadCancelBtn.visibility = View.GONE
                status.text = if (ok) getString(R.string.clip_sync_uploaded)
                    else getString(R.string.clip_sync_failed, err ?: "?")
            }
        }
    }

    /** Download the remote clipboard content and apply it locally. */
    @Suppress("DEPRECATION")
    private fun downloadCloud() {
        val p = provider ?: return
        ensureSaved()
        status.text = getString(R.string.clip_sync_downloading)
        downloadCancelBtn.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val r = runCatching {
                val meta = p.pullMeta()
                if (meta.getString("error") != null) error(meta.getString("error")!!)
                val type = meta.getString("type") ?: "Text"
                val text = meta.getString("text").orEmpty()
                val hasData = meta.getBoolean("hasData")
                val dataName = meta.getString("dataName")
                var doneMsg = getString(R.string.clip_sync_downloaded)
                val mb = 1024.0 * 1024.0
                val clip: android.content.ClipData = if (hasData && dataName != null) {
                    val cb = object : IClipSyncProgress.Stub() {
                        override fun onProgress(done: Long, total: Long) {
                            val pct = if (total > 0) (done * 100 / total).toInt() else 0
                            val d = "%.1f".format(done / mb)
                            activity?.runOnUiThread {
                                status.text = if (total > 0)
                                    "下载中 $pct% · $d/${"%.1f".format(total / mb)} MB"
                                else "下载中 · $d MB"
                            }
                        }
                    }
                    val dl = p.pullDataWithProgress(dataName, cb)
                    dl.getString("error")?.let { error(it) }
                    val bytes = (dl.getParcelable("fd") as? android.os.ParcelFileDescriptor)?.use {
                        java.io.FileInputStream(it.fileDescriptor).readBytes()
                    } ?: error("no data for $dataName")
                    if (type == "Text") {
                        android.content.ClipData.newPlainText("clip", String(bytes, Charsets.UTF_8))
                    } else {
                        val uri = ClipSyncClient.saveClipFile(requireContext(), dataName, bytes)
                        doneMsg = getString(R.string.clip_sync_saved_generic)
                        android.content.ClipData.newUri(requireContext().contentResolver, "clip", uri)
                    }
                } else {
                    android.content.ClipData.newPlainText("clip", text)
                }
                withContext(Dispatchers.Main) {
                    requireContext().clipboardManager.setPrimaryClip(clip)
                }
                doneMsg
            }
            withContext(Dispatchers.Main) {
                downloadCancelBtn.visibility = View.GONE
                status.text = r.fold(
                    { it },
                    { getString(R.string.clip_sync_failed, it.message ?: "?") },
                )
            }
        }
    }

    private fun queryName(uri: Uri): String? = runCatching {
        requireContext().contentResolver.query(
            uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    override fun onDestroyView() {
        provider = null
        super.onDestroyView()
    }
}
