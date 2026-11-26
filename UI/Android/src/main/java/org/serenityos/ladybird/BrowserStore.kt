/**
 * Copyright (c) 2025, the Ladybird developers.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

package org.serenityos.ladybird

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryEntry(val url: String, var title: String, var lastVisit: Long, var visitCount: Int)
data class Bookmark(val url: String, var title: String, val added: Long)

/** History, bookmarks, settings and the open tabs, kept as small JSON files in the app's private storage. */
class BrowserStore(context: Context) {
    private val dir = context.filesDir
    private val prefs = context.getSharedPreferences("browser", Context.MODE_PRIVATE)

    val history = mutableListOf<HistoryEntry>()
    val bookmarks = mutableListOf<Bookmark>()

    init {
        readArray("history.json")?.let { array ->
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                history.add(HistoryEntry(o.getString("url"), o.optString("title"), o.optLong("lastVisit"), o.optInt("visitCount", 1)))
            }
        }
        readArray("bookmarks.json")?.let { array ->
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                bookmarks.add(Bookmark(o.getString("url"), o.optString("title"), o.optLong("added")))
            }
        }
    }

    // --- Settings ---

    var searchEngine: String
        get() = prefs.getString("searchEngine", null) ?: "Google"
        set(value) = prefs.edit().putString("searchEngine", value).apply()

    /** 0 = follow the system, 1 = dark, 2 = light (same values as WebViewImplementation.COLOR_SCHEME_*). */
    var theme: Int
        get() = prefs.getInt("theme", 0)
        set(value) = prefs.edit().putInt("theme", value).apply()

    var desktopSiteByDefault: Boolean
        get() = prefs.getBoolean("desktopSite", false)
        set(value) = prefs.edit().putBoolean("desktopSite", value).apply()

    /** Rasterize pages on the CPU instead of the GPU. Read by each tab's WebContent process when it starts. */
    var cpuPainting: Boolean
        get() = prefs.getBoolean(KEY_CPU_PAINTING, false)
        // commit() rather than apply(): the next WebContent process reads the file right away.
        set(value) { prefs.edit().putBoolean(KEY_CPU_PAINTING, value).commit() }

    var showFrameRate: Boolean
        get() = prefs.getBoolean("showFrameRate", false)
        set(value) = prefs.edit().putBoolean("showFrameRate", value).apply()

    // --- History ---

    fun recordVisit(url: String, title: String) {
        if (!isRecordable(url))
            return
        val now = System.currentTimeMillis()
        val existing = history.find { it.url == url }
        if (existing != null) {
            // Title updates for the page we just recorded shouldn't count as another visit.
            if (now - existing.lastVisit > 2000)
                existing.visitCount++
            existing.lastVisit = now
            if (title.isNotBlank())
                existing.title = title
            history.remove(existing)
            history.add(0, existing)
        } else {
            history.add(0, HistoryEntry(url, title, now, 1))
            if (history.size > MAX_HISTORY)
                history.removeAt(history.lastIndex)
        }
        saveHistory()
    }

    fun removeHistory(entry: HistoryEntry) {
        history.remove(entry)
        saveHistory()
    }

    fun clearHistory() {
        history.clear()
        saveHistory()
    }

    fun mostVisited(count: Int): List<HistoryEntry> {
        // One tile per site, most visited first.
        val seenHosts = HashSet<String>()
        return history.sortedByDescending { it.visitCount }
            .filter { seenHosts.add(hostOf(it.url)) }
            .take(count)
    }

    private fun saveHistory() {
        val array = JSONArray()
        history.forEach { array.put(JSONObject().put("url", it.url).put("title", it.title).put("lastVisit", it.lastVisit).put("visitCount", it.visitCount)) }
        writeArray("history.json", array)
    }

    // --- Bookmarks ---

    fun isBookmarked(url: String) = bookmarks.any { it.url == url }

    fun toggleBookmark(url: String, title: String): Boolean {
        val existing = bookmarks.find { it.url == url }
        if (existing != null)
            bookmarks.remove(existing)
        else
            bookmarks.add(0, Bookmark(url, title, System.currentTimeMillis()))
        saveBookmarks()
        return existing == null
    }

    fun removeBookmark(bookmark: Bookmark) {
        bookmarks.remove(bookmark)
        saveBookmarks()
    }

    private fun saveBookmarks() {
        val array = JSONArray()
        bookmarks.forEach { array.put(JSONObject().put("url", it.url).put("title", it.title).put("added", it.added)) }
        writeArray("bookmarks.json", array)
    }

    // --- Suggestions ---

    data class Suggestion(val title: String, val url: String, val isBookmark: Boolean)

    fun suggestions(query: String, limit: Int): List<Suggestion> {
        val q = query.trim().lowercase()
        if (q.isEmpty())
            return emptyList()
        fun matches(url: String, title: String) = stripScheme(url).lowercase().contains(q) || title.lowercase().contains(q)
        val results = LinkedHashMap<String, Suggestion>()
        bookmarks.filter { matches(it.url, it.title) }.forEach { results.putIfAbsent(it.url, Suggestion(it.title, it.url, true)) }
        history.filter { matches(it.url, it.title) }
            .sortedWith(compareByDescending<HistoryEntry> { stripScheme(it.url).lowercase().startsWith(q) || hostOf(it.url).startsWith(q) }.thenByDescending { it.visitCount })
            .forEach { results.putIfAbsent(it.url, Suggestion(it.title, it.url, false)) }
        return results.values.take(limit)
    }

    // --- Open tabs ---

    data class SavedTab(val url: String, val title: String)

    fun saveTabs(tabs: List<SavedTab>, activeIndex: Int) {
        val array = JSONArray()
        tabs.forEach { array.put(JSONObject().put("url", it.url).put("title", it.title)) }
        writeArray("tabs.json", array)
        prefs.edit().putInt("activeTab", activeIndex).apply()
    }

    fun loadTabs(): Pair<List<SavedTab>, Int> {
        val array = readArray("tabs.json") ?: return Pair(emptyList(), 0)
        val tabs = (0 until array.length()).map { array.getJSONObject(it).let { o -> SavedTab(o.getString("url"), o.optString("title")) } }
        return Pair(tabs, prefs.getInt("activeTab", 0))
    }

    private fun readArray(name: String): JSONArray? = try {
        val file = File(dir, name)
        if (file.exists()) JSONArray(file.readText()) else null
    } catch (e: Exception) {
        null
    }

    private fun writeArray(name: String, array: JSONArray) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(array.toString())
        tmp.renameTo(File(dir, name))
    }

    companion object {
        private const val MAX_HISTORY = 2000
        private const val KEY_CPU_PAINTING = "cpuPainting"

        fun readCpuPainting(context: Context) =
            context.getSharedPreferences("browser", Context.MODE_PRIVATE).getBoolean(KEY_CPU_PAINTING, false)

        fun isRecordable(url: String) = url.startsWith("http://") || url.startsWith("https://")

        fun stripScheme(url: String) = url.removePrefix("https://").removePrefix("http://").removePrefix("www.").removeSuffix("/")

        fun hostOf(url: String): String = try {
            android.net.Uri.parse(url).host?.removePrefix("www.") ?: url
        } catch (e: Exception) {
            url
        }
    }
}
