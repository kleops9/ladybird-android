/**
 * Copyright (c) 2023, Andrew Kaster <akaster@serenityos.org>
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

package org.serenityos.ladybird

import android.annotation.SuppressLint
import android.app.Dialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.CircularProgressDrawable
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import org.serenityos.ladybird.databinding.ActivityMainBinding
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.io.path.Path
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory

class LadybirdActivity : AppCompatActivity(), TabHost {

    private lateinit var binding: ActivityMainBinding
    private lateinit var resourceDir: String
    private lateinit var store: BrowserStore
    private lateinit var downloads: Downloads
    private var timerService = TimerExecutorService()

    private val tabs = mutableListOf<Tab>()
    private var activeTab: Tab? = null
    private var nextTabId = 1L

    private var menuPopup: PopupWindow? = null
    private val suggestionAdapter = SuggestionAdapter()
    private val tabAdapter = TabCardAdapter()
    private var isEditingUrl = false
    private var isFinding = false
    /** Which tabs the tab switcher shows: normal or incognito. */
    private var switcherShowsIncognito = false

    // Whether onCreate() got as far as starting the native code, after which there is something to tear down.
    private var nativeStarted = false

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            val entry = downloads.entryFor(id) ?: return
            val status = downloads.status(entry) ?: return
            if (status.state == Downloads.State.Done) {
                Snackbar.make(binding.content, "Downloaded ${entry.fileName}", Snackbar.LENGTH_LONG)
                    .setAction("Open") { openDownload(entry) }
                    .show()
            } else if (status.state == Downloads.State.Failed) {
                Snackbar.make(binding.content, "Couldn't download ${entry.fileName}", Snackbar.LENGTH_LONG)
                    .setAction(R.string.downloads) { showDownloads() }
                    .show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        resourceDir = File(filesDir, "Lagom").absolutePath
        extractAssetsIfNeeded()
        val hubDownloads = File(cacheDir, "downloads").apply { mkdirs() }
        if (!initNativeCode(
                applicationInfo.nativeLibraryDir, resourceDir, filesDir.absolutePath, cacheDir.absolutePath,
                hubDownloads.absolutePath, File(filesDir, "cacert.pem").absolutePath, "Ladybird", timerService, isNight)) {
            Toast.makeText(this, "Ladybird couldn't start its browser process", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        nativeStarted = true

        store = BrowserStore(this)
        downloads = Downloads(this)
        applyNightMode()
        setDarkMode(isNight)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        buildUi()

        // DownloadManager runs in another process, so its completion broadcast has to be received from outside.
        ContextCompat.registerReceiver(this, downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })

        restoreTabs()
        intent?.dataString?.let { openUrlFromIntent(it) }
        if (tabs.isEmpty())
            createTab(null, activate = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let { openUrlFromIntent(it) }
    }

    private fun openUrlFromIntent(url: String) {
        val tab = createTab(null, activate = true)
        tab.load(this, url)
        showActiveTab()
    }

    override fun onPause() {
        super.onPause()
        saveTabs()
        activeTab?.view?.impl?.takeIf { it.isInitialized }?.setVisible(false)
    }

    override fun onResume() {
        super.onResume()
        activeTab?.view?.impl?.takeIf { it.isInitialized }?.setVisible(true)
    }

    override fun onDestroy() {
        if (!nativeStarted) {
            super.onDestroy()
            return
        }
        unregisterReceiver(downloadReceiver)
        for (tab in tabs)
            tab.close()
        tabs.clear()
        disposeNativeCode()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Pages follow the app into dark mode and out of it.
        if (nativeStarted)
            setDarkMode(isNight)
        // The activity handles uiMode itself so tabs survive a dark mode switch; rebuild the chrome with the new colors.
        activeTab?.view?.let { (it.parent as? ViewGroup)?.removeView(it) }
        menuPopup?.dismiss()
        val switcherWasVisible = binding.tabSwitcher.root.isVisible
        buildUi()
        for (tab in tabs)
            tab.view?.let { configureNewView(tab, it) }
        showActiveTab()
        if (switcherWasVisible)
            showTabSwitcher()
    }

    // --- Setup ---

    /** Extracts the resources that came with this version of the APK, unless they're already there. */
    private fun extractAssetsIfNeeded() {
        val version = packageManager.getPackageInfo(packageName, 0).lastUpdateTime.toString()
        val stamp = File(resourceDir, ".version")
        if (stamp.exists() && stamp.readText() == version)
            return
        val root = File(resourceDir)
        root.deleteRecursively()
        root.mkdirs()
        val zipPath = File(cacheDir, "ladybird-assets.zip")
        assets.open("ladybird-assets.zip").use { input -> zipPath.outputStream().use { output -> input.copyTo(output) } }
        ZipFile(zipPath).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val file = File(root, entry.name)
                if (!file.canonicalPath.startsWith(root.canonicalPath))
                    return@forEach
                if (!entry.isDirectory) {
                    file.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input -> file.outputStream().use { output -> input.copyTo(output) } }
                }
            }
        }
        zipPath.delete()

        // curl can't read Android's certificate directory (one file per certificate, named by hash), so it gets them all
        // in one file.
        val certMain = File(filesDir, "cacert.pem")
        certMain.outputStream().use { output ->
            Files.walk(Path("/system/etc/security/cacerts")).forEach { certPath ->
                if (!certPath.isDirectory())
                    certPath.inputStream().use { input -> input.copyTo(output) }
            }
        }
        stamp.writeText(version)
    }

    private fun applyNightMode() {
        AppCompatDelegate.setDefaultNightMode(
            when (store.theme) {
                WebViewImplementation.COLOR_SCHEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                WebViewImplementation.COLOR_SCHEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    private val isNight get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        isEditingUrl = false
        isFinding = false
        // The pull to refresh indicator belonged to the views before.
        pullIndicator = null
        pullSpinner = null
        pullRefreshingTab = null

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottom = maxOf(bars.bottom, ime.bottom)
            // The toolbar reaches under the status bar so its color (theme color, incognito) fills it too.
            binding.browserColumn.setPadding(bars.left, 0, bars.right, bottom)
            binding.toolbarContainer.setPadding(0, bars.top, 0, 0)
            binding.tabSwitcher.root.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        binding.homeButton.setOnClickListener { goHome() }
        binding.tabSwitcherButton.setOnClickListener { showTabSwitcher() }
        binding.menuButton.setOnClickListener { showMenu() }
        binding.securityIcon.setOnClickListener { if (!isEditingUrl) showPageInfo() }
        binding.clearButton.setOnClickListener { binding.urlField.setText("") }
        binding.sadTabReload.setOnClickListener { activeTab?.let { reloadCrashedTab(it) } }

        setupOmnibox()
        setupFindBar()
        setupNewTabPage()
        setupTabSwitcher()
        applyChromeColors()
        updateFrameRateOverlay()
    }

    // --- Frame rate overlay (Settings › Show frame rate) ---

    private var frameRateView: WebView? = null
    private var frameRateLastCount = 0L
    private val frameRateTicker = object : Runnable {
        override fun run() {
            if (!store.showFrameRate)
                return
            val view = activeTab?.view
            val count = view?.framesReceived ?: 0L
            val fps = if (view != null && view === frameRateView) count - frameRateLastCount else 0L
            frameRateView = view
            frameRateLastCount = count
            binding.frameRate.text = "$fps fps · ${if (store.cpuPainting) "CPU" else "GPU"}"
            binding.frameRate.postDelayed(this, 1000)
        }
    }

    private fun updateFrameRateOverlay() {
        binding.frameRate.removeCallbacks(frameRateTicker)
        binding.frameRate.isVisible = store.showFrameRate
        if (store.showFrameRate)
            binding.frameRate.post(frameRateTicker)
    }

    // --- Toolbar colors ---

    /**
     * Colors the toolbar (and the status bar behind it) for the active tab: Ladybird's own colors, the page's
     * <meta name="theme-color">, or the indigo of incognito.
     */
    private fun applyChromeColors() {
        val tab = activeTab
        val switcherVisible = binding.tabSwitcher.root.isVisible
        val incognito = tab?.isIncognito == true
        // In dark mode, a page without a dark theme of its own is darkened, but its theme color is still the light one.
        val themeColor = (if (!incognito && !isEditingUrl && !isFinding) tab?.themeColor else null)
            ?.takeUnless { isNight && ColorUtils.calculateLuminance(it) >= 0.4 }

        val background: Int
        val foreground: Int
        val textColor: Int
        val hintColor: Int
        val omnibox: Drawable
        val progressColor: Int
        val lightBars: Boolean
        when {
            incognito -> {
                background = getColor(R.color.incognito_toolbar)
                foreground = getColor(R.color.incognito_text_secondary)
                textColor = getColor(R.color.incognito_text)
                hintColor = ColorUtils.setAlphaComponent(getColor(R.color.incognito_text_secondary), 0xB0)
                omnibox = pill(getColor(R.color.incognito_omnibox), if (isEditingUrl || isFinding) getColor(R.color.brand_violet_100) else getColor(R.color.incognito_border), if (isEditingUrl || isFinding) 1.5f else 1f)
                progressColor = getColor(R.color.brand_violet_100)
                lightBars = false
            }
            themeColor != null -> {
                val dark = ColorUtils.calculateLuminance(themeColor) < 0.4
                background = themeColor
                foreground = if (dark) Color.WHITE else 0xFF0A0A0A.toInt()
                textColor = foreground
                hintColor = ColorUtils.setAlphaComponent(foreground, 0xB0)
                omnibox = pill(ColorUtils.blendARGB(themeColor, if (dark) Color.WHITE else Color.BLACK, if (dark) 0.16f else 0.07f), null, 0f)
                progressColor = foreground
                lightBars = !dark
            }
            else -> {
                background = getColor(R.color.toolbar_bg)
                foreground = getColor(R.color.icon_tint)
                textColor = getColor(R.color.text_primary)
                hintColor = getColor(R.color.text_secondary)
                omnibox = getDrawable(if (isEditingUrl || isFinding) R.drawable.bg_omnibox_focused else R.drawable.bg_omnibox)!!
                progressColor = getColor(R.color.accent)
                lightBars = !isNight
            }
        }

        binding.toolbarContainer.setBackgroundColor(background)
        binding.toolbarDivider.isVisible = !incognito && themeColor == null
        binding.omnibox.background = omnibox
        binding.findBox.background = omnibox.constantState?.newDrawable()?.mutate() ?: omnibox
        binding.urlField.setTextColor(textColor)
        binding.urlField.setHintTextColor(hintColor)
        binding.findField.setTextColor(textColor)
        binding.findField.setHintTextColor(hintColor)
        binding.findCount.setTextColor(hintColor)
        val tint = ColorStateList.valueOf(foreground)
        for (icon in listOf(binding.homeButton, binding.menuButton, binding.securityIcon, binding.clearButton, binding.findIcon, binding.findPrevious, binding.findNext, binding.findClose))
            icon.imageTintList = tint
        binding.tabCount.setTextColor(foreground)
        (binding.tabCount.background.mutate() as? GradientDrawable)?.setStroke(dpf(1.6f).toInt(), foreground)
        binding.progress.setIndicatorColor(progressColor)

        // Incognito keyboards shouldn't learn what's typed into the address bar either.
        val imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        binding.urlField.imeOptions = if (incognito) imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING else imeOptions

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = if (switcherVisible) !isNight else lightBars
            isAppearanceLightNavigationBars = !isNight
        }
    }

    private fun pill(fill: Int, stroke: Int?, strokeWidthDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dpf(999f)
        setColor(fill)
        if (stroke != null)
            setStroke(dpf(strokeWidthDp).toInt().coerceAtLeast(1), stroke)
    }

    // --- Omnibox ---

    private fun setupOmnibox() {
        val urlField = binding.urlField
        urlField.setOnFocusChangeListener { _, hasFocus -> setEditingUrl(hasFocus) }
        urlField.setOnEditorActionListener { textView, actionId, event ->
            val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH || isEnter) {
                navigate(textView.text.toString())
                true
            } else {
                false
            }
        }
        urlField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!isEditingUrl)
                    return
                binding.clearButton.isVisible = !s.isNullOrEmpty()
                updateSuggestions(s?.toString() ?: "")
            }
        })

        binding.suggestions.layoutManager = LinearLayoutManager(this)
        binding.suggestions.adapter = suggestionAdapter
    }

    private fun setEditingUrl(editing: Boolean) {
        isEditingUrl = editing
        val tab = activeTab
        // While editing, the address bar takes the whole toolbar and shows the full URL.
        binding.homeButton.isVisible = !editing
        binding.tabSwitcherButton.isVisible = !editing
        binding.menuButton.isVisible = !editing
        binding.suggestions.isVisible = editing
        if (editing) {
            val fullUrl = if (tab == null || tab.url == Tab.NEW_TAB_URL) "" else tab.url
            binding.urlField.setText(fullUrl)
            binding.urlField.selectAll()
            binding.clearButton.isVisible = fullUrl.isNotEmpty()
            binding.securityIcon.setImageResource(R.drawable.ic_search)
            updateSuggestions("")
            showKeyboard(binding.urlField)
        } else {
            binding.clearButton.isVisible = false
            hideKeyboard(binding.urlField)
            updateToolbar()
        }
        applyChromeColors()
    }

    private fun stopEditingUrl() {
        if (binding.urlField.hasFocus()) {
            binding.urlField.clearFocus()
            binding.root.requestFocus()
        }
        setEditingUrl(false)
    }

    private fun navigate(text: String) {
        val url = WebViewImplementation.sanitizeURL(text, store.searchEngine) ?: return
        val tab = activeTab ?: createTab(null, activate = true)
        stopEditingUrl()
        tab.load(this, url)
        showActiveTab()
    }

    private fun updateSuggestions(query: String) {
        val items = mutableListOf<SuggestionItem>()
        val trimmed = query.trim()
        if (trimmed.isNotEmpty()) {
            val looksLikeUrl = !trimmed.contains(' ') && (trimmed.contains('.') || trimmed.contains(':'))
            if (looksLikeUrl)
                items.add(SuggestionItem(trimmed, "", R.drawable.ic_public, trimmed))
            else
                items.add(SuggestionItem(trimmed, "${store.searchEngine} Search", R.drawable.ic_search, trimmed))
            for (s in store.suggestions(trimmed, 6))
                items.add(SuggestionItem(s.title.ifBlank { s.url }, BrowserStore.stripScheme(s.url), if (s.isBookmark) R.drawable.ic_star else R.drawable.ic_history, s.url))
            if (looksLikeUrl)
                items.add(SuggestionItem(trimmed, "${store.searchEngine} Search", R.drawable.ic_search, "search:$trimmed"))
        } else if (activeTab?.isIncognito != true) {
            // With nothing typed yet, offer recent pages (but don't put history on screen in incognito).
            for (h in store.history.take(8))
                items.add(SuggestionItem(h.title.ifBlank { h.url }, BrowserStore.stripScheme(h.url), R.drawable.ic_history, h.url))
        }
        suggestionAdapter.submit(items)
    }

    private data class SuggestionItem(val title: String, val subtitle: String, val icon: Int, val target: String)

    private inner class SuggestionAdapter : RecyclerView.Adapter<SuggestionAdapter.Holder>() {
        private var items = listOf<SuggestionItem>()

        @SuppressLint("NotifyDataSetChanged")
        fun submit(newItems: List<SuggestionItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.suggestion_icon)
            val title: TextView = view.findViewById(R.id.suggestion_title)
            val url: TextView = view.findViewById(R.id.suggestion_url)
            val fill: ImageButton = view.findViewById(R.id.suggestion_fill)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_suggestion, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.icon.setImageResource(item.icon)
            holder.title.text = item.title
            holder.url.text = item.subtitle
            holder.url.isVisible = item.subtitle.isNotEmpty()
            holder.itemView.setOnClickListener {
                if (item.target.startsWith("search:")) {
                    val query = item.target.removePrefix("search:")
                    val url = WebViewImplementation.sanitizeURL("$query ", store.searchEngine)
                    if (url != null) {
                        stopEditingUrl()
                        (activeTab ?: createTab(null, true)).load(this@LadybirdActivity, url)
                        showActiveTab()
                    }
                } else {
                    navigate(item.target)
                }
            }
            holder.fill.setOnClickListener {
                val text = if (item.target.startsWith("search:")) item.target.removePrefix("search:") else item.target
                binding.urlField.setText(text)
                binding.urlField.setSelection(text.length)
            }
        }
    }

    // --- Find in page ---

    private fun setupFindBar() {
        binding.findField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!isFinding)
                    return
                val query = s?.toString() ?: ""
                activeTab?.view?.impl?.findInPage(query)
                if (query.isEmpty())
                    binding.findCount.text = ""
            }
        })
        binding.findField.setOnEditorActionListener { _, actionId, event ->
            val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || isEnter) {
                activeTab?.view?.impl?.findInPageNext()
                true
            } else {
                false
            }
        }
        binding.findPrevious.setOnClickListener { activeTab?.view?.impl?.findInPagePrevious() }
        binding.findNext.setOnClickListener { activeTab?.view?.impl?.findInPageNext() }
        binding.findClose.setOnClickListener { hideFindBar() }
    }

    private fun showFindBar() {
        if (activeTab?.view == null)
            return
        stopEditingUrl()
        isFinding = true
        binding.toolbar.isVisible = false
        binding.findBar.isVisible = true
        binding.findCount.text = ""
        binding.findField.setText("")
        binding.findField.requestFocus()
        showKeyboard(binding.findField)
        applyChromeColors()
    }

    private fun hideFindBar() {
        if (!isFinding)
            return
        isFinding = false
        // An empty query clears the highlighted matches.
        activeTab?.view?.impl?.takeIf { it.isInitialized }?.findInPage("")
        hideKeyboard(binding.findField)
        binding.findField.clearFocus()
        binding.root.requestFocus()
        binding.findBar.isVisible = false
        binding.toolbar.isVisible = true
        applyChromeColors()
    }

    override fun onTabFindResult(tab: Tab, currentIndex: Int, totalMatches: Int) {
        if (tab !== activeTab || !isFinding)
            return
        binding.findCount.text = when {
            binding.findField.text.isEmpty() -> ""
            totalMatches == 0 -> "0/0"
            totalMatches < 0 -> "${currentIndex + 1}"
            else -> "${currentIndex + 1}/$totalMatches"
        }
    }

    // --- New tab page ---

    private fun setupNewTabPage() {
        binding.ntp.ntpSearch.setOnClickListener { binding.urlField.requestFocus() }
    }

    private fun refreshNewTabPage() {
        val grid = binding.ntp.ntpTiles
        grid.removeAllViews()
        val entries = store.mostVisited(8)
        binding.ntp.ntpTilesLabel.isVisible = entries.isNotEmpty()
        for (entry in entries) {
            val tile = layoutInflater.inflate(R.layout.item_ntp_tile, grid, false)
            val host = BrowserStore.hostOf(entry.url)
            tile.findViewById<TextView>(R.id.tile_letter).text = host.firstOrNull()?.uppercase() ?: "?"
            tile.findViewById<TextView>(R.id.tile_title).text = entry.title.ifBlank { host }
            tile.setOnClickListener { navigate(entry.url) }
            tile.layoutParams = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0 }
            grid.addView(tile)
        }
    }

    // --- Tabs ---

    private fun createTab(url: String?, activate: Boolean, opener: Tab? = null, incognito: Boolean = opener?.isIncognito ?: false): Tab {
        val tab = Tab(nextTabId++, this, openerId = opener?.id, isIncognito = incognito)
        val index = if (opener != null) tabs.indexOf(opener) + 1 + tabs.count { it.openerId == opener.id } else tabs.size
        tabs.add(index.coerceIn(0, tabs.size), tab)
        if (url != null)
            tab.load(this, url)
        if (activate)
            activateTab(tab)
        else
            updateTabCount()
        return tab
    }

    private fun activateTab(tab: Tab) {
        val previous = activeTab
        if (previous != null && previous !== tab) {
            hideFindBar()
            previous.captureThumbnail()
            previous.view?.impl?.takeIf { it.isInitialized }?.setVisible(false)
        }
        activeTab = tab
        tab.lastActive = android.os.SystemClock.elapsedRealtime()
        // A discarded tab reloads its page when it is shown again.
        if (tab.view == null && !tab.isCrashed && tab.url != Tab.NEW_TAB_URL)
            tab.load(this, tab.url)
        tab.view?.impl?.takeIf { it.isInitialized }?.setVisible(true)
        showActiveTab()
    }

    private fun closeTab(tab: Tab) {
        val index = tabs.indexOf(tab)
        if (index < 0)
            return
        tab.close()
        tabs.removeAt(index)
        if (tab.isIncognito && tabs.none { it.isIncognito }) {
            // The last incognito tab is gone (and its process with it): forget its cookies and site storage.
            WebViewImplementation.clearPrivateBrowsingData()
        }
        if (activeTab === tab) {
            activeTab = null
            hideFindBar()
            // Prefer the opener, then a neighbour of the same kind (normal or incognito), then any tab.
            val opener = tabs.find { it.id == tab.openerId }
            val sameKind = tabs.filter { it.isIncognito == tab.isIncognito }
            val neighbour = sameKind.lastOrNull { tabs.indexOf(it) < index } ?: sameKind.firstOrNull()
            val next = opener ?: neighbour ?: tabs.lastOrNull { !it.isIncognito } ?: tabs.lastOrNull()
            if (next != null)
                activateTab(next)
            else if (!binding.tabSwitcher.root.isVisible)
                createTab(null, activate = true)
        }
        updateTabCount()
        tabAdapter.refresh()
        saveTabs()
    }

    /** Puts the active tab's content on screen: its page, a new tab page, or the crash page. */
    private fun showActiveTab() {
        val tab = activeTab ?: return
        val container = binding.webContainer
        val view = tab.view
        for (i in container.childCount - 1 downTo 0) {
            if (container.getChildAt(i) !== view)
                container.removeViewAt(i)
        }
        if (view != null && view.parent !== container) {
            (view.parent as? ViewGroup)?.removeView(view)
            container.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val showNtp = tab.isNewTabPage && !tab.isCrashed
        binding.sadTab.isVisible = tab.isCrashed
        binding.ntp.root.isVisible = showNtp && !tab.isIncognito
        binding.incognitoNtp.root.isVisible = showNtp && tab.isIncognito
        if (binding.ntp.root.isVisible)
            refreshNewTabPage()
        updateToolbar()
        updateTabCount()
        applyChromeColors()
    }

    private fun updateTabCount() {
        // Like the switcher, the toolbar counts the tabs of the kind you're in.
        val incognito = activeTab?.isIncognito == true
        val count = tabs.count { it.isIncognito == incognito }
        binding.tabCount.text = if (count > 99) ":D" else count.toString()
        val normal = tabs.count { !it.isIncognito }
        binding.tabSwitcher.switcherSegmentNormal.text = resources.getQuantityString(R.plurals.open_tabs, normal, normal)
        binding.tabSwitcher.switcherIncognitoCount.text = tabs.count { it.isIncognito }.toString()
    }

    private fun updateToolbar() {
        val tab = activeTab ?: return
        if (!isEditingUrl) {
            val url = tab.url
            binding.urlField.setText(if (url == Tab.NEW_TAB_URL) "" else displayUrl(url))
            binding.securityIcon.setImageResource(
                when {
                    url == Tab.NEW_TAB_URL -> if (tab.isIncognito) R.drawable.ic_incognito else R.drawable.ic_search
                    tab.isErrorPage -> R.drawable.ic_info
                    url.startsWith("https://") -> R.drawable.ic_lock
                    else -> R.drawable.ic_info
                }
            )
        }
        val progress = binding.progress
        if (tab.isLoading) {
            progress.visibility = View.VISIBLE
            progress.setProgressCompat((tab.loadProgress * 1000).toInt(), true)
        } else if (progress.visibility == View.VISIBLE) {
            progress.setProgressCompat(1000, true)
            progress.postDelayed({ if (activeTab?.isLoading != true) progress.visibility = View.INVISIBLE }, 250)
        }
    }

    private fun displayUrl(url: String) = url.removePrefix("https://").removeSuffix("/")

    private fun goHome() {
        val tab = activeTab ?: return
        stopEditingUrl()
        hideFindBar()
        tab.discard()
        tab.url = Tab.NEW_TAB_URL
        tab.title = ""
        tab.favicon = null
        tab.thumbnail = null
        tab.themeColor = null
        tab.isCrashed = false
        tab.isErrorPage = false
        showActiveTab()
    }

    private fun reloadCrashedTab(tab: Tab) {
        tab.isCrashed = false
        tab.load(this, tab.url)
        showActiveTab()
    }

    private fun saveTabs() {
        if (!::store.isInitialized)
            return
        // Incognito tabs are never written to disk.
        val normal = tabs.filter { !it.isIncognito }
        val saved = normal.map { BrowserStore.SavedTab(it.url, it.title) }
        store.saveTabs(saved, normal.indexOf(activeTab).coerceAtLeast(0))
    }

    private fun restoreTabs() {
        val (saved, activeIndex) = store.loadTabs()
        for (s in saved) {
            // Restored tabs stay unloaded until they are shown.
            tabs.add(Tab(nextTabId++, this, url = s.url, title = s.title))
        }
        tabs.getOrNull(activeIndex.coerceIn(0, maxOf(0, tabs.size - 1)))?.let { activateTab(it) }
    }

    // --- TabHost ---

    override fun configureNewView(tab: Tab, view: WebView) {
        view.presize(binding.webContainer.width, binding.webContainer.height)
        // A tab gets its view when it first loads a page, after it was shown on the new tab page. The Hub doesn't paint a
        // view it thinks is hidden.
        if (tab === activeTab && !binding.tabSwitcher.root.isVisible)
            view.impl.setVisible(true)
    }

    // --- Pull to refresh ---

    private var pullIndicator: ImageView? = null
    private var pullSpinner: CircularProgressDrawable? = null
    /** The tab that a pull is refreshing, until its page has loaded. */
    private var pullRefreshingTab: Tab? = null
    private var pullRefreshSawLoading = false
    private val hidePullIndicatorRunnable = Runnable { hidePullIndicator() }

    private fun pullIndicatorView(): ImageView {
        pullIndicator?.let { return it }
        val spinner = CircularProgressDrawable(this).apply {
            setStyle(CircularProgressDrawable.DEFAULT)
            setColorSchemeColors(getColor(R.color.accent))
        }
        val indicator = ImageView(this).apply {
            setImageDrawable(spinner)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(getColor(R.color.toolbar_bg))
            }
            elevation = dpf(6f)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isVisible = false
        }
        binding.content.addView(indicator, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        pullIndicator = indicator
        pullSpinner = spinner
        return indicator
    }

    override fun onTabPullToRefresh(tab: Tab, progress: Float) {
        if (tab !== activeTab || pullRefreshingTab != null)
            return
        val indicator = pullIndicatorView()
        val spinner = pullSpinner!!
        indicator.animate().cancel()
        indicator.isVisible = true
        // The indicator comes down with the finger, more and more slowly past the distance that refreshes.
        val pulled = if (progress <= 1f) progress else 1f + (1f - 1f / progress) * 0.5f
        indicator.translationY = -dpf(40f) + pulled * dpf(104f)
        indicator.alpha = (0.3f + progress).coerceAtMost(1f)
        spinner.stop()
        spinner.arrowEnabled = true
        spinner.setArrowScale(progress.coerceAtMost(1f))
        spinner.setStartEndTrim(0f, 0.8f * progress.coerceAtMost(1f))
        spinner.progressRotation = 0.25f * progress
        indicator.rotation = 0f
    }

    override fun onTabPullToRefreshEnd(tab: Tab, refresh: Boolean) {
        if (tab !== activeTab || pullRefreshingTab != null)
            return
        if (!refresh) {
            hidePullIndicator()
            return
        }
        val indicator = pullIndicatorView()
        val spinner = pullSpinner!!
        indicator.animate().translationY(dpf(24f)).alpha(1f).setDuration(150).start()
        spinner.arrowEnabled = false
        spinner.start()
        pullRefreshingTab = tab
        pullRefreshSawLoading = false
        tab.view?.impl?.reload()
        indicator.removeCallbacks(hidePullIndicatorRunnable)
        indicator.postDelayed(hidePullIndicatorRunnable, 15_000)
    }

    private fun hidePullIndicator() {
        pullRefreshingTab = null
        val indicator = pullIndicator ?: return
        indicator.removeCallbacks(hidePullIndicatorRunnable)
        indicator.animate().translationY(-dpf(40f)).alpha(0f).setDuration(200).withEndAction {
            indicator.isVisible = false
            pullSpinner?.stop()
        }.start()
    }

    override fun onTabStateChanged(tab: Tab) {
        // The indicator of a pull to refresh spins until the page has started loading again and finished.
        if (tab === pullRefreshingTab) {
            if (tab.isLoading)
                pullRefreshSawLoading = true
            else if (pullRefreshSawLoading)
                hidePullIndicator()
        }
        if (tab === activeTab) {
            if (tab.view != null && (binding.ntp.root.isVisible || binding.incognitoNtp.root.isVisible)) {
                showActiveTab()
            } else {
                updateToolbar()
                applyChromeColors()
            }
        }
        if (binding.tabSwitcher.root.isVisible)
            tabAdapter.refresh()
    }

    override fun onTabNavigated(tab: Tab, url: String, title: String) {
        if (tab.isIncognito)
            return
        store.recordVisit(url, title)
        // Save as we go: Android can kill the app in the background without calling onPause again.
        saveTabs()
    }

    override fun onTabRequestsNewTab(opener: Tab, activate: Boolean, pageIndex: Long): Tab? {
        val tab = Tab(nextTabId++, this, url = "about:blank", openerId = opener.id, isIncognito = opener.isIncognito)
        val index = tabs.indexOf(opener) + 1
        tabs.add(index.coerceIn(0, tabs.size), tab)
        tab.ensureView(this, pageIndex)
        if (activate)
            activateTab(tab)
        else
            updateTabCount()
        return tab
    }

    override fun onTabRequestsClose(tab: Tab) {
        binding.root.post { closeTab(tab) }
    }

    override fun onTabRequestsActivate(tab: Tab) {
        if (tab !== activeTab)
            activateTab(tab)
    }

    override fun onTabCrashed(tab: Tab) {
        if (tab === activeTab)
            showActiveTab()
    }

    override fun onTabContextMenu(tab: Tab, kind: Int, url: String?) {
        if (url == null || kind == WebViewImplementation.CONTEXT_MENU_PAGE)
            return
        val sheet = BottomSheetDialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(16))
        }
        content.addView(TextView(this).apply {
            text = url
            maxLines = 2
            setTextAppearance(R.style.TextAppearance_Ladybird_Muted)
            setPadding(dp(20), dp(4), dp(20), dp(12))
        })
        content.addView(View(this).apply {
            setBackgroundColor(getColor(R.color.divider))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { setMargins(0, 0, 0, dp(4)) }
        })
        fun item(icon: Int, label: String, action: () -> Unit) {
            content.addView(menuRow(icon, label) {
                sheet.dismiss()
                action()
            })
        }
        val isImage = kind == WebViewImplementation.CONTEXT_MENU_IMAGE
        if (tab.isIncognito) {
            item(R.drawable.ic_incognito, if (isImage) "Open image in incognito tab" else "Open in incognito tab") { openInBackground(tab, url) }
        } else {
            item(R.drawable.ic_open_in_new, if (isImage) "Open image in new tab" else "Open in new tab") { openInBackground(tab, url) }
            item(R.drawable.ic_incognito, "Open in incognito tab") {
                createTab(url, activate = true, incognito = true)
            }
        }
        item(R.drawable.ic_download, if (isImage) "Download image" else "Download link") {
            onTabDownload(tab, url, "", "")
        }
        item(R.drawable.ic_content_copy, if (isImage) "Copy image address" else "Copy link address") { copyToClipboard(url) }
        item(R.drawable.ic_share, if (isImage) "Share image link" else "Share link") { shareUrl(url, null) }
        sheet.setContentView(content)
        sheet.show()
    }

    private fun openInBackground(opener: Tab, url: String) {
        val tab = createTab(url, activate = false, opener = opener)
        Snackbar.make(binding.content, if (tab.isIncognito) "Incognito tab opened in background" else "Tab opened in background", Snackbar.LENGTH_LONG)
            .setAction("Switch") { activateTab(tab) }
            .show()
    }

    override fun onTabAlert(tab: Tab, message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(dialogTitle(tab))
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener { tab.view?.impl?.alertClosed() }
            .show()
    }

    override fun onTabConfirm(tab: Tab, message: String) {
        var accepted = false
        MaterialAlertDialogBuilder(this)
            .setTitle(dialogTitle(tab))
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> accepted = true }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { tab.view?.impl?.confirmClosed(accepted) }
            .show()
    }

    override fun onTabPrompt(tab: Tab, message: String, default: String) {
        val input = EditText(this).apply { setText(default) }
        val frame = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        var response: String? = null
        MaterialAlertDialogBuilder(this)
            .setTitle(dialogTitle(tab))
            .setMessage(message)
            .setView(frame)
            .setPositiveButton(android.R.string.ok) { _, _ -> response = input.text.toString() }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { tab.view?.impl?.promptClosed(response) }
            .show()
    }

    private fun dialogTitle(tab: Tab) = "${BrowserStore.hostOf(tab.url)} says"

    override fun onTabSelectDropdown(tab: Tab, labels: Array<String>, ids: IntArray, enabled: BooleanArray, selectedIndex: Int) {
        var chosen = -1
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_single_choice, labels) {
            override fun isEnabled(position: Int) = enabled[position]
            override fun areAllItemsEnabled() = false
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                view.alpha = if (enabled[position] || ids[position] < 0) 1f else 0.4f
                return view
            }
        }
        MaterialAlertDialogBuilder(this)
            .setSingleChoiceItems(adapter, selectedIndex) { dialog, which ->
                if (enabled[which]) {
                    chosen = ids[which]
                    dialog.dismiss()
                }
            }
            .setOnDismissListener { tab.view?.impl?.selectDropdownClosed(chosen) }
            .show()
    }

    // --- Downloads ---

    override fun onTabDownload(tab: Tab, url: String, contentDisposition: String, mimeType: String) {
        // FIXME: Have the Hub download links too, so that it sends the page's cookies.
        val cookies: String? = null
        val userAgent = WebViewImplementation.userAgent(!store.desktopSiteByDefault)
        try {
            val entry = downloads.start(url, contentDisposition, mimeType, cookies, userAgent, tab.url)
            Snackbar.make(binding.content, "Downloading ${entry.fileName}", Snackbar.LENGTH_LONG)
                .setAction(R.string.downloads) { showDownloads() }
                .show()
        } catch (e: Exception) {
            Snackbar.make(binding.content, "Couldn't download: ${e.message ?: url}", Snackbar.LENGTH_LONG).show()
        }
        // A tab opened only to fetch the file (a target=_blank link to it) has nothing to show; close it.
        if (tab.hasNoPage && tab.openerId != null && tabs.any { it.id == tab.openerId })
            binding.root.post { closeTab(tab) }
    }

    private fun openDownload(entry: Downloads.Entry) {
        if (!downloads.open(entry))
            Toast.makeText(this, "No app can open ${entry.fileName}", Toast.LENGTH_SHORT).show()
    }

    private fun showDownloads() {
        downloads.prune()
        showListScreen(getString(R.string.downloads), null, "No downloads yet.\nLong-press a link and choose “Download link”, or open a file.", {
            downloads.entries.map { entry ->
                val status = downloads.status(entry)
                val (subtitle, progress) = when (status?.state) {
                    Downloads.State.Done, null -> {
                        val size = if (status != null && status.totalBytes > 0) "${Downloads.formatBytes(status.totalBytes)} · " else ""
                        Pair("$size${hostOrData(entry.url)} · ${DateUtils.getRelativeTimeSpanString(entry.started)}", null)
                    }
                    Downloads.State.Failed -> Pair("Failed · ${hostOrData(entry.url)}", null)
                    Downloads.State.Paused -> Pair("Paused · ${Downloads.formatBytes(status.bytesSoFar)}", progressOf(status))
                    Downloads.State.Pending -> Pair("Waiting…", -1f)
                    Downloads.State.Running -> Pair(
                        if (status.totalBytes > 0) "${Downloads.formatBytes(status.bytesSoFar)} of ${Downloads.formatBytes(status.totalBytes)}"
                        else Downloads.formatBytes(status.bytesSoFar),
                        progressOf(status),
                    )
                }
                ListEntry(
                    title = entry.fileName,
                    url = entry.url,
                    subtitle = subtitle,
                    icon = if (entry.mimeType.startsWith("image/")) R.drawable.ic_image else R.drawable.ic_file,
                    progress = progress,
                    onOpen = { if (status?.state == Downloads.State.Done || status == null) openDownload(entry) },
                    removeConfirmation = "Delete ${entry.fileName}?",
                ) { downloads.delete(entry) }
            }
        }, null, liveRefresh = true)
    }

    private fun progressOf(status: Downloads.Status) = if (status.totalBytes > 0) status.bytesSoFar.toFloat() / status.totalBytes else -1f

    private fun hostOrData(url: String) = if (url.startsWith("data:")) "Saved from page" else BrowserStore.hostOf(url)

    // --- Tab switcher ---

    private fun switcherTabs() = tabs.filter { it.isIncognito == switcherShowsIncognito }

    private fun setupTabSwitcher() {
        val switcher = binding.tabSwitcher
        val columns = if (resources.configuration.screenWidthDp >= 600) 3 else 2
        switcher.switcherGrid.layoutManager = GridLayoutManager(this, columns)
        switcher.switcherGrid.adapter = tabAdapter
        switcher.switcherNewTab.setOnClickListener {
            hideTabSwitcher()
            createTab(null, activate = true, incognito = switcherShowsIncognito)
            if (!switcherShowsIncognito)
                binding.urlField.requestFocus()
        }
        switcher.switcherSegmentNormal.setOnClickListener { setSwitcherMode(false) }
        switcher.switcherSegmentIncognito.setOnClickListener { setSwitcherMode(true) }
        switcher.switcherMenu.setOnClickListener { anchor ->
            val popup = androidx.appcompat.widget.PopupMenu(this, anchor)
            popup.menu.add(0, 1, 0, if (switcherShowsIncognito) R.string.close_incognito_tabs else R.string.close_all_tabs)
            popup.menu.add(0, 3, 1, R.string.new_incognito_tab)
            popup.menu.add(0, 2, 2, R.string.settings)
            popup.setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> closeAllTabs(switcherShowsIncognito)
                    2 -> showSettings()
                    3 -> {
                        hideTabSwitcher()
                        createTab(null, activate = true, incognito = true)
                    }
                }
                true
            }
            popup.show()
        }
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                tabAdapter.tabAt(vh.bindingAdapterPosition)?.let { closeTab(it) }
            }
        }).attachToRecyclerView(switcher.switcherGrid)
    }

    private fun setSwitcherMode(incognito: Boolean) {
        switcherShowsIncognito = incognito
        binding.tabSwitcher.switcherSegmentNormal.isSelected = !incognito
        binding.tabSwitcher.switcherSegmentIncognito.isSelected = incognito
        tabAdapter.refresh()
        binding.tabSwitcher.switcherGrid.scrollToPosition(switcherTabs().indexOf(activeTab).coerceAtLeast(0))
    }

    private fun showTabSwitcher() {
        stopEditingUrl()
        hideFindBar()
        activeTab?.captureThumbnail()
        updateTabCount()
        binding.tabSwitcher.root.isVisible = true
        setSwitcherMode(activeTab?.isIncognito == true)
        applyChromeColors()
    }

    private fun hideTabSwitcher() {
        binding.tabSwitcher.root.isVisible = false
        if (activeTab == null || tabs.isEmpty())
            createTab(null, activate = true)
        applyChromeColors()
    }

    private fun closeAllTabs(incognito: Boolean) {
        for (tab in tabs.filter { it.isIncognito == incognito }) {
            tab.close()
            tabs.remove(tab)
        }
        if (incognito)
            WebViewImplementation.clearPrivateBrowsingData()
        if (activeTab?.let { it !in tabs } != false)
            activeTab = tabs.lastOrNull()
        tabAdapter.refresh()
        updateTabCount()
        saveTabs()
    }

    private inner class TabCardAdapter : RecyclerView.Adapter<TabCardAdapter.Holder>() {
        private var items = listOf<Tab>()

        fun tabAt(position: Int) = items.getOrNull(position)

        @SuppressLint("NotifyDataSetChanged")
        fun refresh() {
            items = switcherTabs()
            notifyDataSetChanged()
            binding.tabSwitcher.switcherEmpty.isVisible = items.isEmpty() && switcherShowsIncognito
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val favicon: ImageView = view.findViewById(R.id.card_favicon)
            val title: TextView = view.findViewById(R.id.card_title)
            val close: ImageButton = view.findViewById(R.id.card_close)
            val thumbnail: ImageView = view.findViewById(R.id.card_thumbnail)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_tab_card, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val tab = items[position]
            val selected = tab === activeTab
            if (tab.isIncognito) {
                holder.itemView.setBackgroundResource(if (selected) R.drawable.bg_card_incognito_selected else R.drawable.bg_card_incognito)
                holder.title.setTextColor(getColor(R.color.incognito_text))
                holder.close.imageTintList = ColorStateList.valueOf(getColor(R.color.incognito_text_secondary))
            } else {
                holder.itemView.setBackgroundResource(if (selected) R.drawable.bg_card_selected else R.drawable.bg_card)
                holder.title.setTextColor(getColor(R.color.text_primary))
                holder.close.imageTintList = null
            }
            holder.title.text = tab.displayTitle
            holder.favicon.imageTintList = null
            when {
                tab.favicon != null -> holder.favicon.setImageBitmap(tab.favicon)
                tab.isIncognito -> {
                    holder.favicon.setImageResource(R.drawable.ic_incognito)
                    holder.favicon.imageTintList = ColorStateList.valueOf(getColor(R.color.incognito_text_secondary))
                }
                else -> holder.favicon.setImageResource(R.drawable.ic_public)
            }
            if (tab.isNewTabPage && tab.isIncognito) {
                holder.thumbnail.setImageResource(R.drawable.bg_brand_band)
                holder.thumbnail.scaleType = ImageView.ScaleType.FIT_XY
            } else {
                holder.thumbnail.scaleType = ImageView.ScaleType.MATRIX
                setTopCropImage(holder.thumbnail, if (tab.isNewTabPage) null else tab.thumbnail)
            }
            holder.itemView.setOnClickListener {
                activateTab(tab)
                hideTabSwitcher()
            }
            holder.close.setOnClickListener { closeTab(tab) }
        }
    }

    private fun setTopCropImage(view: ImageView, bitmap: Bitmap?) {
        view.setImageBitmap(bitmap)
        if (bitmap == null)
            return
        view.post {
            if (view.width == 0)
                return@post
            val scale = view.width.toFloat() / bitmap.width
            view.imageMatrix = Matrix().apply { setScale(scale, scale) }
        }
    }

    // --- Overflow menu ---

    private fun showMenu() {
        val tab = activeTab ?: return
        val content = layoutInflater.inflate(R.layout.view_menu, binding.root, false)
        val popup = PopupWindow(content, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.elevation = dp(10).toFloat()
        popup.setBackgroundDrawable(getDrawable(R.drawable.bg_menu))
        menuPopup = popup

        fun iconButton(id: Int, enabled: Boolean, action: () -> Unit) {
            content.findViewById<ImageButton>(id).apply {
                isEnabled = enabled
                alpha = if (enabled) 1f else 0.38f
                setOnClickListener {
                    popup.dismiss()
                    action()
                }
            }
        }
        val hasPage = tab.view != null
        iconButton(R.id.menu_back, tab.canGoBack) { tab.view?.impl?.traverseHistory(-1) }
        iconButton(R.id.menu_forward, tab.canGoForward) { tab.view?.impl?.traverseHistory(1) }
        val bookmarkable = BrowserStore.isRecordable(tab.url)
        content.findViewById<ImageButton>(R.id.menu_bookmark).setImageResource(if (store.isBookmarked(tab.url)) R.drawable.ic_star else R.drawable.ic_star_border)
        iconButton(R.id.menu_bookmark, bookmarkable) {
            val added = store.toggleBookmark(tab.url, tab.title)
            Snackbar.make(binding.content, if (added) "Bookmarked" else "Bookmark removed", Snackbar.LENGTH_SHORT).show()
        }
        iconButton(R.id.menu_info, hasPage) { showPageInfo() }
        iconButton(R.id.menu_reload, hasPage) { tab.view?.impl?.reload() }

        val items = content.findViewById<LinearLayout>(R.id.menu_items)
        fun add(icon: Int, label: Int, action: () -> Unit): View {
            val row = menuRow(icon, getString(label)) {
                popup.dismiss()
                action()
            }
            items.addView(row)
            return row
        }
        fun divider() = items.addView(View(this).apply {
            setBackgroundColor(getColor(R.color.divider))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { setMargins(0, dp(4), 0, dp(4)) }
        })
        add(R.drawable.ic_add, R.string.new_tab) {
            createTab(null, activate = true)
            binding.urlField.requestFocus()
        }
        add(R.drawable.ic_incognito, R.string.new_incognito_tab) { createTab(null, activate = true, incognito = true) }
        divider()
        add(R.drawable.ic_history, R.string.history) { showHistory() }
        add(R.drawable.ic_download, R.string.downloads) { showDownloads() }
        add(R.drawable.ic_star_border, R.string.bookmarks) { showBookmarks() }
        divider()
        if (hasPage) {
            add(R.drawable.ic_find_in_page, R.string.find_in_page) { showFindBar() }
            add(R.drawable.ic_share, R.string.share) { shareUrl(tab.url, tab.title) }
            add(R.drawable.ic_content_copy, R.string.copy_link) { copyToClipboard(tab.url) }

            // Zoom: "−  100%  +" inline. Pinching the page changes the same zoom level.
            val zoomRow = menuRow(R.drawable.ic_zoom_in, getString(R.string.zoom)) {}
            zoomRow.isClickable = false
            val trailing = zoomRow.findViewById<LinearLayout>(R.id.menu_item_trailing)
            val percent = TextView(this).apply {
                setTextAppearance(R.style.TextAppearance_Ladybird_Emphasis)
                gravity = Gravity.CENTER
                minWidth = dp(48)
            }
            fun refreshPercent(level: Float) {
                percent.text = "${(level * 100).toInt()}%"
            }
            refreshPercent(tab.zoomLevel)
            fun zoomButton(icon: Int, direction: Int) = ImageButton(this).apply {
                setImageResource(icon)
                setBackgroundResource(android.R.drawable.list_selector_background)
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                setOnClickListener {
                    val v = tab.view ?: return@setOnClickListener
                    tab.zoomLevel = v.zoom(direction)
                    refreshPercent(tab.zoomLevel)
                }
            }
            trailing.addView(zoomButton(R.drawable.ic_remove, -1))
            trailing.addView(percent)
            trailing.addView(zoomButton(R.drawable.ic_add, 1))
            items.addView(zoomRow)
        }
        val desktopRow = add(R.drawable.ic_desktop, R.string.desktop_site) {
            store.desktopSiteByDefault = !store.desktopSiteByDefault
            for (t in tabs)
                WebViewImplementation.setMobileUserAgent(!store.desktopSiteByDefault)
            tab.view?.impl?.reload()
        }
        desktopRow.findViewById<LinearLayout>(R.id.menu_item_trailing).addView(CheckBox(this).apply {
            isChecked = store.desktopSiteByDefault
            isClickable = false
        })
        add(R.drawable.ic_settings, R.string.settings) { showSettings() }

        popup.showAsDropDown(binding.menuButton, 0, -binding.menuButton.height + dp(4), Gravity.END)
    }

    private fun menuRow(icon: Int, label: String, action: () -> Unit): View {
        val row = layoutInflater.inflate(R.layout.item_menu, null, false)
        row.findViewById<ImageView>(R.id.menu_item_icon).setImageResource(icon)
        row.findViewById<TextView>(R.id.menu_item_text).text = label
        row.setOnClickListener { action() }
        row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
        return row
    }

    // --- Menu actions ---

    private fun showPageInfo() {
        val tab = activeTab ?: return
        if (tab.url == Tab.NEW_TAB_URL)
            return
        val secure = tab.url.startsWith("https://")
        MaterialAlertDialogBuilder(this)
            .setIcon(if (secure) R.drawable.ic_lock else R.drawable.ic_info)
            .setTitle(BrowserStore.hostOf(tab.url))
            .setMessage(
                if (secure) "Connection is secure\n\nYour information (for example, passwords or credit card numbers) is private when it is sent to this site."
                else "Your connection to this site is not secure\n\nYou should not enter any sensitive information on this site."
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun shareUrl(url: String, title: String?) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            if (!title.isNullOrBlank())
                putExtra(Intent.EXTRA_SUBJECT, title)
        }
        startActivity(Intent.createChooser(intent, null))
    }

    private fun copyToClipboard(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("URL", text))
        Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
    }

    private fun showSettings() {
        fun themeName(theme: Int) = when (theme) {
            WebViewImplementation.COLOR_SCHEME_DARK -> "Dark"
            WebViewImplementation.COLOR_SCHEME_LIGHT -> "Light"
            else -> "System default"
        }
        val settings = engineSettings
        val blocking = if (settings.contentBlockingEnabled) "On · ${enabledFilterListCount(settings)} lists" else "Off"
        val dns = settings.dnsServer?.let { dnsServerName(it) } ?: "System DNS"
        val entries = arrayOf(
            "Search engine\n${store.searchEngine}",
            "Theme\n${themeName(store.theme)}",
            "Desktop site\n${if (store.desktopSiteByDefault) "On" else "Off"}",
            "Ad & tracker blocking\n$blocking",
            "Privacy & security\n$dns · Global Privacy Control ${if (settings.globalPrivacyControl) "on" else "off"}",
            "Tabs\n${unloadTabsName(settings.unloadTabsAfterMinutes)}",
            "Site settings\nAutoplay: ${autoplayName(settings.autoplayPolicy)}",
            "Rendering\n${if (store.cpuPainting) "CPU" else "GPU (Vulkan)"}",
            "Show frame rate\n${if (store.showFrameRate) "On" else "Off"}",
            "Clear browsing history",
            "About Ladybird",
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings)
            .setItems(entries) { _, which ->
                when (which) {
                    0 -> chooseSearchEngine()
                    1 -> chooseTheme()
                    2 -> {
                        store.desktopSiteByDefault = !store.desktopSiteByDefault
                        for (t in tabs)
                            WebViewImplementation.setMobileUserAgent(!store.desktopSiteByDefault)
                        showSettings()
                    }
                    3 -> showContentBlockingSettings()
                    4 -> showPrivacySettings()
                    5 -> showTabSettings()
                    6 -> showSiteSettings()
                    7 -> chooseRenderer()
                    8 -> {
                        store.showFrameRate = !store.showFrameRate
                        updateFrameRateOverlay()
                        showSettings()
                    }
                    9 -> confirmClearHistory()
                    10 -> showAbout()
                }
            }
            .show()
    }

    // --- Engine settings: ad blocking, privacy, tabs, site settings ---

    private fun enabledFilterListCount(settings: EngineSettings) =
        settings.builtInLists.count { it.value } + settings.subscriptions.count { it.enabled }

    private fun dnsServerName(dns: EngineSettings.DnsServer) = DNS_PRESETS.firstOrNull { it.second == dns.copy(dnssec = false) }?.first
        ?: "${dns.server}${if (dns.overTls) " (encrypted)" else ""}"

    private fun unloadTabsName(minutes: Int) = when {
        minutes <= 0 -> "Keep background tabs loaded"
        minutes < 60 -> "Unload background tabs after $minutes minutes"
        minutes == 60 -> "Unload background tabs after 1 hour"
        minutes % 60 == 0 -> "Unload background tabs after ${minutes / 60} hours"
        else -> "Unload background tabs after $minutes minutes"
    }

    private fun autoplayName(policy: String) = when (policy) {
        "allow-audio-and-video" -> "Allowed"
        "block-audio-and-video" -> "Blocked"
        else -> "Muted only"
    }

    private fun showSettingsList(title: String, entries: Array<String>, onBack: () -> Unit = { showSettings() }, onPick: (Int) -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setItems(entries) { _, which -> onPick(which) }
            .setNegativeButton("Back") { _, _ -> onBack() }
            .show()
    }

    private fun showContentBlockingSettings() {
        val settings = engineSettings
        val on = settings.contentBlockingEnabled
        val host = activeTab?.url?.let { BrowserStore.hostOf(it) }?.takeIf { it.isNotEmpty() && activeTab?.isNewTabPage != true }
        val allowed = settings.allowedSites
        val entries = mutableListOf(
            "Block ads and trackers\n${if (on) "On" else "Off"}",
            "Filter lists\n${enabledFilterListCount(settings)} on · downloaded and updated daily",
            "Your own filter lists\n${settings.subscriptions.size.let { if (it == 0) "Subscribe to a list by its address" else "$it lists" }}",
            "Your own filter rules\n${settings.customFilters.lines().count { it.isNotBlank() }.let { if (it == 0) "None" else "$it rules" }}",
            "Sites where nothing is blocked\n${if (allowed.isEmpty()) "None" else allowed.joinToString(", ")}",
        )
        if (host != null)
            entries.add(if (host in allowed) "Block on $host again" else "Don't block on $host")
        showSettingsList("Ad & tracker blocking", entries.toTypedArray()) { which ->
            when (which) {
                0 -> {
                    settings.setContentBlockingEnabled(!on)
                    showContentBlockingSettings()
                }
                1 -> chooseFilterLists()
                2 -> showFilterListSubscriptions()
                3 -> editCustomFilters()
                4 -> showAllowedSites()
                5 -> {
                    settings.setAllowedSites(if (host!! in allowed) allowed - host else allowed + host)
                    activeTab?.view?.impl?.reload()
                    showContentBlockingSettings()
                }
            }
        }
    }

    private fun chooseFilterLists() {
        val settings = engineSettings
        val lists = EngineSettings.BUILT_IN_LISTS
        val current = settings.builtInLists
        val labels = lists.map { (_, name, isLanguage) -> if (isLanguage) "Websites in $name" else name }.toTypedArray()
        val checked = lists.map { current[it.first] == true }.toBooleanArray()
        val original = checked.copyOf()
        MaterialAlertDialogBuilder(this)
            .setTitle("Filter lists")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Done") { _, _ ->
                var addedList = false
                for (index in lists.indices) {
                    if (checked[index] == original[index])
                        continue
                    settings.setBuiltInList(lists[index].first, checked[index])
                    addedList = addedList || checked[index]
                }
                if (addedList)
                    settings.downloadFilterLists()
                showContentBlockingSettings()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> showContentBlockingSettings() }
            .show()
    }

    private fun showFilterListSubscriptions() {
        val settings = engineSettings
        val subscriptions = settings.subscriptions
        val entries = (listOf("Subscribe to a list…") + subscriptions.map { "${it.url}\n${if (it.enabled) "On" else "Off"}" }).toTypedArray()
        showSettingsList("Your own filter lists", entries, { showContentBlockingSettings() }) { which ->
            if (which == 0) {
                promptForText("Subscribe to a filter list", "Address of the list (https://…)", "") { url ->
                    val trimmed = url.trim()
                    if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://")) {
                        Toast.makeText(this, "The address must start with https://", Toast.LENGTH_LONG).show()
                    } else if (subscriptions.none { it.url == trimmed }) {
                        val identifier = "android-" + Integer.toHexString(trimmed.hashCode()).trimStart('-')
                        settings.setSubscriptions(subscriptions + EngineSettings.Subscription(identifier, trimmed, true))
                        settings.downloadFilterLists()
                    }
                    showFilterListSubscriptions()
                }
                return@showSettingsList
            }
            val subscription = subscriptions[which - 1]
            MaterialAlertDialogBuilder(this)
                .setTitle(subscription.url)
                .setItems(arrayOf(if (subscription.enabled) "Turn off" else "Turn on", "Remove")) { _, action ->
                    val updated = when (action) {
                        0 -> subscriptions.map { if (it == subscription) it.copy(enabled = !it.enabled) else it }
                        else -> subscriptions - subscription
                    }
                    settings.setSubscriptions(updated)
                    if (action == 0 && !subscription.enabled)
                        settings.downloadFilterLists()
                    showFilterListSubscriptions()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> showFilterListSubscriptions() }
                .show()
        }
    }

    private fun editCustomFilters() {
        val settings = engineSettings
        val field = EditText(this).apply {
            setText(settings.customFilters)
            hint = "One rule per line, as in Adblock Plus lists,\nsuch as ||ads.example.com^ or example.com##.banner"
            minLines = 6
            gravity = Gravity.TOP or Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(field)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Your own filter rules")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                settings.setCustomFilters(field.text.toString().trimEnd())
                showContentBlockingSettings()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> showContentBlockingSettings() }
            .show()
    }

    private fun showAllowedSites() {
        val settings = engineSettings
        val sites = settings.allowedSites
        if (sites.isEmpty()) {
            Toast.makeText(this, "Nothing is blocked only on sites you add from this menu", Toast.LENGTH_SHORT).show()
            showContentBlockingSettings()
            return
        }
        showSettingsList("Tap a site to block on it again", sites.toTypedArray(), { showContentBlockingSettings() }) { which ->
            settings.setAllowedSites(sites - sites[which])
            showAllowedSites()
        }
    }

    private fun showPrivacySettings() {
        val settings = engineSettings
        val dns = settings.dnsServer
        val entries = mutableListOf(
            "Global Privacy Control\nTell websites not to sell or share your data · ${if (settings.globalPrivacyControl) "On" else "Off"}",
            "DNS server\n${dns?.let { dnsServerName(it) } ?: "System DNS"}",
        )
        if (dns != null)
            entries.add("Check DNSSEC signatures\n${if (dns.dnssec) "On" else "Off"}")
        showSettingsList("Privacy & security", entries.toTypedArray()) { which ->
            when (which) {
                0 -> {
                    settings.setGlobalPrivacyControl(!settings.globalPrivacyControl)
                    showPrivacySettings()
                }
                1 -> chooseDnsServer()
                2 -> {
                    settings.setDnsServer(dns!!.copy(dnssec = !dns.dnssec))
                    showPrivacySettings()
                }
            }
        }
    }

    private fun chooseDnsServer() {
        val settings = engineSettings
        val current = settings.dnsServer
        val labels = (listOf("System DNS") + DNS_PRESETS.map { it.first } + listOf("Custom server…")).toTypedArray()
        val selected = when {
            current == null -> 0
            else -> DNS_PRESETS.indexOfFirst { it.second == current.copy(dnssec = false) }.let { if (it >= 0) it + 1 else labels.size - 1 }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("DNS server")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> settings.setDnsServer(null)
                    labels.size - 1 -> {
                        promptForText("Custom DNS server", "Address, such as 1.1.1.1 or 1.1.1.1:853", current?.let { "${it.server}:${it.port}" } ?: "") { text ->
                            val server = text.trim().substringBeforeLast(':').ifEmpty { text.trim() }
                            val port = text.trim().substringAfterLast(':', "").toIntOrNull()
                            if (server.isNotEmpty()) {
                                val overTls = port == null || port == 853
                                settings.setDnsServer(EngineSettings.DnsServer(server, port ?: 853, overTls, current?.dnssec ?: false))
                            }
                            showPrivacySettings()
                        }
                        return@setSingleChoiceItems
                    }
                    else -> settings.setDnsServer(DNS_PRESETS[which - 1].second.copy(dnssec = current?.dnssec ?: false))
                }
                showPrivacySettings()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> showPrivacySettings() }
            .show()
    }

    private fun showTabSettings() {
        val settings = engineSettings
        val processes = mapOf("perTab" to "Never", "perOrigin" to "Same subdomain", "perSite" to "Same site")
        val entries = arrayOf(
            "Unload tabs you haven't looked at\n${unloadTabsName(settings.unloadTabsAfterMinutes)}. A tab reloads when you return to it.",
            "Let tabs share a process\n${processes[settings.tabProcesses] ?: settings.tabProcesses}. Shared processes use less memory, but crash together.",
        )
        showSettingsList("Tabs", entries) { which ->
            when (which) {
                0 -> {
                    val minutes = intArrayOf(0, 30, 60, 120, 240, 480)
                    val labels = arrayOf("Never", "After 30 minutes", "After 1 hour", "After 2 hours", "After 4 hours", "After 8 hours")
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Unload tabs you haven't looked at")
                        .setSingleChoiceItems(labels, minutes.indexOf(settings.unloadTabsAfterMinutes)) { dialog, picked ->
                            dialog.dismiss()
                            settings.setUnloadTabsAfterMinutes(minutes[picked])
                            showTabSettings()
                        }
                        .setNegativeButton(android.R.string.cancel) { _, _ -> showTabSettings() }
                        .show()
                }
                1 -> {
                    val values = processes.keys.toList()
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Let tabs share a process")
                        .setSingleChoiceItems(processes.values.toTypedArray(), values.indexOf(settings.tabProcesses)) { dialog, picked ->
                            dialog.dismiss()
                            settings.setTabProcesses(values[picked])
                            showTabSettings()
                        }
                        .setNegativeButton(android.R.string.cancel) { _, _ -> showTabSettings() }
                        .show()
                }
            }
        }
    }

    private fun showSiteSettings() {
        val settings = engineSettings
        showSettingsList("Site settings", arrayOf("Autoplay\n${autoplayName(settings.autoplayPolicy)}")) {
            val values = arrayOf("allow-audio-and-video", "block-audio", "block-audio-and-video")
            val labels = arrayOf("Allow audio and video", "Allow only muted video", "Block audio and video")
            MaterialAlertDialogBuilder(this)
                .setTitle("Autoplay")
                .setSingleChoiceItems(labels, values.indexOf(settings.autoplayPolicy)) { dialog, picked ->
                    dialog.dismiss()
                    settings.setAutoplayPolicy(values[picked])
                    showSiteSettings()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> showSiteSettings() }
                .show()
        }
    }

    private fun promptForText(title: String, hint: String, initial: String, onDone: (String) -> Unit) {
        val field = EditText(this).apply {
            setText(initial)
            this.hint = hint
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(field)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ -> onDone(field.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAbout() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(28), dp(24), dp(8))
        }
        content.addView(ImageView(this).apply {
            setImageResource(R.drawable.ladybird_logo_full)
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36))
        })
        content.addView(TextView(this).apply {
            text = getString(R.string.tagline)
            setTextAppearance(R.style.TextAppearance_Ladybird_Muted)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        })
        content.addView(TextView(this).apply {
            text = "Using a novel engine based on web standards, with no code borrowed from other browsers.\n\nladybird.org"
            setTextAppearance(R.style.TextAppearance_Ladybird_Body)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        })
        MaterialAlertDialogBuilder(this)
            .setView(content)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun chooseRenderer() {
        val labels = arrayOf("GPU (Vulkan)", "CPU")
        MaterialAlertDialogBuilder(this)
            .setTitle("Rendering")
            .setSingleChoiceItems(labels, if (store.cpuPainting) 1 else 0) { dialog, which ->
                dialog.dismiss()
                val cpu = which == 1
                if (cpu == store.cpuPainting)
                    return@setSingleChoiceItems
                store.cpuPainting = cpu
                // Each tab's process picks its renderer when it starts, so restart them: background tabs reload
                // when shown, and the active tab reloads now.
                for (tab in tabs)
                    tab.discard()
                activeTab?.let { activateTab(it) }
                Snackbar.make(binding.content, "Pages now render on the ${if (cpu) "CPU" else "GPU"}", Snackbar.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun chooseSearchEngine() {
        val names = WebViewImplementation.searchEngineNames()
        MaterialAlertDialogBuilder(this)
            .setTitle("Search engine")
            .setSingleChoiceItems(names, names.indexOf(store.searchEngine)) { dialog, which ->
                store.searchEngine = names[which]
                dialog.dismiss()
            }
            .show()
    }

    private fun chooseTheme() {
        val labels = arrayOf("System default", "Light", "Dark")
        val values = intArrayOf(WebViewImplementation.COLOR_SCHEME_AUTO, WebViewImplementation.COLOR_SCHEME_LIGHT, WebViewImplementation.COLOR_SCHEME_DARK)
        MaterialAlertDialogBuilder(this)
            .setTitle("Theme")
            .setSingleChoiceItems(labels, values.indexOf(store.theme)) { dialog, which ->
                dialog.dismiss()
                if (store.theme != values[which]) {
                    store.theme = values[which]
                    applyNightMode()
                }
            }
            .show()
    }

    private fun confirmClearHistory() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear browsing history?")
            .setMessage("This clears your history and the most visited sites on the new tab page.")
            .setPositiveButton("Clear") { _, _ ->
                store.clearHistory()
                if (activeTab?.isNewTabPage == true)
                    refreshNewTabPage()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // --- History, bookmarks and downloads screens ---

    private data class ListEntry(
        val title: String,
        val url: String,
        val subtitle: String,
        /** An icon instead of the site's first letter. */
        val icon: Int? = null,
        /** Progress from 0 to 1, or a negative number for "in progress, size unknown". */
        val progress: Float? = null,
        /** What tapping does; by default, open the URL. */
        val onOpen: (() -> Unit)? = null,
        /** Ask before removing (removing deletes something). */
        val removeConfirmation: String? = null,
        val onRemove: () -> Unit,
    )

    private fun showHistory() {
        showListScreen(getString(R.string.history), "Clear data", "Your history is empty", {
            store.history.map { h ->
                ListEntry(h.title.ifBlank { h.url }, h.url,
                    "${BrowserStore.hostOf(h.url)} · ${DateUtils.getRelativeTimeSpanString(h.lastVisit)}") { store.removeHistory(h) }
            }
        }, { store.clearHistory() })
    }

    private fun showBookmarks() {
        showListScreen(getString(R.string.bookmarks), null, "No bookmarks yet. Tap ☆ in the menu to bookmark a page.", {
            store.bookmarks.map { b -> ListEntry(b.title.ifBlank { b.url }, b.url, BrowserStore.stripScheme(b.url)) { store.removeBookmark(b) } }
        }, null)
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun showListScreen(title: String, actionLabel: String?, emptyText: String, load: () -> List<ListEntry>, action: (() -> Unit)?, liveRefresh: Boolean = false) {
        val dialog = Dialog(this, R.style.Theme_Ladybird_FullScreenDialog)
        dialog.setContentView(R.layout.dialog_list_screen)
        dialog.window?.let {
            WindowCompat.setDecorFitsSystemWindows(it, false)
            WindowCompat.getInsetsController(it, it.decorView).apply {
                isAppearanceLightStatusBars = !isNight
                isAppearanceLightNavigationBars = !isNight
            }
        }
        val root = dialog.findViewById<View>(R.id.list_screen_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        dialog.findViewById<TextView>(R.id.list_title).text = title
        dialog.findViewById<View>(R.id.list_back).setOnClickListener { dialog.dismiss() }
        val empty = dialog.findViewById<TextView>(R.id.list_empty)
        empty.text = emptyText
        val search = dialog.findViewById<EditText>(R.id.list_search)
        val recycler = dialog.findViewById<RecyclerView>(R.id.list_recycler)
        recycler.layoutManager = LinearLayoutManager(this)

        lateinit var refresh: () -> Unit
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            var entries = listOf<ListEntry>()
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_list_entry, parent, false)) {}

            override fun getItemCount() = entries.size
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val e = entries[position]
                val v = holder.itemView
                val letter = v.findViewById<TextView>(R.id.entry_letter)
                val icon = v.findViewById<ImageView>(R.id.entry_icon)
                letter.isVisible = e.icon == null
                icon.isVisible = e.icon != null
                if (e.icon != null) {
                    icon.setImageResource(e.icon)
                    icon.imageTintList = ColorStateList.valueOf(getColor(R.color.accent))
                } else {
                    letter.text = BrowserStore.hostOf(e.url).firstOrNull()?.uppercase() ?: "?"
                }
                v.findViewById<TextView>(R.id.entry_title).text = e.title
                v.findViewById<TextView>(R.id.entry_subtitle).text = e.subtitle
                val progress = v.findViewById<LinearProgressIndicator>(R.id.entry_progress)
                progress.isVisible = e.progress != null
                if (e.progress != null) {
                    progress.isIndeterminate = e.progress < 0
                    if (e.progress >= 0)
                        progress.setProgressCompat((e.progress * 1000).toInt(), false)
                }
                v.setOnClickListener {
                    val open = e.onOpen
                    if (open != null) {
                        open()
                    } else {
                        dialog.dismiss()
                        navigate(e.url)
                    }
                }
                v.findViewById<View>(R.id.entry_remove).setOnClickListener {
                    val confirmation = e.removeConfirmation
                    if (confirmation != null) {
                        MaterialAlertDialogBuilder(this@LadybirdActivity)
                            .setTitle(confirmation)
                            .setMessage("The file will be removed from your device.")
                            .setPositiveButton("Delete") { _, _ ->
                                e.onRemove()
                                refresh()
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    } else {
                        e.onRemove()
                        refresh()
                    }
                }
            }
        }
        refresh = {
            val q = search.text.toString().trim().lowercase()
            adapter.entries = load().filter { q.isEmpty() || it.title.lowercase().contains(q) || it.url.lowercase().contains(q) }
            adapter.notifyDataSetChanged()
            empty.isVisible = adapter.entries.isEmpty()
        }
        recycler.adapter = adapter
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = refresh()
        })

        val actionButton = dialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.list_action)
        if (actionLabel != null && action != null) {
            actionButton.text = actionLabel
            actionButton.setOnClickListener {
                action()
                refresh()
            }
        } else {
            actionButton.isVisible = false
        }
        refresh()

        if (liveRefresh) {
            // Keep progress current while the screen is open.
            val ticker = object : Runnable {
                override fun run() {
                    if (!dialog.isShowing)
                        return
                    refresh()
                    root.postDelayed(this, 1000)
                }
            }
            dialog.setOnShowListener { root.postDelayed(ticker, 1000) }
        }
        dialog.show()
    }

    // --- Back navigation ---

    private fun handleBack() {
        val tab = activeTab
        when {
            binding.tabSwitcher.root.isVisible -> hideTabSwitcher()
            isFinding -> hideFindBar()
            isEditingUrl -> stopEditingUrl()
            tab != null && tab.view != null && tab.canGoBack -> tab.view?.impl?.traverseHistory(-1)
            // A tab opened by another tab (a link with target=_blank, window.open) closes and returns to its opener.
            tab != null && tab.openerId != null && tabs.any { it.id == tab.openerId } -> closeTab(tab)
            else -> moveTaskToBack(true)
        }
    }

    // --- Utilities ---

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun dpf(value: Float) = value * resources.displayMetrics.density

    private fun showKeyboard(view: View) {
        view.post { getSystemService(InputMethodManager::class.java).showSoftInput(view, InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun hideKeyboard(view: View) {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun scheduleEventLoop() {
        mainExecutor.execute {
            execMainEventLoop()
        }
    }

    // --- Calls from the Hub (through native code) ---

    /** A page or the Hub asked for a URL to open in a new tab, as for a link that the user opened in one. */
    private fun openUrlInNewTabFromHub(url: String, activate: Boolean, isPrivate: Boolean) {
        val tab = createTab(url, activate = activate, incognito = isPrivate)
        if (activate)
            showActiveTab()
        else
            onTabStateChanged(tab)
    }

    private fun setClipboardText(text: String) = copyToClipboard(text)

    private fun clipboardText(): String {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip ?: return ""
        if (clip.itemCount == 0)
            return ""
        return clip.getItemAt(0).coerceToText(this)?.toString() ?: ""
    }

    /** status is a WebView::FileDownloader::DownloadStatus: 0 in progress, 1 paused, 2 completed, 3 canceled, 4 failed. */
    private fun onDownloadChanged(id: Long, url: String, destination: String, status: Int, downloaded: Long, total: Long, isPrivate: Boolean) {
        if (!hubDownloadsAnnounced.contains(id) && status == 0) {
            hubDownloadsAnnounced.add(id)
            Snackbar.make(binding.content, "Downloading ${File(destination).name}", Snackbar.LENGTH_SHORT).show()
        }
        when (status) {
            2 -> {
                hubDownloadsAnnounced.remove(id)
                try {
                    val entry = downloads.importFile(File(destination), url)
                    Snackbar.make(binding.content, "Downloaded ${entry.fileName}", Snackbar.LENGTH_LONG)
                        .setAction("Open") { openDownload(entry) }
                        .show()
                } catch (e: Exception) {
                    Snackbar.make(binding.content, "Couldn't save ${File(destination).name}: ${e.message}", Snackbar.LENGTH_LONG).show()
                }
            }
            3, 4 -> {
                hubDownloadsAnnounced.remove(id)
                if (status == 4)
                    Snackbar.make(binding.content, "Couldn't download ${File(destination).name}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private val hubDownloadsAnnounced = mutableSetOf<Long>()

    private external fun initNativeCode(
        nativeLibraryDir: String, resourceDir: String, dataDir: String, cacheDir: String, downloadsDir: String,
        certificatesPath: String, tag: String, timerService: TimerExecutorService, darkMode: Boolean
    ): Boolean

    private external fun setDarkMode(dark: Boolean)
    private external fun nativeSettingsJson(): String
    private external fun nativeUpdateSettings(changes: String)

    /** The engine's own settings, which the Hub keeps, as on desktop. */
    private val engineSettings by lazy { EngineSettings({ nativeSettingsJson() }, { nativeUpdateSettings(it) }) }

    private external fun disposeNativeCode()
    private external fun execMainEventLoop()

    companion object {
        // Used to load the 'ladybird' library on application startup.
        init {
            System.loadLibrary("Ladybird")
        }

        /** Public DNS resolvers that answer over TLS, by the addresses their certificates name. */
        private val DNS_PRESETS = listOf(
            "Cloudflare (1.1.1.1, encrypted)" to EngineSettings.DnsServer("1.1.1.1", 853, overTls = true, dnssec = false),
            "Quad9 (9.9.9.9, encrypted)" to EngineSettings.DnsServer("9.9.9.9", 853, overTls = true, dnssec = false),
            "Google (8.8.8.8, encrypted)" to EngineSettings.DnsServer("8.8.8.8", 853, overTls = true, dnssec = false),
        )
    }
}
