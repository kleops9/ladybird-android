/*
 * Copyright (c) 2026, the Ladybird developers.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

package org.serenityos.ladybird

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * The engine's settings, which the Hub keeps and saves, as on desktop. Reads what the Hub last said about them, and sends
 * it changes as JSON with the members to change, which it merges into its settings. A change shows here at once, before
 * the Hub has said that it applied it.
 */
class EngineSettings(private val readJson: () -> String, private val sendChanges: (String) -> Unit) {
    private class SentChange(val time: Long, val changes: JSONObject)

    private val recentChanges = mutableListOf<SentChange>()

    /** The settings, with the changes sent lately merged in. */
    val json: JSONObject
        get() {
            val settings = runCatching { JSONObject(readJson()) }.getOrDefault(JSONObject())
            val now = SystemClock.elapsedRealtime()
            recentChanges.removeAll { now - it.time > RECENT_CHANGE_MILLIS }
            for (change in recentChanges)
                merge(settings, change.changes)
            return settings
        }

    /** Sets the member at the dotted path, such as "contentBlockers.enabled", to the value. */
    fun set(dottedPath: String, value: Any) {
        val path = dottedPath.split('.')
        val changes = JSONObject()
        var level = changes
        for (key in path.dropLast(1)) {
            val child = JSONObject()
            level.put(key, child)
            level = child
        }
        level.put(path.last(), value)
        recentChanges.add(SentChange(SystemClock.elapsedRealtime(), changes))
        sendChanges(changes.toString())
    }

    private fun section(name: String): JSONObject = json.optJSONObject(name) ?: JSONObject()

    // --- Ad and tracker blocking ---

    data class Subscription(val identifier: String, val url: String, val enabled: Boolean)

    val contentBlockingEnabled get() = section("contentBlockers").optBoolean("enabled", false)

    /** Which of the built-in filter lists are on, by identifier. */
    val builtInLists: Map<String, Boolean>
        get() {
            val lists = section("contentBlockers").optJSONObject("builtInLists") ?: return emptyMap()
            return lists.keys().asSequence().associateWith { lists.optBoolean(it) }
        }

