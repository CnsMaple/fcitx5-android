/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.net.Uri
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.isEmpty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.common.withLoadingDialog
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.toast

abstract class FcitxPreferenceFragment : PaddingPreferenceFragment() {
    abstract fun getPageTitle(): String
    abstract suspend fun obtainConfig(fcitx: FcitxAPI): RawConfig
    abstract suspend fun saveConfig(fcitx: FcitxAPI, newConfig: RawConfig)

    private lateinit var raw: RawConfig
    private var configLoaded = false

    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(supervisorJob)

    private val viewModel: MainViewModel by activityViewModels()

    private val fcitx: FcitxConnection
        get() = viewModel.fcitx

    private var currentRimeDirDialog: android.app.AlertDialog? = null
    private var pendingStoragePermission = false

    private val rimeDirectoryPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val path = extractRimeDirPath(uri)
            showRimeDirConfirmDialog(uri, path)
        }
    }

    private fun showRimeDirConfirmDialog(uri: Uri, path: String) {
        val ctx = requireContext()
        val prefs = AppPrefs.getInstance()
        val currentPath = prefs.rimeUserDataPath.getValue()
        val defaultPath = ctx.getString(R.string.rime_user_data_dir_internal)
        val displayOldPath = if (currentPath.isNotEmpty()) currentPath else defaultPath
        android.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.rime_user_data_dir_title)
            .setMessage(ctx.getString(R.string.rime_user_data_dir_confirm_message, displayOldPath, path))
            .setPositiveButton(R.string.rime_user_data_dir_confirm) { _, _ ->
                applyRimeDirSelection(uri, path)
            }
            .setNegativeButton(R.string.rime_user_data_dir_cancel) { _, _ ->
                // Re-open the main directory picker dialog
                showRimeDirMainDialog()
            }
            .show()
    }

    private fun applyRimeDirSelection(uri: Uri, path: String) {
        val ctx = requireContext()
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            ctx.toast(e)
        }
        val prefs = AppPrefs.getInstance()
        prefs.rimeUserDataUri.setValue(uri.toString())
        prefs.rimeUserDataPath.setValue(path)
        findPreference<Preference>("UserDataDir")?.let { pref ->
            pref.summaryProvider = null
            pref.summary = ctx.getString(R.string.rime_user_data_dir_current, path)
        }
        showRestartRimeDialog(path)
    }

    private fun showRestartRimeDialog(newPath: String) {
        val ctx = requireContext()
        android.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.rime_user_data_dir_title)
            .setMessage(ctx.getString(R.string.rime_user_data_dir_restart_required))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                FcitxDaemon.restartFcitx()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun extractRimeDirPath(uri: Uri): String {
        val docId = try {
            android.provider.DocumentsContract.getTreeDocumentId(uri)
        } catch (e: Exception) {
            return uri.toString()
        }
        // docId format: "primary:path/to/folder" for internal storage
        // or "XXXX-XXXX:path/to/folder" for SD card
        val parts = docId.split(":", limit = 2)
        if (parts.size != 2) return uri.toString()
        val volumeId = parts[0]
        val path = parts[1]
        return if (volumeId == "primary") {
            "/storage/emulated/0/$path"
        } else {
            "/storage/$volumeId/$path"
        }
    }

    /**
     * Show the RIME user data directory dialog with 4 buttons:
     * Default(默认), Select(选择), OK(确定), Cancel(取消)
     */
    private fun launchRimeDirectoryPicker() {
        showRimeDirMainDialog()
    }

    private fun showRimeDirMainDialog() {
        val ctx = requireContext()
        val prefs = AppPrefs.getInstance()
        val currentPath = prefs.rimeUserDataPath.getValue()
        val defaultPath = ctx.getString(R.string.rime_user_data_dir_internal)
        val displayPath = if (currentPath.isNotEmpty()) currentPath else defaultPath

        val inflater = android.view.LayoutInflater.from(ctx)
        val rootView = inflater.inflate(
            org.fcitx.fcitx5.android.R.layout.dialog_rime_user_data_dir,
            null
        )
        val pathText = rootView.findViewById<android.widget.TextView>(
            org.fcitx.fcitx5.android.R.id.rime_dir_current_path
        )
        val previewText = rootView.findViewById<android.widget.TextView>(
            org.fcitx.fcitx5.android.R.id.rime_dir_preview
        )
        val btnDefault = rootView.findViewById<android.widget.Button>(
            org.fcitx.fcitx5.android.R.id.rime_dir_btn_default
        )
        val btnCancel = rootView.findViewById<android.widget.Button>(
            org.fcitx.fcitx5.android.R.id.rime_dir_btn_cancel
        )
        val btnSelect = rootView.findViewById<android.widget.Button>(
            org.fcitx.fcitx5.android.R.id.rime_dir_btn_select
        )
        val btnOk = rootView.findViewById<android.widget.Button>(
            org.fcitx.fcitx5.android.R.id.rime_dir_btn_ok
        )

        pathText.text = displayPath
        previewText.visibility = android.view.View.GONE

        val dialog = android.app.AlertDialog.Builder(ctx)
            .setView(rootView)
            .show()
        currentRimeDirDialog = dialog

        // Handle "Select" button - open SAF directory picker
        btnSelect.setOnClickListener {
            // On Android 11+, check MANAGE_EXTERNAL_STORAGE permission is granted
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!android.os.Environment.isExternalStorageManager()) {
                    pendingStoragePermission = true
                    dialog.dismiss()
                    currentRimeDirDialog = null
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:${ctx.packageName}")
                    }
                    ctx.startActivity(intent)
                    return@setOnClickListener
                }
            }
            dialog.dismiss()
            currentRimeDirDialog = null
            rimeDirectoryPicker.launch(null)
        }

        // Handle "Default" button - reset to internal directory
        btnDefault.setOnClickListener {
            dialog.dismiss()
            currentRimeDirDialog = null
            prefs.rimeUserDataUri.setValue("")
            prefs.rimeUserDataPath.setValue("")
            findPreference<Preference>("UserDataDir")?.let { pref ->
                pref.summaryProvider = null
                pref.summary = ctx.getString(R.string.rime_user_data_dir_internal)
            }
            showRestartRimeDialog(defaultPath)
        }

        // Handle "Cancel" button
        btnCancel.setOnClickListener {
            dialog.dismiss()
            currentRimeDirDialog = null
        }

        // Handle "OK" button - apply current selection
        btnOk.setOnClickListener {
            val savedUri = prefs.rimeUserDataUri.getValue()
            val savedPath = prefs.rimeUserDataPath.getValue()
            if (savedPath.isNotEmpty() && savedUri.isNotEmpty()) {
                // Verify the directory still exists and has valid permissions
                try {
                    val uri = android.net.Uri.parse(savedUri)
                    val treeDocId = android.provider.DocumentsContract.getTreeDocumentId(uri)
                    val docUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                        uri, treeDocId
                    )
                    val cursor = ctx.contentResolver.query(docUri, null, null, null, null)
                    cursor?.use { _ ->
                        // Directory exists and is accessible
                        dialog.dismiss()
                        currentRimeDirDialog = null
                        showRestartRimeDialog(savedPath)
                        return@setOnClickListener
                    }
                } catch (_: Exception) {
                    // fall through to show error dialog
                }
                // Directory inaccessible (permission revoked or deleted)
                dialog.dismiss()
                currentRimeDirDialog = null
                showRimeDirErrorDialog()
            }
        }
    }

    private fun showRimeDirErrorDialog() {
        val ctx = requireContext()
        android.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.rime_user_data_dir_title)
            .setMessage(R.string.rime_user_data_dir_permission_revoked)
            .setPositiveButton(R.string.rime_user_data_dir_re_authorize) { _, _ ->
                rimeDirectoryPicker.launch(null)
            }
            .setNegativeButton(R.string.rime_user_data_dir_use_internal) { _, _ ->
                val prefs = AppPrefs.getInstance()
                prefs.rimeUserDataUri.setValue("")
                prefs.rimeUserDataPath.setValue("")
                showRestartRimeDialog(ctx.getString(R.string.rime_user_data_dir_internal))
            }
            .show()
    }

    private fun save() {
        if (!configLoaded) return
        // launch "saveConfig" job under supervisorJob scope
        scope.launch {
            fcitx.runOnReady {
                saveConfig(this, raw["cfg"])
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireActivity().onBackPressedDispatcher
            .addCallback(this, object : OnBackPressedCallback(true) {
                // prevent "back" from navigating away from this Fragment when it's still saving
                override fun handleOnBackPressed() {
                    lifecycleScope.withLoadingDialog(requireContext(), R.string.saving) {
                        // complete the parent job and wait all "saveConfig" jobs to finish
                        supervisorJob.complete()
                        supervisorJob.join()
                        scope.cancel()
                        findNavController().popBackStack()
                    }
                }
            })
    }

    /**
     * **TLDR:**
     * Intentionally empty, since we need to create PreferenceScreen during onStart,
     * or it will crash when MainActivity relaunches.
     *
     * **Long version:**
     * When `MainActivity` relaunches, its `onCreate` get called, and somewhere in `super.onCreate`
     * decided to `restoreChildFragmentState` of `NavHostFragment`, thus recreate the child fragment.
     * If that fragment was derived from `FcitxPreferenceFragment`, it needs to call `obtainConfig`
     * which would need the route params, and in turn needs `NavGraph`.
     * But at this time it's still in `MainActivity`'s `super.onCreate`, the Activity did not have
     * chance to set up `NavGraph` on `navController`, so accessing `lazyRoute` would crash.
     *
     * That is to say, if we declare `app:navGraph` on `<FragmentContainerView />` in `activity_main.xml`,
     * the graph would have been initialized when `NavHostFragment` got inflated, and does not suffer
     * from this problem? But maintain navigation destinations in XML is too tedious ...
     */
    final override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // make sure to create preference only once since `onViewCreated` is also called on Fragment resume
        if (preferenceScreen?.isEmpty() == false) return
        val context = requireContext()
        lifecycleScope.withLoadingDialog(context) {
            raw = fcitx.runOnReady { obtainConfig(this) }
            configLoaded = raw.findByName("cfg") != null && raw.findByName("desc") != null
            preferenceScreen = if (configLoaded) {
                PreferenceScreenFactory.create(
                    preferenceManager, parentFragmentManager, raw, ::save,
                    onPickRimeDirectory = { launchRimeDirectoryPicker() }
                ).apply {
                    if (isEmpty()) {
                        addPreference(R.string.no_config_options)
                    }
                }
            } else {
                preferenceManager.createPreferenceScreen(context).apply {
                    addPreference(R.string.config_addon_not_loaded)
                }
            }
            viewModel.disableAboutButton()
        }
    }



    override fun onResume() {
        super.onResume()
        if (pendingStoragePermission) {
            pendingStoragePermission = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                android.os.Environment.isExternalStorageManager()
            ) {
                rimeDirectoryPicker.launch(null)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.setToolbarTitle(getPageTitle())
    }
}