package org.fcitx.fcitx5.android.plugin.cloudvoice

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tiny ring buffer of session events, surfaced to the host app for troubleshooting. */
object SessionLog {

    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun log(tag: String, msg: String) {
        lines.addLast("${fmt.format(Date())} [$tag] $msg")
        while (lines.size > 80) lines.removeFirst()
    }

    @Synchronized
    fun dump(): String =
        lines.joinToString("\n").ifEmpty { "(no session activity recorded yet)" }
}
