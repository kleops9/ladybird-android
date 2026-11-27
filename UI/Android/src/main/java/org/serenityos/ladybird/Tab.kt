/**
 * Copyright (c) 2025, the Ladybird developers.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

package org.serenityos.ladybird

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock

/** What the browser UI needs to hear from a tab. Implemented by LadybirdActivity. */
interface TabHost {
    fun onTabStateChanged(tab: Tab)
    fun onTabNavigated(tab: Tab, url: String, title: String)
    fun onTabRequestsNewTab(opener: Tab, activate: Boolean, pageIndex: Long): Tab?
    fun onTabRequestsClose(tab: Tab)
    fun onTabRequestsActivate(tab: Tab)
    fun onTabContextMenu(tab: Tab, kind: Int, url: String?)
    fun onTabAlert(tab: Tab, message: String)
    fun onTabConfirm(tab: Tab, message: String)
    fun onTabPrompt(tab: Tab, message: String, default: String)
    fun onTabSelectDropdown(tab: Tab, labels: Array<String>, ids: IntArray, enabled: BooleanArray, selectedIndex: Int)
    fun onTabCrashed(tab: Tab)
    fun onTabDownload(tab: Tab, url: String, contentDisposition: String, mimeType: String)
    fun onTabFindResult(tab: Tab, currentIndex: Int, totalMatches: Int)
    fun configureNewView(tab: Tab, view: WebView)
    fun onTabPullToRefresh(tab: Tab, progress: Float)
    fun onTabPullToRefreshEnd(tab: Tab, refresh: Boolean)
}

/**
 * One browser tab. A tab may have no WebView: a fresh new tab shows the native new tab page, and a
 * background tab whose WebContent process was reclaimed ("discarded") reloads its URL when shown again.
 */
