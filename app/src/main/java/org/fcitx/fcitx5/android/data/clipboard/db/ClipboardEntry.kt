/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard.db

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.os.Build
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import org.fcitx.fcitx5.android.utils.timestamp
import java.io.File
import java.security.MessageDigest

@Entity(tableName = ClipboardEntry.TABLE_NAME)
data class ClipboardEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val text: String,
    val pinned: Boolean = false,
    @ColumnInfo(defaultValue = "-1")
    val timestamp: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = ClipDescription.MIMETYPE_TEXT_PLAIN)
    val type: String = ClipDescription.MIMETYPE_TEXT_PLAIN,
    @ColumnInfo(defaultValue = "0")
    val deleted: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val sensitive: Boolean = false,
    @ColumnInfo(defaultValue = "")
    val uri: String = ""
) {
    // computed (no backing field) so Room ignores it
    val isImage: Boolean get() = uri.isNotEmpty() && type.startsWith("image/")

    companion object {
        const val BULLET = "•"

        const val TABLE_NAME = "clipboard"

        private val IS_SENSITIVE = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ClipDescription.EXTRA_IS_SENSITIVE
        } else {
            "android.content.extra.IS_SENSITIVE"
        }

        fun fromClipData(
            clipData: ClipData,
            transformer: ((String) -> String)? = null
        ): ClipboardEntry? {
            val desc = clipData.description
            // TODO: handle multiple items (when does this happen?)
            val item = clipData.getItemAt(0) ?: return null
            val clipUri = item.uri
            val sensitive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                desc.extras?.getBoolean(IS_SENSITIVE) ?: false
            } else {
                false
            }
            val text: String
            val uriStr: String
            if (clipUri != null) {
                val name = clipUri.lastPathSegment?.substringAfterLast('/') ?: ""
                text = if (desc.hasMimeType("image/*")) "图片 $name" else "文件 $name"
                uriStr = clipUri.toString()
            } else {
                val t = item.text?.toString() ?: return null
                text = if (transformer != null) transformer(t) else t
                uriStr = ""
            }
            return ClipboardEntry(
                text = text,
                timestamp = clipData.timestamp(),
                type = desc.getMimeType(0),
                sensitive = sensitive,
                uri = uriStr
            )
        }

        // locally cached thumbnail for a media clip, keyed by its original uri
        fun thumbFile(context: Context, uri: String): File {
            val h = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }
            return File(context.filesDir, "clip_thumbs/$h.png")
        }
    }
}