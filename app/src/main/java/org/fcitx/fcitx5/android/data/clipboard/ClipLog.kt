package org.fcitx.fcitx5.android.data.clipboard

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ring buffer of clip-sync events, surfaced for troubleshooting. */
object ClipLog {
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun log(tag: String, msg: String) {
        lines.addLast("${fmt.format(Date())} [$tag] $msg")
        while (lines.size > 80) lines.removeFirst()
    }

    @Synchronized
    fun dump(): String =
        lines.joinToString("\n").ifEmpty { "(no clip-sync activity recorded yet)" }
}