class Tab(
    val id: Long,
    private val host: TabHost,
    var url: String = NEW_TAB_URL,
    var title: String = "",
    /** The tab that opened this one (window.open or target=_blank); closing it returns there. */
    var openerId: Long? = null,
    /** Incognito tabs keep cookies and site storage in memory only, and stay out of history and saved tabs. */
    val isIncognito: Boolean = false,
) : WebViewClient {
    var view: WebView? = null
        private set
    var favicon: Bitmap? = null
    var thumbnail: Bitmap? = null
    var themeColor: Int? = null
    var canGoBack = false
    var canGoForward = false
    var isLoading = false
    var loadProgress = 0f
    var isCrashed = false
    /** The page failed to load and Ladybird shows its error page (whose own URL is about:error). */
    var isErrorPage = false
    var zoomLevel = 1f
    /** The URL of the document on screen, as opposed to [url], which may be a navigation still in progress. */
    private var committedUrl: String? = null
    /**
     * The address a load was asked for, until the page reaches it. A tab's view starts on about:blank before it goes
     * there, which the address bar shouldn't show in the meantime.
     */
    private var navigatingTo: String? = null

    /** Whether this is the start of a new view on its way to the address it was asked for. */
    private fun isBlankOnTheWay(url: String) = url == ABOUT_BLANK && navigatingTo != null && navigatingTo != ABOUT_BLANK
    var lastActive = SystemClock.elapsedRealtime()
    private var pendingResources = 0
    private var peakResources = 0

    val isNewTabPage get() = url == NEW_TAB_URL && view == null
    val displayTitle get() = title.ifBlank { if (url == NEW_TAB_URL) "New tab" else url }

    /**
     * Creates the WebView (and its view in the Hub) on demand. With a view ID, shows a view the Hub already made for a
     * page this tab's opener opened.
     */
    fun ensureView(context: Context, existingViewId: Long = -1): WebView {
        view?.let { return it }
        val newView = WebView(context)
        newView.client = this
        newView.initialize(isIncognito, existingViewId)
        newView.isIncognito = isIncognito
        view = newView
        isCrashed = false
        zoomLevel = 1f
        host.configureNewView(this, newView)
        return newView
    }

    fun load(context: Context, target: String) {
        url = target
        navigatingTo = target
        val v = ensureView(context)
        v.loadURL(target)
        isLoading = true
        loadProgress = 0.1f
        host.onTabStateChanged(this)
    }

    /** Frees the WebView and its process but keeps what's needed to restore the page later. */
    fun discard() {
        val v = view ?: return
        if (thumbnail == null)
            thumbnail = v.captureThumbnail(THUMBNAIL_WIDTH)
        v.dispose()
        view = null
        isLoading = false
        canGoForward = false
    }

    fun close() {
        discard()
    }

    fun captureThumbnail() {
        view?.captureThumbnail(THUMBNAIL_WIDTH)?.let { thumbnail = it }
    }

    // --- WebViewClient ---

    // Load timing, logged under "LadybirdLoad" (adb logcat -s LadybirdLoad) to compare sites. Only debug builds log it,
    // as it names the pages visited.
    private var loadStartTime = 0L
    private var firstFrameLogged = false

    fun onFrameForTiming() {
        if (loadStartTime != 0L && !firstFrameLogged) {
            firstFrameLogged = true
            if (BuildConfig.DEBUG) android.util.Log.i("LadybirdLoad", "first-frame +${SystemClock.elapsedRealtime() - loadStartTime}ms $url")
        }
    }

    override fun onLoadStart(url: String, isRedirect: Boolean) {
        if (url == ABOUT_ERROR)
            return
        if (!isBlankOnTheWay(url))
            navigatingTo = null
        if (!isRedirect) {
            loadStartTime = SystemClock.elapsedRealtime()
            firstFrameLogged = false
        }
        if (BuildConfig.DEBUG) android.util.Log.i("LadybirdLoad", "start${if (isRedirect) " (redirect)" else ""} $url")
        if (navigatingTo == null)
            this.url = url
        isErrorPage = false
        isLoading = true
        loadProgress = 0.15f
        pendingResources = 0
        peakResources = 0
        themeColor = null
        favicon = null
        host.onTabStateChanged(this)
    }

    override fun onLoadFinish(url: String) {
        // The blank start of a new view finishing isn't the page the tab is loading.
        if (isBlankOnTheWay(url))
            return
        navigatingTo = null
        if (loadStartTime != 0L)
            if (BuildConfig.DEBUG) android.util.Log.i("LadybirdLoad", "finish +${SystemClock.elapsedRealtime() - loadStartTime}ms peak-pending=$peakResources $url")
        isLoading = false
        loadProgress = 1f
        // Keep showing the address that failed rather than about:error, so it can be fixed or reloaded.
        isErrorPage = url == ABOUT_ERROR
        if (!isErrorPage) {
            this.url = url
            committedUrl = url
        }
        host.onTabStateChanged(this)
        if (!isErrorPage)
            host.onTabNavigated(this, url, title)
    }

    override fun onUrlChange(url: String) {
        if (url == ABOUT_ERROR || isBlankOnTheWay(url))
            return
        this.url = url
        committedUrl = url
        host.onTabStateChanged(this)
    }

    override fun onTitleChange(title: String) {
        this.title = title
        host.onTabStateChanged(this)
        if (!isLoading && !isErrorPage)
            host.onTabNavigated(this, url, title)
    }

    override fun onFaviconChange(favicon: Bitmap) {
        this.favicon = favicon
        host.onTabStateChanged(this)
    }

    override fun onNavigationButtonsStateChanged(back: Boolean, forward: Boolean) {
        canGoBack = back
        canGoForward = forward
        host.onTabStateChanged(this)
    }

    override fun onPullToRefresh(progress: Float) = host.onTabPullToRefresh(this, progress)

    override fun onPullToRefreshEnd(refresh: Boolean) = host.onTabPullToRefreshEnd(this, refresh)

    override fun onThemeColorChange(color: Int) {
        // Pages without <meta name="theme-color"> report a transparent color.
        themeColor = if (android.graphics.Color.alpha(color) < 255) null else color
        host.onTabStateChanged(this)
    }

    override fun onNewWebView(activate: Boolean, pageIndex: Long): String? {
        val newTab = host.onTabRequestsNewTab(this, activate, pageIndex) ?: return null
        return newTab.view?.impl?.handle
    }

    override fun onCloseRequested() = host.onTabRequestsClose(this)
    override fun onActivateRequested() = host.onTabRequestsActivate(this)

    override fun onContextMenu(kind: Int, url: String?, x: Int, y: Int) = host.onTabContextMenu(this, kind, url)

    override fun onRequestAlert(message: String) = host.onTabAlert(this, message)
    override fun onRequestConfirm(message: String) = host.onTabConfirm(this, message)
    override fun onRequestPrompt(message: String, default: String) = host.onTabPrompt(this, message, default)

    override fun onRequestSelectDropdown(labels: Array<String>, ids: IntArray, enabled: BooleanArray, selectedIndex: Int) =
        host.onTabSelectDropdown(this, labels, ids, enabled, selectedIndex)

    override fun onFindInPageResult(currentIndex: Int, totalMatches: Int) = host.onTabFindResult(this, currentIndex, totalMatches)

    override fun onZoomChanged(level: Float) {
        zoomLevel = level
    }

    /** True for a tab that was opened only to start a download (a link to a file with target=_blank). */
    val hasNoPage get() = committedUrl == null || committedUrl == "about:blank"

    override fun onWebContentCrashed() {
        if (isCrashed)
            return
        isCrashed = true
        isLoading = false
        view?.let {
            view = null
            // This can be called from inside the native view's own callback; free it afterwards.
            it.post { it.dispose() }
        }
        host.onTabCrashed(this)
    }

    companion object {
        const val NEW_TAB_URL = "about:newtab"
        const val ABOUT_ERROR = "about:error"
        const val ABOUT_BLANK = "about:blank"
        const val THUMBNAIL_WIDTH = 360
    }
}
