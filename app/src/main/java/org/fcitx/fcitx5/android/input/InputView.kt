/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcaster
import org.fcitx.fcitx5.android.input.broadcast.PreeditEmptyStateComponent
import org.fcitx.fcitx5.android.input.broadcast.PunctuationComponent
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.FloatingKeyboardMode
import org.fcitx.fcitx5.android.input.keyboard.KeyboardHeightPercentBase.DisplayMetrics
import org.fcitx.fcitx5.android.input.keyboard.KeyboardHeightPercentBase.RealSize
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.picker.emojiPicker
import org.fcitx.fcitx5.android.input.picker.emoticonPicker
import org.fcitx.fcitx5.android.input.picker.symbolPicker
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.preedit.PreeditComponent
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.unset
import org.fcitx.fcitx5.android.utils.windowManager
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.manager.wrapToUniqueComponent
import org.mechdancer.dependency.plusAssign
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.endToStartOf
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.startToEndOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable
import timber.log.Timber

@SuppressLint("ViewConstructor")
class InputView(
    service: FcitxInputMethodService,
    fcitx: FcitxConnection,
    theme: Theme
) : BaseInputView(service, fcitx, theme) {

    private val keyBorder by ThemeManager.prefs.keyBorder

    private val customBackground = imageView {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }

    private val placeholderOnClickListener = OnClickListener { }

    // use clickable view as padding, so MotionEvent can be split to padding view and keyboard view
    private val leftPaddingSpace = view(::View) {
        setOnClickListener(placeholderOnClickListener)
    }
    private val rightPaddingSpace = view(::View) {
        setOnClickListener(placeholderOnClickListener)
    }
    private val bottomPaddingSpace = view(::View) {
        // height as keyboardBottomPadding
        // bottomMargin as WindowInsets (Navigation Bar) offset
        setOnClickListener(placeholderOnClickListener)
    }

    private val scope = DynamicScope()
    private val broadcaster = InputBroadcaster()
    private val popup = PopupComponent()
    private val punctuation = PunctuationComponent()
    private val returnKeyDrawable = ReturnKeyDrawableComponent()
    private val preeditEmptyState = PreeditEmptyStateComponent()
    private val preedit = PreeditComponent()
    private val commonKeyActionListener = CommonKeyActionListener()
    private val windowManager = InputWindowManager()
    private val kawaiiBar = KawaiiBarComponent()
    private val horizontalCandidate = HorizontalCandidateComponent()
    private val keyboardWindow = KeyboardWindow()
    private val symbolPicker = symbolPicker()
    private val emojiPicker = emojiPicker()
    private val emoticonPicker = emoticonPicker()

    private fun setupScope() {
        scope += this@InputView.wrapToUniqueComponent()
        scope += service.wrapToUniqueComponent()
        scope += fcitx.wrapToUniqueComponent()
        scope += theme.wrapToUniqueComponent()
        scope += themedContext.wrapToUniqueComponent()
        scope += broadcaster
        scope += popup
        scope += punctuation
        scope += returnKeyDrawable
        scope += preeditEmptyState
        scope += preedit
        scope += commonKeyActionListener
        scope += windowManager
        scope += kawaiiBar
        scope += horizontalCandidate
        broadcaster.onScopeSetupFinished(scope)
    }

    private val keyboardPrefs = AppPrefs.getInstance().keyboard

    private val focusChangeResetKeyboard by keyboardPrefs.focusChangeResetKeyboard

    private val keyboardHeightPercent = keyboardPrefs.keyboardHeightPercent
    private val keyboardHeightPercentLandscape = keyboardPrefs.keyboardHeightPercentLandscape
    private val keyboardSidePadding = keyboardPrefs.keyboardSidePadding
    private val keyboardSidePaddingLandscape = keyboardPrefs.keyboardSidePaddingLandscape
    private val keyboardBottomPadding = keyboardPrefs.keyboardBottomPadding
    private val keyboardBottomPaddingLandscape = keyboardPrefs.keyboardBottomPaddingLandscape

    private val advancedPrefs = AppPrefs.getInstance().advanced
    private val keyboardHeightPercentBase = advancedPrefs.keyboardHeightPercentBase

    private val floatingKeyboardMode = keyboardPrefs.floatingKeyboardMode
    private val floatingKeyboardWidth = keyboardPrefs.floatingKeyboardWidth
    private val usePortraitSizeInLandscape = keyboardPrefs.usePortraitSizeInLandscape
    private val internalPrefs = AppPrefs.getInstance().internal
    private val floatingKeyboardX = internalPrefs.floatingKeyboardX
    private val floatingKeyboardY = internalPrefs.floatingKeyboardY

    private val keyboardSizePrefs = listOf(
        keyboardHeightPercent,
        keyboardHeightPercentLandscape,
        keyboardSidePadding,
        keyboardSidePaddingLandscape,
        keyboardBottomPadding,
        keyboardBottomPaddingLandscape,
        keyboardHeightPercentBase,
        floatingKeyboardMode,
        floatingKeyboardWidth,
        usePortraitSizeInLandscape,
    )

    private var navInsetBottom = 0
    private val floatHandleH = dp(28)
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragStartLeft = 0
    private var dragStartTop = 0

    // grab handle shown at the top of the card in floating mode; drag it to move the keyboard
    private val dragHandle = FrameLayout(themedContext).apply {
        val bar = View(themedContext)
        bar.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(2).toFloat()
            setColor(ColorUtils.setAlphaComponent(theme.keyTextColor, 90))
        }
        addView(bar, FrameLayout.LayoutParams(dp(44), dp(4), Gravity.CENTER))
        visibility = View.GONE
        setOnTouchListener { _, e -> onHandleTouch(e) }
    }

    // IME's configuration.orientation is unreliable here; judge by actual metrics aspect
    private fun isLandscape(): Boolean {
        val dm = resources.displayMetrics
        return dm.widthPixels > dm.heightPixels
    }

    // use landscape-specific size params, unless "portrait size in landscape" is enabled
    private fun useLandscapeSize(): Boolean =
        isLandscape() && !usePortraitSizeInLandscape.getValue()

    // in landscape but told to use the portrait keyboard size -> compute width/height from the
    // portrait axes (height base = long edge, card width base = short edge) instead of landscape's
    private fun sizeAsPortrait(): Boolean = isLandscape() && usePortraitSizeInLandscape.getValue()

    private val keyboardHeightPx: Int
        get() {
            val baseType = keyboardHeightPercentBase.getValue()
            val dm = resources.displayMetrics
            val base = when (baseType) {
                DisplayMetrics ->
                    if (sizeAsPortrait()) maxOf(dm.widthPixels, dm.heightPixels) else dm.heightPixels
                RealSize -> Point().also {
                    @Suppress("DEPRECATION")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        context.display
                    } else {
                        context.windowManager.defaultDisplay
                    }.getRealSize(it)
                }.let { if (sizeAsPortrait()) maxOf(it.x, it.y) else it.y }
            }
            val percent = (if (useLandscapeSize()) keyboardHeightPercentLandscape else keyboardHeightPercent).getValue()
            Timber.d("keyboardHeightPx get(): baseType=${baseType}, base=${base}, percent=${percent}")
            return base * percent / 100
        }

    private val keyboardSidePaddingPx: Int
        get() {
            val value = (if (useLandscapeSize()) keyboardSidePaddingLandscape else keyboardSidePadding).getValue()
            return dp(value)
        }

    private val keyboardBottomPaddingPx: Int
        get() {
            val value = (if (useLandscapeSize()) keyboardBottomPaddingLandscape else keyboardBottomPadding).getValue()
            return dp(value)
        }

    @Keep
    private val onKeyboardSizeChangeListener = ManagedPreferenceProvider.OnChangeListener { key ->
        if (keyboardSizePrefs.any { it.key == key }) {
            updateKeyboardSize()
        }
    }

    val keyboardView: View

    init {
        // MUST call before any operation
        setupScope()

        // restore punctuation mapping in case of InputView recreation
        fcitx.launchOnReady {
            punctuation.updatePunctuationMapping(it.statusAreaActionsCached)
        }

        // make sure KeyboardWindow's view has been created before it receives any broadcast
        windowManager.addEssentialWindow(keyboardWindow, createView = true)
        windowManager.addEssentialWindow(symbolPicker)
        windowManager.addEssentialWindow(emojiPicker)
        windowManager.addEssentialWindow(emoticonPicker)
        // show KeyboardWindow by default
        windowManager.attachWindow(KeyboardWindow)

        broadcaster.onImeUpdate(fcitx.runImmediately { inputMethodEntryCached })

        customBackground.imageDrawable = theme.backgroundDrawable(keyBorder)

        keyboardView = constraintLayout {
            // allow MotionEvent to be delivered to keyboard while pressing on padding views.
            // although it should be default for apps targeting Honeycomb (3.0, API 11) and higher,
            // but it's not the case on some devices ... just set it here
            isMotionEventSplittingEnabled = true
            add(customBackground, lParams {
                centerVertically()
                centerHorizontally()
            })
            add(kawaiiBar.view, lParams(matchParent, dp(KawaiiBarComponent.HEIGHT)) {
                topOfParent()
                centerHorizontally()
            })
            add(leftPaddingSpace, lParams {
                below(kawaiiBar.view)
                startOfParent()
                bottomOfParent()
            })
            add(rightPaddingSpace, lParams {
                below(kawaiiBar.view)
                endOfParent()
                bottomOfParent()
            })
            add(windowManager.view, lParams {
                below(kawaiiBar.view)
                above(bottomPaddingSpace)
                /**
                 * set start and end constrain in [updateKeyboardSize]
                 */
            })
            add(bottomPaddingSpace, lParams {
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
                bottomOfParent()
            })
        }
        keyboardView.addView(dragHandle, LayoutParams(0, floatHandleH).apply {
            startToStart = LayoutParams.PARENT_ID
            endToEnd = LayoutParams.PARENT_ID
            topToTop = LayoutParams.PARENT_ID
        })

        add(preedit.ui.root, lParams(matchParent, wrapContent) {
            above(keyboardView)
            centerHorizontally()
        })
        add(keyboardView, lParams(matchParent, wrapContent) {
            centerHorizontally()
            bottomOfParent()
        })
        add(popup.root, lParams(matchParent, matchParent) {
            centerVertically()
            centerHorizontally()
        })

        updateKeyboardSize()

        keyboardPrefs.registerOnChangeListener(onKeyboardSizeChangeListener)
        advancedPrefs.registerOnChangeListener(onKeyboardSizeChangeListener)
    }

    private fun updateKeyboardSize() {
        windowManager.view.updateLayoutParams {
            // floating: scale the keyboard body height by the same factor as the card width,
            // so the whole keyboard keeps its original aspect ratio (no distortion)
            height = if (isFloating())
                keyboardHeightPx * floatingKeyboardWidth.getValue() / 100
            else keyboardHeightPx
        }
        bottomPaddingSpace.updateLayoutParams<LayoutParams> {
            height = if (isFloating()) 0 else keyboardBottomPaddingPx
            bottomMargin = if (isFloating()) 0 else navInsetBottom
        }
        val sidePadding = if (isFloating()) 0 else keyboardSidePaddingPx
        if (sidePadding == 0) {
            // hide side padding space views when unnecessary
            leftPaddingSpace.visibility = GONE
            rightPaddingSpace.visibility = GONE
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToEnd = unset
                endToStart = unset
                startOfParent()
                endOfParent()
            }
        } else {
            leftPaddingSpace.visibility = VISIBLE
            rightPaddingSpace.visibility = VISIBLE
            leftPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            rightPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToStart = unset
                endToEnd = unset
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
            }
        }
        preedit.ui.root.setPadding(sidePadding, 0, sidePadding, 0)
        kawaiiBar.view.setPadding(sidePadding, 0, sidePadding, 0)
        applyCardMode()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        navInsetBottom = getNavBarBottomInset(insets)
        updateKeyboardSize()
        return insets
    }

    /** Exposed to [FcitxInputMethodService.onComputeInsets] for the touchable region. */
    fun isFloatingKeyboard() = isFloating()

    private fun isFloating(): Boolean = when (floatingKeyboardMode.getValue()) {
        FloatingKeyboardMode.Off -> false
        FloatingKeyboardMode.Always -> true
        FloatingKeyboardMode.Landscape ->
            isLandscape()
    }

    private fun floatingCardWidth(parentW: Int): Int {
        val baseW = if (sizeAsPortrait())
            minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        else parentW
        return baseW * floatingKeyboardWidth.getValue() / 100
    }

    private fun applyCardMode() {
        // keyboardView has no LayoutParams until it is added to this view; skip until then
        if (keyboardView.layoutParams == null) return
        val floating = isFloating()
        val parentW = width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val cardW = floatingCardWidth(parentW)
        val savedX = floatingKeyboardX.getValue()
        val savedY = floatingKeyboardY.getValue()
        keyboardView.updateLayoutParams<LayoutParams> {
            if (floating) {
                width = cardW
                // horizontal: centered by default, absolute margin only after a drag
                startToStart = LayoutParams.PARENT_ID
                endToEnd = LayoutParams.PARENT_ID
                horizontalBias = 0.5f
                leftMargin = 0
                if (savedX >= 0) {
                    endToEnd = LayoutParams.UNSET
                    horizontalBias = 0f
                    leftMargin = savedX.coerceIn(0, (parentW - cardW).coerceAtLeast(0))
                }
                // vertical: near the bottom by default, absolute margin only after a drag
                topToTop = LayoutParams.UNSET
                bottomToBottom = LayoutParams.PARENT_ID
                verticalBias = 1f
                topMargin = 0
                if (savedY >= 0) {
                    bottomToBottom = LayoutParams.UNSET
                    topToTop = LayoutParams.PARENT_ID
                    verticalBias = 0f
                    val cardH = keyboardView.height
                    topMargin = savedY.coerceIn(0, (height - cardH - navInsetBottom).coerceAtLeast(0))
                }
            } else {
                width = LayoutParams.MATCH_PARENT
                startToStart = LayoutParams.PARENT_ID
                endToEnd = LayoutParams.PARENT_ID
                topToTop = LayoutParams.UNSET
                bottomToBottom = LayoutParams.PARENT_ID
                horizontalBias = 0.5f
                verticalBias = 1f
                leftMargin = 0
                topMargin = 0
            }
        }
        kawaiiBar.view.updateLayoutParams<LayoutParams> {
            topMargin = if (floating) floatHandleH else 0
        }
        val bgColor = if (theme is Theme.Builtin)
            theme.keyboardColor else theme.backgroundColor
        keyboardView.background = if (floating) GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(bgColor)
        } else null
        keyboardView.elevation = if (floating) dp(8).toFloat() else 0f
        dragHandle.visibility = if (floating) View.VISIBLE else View.GONE
        customBackground.visibility = if (floating) View.GONE else View.VISIBLE
    }

    private fun onHandleTouch(e: MotionEvent): Boolean {
        val lp = keyboardView.layoutParams as? LayoutParams ?: return false
        val parentW = width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val parentH = height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = e.rawX
                dragStartY = e.rawY
                val loc = IntArray(2)
                keyboardView.getLocationInWindow(loc)
                dragStartLeft = loc[0]
                dragStartTop = loc[1]
                // switch the card from bias-centering to absolute margins so the drag takes effect
                keyboardView.updateLayoutParams<LayoutParams> {
                    startToStart = LayoutParams.PARENT_ID
                    endToEnd = LayoutParams.UNSET
                    horizontalBias = 0f
                    topToTop = LayoutParams.PARENT_ID
                    bottomToBottom = LayoutParams.UNSET
                    verticalBias = 0f
                    leftMargin = loc[0]
                    topMargin = loc[1]
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val cardW = keyboardView.width.takeIf { it > 0 } ?: floatingCardWidth(parentW)
                val cardH = keyboardView.height
                val nx = (dragStartLeft + (e.rawX - dragStartX)).toInt()
                    .coerceIn(0, (parentW - cardW).coerceAtLeast(0))
                val ny = (dragStartTop + (e.rawY - dragStartY)).toInt()
                    .coerceIn(0, (parentH - cardH - navInsetBottom).coerceAtLeast(0))
                keyboardView.updateLayoutParams<LayoutParams> {
                    leftMargin = nx
                    topMargin = ny
                }
                // ponytail: insets recompute rides on the relayout; may lag ~1 frame on fast drags
                requestLayout()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                floatingKeyboardX.setValue(lp.leftMargin)
                floatingKeyboardY.setValue(lp.topMargin)
                return true
            }
        }
        return false
    }

    /**
     * called when [InputView] is about to show, or restart
     */
    fun startInput(info: EditorInfo, capFlags: CapabilityFlags, restarting: Boolean = false) {
        broadcaster.onStartInput(info, capFlags)
        returnKeyDrawable.updateDrawableOnEditorInfo(info)
        if (focusChangeResetKeyboard || !restarting) {
            windowManager.attachWindow(KeyboardWindow)
        }
    }

    /** Run [block] on the keyboard window when it's available (voice overlay etc). */
    fun withKeyboardWindow(block: KeyboardWindow.() -> Unit) {
        (windowManager.getEssentialWindow(KeyboardWindow) as? KeyboardWindow)?.block()
    }

    override fun onStartHandleFcitxEvent() {
        val inputPanelData = fcitx.runImmediately { inputPanelCached }
        val inputMethodEntry = fcitx.runImmediately { inputMethodEntryCached }
        val statusAreaActions = fcitx.runImmediately { statusAreaActionsCached }
        arrayOf(
            FcitxEvent.InputPanelEvent(inputPanelData),
            FcitxEvent.IMChangeEvent(inputMethodEntry),
            FcitxEvent.StatusAreaEvent(
                FcitxEvent.StatusAreaEvent.Data(statusAreaActions, inputMethodEntry)
            )
        ).forEach { handleFcitxEvent(it) }
    }

    override fun handleFcitxEvent(it: FcitxEvent<*>) {
        when (it) {
            is FcitxEvent.CandidateListEvent -> {
                broadcaster.onCandidateUpdate(it.data)
            }
            is FcitxEvent.ClientPreeditEvent -> {
                preeditEmptyState.updatePreeditEmptyState(clientPreedit = it.data)
                broadcaster.onClientPreeditUpdate(it.data)
            }
            is FcitxEvent.InputPanelEvent -> {
                preeditEmptyState.updatePreeditEmptyState(preedit = it.data.preedit)
                broadcaster.onInputPanelUpdate(it.data)
            }
            is FcitxEvent.IMChangeEvent -> {
                broadcaster.onImeUpdate(it.data)
            }
            is FcitxEvent.StatusAreaEvent -> {
                punctuation.updatePunctuationMapping(it.data.actions)
                broadcaster.onStatusAreaUpdate(it.data.actions)
            }
            else -> {}
        }
    }

    fun updateSelection(start: Int, end: Int) {
        broadcaster.onSelectionUpdate(start, end)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean {
        return kawaiiBar.handleInlineSuggestions(response)
    }

    override fun onDetachedFromWindow() {
        advancedPrefs.unregisterOnChangeListener(onKeyboardSizeChangeListener)
        keyboardPrefs.unregisterOnChangeListener(onKeyboardSizeChangeListener)
        // clear DynamicScope, implies that InputView should not be attached again after detached.
        scope.clear()
        super.onDetachedFromWindow()
    }

}
