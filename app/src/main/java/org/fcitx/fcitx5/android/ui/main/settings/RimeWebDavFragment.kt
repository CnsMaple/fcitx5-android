package org.fcitx.fcitx5.android.ui.main.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.utils.rimeBackup
import org.fcitx.fcitx5.android.utils.rimePlainSync
import org.fcitx.fcitx5.android.utils.rimePull
import org.fcitx.fcitx5.android.utils.rimePush
import org.fcitx.fcitx5.android.utils.rimeRestore
import org.fcitx.fcitx5.android.utils.rimeSyncNow
import org.fcitx.fcitx5.android.utils.treeUriToRealPath

/** Rime snapshot config page: url/user/pass + backup dir + patterns + backup/restore/push/pull. */
class RimeWebDavFragment : Fragment() {

    private lateinit var form: LinearLayout
    private lateinit var url: EditText
    private lateinit var user: EditText
    private lateinit var pass: EditText
    private lateinit var patterns: EditText
    private lateinit var dirText: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        form = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        return ScrollView(requireContext()).apply {
            addView(form, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val pad = dp(16)
        form.setPadding(pad, pad, pad, pad)

        fun label(t: String) = TextView(requireContext()).apply {
            text = t; setPadding(0, dp(14), 0, dp(4))
        }

        form.addView(label(getString(R.string.clip_sync_server_url)))
        url = et(false); form.addView(url)
        form.addView(label(getString(R.string.clip_sync_user)))
        user = et(false); form.addView(user)
        form.addView(label(getString(R.string.clip_sync_password)))
        pass = et(true); form.addView(pass)

        form.addView(label(getString(R.string.rime_backup_dir)))
        val dirRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        dirText = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            textSize = 12f
            text = dirLabel()
        }
        dirRow.addView(dirText)
        dirRow.addView(Button(requireContext()).apply {
            text = getString(R.string.clip_sync_choose_dir)
            setOnClickListener { chooseDir() }
        })
        form.addView(dirRow)

        form.addView(label(getString(R.string.rime_backup_patterns)))
        patterns = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 4
            gravity = android.view.Gravity.TOP
            setHorizontallyScrolling(false)
        }
        form.addView(patterns)

        form.addView(Button(requireContext()).apply {
            text = getString(R.string.rime_sync_now)
            setOnClickListener { saveConfig(); rimeSyncNow(requireContext()) }
        })
        form.addView(Button(requireContext()).apply {
            text = getString(R.string.rime_backup)
            setOnClickListener { saveConfig(); rimeBackup(requireContext()) }
        })
        form.addView(Button(requireContext()).apply {
            text = getString(R.string.rime_restore)
            setOnClickListener { rimeRestore(requireContext()) }
        })
        form.addView(Button(requireContext()).apply {
            text = getString(R.string.rime_push)
            setOnClickListener { saveConfig(); rimePush(requireContext()) }
        })
        form.addView(Button(requireContext()).apply {
            text = getString(R.string.rime_pull)
            setOnClickListener { rimePull(requireContext()) }
        })
        form.addView(Button(requireContext()).apply {
            text = getString(R.string.rime_webdav_plain_sync)
            setOnClickListener { rimePlainSync(requireContext()) }
        })

        // config applies immediately on change
        listOf(url, user, pass, patterns).forEach { it.doAfterTextChanged { if (!loading) saveConfig() } }

        loadConfig()
    }

    private var loading = false

    private fun dp(v: Int) = (resources.displayMetrics.density * v).toInt()

    private fun et(pw: Boolean) = EditText(requireContext()).apply {
        isSingleLine = true
        if (pw) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    private fun dirLabel(): String {
        val d = AppPrefs.getInstance().internal.rimeBackupDir.getValue()
        return if (d.isEmpty()) getString(R.string.rime_backup_dir_unset) else d
    }

    private fun loadConfig() {
        val p = AppPrefs.getInstance().internal
        loading = true
        url.setText(p.rimeWebDavUrl.getValue())
        user.setText(p.rimeWebDavUser.getValue())
        pass.setText(p.rimeWebDavPass.getValue())
        patterns.setText(p.rimeBackupPatterns.getValue())
        dirText.text = dirLabel()
        loading = false
    }

    private fun saveConfig() {
        val p = AppPrefs.getInstance().internal
        p.rimeWebDavUrl.setValue(url.text.toString().trim().trimEnd('/'))
        p.rimeWebDavUser.setValue(user.text.toString().trim())
        p.rimeWebDavPass.setValue(pass.text.toString())
        p.rimeBackupPatterns.setValue(patterns.text.toString())
    }

    private val dirLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        val real = treeUriToRealPath(uri)
        if (real == null) {
            Toast.makeText(requireContext(), R.string.clip_sync_dir_unsupported, Toast.LENGTH_SHORT).show()
        } else {
            runCatching {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            AppPrefs.getInstance().internal.rimeBackupDir.setValue(real)
            dirText.text = dirLabel()
        }
    }

    private val storagePermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { if (Environment.isExternalStorageManager()) dirLauncher.launch(null) }

    private fun chooseDir() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(requireContext(), R.string.clip_sync_dir_need_android_11, Toast.LENGTH_SHORT).show()
            return
        }
        if (Environment.isExternalStorageManager()) {
            dirLauncher.launch(null)
        } else {
            Toast.makeText(requireContext(), R.string.clip_sync_dir_need_permission, Toast.LENGTH_LONG).show()
            storagePermLauncher.launch(
                Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    .setData(Uri.parse("package:${requireContext().packageName}"))
            )
        }
    }
}
