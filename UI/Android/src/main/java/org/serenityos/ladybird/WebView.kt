/**
 * Copyright (c) 2023, Andrew Kaster <akaster@serenityos.org>
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

package org.serenityos.ladybird

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.text.InputType
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import kotlin.math.roundToInt

/** Receives page events for one WebView. Implemented by Tab. */
interface WebViewClient {
    fun onLoadStart(url: String, isRedirect: Boolean)
    fun onLoadFinish(url: String)
    fun onUrlChange(url: String)
    fun onTitleChange(title: String)
    fun onFaviconChange(favicon: Bitmap)
    fun onNavigationButtonsStateChanged(back: Boolean, forward: Boolean)
    fun onThemeColorChange(color: Int)
    /** The finger pulls down a page that is scrolled to its top, by this share of the distance that refreshes it. */
    fun onPullToRefresh(progress: Float)
    /** The pull ended, refreshing the page if it went far enough. */
    fun onPullToRefreshEnd(refresh: Boolean)
    fun onNewWebView(activate: Boolean, pageIndex: Long): String?
    fun onCloseRequested()
    fun onActivateRequested()
    fun onContextMenu(kind: Int, url: String?, x: Int, y: Int)
    fun onRequestAlert(message: String)
    fun onRequestConfirm(message: String)
    fun onRequestPrompt(message: String, default: String)
    fun onRequestSelectDropdown(labels: Array<String>, ids: IntArray, enabled: BooleanArray, selectedIndex: Int)
    fun onWebContentCrashed()
    fun onFindInPageResult(currentIndex: Int, totalMatches: Int)
    fun onZoomChanged(level: Float)
}

/**
 * Shows one page and turns touch and keyboard input into the mouse, wheel and key events Ladybird understands:
 * a tap is a click, a drag or fling scrolls, a long press opens the context menu, and a pinch zooms.
 */
