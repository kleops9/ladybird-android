/**
 * Copyright (c) 2023, Andrew Kaster <akaster@serenityos.org>
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

package org.serenityos.ladybird

import android.graphics.Bitmap

/**
 * Wrapper around WebView::ViewImplementation for use by Kotlin.
 *
 * Every callback from native code arrives on the main thread (the UI process runs its
 * Core::EventLoop on the Android main looper).
 */
class WebViewImplementation(private val view: WebView) {
    // Instance Pointer to native object, very unsafe :)
    private var nativeInstance: Long = 0

    val isInitialized get() = nativeInstance != 0L
    val instance get() = nativeInstance

    /**
     * Creates the view in the Hub. With a view ID, shows a view that the Hub already made, such as one a page opened
     * with window.open().
     */
    fun initialize(isPrivate: Boolean, existingViewId: Long = -1) {
        nativeInstance = nativeObjectInit(isPrivate, existingViewId, view.resources.displayMetrics.density, displayRefreshRate())
    }

    /** The fastest the display refreshes at its resolution. The Compositor follows the display's actual refreshes. */
    private fun displayRefreshRate(): Float {
        val display = view.context.display ?: return 60f
        val mode = display.mode
        return display.supportedModes
            .filter { it.physicalWidth == mode.physicalWidth && it.physicalHeight == mode.physicalHeight }
            .maxOfOrNull { it.refreshRate } ?: display.refreshRate
    }

    fun dispose() {
        if (nativeInstance == 0L)
            return
        nativeObjectDispose(nativeInstance)
        nativeInstance = 0
    }

    /** What a page names this tab by when it opens a view in it. */
    val handle: String get() = nativeViewId(nativeInstance).toString()

    fun loadURL(url: String) = nativeLoadURL(nativeInstance, url)
    fun reload() = nativeReload(nativeInstance)
    fun stopLoading() = nativeStopLoading(nativeInstance)
    fun traverseHistory(delta: Int) = nativeTraverseHistory(nativeInstance, delta)
    fun zoom(direction: Int): Float = nativeZoom(nativeInstance, direction)
    fun pinch(focusX: Float, focusY: Float, scaleDelta: Float, continuesGesture: Boolean) =
        nativePinch(nativeInstance, focusX, focusY, scaleDelta, continuesGesture)
    fun findInPage(query: String) = nativeFindInPage(nativeInstance, query)
    fun findInPageNext() = nativeFindInPageNext(nativeInstance)
    fun findInPagePrevious() = nativeFindInPagePrevious(nativeInstance)
    fun findInPageEnd() = nativeFindInPageEnd(nativeInstance)
    fun commitText(text: String) = nativeCommitText(nativeInstance, text)
    fun drawIntoBitmap(bitmap: Bitmap) = nativeDrawIntoBitmap(nativeInstance, bitmap)
    fun setSurface(surface: android.view.Surface?) = nativeSetSurface(nativeInstance, surface)
    fun setPresentationPaused(paused: Boolean) = nativeSetPresentationPaused(nativeInstance, paused)
    fun scrollGesture(ended: Boolean, x: Float, y: Float, rawX: Float, rawY: Float, deltaX: Float, deltaY: Float) =
        nativeScrollGesture(nativeInstance, ended, x, y, rawX, rawY, deltaX, deltaY)
        fun setViewportGeometry(w: Int, h: Int) = nativeSetViewportGeometry(nativeInstance, w, h)
    fun setDevicePixelRatio(ratio: Float) = nativeSetDevicePixelRatio(nativeInstance, ratio)
    fun setHasFocus(focus: Boolean) = nativeSetHasFocus(nativeInstance, focus)
    fun setVisible(visible: Boolean) = nativeSetVisible(nativeInstance, visible)

    fun mouseEvent(type: Int, x: Float, y: Float, rawX: Float, rawY: Float, button: Int, wheelX: Int = 0, wheelY: Int = 0) =
        nativeMouseEvent(nativeInstance, type, x, y, rawX, rawY, button, wheelX, wheelY)

    fun keyEvent(down: Boolean, key: Int, modifiers: Int, codePoint: Int) =
        nativeKeyEvent(nativeInstance, down, key, modifiers, codePoint)

    fun alertClosed() = nativeAlertClosed(nativeInstance)
    fun confirmClosed(accepted: Boolean) = nativeConfirmClosed(nativeInstance, accepted)
    fun promptClosed(response: String?) = nativePromptClosed(nativeInstance, response)
    fun selectDropdownClosed(id: Int) = nativeSelectDropdownClosed(nativeInstance, id)

    // Functions called from native code. These need block bodies: an expression body returning `Unit?`
    // compiles to a method returning Object, which doesn't match the "...V" signatures JNI looks up.
    fun invalidateLayout() {
        view.onFrameReady()
    }

    fun onLoadStart(url: String, isRedirect: Boolean) {
        // A new document starts without a focused input; the old page won't tell us its input lost focus.
        if (view.isFocusedEditable)
            view.onFocusedEditableChange(false)
        view.client?.onLoadStart(url, isRedirect)
    }

    fun onLoadFinish(url: String) {
        view.client?.onLoadFinish(url)
    }

    fun onUrlChange(url: String) {
        view.client?.onUrlChange(url)
    }

    fun onTitleChange(title: String) {
        view.client?.onTitleChange(title)
    }

    fun onFaviconChange(pixels: IntArray, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        view.client?.onFaviconChange(bitmap)
    }

    fun onNavigationButtonsStateChanged(back: Boolean, forward: Boolean) {
        view.client?.onNavigationButtonsStateChanged(back, forward)
    }

    fun onThemeColorChange(color: Int) {
        view.client?.onThemeColorChange(color)
    }

