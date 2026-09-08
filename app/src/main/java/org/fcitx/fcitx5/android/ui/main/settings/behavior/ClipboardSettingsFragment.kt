/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.navigateWithAnim

class ClipboardSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().clipboard) {

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        val ctx = preferenceManager.context
        screen.addPreference(Preference(ctx).apply {
            key = "clip_sync_entry"
            title = ctx.getString(R.string.clip_sync_title)
            summary = ctx.getString(R.string.clip_sync_summary)
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                navigateWithAnim(SettingsRoute.ClipSync)
                true
            }
        })
    }
}
