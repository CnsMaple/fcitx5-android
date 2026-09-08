/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

enum class EnterLongPressBehavior(override val stringRes: Int) : ManagedPreferenceEnum {
    None(R.string.enter_behavior_none),
    Emoji(R.string.enter_behavior_emoji),
    Newline(R.string.enter_behavior_newline),
    Send(R.string.enter_behavior_send);
}
