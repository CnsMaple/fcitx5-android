/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.preference.DialogPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.Preference.SummaryProvider
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import arrow.core.getOrElse
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.Key
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.core.syncRimeUserData
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.ui.main.modified.MySwitchPreference
import org.fcitx.fcitx5.android.utils.LongClickPreference
import org.fcitx.fcitx5.android.utils.buildDocumentsProviderIntent
import org.fcitx.fcitx5.android.utils.buildPrimaryStorageIntent
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor.ConfigBool
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor.ConfigCustom
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor.ConfigEnum
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor.ConfigEnumList
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor.ConfigExternal
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor.ConfigInt
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor.ConfigKey
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor.ConfigList
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor.ConfigString
import org.fcitx.fcitx5.android.utils.config.ConfigType
import org.fcitx.fcitx5.android.utils.loadingSpinner
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import org.fcitx.fcitx5.android.utils.parcelableArray
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber

object PreferenceScreenFactory {

    private val hideKeyConfig by AppPrefs.getInstance().advanced.hideKeyConfig

    fun create(
        preferenceManager: PreferenceManager,
        fragmentManager: FragmentManager,
        raw: RawConfig,
        save: () -> Unit
    ): PreferenceScreen {
        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)
        val cfg = raw["cfg"]
        val desc = raw["desc"]
        val store = FcitxRawConfigStore(cfg)
        // TODO: needs some error handling
        val topLevelDesc = ConfigDescriptor.parseTopLevel(desc).getOrElse { throw it }
        screen.title = topLevelDesc.name
        topLevelDesc.values.forEach {
            general(context, fragmentManager, cfg.findByName(it.name), screen, it, store, save)
        }
        return screen
    }

    private fun general(
        context: Context,
        fragmentManager: FragmentManager,
        cfg: RawConfig?,
        screen: PreferenceScreen,
        descriptor: ConfigDescriptor<*, *>,
        store: PreferenceDataStore,
        save: () -> Unit
    ) {

        // Hide key related configs
        if (hideKeyConfig && ConfigType.pretty(descriptor.ty).contains("Key")) {
            return
        }

        if (descriptor is ConfigCustom) {
            custom(context, fragmentManager, cfg, screen, descriptor, save)
            return
        }

        fun stubPreference() = Preference(context).apply {
            summary =
                "${context.getString(R.string.unimplemented_type)} '${ConfigType.pretty(descriptor.ty)}'"
        }

        fun <T : Any> navigate(route: T): Boolean {
            return try {
                fragmentManager.primaryNavigationFragment!!.navigateWithAnim(route)
                true
            } catch (e: Exception) {
                Timber.w("Unable to navigate(route=$route): $e")
                false
            }
        }

        fun pinyinDictionary() = Preference(context).apply {
            setOnPreferenceClickListener {
                navigate(SettingsRoute.PinyinDict(""))
            }
        }

        fun punctuationEditor(title: String, lang: String?) = Preference(context).apply {
            setOnPreferenceClickListener {
                navigate(SettingsRoute.Punctuation(title, lang))
            }
        }

        fun quickPhraseEditor() = Preference(context).apply {
            setOnPreferenceClickListener {
                navigate(SettingsRoute.QuickPhraseList)
            }
        }

        fun tableInputMethod() = Preference(context).apply {
            setOnPreferenceClickListener {
                navigate(SettingsRoute.TableInputMethods)
            }
        }

        fun pinyinCustomPhrase() = Preference(context).apply {
            setOnPreferenceClickListener {
                navigate(SettingsRoute.PinyinCustomPhrase)
            }
        }

        fun rimeUserDataDir(title: String): Preference = LongClickPreference(context).apply {
            val pref = AppPrefs.getInstance().internal.rimeUserDataDir

            fun updateSummary() {
                summary = pref.getValue().ifEmpty {
                    context.getString(R.string.rime_user_data_dir_default)
                }
            }

            fun hasRecentActivity(dir: File, t0: Long): Boolean = runCatching {
                dir.walkTopDown().any { it.isFile && it.lastModified() >= t0 }
            }.getOrDefault(false)

            // Loading the rime addon makes RimeEngine construct itself, which
            // starts librime maintenance (deploy) automatically in the new dir.
            fun showRedeployDialog(path: String) {
                val activity = context as? ComponentActivity ?: return
                val target = if (path.isEmpty())
                    File(activity.getExternalFilesDir(null) ?: activity.filesDir, "data/rime")
                else File(path)
                val t0 = System.currentTimeMillis()
                val conn = FcitxDaemon.getFirstConnectionOrNull()
                if (conn != null) {
                    activity.lifecycleScope.launch {
                        runCatching { conn.runOnReady { getAddonConfig("rime") } }
                    }
                }

                val dp = activity.resources.displayMetrics.density
                val text = TextView(activity).apply {
                    setText(R.string.rime_deploying)
                    setPadding((12 * dp).toInt(), 0, 0, 0)
                }
                val layout = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding((24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt())
                    addView(loadingSpinner(activity))
                    addView(text)
                }
                val dialog = AlertDialog.Builder(context)
                    .setTitle(R.string.rime_redeploy_title)
                    .setView(layout)
                    .setCancelable(false)
                    .show()

                activity.lifecycleScope.launch {
                    // librime touches user.yaml at the end of every maintenance
                    // (full deploy or no-op); build/default.yaml only when rebuilt.
                    val userYaml = File(target, "user.yaml")
                    val defaultYaml = File(target, "build/default.yaml")
                    var elapsed = 0L
                    while (elapsed < 600_000L) {
                        delay(1000)
                        elapsed += 1000
                        if (activity.isFinishing || activity.isDestroyed) {
                            dialog.dismiss()
                            return@launch
                        }
                        if (userYaml.exists() && userYaml.lastModified() >= t0) {
                            text.setText(
                                if (defaultYaml.exists() && defaultYaml.lastModified() >= t0)
                                    R.string.rime_deploy_done
                                else
                                    R.string.rime_deploy_uptodate
                            )
                            delay(1500)
                            dialog.dismiss()
                            return@launch
                        }
                        // maintenance never started writing after a while: nothing to do
                        if (elapsed > 30_000 && !hasRecentActivity(target, t0)) {
                            text.setText(R.string.rime_deploy_uptodate)
                            delay(1500)
                            dialog.dismiss()
                            return@launch
                        }
                    }
                    text.setText(R.string.rime_deploy_timeout)
                    dialog.setCancelable(true)
                }
            }

            fun restartWithDir(path: String) {
                pref.setValue(path)
                updateSummary()
                FcitxDaemon.restartFcitx()
                showRedeployDialog(path)
            }

            // Only trees on the primary shared storage map to a real POSIX path
            // that librime (native) can use.
            fun treeUriToRealPath(uri: Uri): String? {
                if (uri.host != "com.android.externalstorage.documents") return null
                val docId = uri.pathSegments.getOrNull(1) ?: return null
                val parts = docId.split(":", limit = 2)
                if (parts.size != 2 || parts[0] != "primary") return null
                return File(Environment.getExternalStorageDirectory(), parts[1]).absolutePath
            }

            fun launchTreePicker(activity: ComponentActivity) {
                lateinit var picker: ActivityResultLauncher<Uri?>
                picker = activity.activityResultRegistry.register(
                    "rimePickDir", ActivityResultContracts.OpenDocumentTree()
                ) { uri: Uri? ->
                    picker.unregister()
                    if (uri == null) return@register
                    val real = treeUriToRealPath(uri)
                    if (real == null) {
                        context.toast(R.string.rime_user_data_dir_unsupported)
                    } else {
                        runCatching {
                            activity.contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            )
                        }
                        restartWithDir(real)
                    }
                }
                picker.launch(null)
            }

            fun pickDir() {
                val activity = context as? ComponentActivity ?: return
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    context.toast(R.string.rime_user_data_dir_need_android_11)
                    return
                }
                if (Environment.isExternalStorageManager()) {
                    launchTreePicker(activity)
                } else {
                    context.toast(R.string.rime_user_data_dir_need_permission)
                    lateinit var permLauncher: ActivityResultLauncher<Intent>
                    permLauncher = activity.activityResultRegistry.register(
                        "rimeManageStorage", ActivityResultContracts.StartActivityForResult()
                    ) {
                        permLauncher.unregister()
                        if (Environment.isExternalStorageManager()) launchTreePicker(activity)
                    }
                    permLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            .setData(Uri.parse("package:${activity.packageName}"))
                    )
                }
            }

            fun openCurrentDir() {
                try {
                    val custom = pref.getValue()
                    if (custom.isEmpty()) {
                        context.startActivity(buildDocumentsProviderIntent())
                    } else {
                        val rel = custom.removePrefix(
                            Environment.getExternalStorageDirectory().absolutePath + "/"
                        )
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                DocumentsContract.buildTreeDocumentUri(
                                    "com.android.externalstorage.documents",
                                    "primary:$rel"
                                )
                            )
                        )
                    }
                } catch (e: Exception) {
                    context.toast(e)
                }
            }

            updateSummary()
            setOnPreferenceClickListener {
                AlertDialog.Builder(context)
                    .setTitle(title)
                    .setItems(
                        arrayOf(
                            context.getString(R.string.rime_user_data_dir_change),
                            context.getString(R.string.rime_user_data_dir_reset),
                            context.getString(R.string.rime_user_data_dir_open)
                        )
                    ) { _, which ->
                        when (which) {
                            0 -> pickDir()
                            1 -> if (pref.getValue().isNotEmpty()) restartWithDir("")
                            2 -> openCurrentDir()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }

            // keep the original hidden entry to the default dir
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setOnPreferenceLongClickListener {
                    try {
                        context.startActivity(buildPrimaryStorageIntent("data/rime"))
                    } catch (e: Exception) {
                        context.toast(e)
                    }
                }
            }
        }

        fun rimeWebDavSync(title: String): Preference = Preference(context).apply {
            this.title = context.getString(R.string.rime_webdav_config_entry)
            summary = context.getString(R.string.rime_webdav_config_entry_summary)
            setOnPreferenceClickListener {
                navigate(SettingsRoute.RimeWebDav)
                true
            }
        }

        fun listPreference(subtype: ConfigType<*>): Preference = object : Preference(context) {
            override fun onClick() {
                navigate(SettingsRoute.ListConfig(cfg ?: RawConfig(), descriptor))
                fragmentManager.setFragmentResultListener(
                    descriptor.name,
                    fragmentManager.primaryNavigationFragment!!
                ) { _, v ->
                    cfg?.subItems = v.parcelableArray(descriptor.name)
                    if (callChangeListener(null)) {
                        notifyChanged()
                    }
                }
            }
        }.apply {
            if (subtype == ConfigType.TyKey) {
                summaryProvider = SummaryProvider<Preference> {
                    val keys = cfg?.subItems?.joinToString("\n") {
                        Key.parse(it.value).localizedString
                    }
                    if (keys.isNullOrEmpty()) context.getString(R.string.none) else keys
                }
            }
        }

        fun addonConfigPreference(addon: String) = Preference(context).apply {
            setOnPreferenceClickListener {
                navigate(
                    SettingsRoute.AddonConfig(descriptor.description ?: descriptor.name, addon)
                )
            }
        }

        when (descriptor) {
            is ConfigBool -> MySwitchPreference(context).apply {
                summary = descriptor.tooltip
                setDefaultValue(descriptor.defaultValue)
            }
            is ConfigEnum -> ListPreference(context).apply {
                entries = (descriptor.entriesI18n ?: descriptor.entries).toTypedArray()
                entryValues = descriptor.entries.toTypedArray()
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                setDefaultValue(descriptor.defaultValue)
            }
            is ConfigEnumList -> listPreference(ConfigType.TyEnum)
            is ConfigExternal -> when (descriptor.knownType) {
                ConfigExternal.ETy.PinyinDict -> pinyinDictionary()
                ConfigExternal.ETy.Punctuation -> punctuationEditor(
                    descriptor.description ?: descriptor.name,
                    // fcitx://config/addon/punctuation/punctuationmap/zh_CN
                    descriptor.uri?.substringAfterLast('/')
                )
                ConfigExternal.ETy.QuickPhrase -> quickPhraseEditor()
                ConfigExternal.ETy.Chttrans -> addonConfigPreference("chttrans")
                ConfigExternal.ETy.TableGlobal -> addonConfigPreference("table")
                ConfigExternal.ETy.AndroidTable -> tableInputMethod()
                ConfigExternal.ETy.PinyinCustomPhrase -> pinyinCustomPhrase()
                ConfigExternal.ETy.RimeUserDataDir -> rimeUserDataDir(
                    descriptor.description ?: descriptor.name
                )
                ConfigExternal.ETy.RimeWebDavSync -> rimeWebDavSync(
                    descriptor.description ?: descriptor.name
                )
                else -> stubPreference()
            }
            is ConfigInt -> {
                val min = descriptor.intMin
                val max = descriptor.intMax
                if (min != null && max != null && max - min <= 100) {
                    DialogSeekBarPreference(context).apply {
                        summaryProvider = DialogSeekBarPreference.SimpleSummaryProvider
                        descriptor.defaultValue?.let { setDefaultValue(it) }
                        this.min = min
                        this.max = max
                    }
                } else {
                    EditTextIntPreference(context).apply {
                        summaryProvider = EditTextIntPreference.SimpleSummaryProvider
                        descriptor.defaultValue?.let { setDefaultValue(it) }
                        min?.let { this.min = it }
                        max?.let { this.max = it }
                    }
                }
            }
            is ConfigKey -> FcitxKeyPreference(context).apply {
                summaryProvider = FcitxKeyPreference.SimpleSummaryProvider
                descriptor.defaultValue?.let { setDefaultValue(it) }
            }
            is ConfigList -> if (descriptor.ty.subtype in ListFragment.supportedSubtypes)
                listPreference(descriptor.ty.subtype)
            else
                stubPreference()
            is ConfigString -> EditTextPreference(context).apply {
                summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
                setDefaultValue(descriptor.defaultValue)
            }
            is ConfigCustom -> throw IllegalAccessException("Impossible!")
        }.apply {
            key = descriptor.name
            title = descriptor.description ?: descriptor.name
            isSingleLineTitle = false
            isIconSpaceReserved = false
            preferenceDataStore = store
            if (this is DialogPreference) {
                dialogTitle = title
                dialogMessage = descriptor.tooltip
            }
            setOnPreferenceChangeListener { _, _ ->
                // setOnPreferenceChangeListener runs before preferenceDataStore was updated,
                // post to save() to make sure store has been updated (hopefully)
                ContextCompat.getMainExecutor(context).execute {
                    save()
                }
                true
            }
            screen.addPreference(this)
        }
    }

    private fun custom(
        context: Context,
        fragmentManager: FragmentManager,
        cfg: RawConfig?,
        screen: PreferenceScreen,
        descriptor: ConfigCustom,
        save: () -> Unit
    ) {
        val subStore = FcitxRawConfigStore(cfg ?: RawConfig())
        val subPref = PreferenceCategory(context).apply {
            key = descriptor.name
            title = descriptor.description ?: descriptor.name
            isSingleLineTitle = false
            isIconSpaceReserved = false
        }
        screen.addPreference(subPref)
        descriptor.customTypeDef?.values?.forEach {
            general(context, fragmentManager, cfg?.findByName(it.name), screen, it, subStore, save)
        }
    }

}