// FIXME: This should (eventually) implement NestedScrollingChild3 and ScrollingView
//
// Frames are presented by native code straight into this TextureView's buffer as soon as the Compositor finishes them,
// so drawing a page involves no Java code, no Bitmap and no per-frame texture upload.
class WebView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    val impl = WebViewImplementation(this)
    var client: WebViewClient? = null
    private var zoomLevel = 1f
    private var surface: Surface? = null
    /** The size the page lays out at, known before the view is on screen (see [presize]). */
    private var pageWidth = 0
    private var pageHeight = 0

    var isFocusedEditable = false
        private set

    /** Whether the page is scrolled to its top, as the engine reports it. A pull down from there refreshes the page. */
    var isScrolledToTop = true

    /** Incognito pages ask the keyboard not to learn from what is typed. */
    var isIncognito = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        // The framework would otherwise grey out the whole page while it has focus.
        defaultFocusHighlightEnabled = false
        isOpaque = true
        surfaceTextureListener = this
    }

    fun initialize(isPrivate: Boolean, existingViewId: Long = -1) {
        impl.initialize(isPrivate, existingViewId)
        if (pageWidth > 0 && pageHeight > 0)
            pushGeometry(pageWidth, pageHeight)
        surface?.let { impl.setSurface(it) }
    }

    fun dispose() {
        stopMomentum()
        if (impl.isInitialized)
            impl.setSurface(null)
        impl.dispose()
    }

    // --- SurfaceTextureListener: the buffer native code presents into. It goes away while the view is detached
    // (e.g. its tab is in the background) and comes back when it's shown again. ---

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        val newSurface = Surface(texture)
        surface = newSurface
        if (impl.isInitialized)
            impl.setSurface(newSurface)
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        if (impl.isInitialized)
            impl.setSurface(null)
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}

    fun loadURL(url: String) = impl.loadURL(url)

    val currentZoom get() = zoomLevel

    fun zoom(direction: Int): Float {
        zoomLevel = impl.zoom(direction)
        return zoomLevel
    }

    /** Frames received from WebContent, for the frame rate overlay. */
    var framesReceived = 0L
        private set

    /** Called by the native view when a new frame is ready (native code has already presented it). */
    fun onFrameReady() {
        ++framesReceived
        (client as? Tab)?.onFrameForTiming()
    }

    /**
     * Gives the page its size before the view is on screen, so a tab opened in the background
     * lays out and paints at the size it will be shown at.
     */
    fun presize(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || (pageWidth == w && pageHeight == h))
            return
        pageWidth = w
        pageHeight = h
        if (impl.isInitialized)
            pushGeometry(w, h)
    }

    /** A scaled-down copy of the page, for the tab switcher. */
    fun captureThumbnail(maxWidth: Int): Bitmap? {
        if (!impl.isInitialized || pageWidth <= 0 || pageHeight <= 0)
            return null
        val thumbnailHeight = (pageHeight * maxWidth.toFloat() / pageWidth).roundToInt().coerceAtLeast(1)
        // On screen, the GPU scales the texture down for us.
        if (isAvailable && !isShowingPinchPreview)
            getBitmap(maxWidth, thumbnailHeight)?.let { return it }
        // A tab that was never shown has no texture: copy its last frame out of native code instead.
        val full = Bitmap.createBitmap(pageWidth, pageHeight, Bitmap.Config.ARGB_8888)
        impl.drawIntoBitmap(full)
        val scaled = Bitmap.createScaledBitmap(full, maxWidth, thumbnailHeight, true)
        if (scaled !== full)
            full.recycle()
        return scaled
    }

    fun onFocusedEditableChange(editable: Boolean) {
        isFocusedEditable = editable
        val imm = context.getSystemService(InputMethodManager::class.java)
        if (editable) {
            requestFocus()
            imm.restartInput(this)
            imm.showSoftInput(this, 0)
        } else if (hasFocus()) {
            imm.hideSoftInputFromWindow(windowToken, 0)
        }
    }

    // --- Touch input ---

    private val density get() = resources.displayMetrics.density
    private var wheelRemainderX = 0f
    private var wheelRemainderY = 0f
    private var isScrolling = false
    /** Set while a touch scroll gesture is open in the Compositor, which it ends when the finger lifts. */
    private var scrollGestureActive = false

    /** Set while the finger pulls down a page at its top: the rest of the gesture pulls to refresh, and scrolls nothing. */
    private var pulling = false
    private var pullDistance = 0f
    private val pullThreshold get() = PULL_TO_REFRESH_DISTANCE_DP * density

    private fun endPull(refresh: Boolean) {
        if (!pulling)
            return
        pulling = false
        client?.onPullToRefreshEnd(refresh)
    }

    // A flick keeps scrolling with Android's own fling, as it does in Chrome, which the Compositor scrolls by as the
    // momentum of the gesture.
    private val scroller = OverScroller(context)
    private var lastFlingX = 0
    private var lastFlingY = 0
    private var momentumRunning = false
    private var momentumX = 0f
    private var momentumY = 0f
    private var momentumRawX = 0f
    private var momentumRawY = 0f

    /** Touch scrolls go to the Compositor as the steps of a gesture, in CSS pixels. */
    private fun sendScrollGestureStep(dx: Float, dy: Float, event: MotionEvent) {
        val scale = density * zoomLevel
        scrollGestureActive = true
        impl.scrollGesture(WebViewImplementation.SCROLL_ONGOING, event.x, event.y, event.rawX, event.rawY, dx / scale, dy / scale)
    }

    private fun endScrollGesture(event: MotionEvent) {
        if (!scrollGestureActive)
            return
        scrollGestureActive = false
        impl.scrollGesture(WebViewImplementation.SCROLL_ENDED, event.x, event.y, event.rawX, event.rawY, 0f, 0f)
    }

    private fun startMomentum(velocityX: Float, velocityY: Float, event: MotionEvent) {
        lastFlingX = 0
        lastFlingY = 0
        momentumX = event.x
        momentumY = event.y
        momentumRawX = event.rawX
        momentumRawY = event.rawY
        scroller.fling(0, 0, -velocityX.roundToInt(), -velocityY.roundToInt(), Int.MIN_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MAX_VALUE)
        momentumRunning = true
        postOnAnimation(momentumRunnable)
    }

    private fun stopMomentum() {
        if (!momentumRunning)
            return
        momentumRunning = false
        scroller.forceFinished(true)
        if (impl.isInitialized)
            impl.scrollGesture(WebViewImplementation.SCROLL_ENDED, momentumX, momentumY, momentumRawX, momentumRawY, 0f, 0f)
    }

    private val momentumRunnable = object : Runnable {
        override fun run() {
            if (!momentumRunning || !impl.isInitialized)
                return
            if (!scroller.computeScrollOffset()) {
                stopMomentum()
                return
            }
            val dx = scroller.currX - lastFlingX
            val dy = scroller.currY - lastFlingY
            lastFlingX = scroller.currX
            lastFlingY = scroller.currY
            if (dx != 0 || dy != 0) {
                val scale = density * zoomLevel
                impl.scrollGesture(WebViewImplementation.SCROLL_MOMENTUM, momentumX, momentumY, momentumRawX, momentumRawY, dx / scale, dy / scale)
            }
            postOnAnimation(this)
        }
    }

    /** Wheel deltas are in CSS pixels; finger movement is in device pixels. */
    private fun sendScroll(dx: Float, dy: Float, x: Float, y: Float) {
        val scale = density * zoomLevel
        wheelRemainderX += dx / scale
        wheelRemainderY += dy / scale
        val wheelX = wheelRemainderX.toInt()
        val wheelY = wheelRemainderY.toInt()
        if (wheelX == 0 && wheelY == 0)
            return
        wheelRemainderX -= wheelX
        wheelRemainderY -= wheelY
        val location = IntArray(2).also { getLocationOnScreen(it) }
        impl.mouseEvent(WebViewImplementation.MOUSE_WHEEL, x, y, x + location[0], y + location[1], WebViewImplementation.BUTTON_NONE, wheelX, wheelY)
    }

    private fun sendClick(event: MotionEvent, button: Int) {
        val x = event.x
        val y = event.y
        impl.mouseEvent(WebViewImplementation.MOUSE_MOVE, x, y, event.rawX, event.rawY, WebViewImplementation.BUTTON_NONE)
        impl.mouseEvent(WebViewImplementation.MOUSE_DOWN, x, y, event.rawX, event.rawY, button)
        impl.mouseEvent(WebViewImplementation.MOUSE_UP, x, y, event.rawX, event.rawY, button)
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            isScrolling = false
            // A finger on the page stops a fling.
            stopMomentum()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            requestFocus()
            sendClick(e, WebViewImplementation.BUTTON_PRIMARY)
            // Tapping an input that already has focus doesn't send a focus change, so bring the keyboard back here.
            if (isFocusedEditable)
                context.getSystemService(InputMethodManager::class.java).showSoftInput(this@WebView, 0)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            isScrolling = true
            // A gesture that starts by pulling down a page at its top pulls to refresh. One that scrolled first doesn't.
            if (!pulling && !scrollGestureActive && isScrolledToTop && zoomLevel <= 1.01f && distanceY < 0 && -distanceY >= kotlin.math.abs(distanceX)) {
                pulling = true
                pullDistance = 0f
            }
            if (pulling) {
                pullDistance = (pullDistance - distanceY).coerceAtLeast(0f)
                client?.onPullToRefresh(pullDistance / pullThreshold)
                return true
            }
            sendScrollGestureStep(distanceX, distanceY, e2)
            return true
        }

        // The fingers lift with a fling: the gesture ends, and its momentum follows.
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (!scrollGestureActive)
                return false
            endScrollGesture(e2)
            startMomentum(velocityX, velocityY, e2)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (isScrolling)
                return
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            sendClick(e, WebViewImplementation.BUTTON_SECONDARY)
        }
    })

    // --- Pinch to zoom ---
    //
    // The page zooms its visual viewport, as in Chrome: the layout stays as it is and the page is magnified around the
    // fingers. Moving both fingers pans, which scrolls the page.

    private var isPinching = false
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    /** Set when a second finger lands: the rest of this touch sequence belongs to the pinch, not to taps or scrolls. */
    private var touchIsMultiFinger = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isPinching = true
            lastFocusX = detector.focusX
            lastFocusY = detector.focusY
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            impl.pinch(detector.focusX, detector.focusY, detector.scaleFactor - 1f, true)
            sendScroll(lastFocusX - detector.focusX, lastFocusY - detector.focusY, detector.focusX, detector.focusY)
            lastFocusX = detector.focusX
            lastFocusY = detector.focusY
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            isPinching = false
            impl.pinch(detector.focusX, detector.focusY, 0f, false)
        }
    })

    private val isShowingPinchPreview get() = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!impl.isInitialized)
            return false
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> touchIsMultiFinger = false
            MotionEvent.ACTION_POINTER_DOWN -> if (!touchIsMultiFinger) {
                touchIsMultiFinger = true
                endScrollGesture(event)
                stopMomentum()
                endPull(false)
                // Stop the single-finger gesture in progress (pending long press, scroll).
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                gestureDetector.onTouchEvent(cancel)
                cancel.recycle()
            }
        }
        if (touchIsMultiFinger)
            return true
        val handled = gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            endScrollGesture(event)
            endPull(event.actionMasked == MotionEvent.ACTION_UP && pullDistance >= pullThreshold)
        }
        return handled || super.onTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        // Mouse wheel and trackpad scrolling (e.g. a mouse attached to the device, or the emulator).
        if (impl.isInitialized && event.action == MotionEvent.ACTION_SCROLL) {
            val step = 48 * density
            sendScroll(-event.getAxisValue(MotionEvent.AXIS_HSCROLL) * step, -event.getAxisValue(MotionEvent.AXIS_VSCROLL) * step, event.x, event.y)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    // --- Keyboard input ---

    override fun onCheckIsTextEditor() = isFocusedEditable

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!isFocusedEditable)
            return null
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT
        outAttrs.imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        if (isIncognito)
            outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        return PageInputConnection()
    }

    /**
     * The page owns the text, so the keyboard's edits are replayed as key presses. While the keyboard is
     * composing a word, each update is applied as "backspace what changed, type the new ending".
     */
    private inner class PageInputConnection : BaseInputConnection(this@WebView, false) {
        private var composing = ""

        private fun typeText(text: String) {
            var index = 0
            while (index < text.length) {
                val codePoint = text.codePointAt(index)
                index += Character.charCount(codePoint)
                if (codePoint == '\n'.code) {
                    sendKey(KEY_RETURN, '\n'.code)
                } else {
                    sendKey(keyCodeForCodePoint(codePoint), codePoint)
                }
            }
        }

        private fun backspace(count: Int) = repeat(count) { sendKey(KEY_BACKSPACE, 0) }

        private fun replaceComposing(newText: String) {
            val common = composing.commonPrefixWith(newText).length
            backspace(composing.codePointCount(common, composing.length))
            typeText(newText.substring(common))
            composing = newText
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            replaceComposing(text.toString())
            return true
        }

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            replaceComposing(text.toString())
            composing = ""
            return true
        }

        override fun finishComposingText(): Boolean {
            composing = ""
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            backspace(beforeLength)
            repeat(afterLength) { sendKey(KEY_DELETE, 0) }
            return true
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            composing = ""
            sendKey(KEY_RETURN, '\n'.code)
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            dispatchPageKey(event)
            return true
        }
    }

    private fun sendKey(key: Int, codePoint: Int, modifiers: Int = 0) {
        impl.keyEvent(true, key, modifiers, codePoint)
        impl.keyEvent(false, key, modifiers, codePoint)
    }

    /** Hardware keyboards (and the emulator's host keyboard) send KeyEvents directly. */
    private fun dispatchPageKey(event: KeyEvent): Boolean {
        if (!impl.isInitialized)
            return false
        val down = when (event.action) {
            KeyEvent.ACTION_DOWN -> true
            KeyEvent.ACTION_UP -> false
            else -> return false
        }
        var modifiers = 0
        if (event.isAltPressed) modifiers = modifiers or MOD_ALT
        if (event.isCtrlPressed) modifiers = modifiers or MOD_CTRL
        if (event.isShiftPressed) modifiers = modifiers or MOD_SHIFT
        if (event.isMetaPressed) modifiers = modifiers or MOD_SUPER

        val special = specialKeys[event.keyCode]
        val unicode = event.unicodeChar
        val codePoint = when {
            event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER -> '\n'.code
            special != null -> 0
            else -> unicode
        }
        val key = special ?: keyCodeForCodePoint(unicode)
        if (key == 0 && codePoint == 0)
            return false
        impl.keyEvent(down, key, modifiers, codePoint)
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK)
            return super.onKeyDown(keyCode, event)
        return dispatchPageKey(event) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK)
            return super.onKeyUp(keyCode, event)
        return dispatchPageKey(event) || super.onKeyUp(keyCode, event)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (impl.isInitialized)
            impl.setHasFocus(gainFocus)
    }

    // --- Drawing ---

    private fun pushGeometry(w: Int, h: Int) {
        impl.setDevicePixelRatio(density)
        impl.setViewportGeometry(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0 || (w == pageWidth && h == pageHeight))
            return
        pageWidth = w
        pageHeight = h
        if (impl.isInitialized)
            pushGeometry(w, h)
    }

    companion object {
        /** How far the finger pulls a page down to refresh it. */
        const val PULL_TO_REFRESH_DISTANCE_DP = 96f

        // Web::UIEvents::KeyCode values (Windows virtual key codes).
        const val KEY_BACKSPACE = 0x08
        const val KEY_TAB = 0x09
        const val KEY_RETURN = 0x0D
        const val KEY_ESCAPE = 0x1B
        const val KEY_DELETE = 0x2E

        // Web::UIEvents::KeyModifier values.
        const val MOD_ALT = 1
        const val MOD_CTRL = 2
        const val MOD_SHIFT = 4
        const val MOD_SUPER = 8

        private val specialKeys = mapOf(
            KeyEvent.KEYCODE_DEL to KEY_BACKSPACE,
            KeyEvent.KEYCODE_FORWARD_DEL to KEY_DELETE,
            KeyEvent.KEYCODE_TAB to KEY_TAB,
            KeyEvent.KEYCODE_ENTER to KEY_RETURN,
            KeyEvent.KEYCODE_NUMPAD_ENTER to KEY_RETURN,
            KeyEvent.KEYCODE_ESCAPE to KEY_ESCAPE,
            KeyEvent.KEYCODE_PAGE_UP to 0x21,
            KeyEvent.KEYCODE_PAGE_DOWN to 0x22,
            KeyEvent.KEYCODE_MOVE_END to 0x23,
            KeyEvent.KEYCODE_MOVE_HOME to 0x24,
            KeyEvent.KEYCODE_DPAD_LEFT to 0x25,
            KeyEvent.KEYCODE_DPAD_UP to 0x26,
            KeyEvent.KEYCODE_DPAD_RIGHT to 0x27,
            KeyEvent.KEYCODE_DPAD_DOWN to 0x28,
        )

        fun keyCodeForCodePoint(codePoint: Int): Int = when (codePoint) {
            in 'a'.code..'z'.code -> codePoint - 'a'.code + 'A'.code
            in 'A'.code..'Z'.code, in '0'.code..'9'.code -> codePoint
            ' '.code -> 0x20
            else -> 0
        }
    }
}
