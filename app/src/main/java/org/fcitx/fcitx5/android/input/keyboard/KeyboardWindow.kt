/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.transition.Slide
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.voice.WaveformView
import org.fcitx.fcitx5.android.input.wm.EssentialWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent

class KeyboardWindow : InputWindow.SimpleInputWindow<KeyboardWindow>(), EssentialWindow,
    InputBroadcastReceiver {

    private val service by manager.inputMethodService()
    private val fcitx by manager.fcitx()
    private val theme by manager.theme()
    private val commonKeyActionListener: CommonKeyActionListener by manager.must()
    private val windowManager: InputWindowManager by manager.must()
    private val popup: PopupComponent by manager.must()
    private val bar: KawaiiBarComponent by manager.must()
    private val returnKeyDrawable: ReturnKeyDrawableComponent by manager.must()

    companion object : EssentialWindow.Key

    override val key: EssentialWindow.Key
        get() = KeyboardWindow

    override fun enterAnimation(lastWindow: InputWindow) = Slide().apply {
        slideEdge = Gravity.BOTTOM
    }.takeIf {
        // disable animation switching between picker
        lastWindow !is PickerWindow
    }

    override fun exitAnimation(nextWindow: InputWindow) =
        super.exitAnimation(nextWindow).takeIf {
            // disable animation switching between picker
            nextWindow !is PickerWindow
        }

    private lateinit var keyboardView: FrameLayout

    private var voiceOverlay: View? = null
    private var voiceWave: WaveformView? = null

    private val keyboards: HashMap<String, BaseKeyboard> by lazy {
        hashMapOf(
            TextKeyboard.Name to TextKeyboard(context, theme),
            NumberKeyboard.Name to NumberKeyboard(context, theme)
        )
    }
    private var currentKeyboardName = ""
    private var lastSymbolType: String by AppPrefs.getInstance().internal.lastSymbolLayout

    private val currentKeyboard: BaseKeyboard? get() = keyboards[currentKeyboardName]

    private val keyActionListener = KeyActionListener { it, source ->
        if (it is KeyAction.LayoutSwitchAction) {
            switchLayout(it.act)
        } else {
            commonKeyActionListener.listener.onKeyAction(it, source)
        }
    }

    private val popupActionListener: PopupActionListener by lazy {
        popup.listener
    }

    // This will be called EXACTLY ONCE
    override fun onCreateView(): View {
        keyboardView = context.frameLayout(R.id.keyboard_view)
        attachLayout(TextKeyboard.Name)
        return keyboardView
    }

    private fun detachCurrentLayout() {
        currentKeyboard?.also {
            it.onDetach()
            keyboardView.removeView(it)
            it.keyActionListener = null
            it.popupActionListener = null
        }
    }

    private fun attachLayout(target: String) {
        currentKeyboardName = target
        currentKeyboard?.let {
            it.keyActionListener = keyActionListener
            it.popupActionListener = popupActionListener
            keyboardView.apply { add(it, lParams(matchParent, matchParent)) }
            it.onAttach()
            it.onReturnDrawableUpdate(returnKeyDrawable.resourceId)
            it.onInputMethodUpdate(fcitx.runImmediately { inputMethodEntryCached })
        }
    }

    fun switchLayout(to: String, remember: Boolean = true) {
        val target = to.ifEmpty { lastSymbolType }
        ContextCompat.getMainExecutor(service).execute {
            if (keyboards.containsKey(target)) {
                if (remember && target != TextKeyboard.Name) {
                    lastSymbolType = target
                }
                if (target == currentKeyboardName) return@execute
                detachCurrentLayout()
                attachLayout(target)
                if (windowManager.isAttached(this)) {
                    notifyBarLayoutChanged()
                }
            } else {
                if (remember) {
                    lastSymbolType = PickerWindow.Key.Symbol.name
                }
                windowManager.attachWindow(PickerWindow.Key.Symbol)
            }
        }
    }

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        val targetLayout = when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER -> NumberKeyboard.Name
            InputType.TYPE_CLASS_PHONE -> NumberKeyboard.Name
            else -> TextKeyboard.Name
        }
        switchLayout(targetLayout, remember = false)
    }

    override fun onImeUpdate(ime: InputMethodEntry) {
        currentKeyboard?.onInputMethodUpdate(ime)
    }

    override fun onPunctuationUpdate(mapping: Map<String, String>) {
        currentKeyboard?.onPunctuationUpdate(mapping)
    }

    override fun onReturnKeyDrawableUpdate(resourceId: Int) {
        currentKeyboard?.onReturnDrawableUpdate(resourceId)
    }

    override fun onAttached() {
        currentKeyboard?.let {
            it.keyActionListener = keyActionListener
            it.popupActionListener = popupActionListener
            it.onAttach()
        }
        notifyBarLayoutChanged()
    }

    override fun onDetached() {
        hideVoiceOverlay()
        currentKeyboard?.let {
            it.onDetach()
            it.keyActionListener = null
            it.popupActionListener = null
        }
        popup.dismissAll()
    }

    // Call this when
    // 1) the keyboard window was newly attached
    // 2) currently keyboard window is attached and switchLayout was used
    private fun notifyBarLayoutChanged() {
        bar.onKeyboardLayoutSwitched(currentKeyboardName == NumberKeyboard.Name)
    }

    /**
     * Voice recording overlay: covers the keyboard area with a waveform panel.
     * Non-clickable/non-focusable so the space key still receives its up event
     * to end the session. Slides in from the bottom, matching layout switches.
     */
    fun showVoiceOverlay() {
        if (voiceOverlay != null) return
        val bgColor = if (theme is org.fcitx.fcitx5.android.data.theme.Theme.Builtin)
            theme.keyboardColor
        else
            theme.backgroundColor
        val overlay = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(matchParent, matchParent)
            setBackgroundColor(bgColor)
            // clickable so new taps land on the overlay instead of falling through to the
            // keys beneath it; the space key's own gesture (down before this overlay existed)
            // still routes its up event to the space key to end the session.
            isClickable = true
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val wave = WaveformView(context).apply {
            // keep the waveform visible on both light and dark themes
            val candidateColors = listOf(
                theme.genericActiveForegroundColor,
                theme.accentKeyBackgroundColor,
                theme.keyTextColor,
            )
            val lineColor = candidateColors.firstOrNull {
                ColorUtils.calculateContrast(it, bgColor) >= 2.5
            } ?: theme.genericActiveForegroundColor
            setWaveformColor(lineColor)
            visibility = View.INVISIBLE
        }
        overlay.addView(
            wave,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        beginOverlayTransition(overlay)
        keyboardView.addView(overlay)
        voiceOverlay = overlay
        voiceWave = wave
    }

    fun startVoiceOverlayWave() {
        voiceWave?.let {
            it.visibility = View.VISIBLE
            it.start()
        }
    }

    fun updateVoiceOverlayAmplitude(amplitude: Float) {
        voiceWave?.updateAmplitude(amplitude)
    }

    fun hideVoiceOverlay() {
        val overlay = voiceOverlay ?: return
        runCatching { voiceWave?.stop() }
        beginOverlayTransition(overlay)
        keyboardView.removeView(overlay)
        voiceOverlay = null
        voiceWave = null
    }

    // ---- clipboard/rime sync progress overlay (with cancel) ----
    private var syncOverlay: View? = null
    private var syncBar: android.widget.ProgressBar? = null
    private var syncText: android.widget.TextView? = null

    fun showSyncProgress(label: String, onCancel: () -> Unit) {
        if (syncOverlay != null) return
        val bgColor = if (theme is org.fcitx.fcitx5.android.data.theme.Theme.Builtin)
            theme.keyboardColor else theme.backgroundColor
        val overlay = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(matchParent, matchParent)
            setBackgroundColor(bgColor)
        }
        val box = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            val p = (resources.displayMetrics.density * 24).toInt()
            setPadding(p, p, p, p)
        }
        syncText = android.widget.TextView(context).apply {
            text = label
            setTextColor(theme.keyTextColor)
            textSize = 14f
            maxLines = 1
            gravity = android.view.Gravity.CENTER
        }
        box.addView(syncText)
        syncBar = android.widget.ProgressBar(
            context, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
            val lp = android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            val p = (resources.displayMetrics.density * 12).toInt()
            lp.topMargin = p
            layoutParams = lp
        }
        box.addView(syncBar)
        box.addView(android.widget.Button(context).apply {
            text = context.getString(R.string.clip_sync_cancel)
            setTextColor(theme.keyTextColor)
            setOnClickListener { onCancel() }
        })
        val boxLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.CENTER
        )
        val side = (context.resources.displayMetrics.density * 24).toInt()
        boxLp.leftMargin = side
        boxLp.rightMargin = side
        overlay.addView(box, boxLp)
        beginOverlayTransition(overlay)
        keyboardView.addView(overlay)
        syncOverlay = overlay
    }

    fun updateSyncProgress(done: Long, total: Long) {
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        syncBar?.progress = pct
        val mb = 1024.0 * 1024.0
        val d = "%.1f".format(done / mb)
        syncText?.text = if (total > 0) "下载中 $pct% · $d/${"%.1f".format(total / mb)} MB"
            else "下载中 · $d MB"
    }

    fun hideSyncProgress() {
        val overlay = syncOverlay ?: return
        beginOverlayTransition(overlay)
        keyboardView.removeView(overlay)
        syncOverlay = null
        syncBar = null
        syncText = null
    }

    // ---- clip-sync image/file action panel (full-screen overlay, cancel on its own row) ----
    private var clipPanel: View? = null

    private fun actionButton(label: String, onClick: () -> Unit) =
        android.widget.Button(context).apply {
            text = label
            setTextColor(theme.keyTextColor)
            setOnClickListener { onClick() }
        }

    fun showClipActionPanel(
        bitmap: android.graphics.Bitmap?, isImage: Boolean,
        onCopy: () -> Unit, onSend: () -> Unit, onShare: () -> Unit, onCancel: () -> Unit,
    ) {
        if (clipPanel != null) return
        val bgColor = if (theme is org.fcitx.fcitx5.android.data.theme.Theme.Builtin)
            theme.keyboardColor else theme.backgroundColor
        val overlay = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(matchParent, matchParent)
            setBackgroundColor(bgColor)
        }
        val box = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val p = (resources.displayMetrics.density * 16).toInt()
            setPadding(p, p, p, p)
        }
        if (bitmap != null) {
            val iv = android.widget.ImageView(context).apply {
                setImageBitmap(bitmap)
                adjustViewBounds = true
                val max = (resources.displayMetrics.density * 120).toInt()
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, max
                )
            }
            box.addView(iv)
        }
        val row1 = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        if (isImage) {
            row1.addView(actionButton(context.getString(R.string.clip_action_copy), onCopy))
            row1.addView(actionButton(context.getString(R.string.clip_action_send), onSend))
        }
        row1.addView(actionButton(context.getString(R.string.clip_action_share), onShare))
        box.addView(row1)
        box.addView(actionButton(context.getString(R.string.clip_sync_cancel), onCancel))
        overlay.addView(
            box,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        beginOverlayTransition(overlay)
        keyboardView.addView(overlay)
        clipPanel = overlay
    }

    fun hideClipActionPanel() {
        val panel = clipPanel ?: return
        keyboardView.removeView(panel)
        clipPanel = null
    }

    /** Full-screen preview of a clipboard image with Send / Cancel stacked on two rows. */
    fun showImagePreviewPanel(
        bitmap: android.graphics.Bitmap?,
        onSend: () -> Unit, onCancel: () -> Unit,
    ) {
        if (clipPanel != null) return
        val bgColor = if (theme is org.fcitx.fcitx5.android.data.theme.Theme.Builtin)
            theme.keyboardColor else theme.backgroundColor
        val overlay = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(matchParent, matchParent)
            setBackgroundColor(bgColor)
        }
        val box = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val p = (resources.displayMetrics.density * 16).toInt()
            setPadding(p, p, p, p)
        }
        if (bitmap != null) {
            box.addView(android.widget.ImageView(context).apply {
                setImageBitmap(bitmap)
                adjustViewBounds = true
                val max = (resources.displayMetrics.density * 140).toInt()
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, max
                )
            })
        }
        box.addView(actionButton(context.getString(R.string.clip_action_send), onSend))
        box.addView(actionButton(context.getString(R.string.clip_sync_cancel), onCancel))
        overlay.addView(
            box,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        beginOverlayTransition(overlay)
        keyboardView.addView(overlay)
        clipPanel = overlay
    }

    private fun beginOverlayTransition(target: View) {
        val ts = TransitionSet().apply {
            addTransition(Slide(Gravity.BOTTOM).apply { addTarget(target) })
            duration = 100
        }
        TransitionManager.beginDelayedTransition(keyboardView, ts)
    }
}