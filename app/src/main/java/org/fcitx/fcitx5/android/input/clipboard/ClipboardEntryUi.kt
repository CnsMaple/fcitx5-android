/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.text.TextUtils
import android.view.View
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable
import splitties.views.setPaddingDp

class ClipboardEntryUi(override val ctx: Context, private val theme: Theme, radius: Float) : Ui {

    val textView = textView {
        minLines = 1
        maxLines = 4
        textSize = 14f
        setPaddingDp(8, 4, 8, 4)
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(theme.keyTextColor)
    }

    val pin = imageView {
        imageDrawable = drawable(R.drawable.ic_baseline_push_pin_24)!!.apply {
            setTint(theme.altKeyTextColor)
            setAlpha(0.3f)
        }
    }

    val thumb = imageView {
        visibility = View.GONE
        adjustViewBounds = true
        maxHeight = dp(120)
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
    }

    val layout = constraintLayout {
        add(thumb, lParams(matchParent, wrapContent) {
            topOfParent()
            startOfParent()
            endOfParent()
        })
        add(textView, lParams(matchParent, wrapContent) {
            below(thumb)
            startOfParent()
            endOfParent()
        })
        add(pin, lParams(dp(12), dp(12)) {
            bottomOfParent(dp(2))
            endOfParent(dp(2))
        })
    }

    override val root = CustomGestureView(ctx).apply {
        isClickable = true
        minimumHeight = dp(30)
        foreground = RippleDrawable(
            ColorStateList.valueOf(theme.keyPressHighlightColor), null,
            GradientDrawable().apply {
                cornerRadius = radius
                setColor(Color.WHITE)
            }
        )
        background = GradientDrawable().apply {
            cornerRadius = radius
            setColor(theme.clipboardEntryColor)
        }
        add(layout, lParams(matchParent, matchParent))
    }

    fun setEntry(text: String, pinned: Boolean, uri: String = "", isImage: Boolean = false) {
        textView.text = text
        pin.visibility = if (pinned) View.VISIBLE else View.GONE
        val bmp = if (isImage && uri.isNotEmpty()) decodeThumb(Uri.parse(uri)) else null
        if (bmp != null) {
            thumb.setImageBitmap(bmp)
            thumb.visibility = View.VISIBLE
        } else {
            thumb.setImageDrawable(null)
            thumb.visibility = View.GONE
        }
    }

    private fun decodeThumb(uri: Uri): android.graphics.Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 400 || bounds.outHeight / sample > 400) sample *= 2
        ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }.getOrNull()
}