    fun onFocusedEditableChange(editable: Boolean) {
        view.onFocusedEditableChange(editable)
    }

    fun onNewWebView(activate: Boolean, pageIndex: Long): String? {
        return view.client?.onNewWebView(activate, pageIndex)
    }

    fun onClose() {
        view.client?.onCloseRequested()
    }

    fun onActivateTab() {
        view.client?.onActivateRequested()
    }

    fun onContextMenu(kind: Int, url: String?, x: Int, y: Int) {
        view.client?.onContextMenu(kind, url, x, y)
    }

    fun onRequestAlert(message: String) {
        view.client?.onRequestAlert(message) ?: alertClosed()
    }

    fun onRequestConfirm(message: String) {
        view.client?.onRequestConfirm(message) ?: confirmClosed(false)
    }

    fun onRequestPrompt(message: String, default: String) {
        view.client?.onRequestPrompt(message, default) ?: promptClosed(null)
    }

    fun onRequestSelectDropdown(labels: Array<String>, ids: IntArray, enabled: BooleanArray, selectedIndex: Int) {
        view.client?.onRequestSelectDropdown(labels, ids, enabled, selectedIndex) ?: selectDropdownClosed(-1)
    }

    fun onWebContentCrashed() {
        view.client?.onWebContentCrashed()
    }

    fun onFindInPageResult(currentIndex: Int, totalMatches: Int) {
        view.client?.onFindInPageResult(currentIndex, totalMatches)
    }

    // Functions implemented in native code
    private external fun nativeObjectInit(isPrivate: Boolean, existingViewId: Long, devicePixelRatio: Float, refreshRate: Float): Long
    private external fun nativeObjectDispose(instance: Long)
    private external fun nativeViewId(instance: Long): Long

    private external fun nativeDrawIntoBitmap(instance: Long, bitmap: Bitmap)
    private external fun nativeSetSurface(instance: Long, surface: android.view.Surface?)
    private external fun nativeSetPresentationPaused(instance: Long, paused: Boolean)
    private external fun nativeSetViewportGeometry(instance: Long, w: Int, h: Int)
    private external fun nativeSetDevicePixelRatio(instance: Long, ratio: Float)
    private external fun nativeSetHasFocus(instance: Long, focus: Boolean)
    private external fun nativeSetVisible(instance: Long, visible: Boolean)
    private external fun nativeLoadURL(instance: Long, url: String)
    private external fun nativeReload(instance: Long)
    private external fun nativeStopLoading(instance: Long)
    private external fun nativeTraverseHistory(instance: Long, delta: Int)
    private external fun nativeZoom(instance: Long, direction: Int): Float
    private external fun nativeScrollGesture(instance: Long, ended: Boolean, x: Float, y: Float, rawX: Float, rawY: Float, deltaX: Float, deltaY: Float)
    private external fun nativeMouseEvent(instance: Long, type: Int, x: Float, y: Float, rawX: Float, rawY: Float, button: Int, wheelX: Int, wheelY: Int)
    private external fun nativeKeyEvent(instance: Long, down: Boolean, key: Int, modifiers: Int, codePoint: Int)
    private external fun nativeAlertClosed(instance: Long)
    private external fun nativeConfirmClosed(instance: Long, accepted: Boolean)
    private external fun nativePromptClosed(instance: Long, response: String?)
    private external fun nativeSelectDropdownClosed(instance: Long, id: Int)
    private external fun nativePinch(instance: Long, focusX: Float, focusY: Float, scaleDelta: Float, continuesGesture: Boolean)
    private external fun nativeFindInPage(instance: Long, query: String)
    private external fun nativeFindInPageNext(instance: Long)
    private external fun nativeFindInPagePrevious(instance: Long)
    private external fun nativeFindInPageEnd(instance: Long)
    private external fun nativeCommitText(instance: Long, text: String)

    companion object {
        const val MOUSE_DOWN = 0
        const val MOUSE_UP = 1
        const val MOUSE_MOVE = 2
        const val MOUSE_WHEEL = 3
        const val MOUSE_DOUBLE_CLICK = 4

        const val BUTTON_NONE = 0
        const val BUTTON_PRIMARY = 1
        const val BUTTON_SECONDARY = 2

        const val CONTEXT_MENU_PAGE = 0
        const val CONTEXT_MENU_LINK = 1
        const val CONTEXT_MENU_IMAGE = 2

        const val COLOR_SCHEME_AUTO = 0
        const val COLOR_SCHEME_DARK = 1
        const val COLOR_SCHEME_LIGHT = 2

        /** Turns omnibox text into a URL (adding https:// or building a search URL), or null if it can't. */
        fun sanitizeURL(text: String, searchEngine: String): String? = nativeSanitizeURL(text, searchEngine)
        fun searchEngineNames(): Array<String> = nativeSearchEngineNames()
        fun userAgent(mobile: Boolean): String = nativeUserAgent(mobile)
        /** The user agent belongs to the browser, so this changes it for every tab. */
        fun setMobileUserAgent(mobile: Boolean) = nativeSetMobileUserAgent(mobile)
        /** Forgets incognito cookies and site storage; call when the last incognito tab has closed. */
        fun clearPrivateBrowsingData() = nativeClearPrivateBrowsingData()

        /*
         * We use a static class initializer to allow the native code to cache some
         * field offsets. This native function looks up and caches interesting
         * class/field/method IDs. Throws on failure.
         */
        private external fun nativeClassInit()
        private external fun nativeSanitizeURL(text: String, searchEngine: String): String?
        private external fun nativeSearchEngineNames(): Array<String>
        private external fun nativeUserAgent(mobile: Boolean): String
        private external fun nativeSetMobileUserAgent(mobile: Boolean)
        private external fun nativeClearPrivateBrowsingData()

        init {
            nativeClassInit()
        }
    }
}
