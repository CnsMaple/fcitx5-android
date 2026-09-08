package org.fcitx.fcitx5.android.data.clipboard

import android.content.Context
import android.content.SharedPreferences

/** Clipboard-sync configuration (url/user/pass), edited from the settings page. */
class ClipSettings(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("clipsync", Context.MODE_PRIVATE)

    var url: String
        get() = sp.getString("url", "")!!
        set(v) = sp.edit().putString("url", v).apply()

    var user: String
        get() = sp.getString("user", "")!!
        set(v) = sp.edit().putString("user", v).apply()

    var pass: String
        get() = sp.getString("pass", "")!!
        set(v) = sp.edit().putString("pass", v).apply()

    fun isConfigured(): Boolean = url.isNotBlank()
}