    val subscriptions: List<Subscription>
        get() {
            val array = section("contentBlockers").optJSONArray("customSubscriptions") ?: return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                val entry = array.optJSONObject(index) ?: return@mapNotNull null
                Subscription(entry.optString("identifier"), entry.optString("url"), entry.optBoolean("enabled", true))
            }
        }

    val customFilters: String get() = section("contentBlockers").optString("customFilters", "")

    /** Sites where nothing is blocked. */
    val allowedSites: List<String>
        get() {
            val array = section("contentBlockers").optJSONArray("disabledSites") ?: return emptyList()
            return (0 until array.length()).map { array.optString(it) }.filter { it.isNotEmpty() }
        }

    fun setContentBlockingEnabled(enabled: Boolean) {
        if (enabled) {
            // The filter lists are downloaded and kept up to date only while the browser may update them by itself.
            set("backgroundNetworking.enabled", true)
            set("backgroundNetworking.features.contentBlockerSubscriptionUpdates", true)
            if (builtInLists.values.none { it } && subscriptions.none { it.enabled }) {
                set("contentBlockers.builtInLists.easyList", true)
                set("contentBlockers.builtInLists.easyPrivacy", true)
            }
        }
        set("contentBlockers.enabled", enabled)
        if (enabled)
            downloadFilterLists()
    }

    fun setBuiltInList(identifier: String, enabled: Boolean) = set("contentBlockers.builtInLists.$identifier", enabled)

    fun setSubscriptions(subscriptions: List<Subscription>) {
        val array = JSONArray()
        for (subscription in subscriptions)
            array.put(JSONObject().put("identifier", subscription.identifier).put("url", subscription.url).put("enabled", subscription.enabled))
        set("contentBlockers.customSubscriptions", array)
    }

    fun setCustomFilters(filters: String) = set("contentBlockers.customFilters", filters)

    fun setAllowedSites(sites: List<String>) = set("contentBlockers.disabledSites", JSONArray(sites))

    /**
     * Has the Hub download the lists that are on and that it hasn't downloaded in the last day. The Hub only looks for
     * lists to download when it starts and when the browser starts or stops updating them by itself, so this stops and
     * starts that.
     */
    fun downloadFilterLists() {
        if (!contentBlockingEnabled)
            return
        set("backgroundNetworking.features.contentBlockerSubscriptionUpdates", false)
        set("backgroundNetworking.features.contentBlockerSubscriptionUpdates", true)
    }

    // --- Privacy and security ---

    val globalPrivacyControl get() = json.optBoolean("globalPrivacyControl", false)

    fun setGlobalPrivacyControl(enabled: Boolean) = set("globalPrivacyControl", enabled)

    data class DnsServer(val server: String, val port: Int, val overTls: Boolean, val dnssec: Boolean)

    /** The DNS server to resolve with, or none to use the system's. */
    val dnsServer: DnsServer?
        get() {
            val dns = section("dnsSettings")
            if (dns.optString("mode") != "custom")
                return null
            return DnsServer(dns.optString("server"), dns.optInt("port", 53), dns.optString("type") == "tls", dns.optBoolean("dnssec", false))
        }

    fun setDnsServer(dns: DnsServer?) {
        if (dns == null) {
            set("dnsSettings.mode", "system")
            return
        }
        set("dnsSettings", JSONObject()
            .put("mode", "custom")
            .put("server", dns.server)
            .put("port", dns.port)
            .put("type", if (dns.overTls) "tls" else "udp")
            .put("dnssec", dns.dnssec))
    }

    // --- Tabs ---

    /** After how many minutes in the background a tab is unloaded, or zero for never. */
    val unloadTabsAfterMinutes get() = section("tabs").optInt("discardHiddenTabsAfterMinutes", 120)

    fun setUnloadTabsAfterMinutes(minutes: Int) = set("tabs.discardHiddenTabsAfterMinutes", minutes)

    /** Which tabs share a process: "perTab", "perOrigin" or "perSite". */
    val tabProcesses: String get() = section("tabs").optString("tabProcesses", "perOrigin")

    fun setTabProcesses(value: String) = set("tabs.tabProcesses", value)

    // --- Site settings ---

    /** "allow-audio-and-video", "block-audio" or "block-audio-and-video". */
    val autoplayPolicy: String get() = section("autoplay").optString("policy", "block-audio")

    fun setAutoplayPolicy(policy: String) = set("autoplay.policy", policy)

    companion object {
        private const val RECENT_CHANGE_MILLIS = 5_000L

        /** The built-in filter lists, as the engine has them: identifier, name, and whether it's for websites of a language. */
        val BUILT_IN_LISTS = listOf(
            Triple("easyList", "EasyList (ads)", false),
            Triple("easyPrivacy", "EasyPrivacy (trackers)", false),
            Triple("easyListCookie", "Cookie banners", false),
            Triple("fanboyAnnoyances", "Annoyances (pop-ups, newsletters)", false),
            Triple("fanboySocial", "Social media widgets", false),
            Triple("adblockWarningRemoval", "Ad blocker warnings", false),
            Triple("easyListDutch", "Dutch", true),
            Triple("easyListGermany", "German", true),
            Triple("listeFR", "French", true),
            Triple("easyListSpanish", "Spanish", true),
            Triple("easyListItaly", "Italian", true),
            Triple("easyListPortuguese", "Portuguese", true),
            Triple("easyListPolish", "Polish", true),
            Triple("easyListCzechAndSlovak", "Czech and Slovak", true),
            Triple("nordicFilters", "Nordic languages", true),
            Triple("ruAdList", "Russian and Ukrainian", true),
            Triple("bulgarianList", "Bulgarian", true),
            Triple("roList", "Romanian", true),
            Triple("latvianList", "Latvian", true),
            Triple("easyListLithuania", "Lithuanian", true),
            Triple("easyListHebrew", "Hebrew", true),
            Triple("listeAR", "Arabic", true),
            Triple("easyListChina", "Chinese", true),
            Triple("koreanList", "Korean", true),
            Triple("indianList", "Languages of India and Sri Lanka", true),
            Triple("abpindo", "Indonesian and Malay", true),
            Triple("abpvn", "Vietnamese", true),
        )

        private fun merge(target: JSONObject, changes: JSONObject) {
            for (key in changes.keys()) {
                val value = changes.get(key)
                if (value is JSONObject) {
                    val child = target.optJSONObject(key) ?: JSONObject().also { target.put(key, it) }
                    merge(child, value)
                } else {
                    target.put(key, value)
                }
            }
        }
    }
}
