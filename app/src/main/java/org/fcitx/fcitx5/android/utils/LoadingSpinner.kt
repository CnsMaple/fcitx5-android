package org.fcitx.fcitx5.android.utils

import android.animation.ObjectAnimator
import android.app.Activity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import org.fcitx.fcitx5.android.R
import kotlin.math.roundToInt

/**
 * A smooth indeterminate spinner: a hardware-layer rotation property animation
 * driven by the RenderThread, so it stays fluid even when the main thread is busy
 * (rime deploy / WebDAV sync). The default indeterminate ProgressBar animates by
 * invalidating a Drawable on the main thread, which stutters/jitters under load.
 */
fun loadingSpinner(activity: Activity): View {
    val size = (24 * activity.resources.displayMetrics.density).roundToInt()
    val color = 0xFF757575.toInt()
    val iv = ImageView(activity).apply {
        setImageResource(R.drawable.ic_spinner_ring)
        imageTintList = android.content.res.ColorStateList.valueOf(color)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        layoutParams = LinearLayout.LayoutParams(size, size)
    }
    iv.post {
        ObjectAnimator.ofFloat(iv, View.ROTATION, 0f, 360f).apply {
            duration = 900
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }
    return iv
}
