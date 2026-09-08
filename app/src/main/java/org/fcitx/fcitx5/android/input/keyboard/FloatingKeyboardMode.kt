/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

enum class FloatingKeyboardMode(override val stringRes: Int) : ManagedPreferenceEnum {
    Off(R.string.floating_keyboard_off),
    Landscape(R.string.floating_keyboard_landscape),
    Always(R.string.floating_keyboard_always);
}
