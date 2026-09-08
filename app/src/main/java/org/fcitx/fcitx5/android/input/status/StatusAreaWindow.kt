/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.status

import android.os.Build
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.Action
import org.fcitx.fcitx5.android.core.SubtypeManager
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.editorinfo.EditorInfoWindow
import org.fcitx.fcitx5.android.input.keyboard.FloatingKeyboardMode
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.ClipSync
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.FloatingKeyboard
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.InputMethod
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.Keyboard
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.ReloadConfig
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.RimeWebDav
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.ThemeList
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.data.clipboard.ClipSyncClient
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.DeviceUtil
import org.fcitx.fcitx5.android.utils.rimeBackup
import org.fcitx.fcitx5.android.utils.rimePlainSync
import org.fcitx.fcitx5.android.utils.rimePull
import org.fcitx.fcitx5.android.utils.rimePush
import org.fcitx.fcitx5.android.utils.rimeRestore
import org.fcitx.fcitx5.android.utils.rimeSyncWithFallback
import org.fcitx.fcitx5.android.utils.alpha
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.backgroundColor
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.recyclerview.recyclerView
import splitties.views.recyclerview.gridLayoutManager

class StatusAreaWindow : InputWindow.ExtendedInputWindow<StatusAreaWindow>(),
    InputBroadcastReceiver {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val fcitx: FcitxConnection by manager.fcitx()
    private val theme by manager.theme()
    private val windowManager: InputWindowManager by manager.must()

    private val editorInfoInspector by AppPrefs.getInstance().internal.editorInfoInspector

    private val staticEntries by lazy {
        val base = arrayOf(
            StatusAreaEntry.Android(
                context.getString(R.string.theme),
                R.drawable.ic_baseline_palette_24,
                ThemeList
            ),
            StatusAreaEntry.Android(
                context.getString(R.string.input_method_options),
                R.drawable.ic_baseline_language_24,
                InputMethod
            ),
            StatusAreaEntry.Android(
                context.getString(R.string.reload_config),
                R.drawable.ic_baseline_sync_24,
                ReloadConfig
            ),
            StatusAreaEntry.Android(
                context.getString(R.string.virtual_keyboard),
                R.drawable.ic_baseline_keyboard_24,
                Keyboard
            ),
            StatusAreaEntry.Android(
                context.getString(R.string.rime_webdav_title),
                R.drawable.ic_baseline_cloud_24,
                RimeWebDav
            ),
            StatusAreaEntry.Android(
                context.getString(R.string.floating_keyboard),
                R.drawable.ic_floating_keyboard_24,
                FloatingKeyboard
            )
        )
        // clipboard sync is built into the app now, so the entry is always available
        base + StatusAreaEntry.Android(
            context.getString(R.string.clip_sync_title),
            R.drawable.ic_clipboard,
            ClipSync
        )
    }

    private fun activateAction(action: Action) {
        // rime's toolbar "Synchronize" -> full WebDAV pull/sync/push when configured
        if (action.name == "fcitx-rime-sync") {
            rimeSyncWithFallback(context)
            return
        }
        fcitx.launchOnReady {
            it.activateAction(action.id)
        }
    }

    private fun popup(anchor: View, items: List<Pair<String, () -> Unit>>) {
        val popup = PopupMenu(context, anchor)
        items.forEachIndexed { i, (label, action) ->
            popup.menu.add(0, i, i, label).setOnMenuItemClickListener { action(); true }
        }
        popupMenu?.dismiss()
        popupMenu = popup
        popup.show()
    }

    private fun clip(action: (ClipSyncClient) -> Unit) {
        // close the status-area grid so the keyboard (and its progress overlay) shows immediately
        windowManager.attachWindow(KeyboardWindow)
        val c = ClipSyncClient.current
        if (c == null) Toast.makeText(context, R.string.clip_sync_not_installed, Toast.LENGTH_SHORT).show()
        else action(c)
    }

    private fun showClipSyncPopup(anchor: View) = popup(anchor, listOf(
        context.getString(R.string.clip_sync_upload_clipboard) to { clip { it.pushCurrentClip(notify = true) } },
        context.getString(R.string.clip_sync_upload_file) to { clip { ClipSyncClient.pick(context, "File") } },
        context.getString(R.string.clip_sync_upload_image) to { clip { ClipSyncClient.pick(context, "Image") } },
        context.getString(R.string.clip_sync_download) to { clip { it.pullOnce(notify = true, force = true) } },
    ))

    private fun showRimeWebDavPopup(anchor: View) = popup(anchor, listOf(
        context.getString(R.string.rime_backup) to { rimeBackup(context) },
        context.getString(R.string.rime_restore) to { rimeRestore(context) },
        context.getString(R.string.rime_push) to { rimePush(context) },
        context.getString(R.string.rime_pull) to { rimePull(context) },
        context.getString(R.string.rime_webdav_plain_sync) to { rimePlainSync(context) },
    ))

    private fun toggleFloatingKeyboard() {
        val pref = AppPrefs.getInstance().keyboard.floatingKeyboardMode
        val enable = pref.getValue() == FloatingKeyboardMode.Off
        pref.setValue(if (enable) FloatingKeyboardMode.Always else FloatingKeyboardMode.Off)
        // close the status-area grid so the keyboard re-lays out with the new mode right away
        windowManager.attachWindow(KeyboardWindow)
        Toast.makeText(
            context,
            if (enable) R.string.floating_keyboard_enabled else R.string.floating_keyboard_disabled,
            Toast.LENGTH_SHORT
        ).show()
    }

    var popupMenu: PopupMenu? = null

    private val adapter: StatusAreaAdapter by lazy {
        object : StatusAreaAdapter() {
            override fun onItemClick(view: View, entry: StatusAreaEntry) {
                when (entry) {
                    is StatusAreaEntry.Fcitx -> {
                        val actions = entry.action.menu
                        if (actions.isNullOrEmpty()) {
                            activateAction(entry.action)
                            return
                        }
                        val popup = PopupMenu(context, view)
                        val menu = popup.menu
                        val hasDivider =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !DeviceUtil.isHMOS && !DeviceUtil.isHonorMagicOS) {
                                menu.setGroupDividerEnabled(true)
                                true
                            } else {
                                false
                            }
                        var groupId = 0 // Menu.NONE; ungrouped
                        actions.forEach {
                            if (it.isSeparator) {
                                if (hasDivider) {
                                    groupId++
                                } else {
                                    val dividerString = buildSpannedString {
                                        color(context.styledColor(android.R.attr.colorForeground).alpha(0.4f)) {
                                            append("──────────")
                                        }
                                    }
                                    menu.add(groupId, 0, 0, dividerString).apply {
                                        isEnabled = false
                                    }
                                }
                            } else {
                                menu.add(groupId, 0, 0, it.shortText).apply {
                                    setOnMenuItemClickListener { _ ->
                                        activateAction(it)
                                        true
                                    }
                                }
                            }
                        }
                        popupMenu?.dismiss()
                        popupMenu = popup
                        popup.show()
                    }
                    is StatusAreaEntry.Android -> when (entry.type) {
                        InputMethod -> fcitx.runImmediately { inputMethodEntryCached }.let {
                            AppUtil.launchMainToInputMethodConfig(
                                context, it.uniqueName, it.displayName
                            )
                        }
                        ReloadConfig -> fcitx.launchOnReady { f ->
                            f.reloadConfig()
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                SubtypeManager.syncWith(f.enabledIme())
                            }
                            service.lifecycleScope.launch {
                                Toast.makeText(service, R.string.done, Toast.LENGTH_SHORT).show()
                            }
                        }
                        Keyboard -> AppUtil.launchMainToKeyboard(context)
                        ThemeList -> AppUtil.launchMainToThemeList(context)
                        ClipSync -> showClipSyncPopup(view)
                        RimeWebDav -> showRimeWebDavPopup(view)
                        FloatingKeyboard -> toggleFloatingKeyboard()
                    }
                }
            }

            override val theme = this@StatusAreaWindow.theme
        }
    }

    private val keyBorder by ThemeManager.prefs.keyBorder

    val view by lazy {
        context.recyclerView {
            if (!keyBorder) {
                backgroundColor = theme.barColor
            }
            layoutManager = gridLayoutManager(4)
            adapter = this@StatusAreaWindow.adapter
        }
    }

    override fun onStatusAreaUpdate(actions: Array<Action>) {
        adapter.entries = arrayOf(
            *staticEntries,
            *Array(actions.size) { StatusAreaEntry.fromAction(actions[it]) }
        )
    }

    override fun onCreateView() = view

    private val editorInfoButton by lazy {
        ToolButton(context, R.drawable.ic_baseline_info_24, theme).apply {
            contentDescription = context.getString(R.string.editor_info_inspector)
            setOnClickListener { windowManager.attachWindow(EditorInfoWindow()) }
        }
    }

    private val settingsButton by lazy {
        ToolButton(context, R.drawable.ic_baseline_settings_24, theme).apply {
            contentDescription = context.getString(R.string.open_input_method_settings)
            setOnClickListener { AppUtil.launchMain(context) }
        }
    }

    private val barExtension by lazy {
        context.horizontalLayout {
            if (editorInfoInspector) {
                add(editorInfoButton, lParams(dp(40), dp(40)))
            }
            add(settingsButton, lParams(dp(40), dp(40)))
        }
    }

    override fun onCreateBarExtension() = barExtension

    override fun onAttached() {
        fcitx.launchOnReady {
            val data = it.statusArea()
            service.lifecycleScope.launch {
                onStatusAreaUpdate(data)
            }
        }
    }

    override fun onDetached() {
        popupMenu?.dismiss()
        popupMenu = null
    }
}
