/**
 * Copyright (c) 2025, the Ladybird developers.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

package org.serenityos.ladybird

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder

/**
 * Downloads files for the browser. Network downloads go through the system DownloadManager (which shows progress in
 * the notification shade and keeps going in the background) with the page's cookies and user agent; data: URLs are
 * decoded and written straight to the Downloads collection. Both end up in the public Downloads folder.
 */
class Downloads(private val context: Context) {
    data class Entry(
        /** DownloadManager id, or -1 for a file written directly (data: URLs). */
        val id: Long,
        val fileName: String,
        val url: String,
        val mimeType: String,
        val started: Long,
        /** For files written directly: their content:// URI. */
        val contentUri: String? = null,
    )

    enum class State { Pending, Running, Paused, Done, Failed }

    data class Status(val state: State, val bytesSoFar: Long, val totalBytes: Long)

    private val manager = context.getSystemService(DownloadManager::class.java)
    private val file = File(context.filesDir, "downloads.json")
    val entries = mutableListOf<Entry>()

    init {
        try {
            if (file.exists()) {
                val array = JSONArray(file.readText())
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    entries.add(Entry(o.getLong("id"), o.getString("fileName"), o.getString("url"), o.optString("mimeType"), o.optLong("started"), o.optString("contentUri").ifEmpty { null }))
                }
            }
        } catch (e: Exception) {
            entries.clear()
        }
    }

    /** Starts a download and returns its entry, or throws with a message suitable for the user. */
    fun start(url: String, contentDisposition: String, mimeType: String, cookies: String?, userAgent: String, referrer: String?): Entry {
        val type = mimeType.ifEmpty { guessMimeType(url) }
        val name = suggestedFileName(url, contentDisposition, type)
        val entry = if (url.startsWith("data:", ignoreCase = true)) {
            saveDataUrl(url, name, type)
        } else {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(name)
                .setDescription(BrowserStore.hostOf(url))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                .addRequestHeader("User-Agent", userAgent)
            if (type.isNotEmpty())
                request.setMimeType(type)
            if (!cookies.isNullOrEmpty())
                request.addRequestHeader("Cookie", cookies)
            if (!referrer.isNullOrEmpty() && BrowserStore.isRecordable(referrer))
                request.addRequestHeader("Referer", referrer)
            Entry(manager.enqueue(request), name, url, type, System.currentTimeMillis())
        }
        entries.add(0, entry)
        save()
        return entry
    }

    fun entryFor(downloadId: Long) = entries.find { it.id == downloadId && it.id >= 0 }

    fun status(entry: Entry): Status? {
        if (entry.id < 0)
            return Status(State.Done, 0, 0)
        manager.query(DownloadManager.Query().setFilterById(entry.id)).use { cursor ->
            if (!cursor.moveToFirst())
                return null
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val soFar = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val state = when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> State.Done
                DownloadManager.STATUS_FAILED -> State.Failed
                DownloadManager.STATUS_PAUSED -> State.Paused
                DownloadManager.STATUS_RUNNING -> State.Running
                else -> State.Pending
            }
            return Status(state, soFar, total)
        }
    }

    /** Drops entries whose file was removed outside the browser (for example in the Files app). */
    fun prune() {
        val before = entries.size
        entries.removeAll { it.id >= 0 && status(it) == null }
        if (entries.size != before)
            save()
    }

    /** Opens a finished download in an app that can show it. Returns false if none can. */
    fun open(entry: Entry): Boolean {
        val uri = if (entry.id >= 0) manager.getUriForDownloadedFile(entry.id) else entry.contentUri?.let(Uri::parse)
        uri ?: return false
        val type = (if (entry.id >= 0) manager.getMimeTypeForDownloadedFile(entry.id) else null) ?: entry.mimeType.ifEmpty { "*/*" }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    /** Removes the entry and deletes its file. */
    fun delete(entry: Entry) {
        if (entry.id >= 0)
            manager.remove(entry.id)
        else
            entry.contentUri?.let { runCatching { context.contentResolver.delete(Uri.parse(it), null, null) } }
        entries.remove(entry)
        save()
    }

    private fun saveDataUrl(url: String, name: String, type: String): Entry {
        // data:[<mediatype>][;base64],<data>
        val comma = url.indexOf(',')
        require(comma > 0) { "This data: URL is malformed" }
        val header = url.substring(5, comma)
        val payload = url.substring(comma + 1)
        val bytes = if (header.endsWith(";base64", ignoreCase = true))
            Base64.decode(payload, Base64.DEFAULT)
        else
            URLDecoder.decode(payload.replace("+", "%2B"), "UTF-8").toByteArray()

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            if (type.isNotEmpty())
                put(MediaStore.Downloads.MIME_TYPE, type)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Couldn't create the file")
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Couldn't write the file")
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        return Entry(-1, name, "data:", type, System.currentTimeMillis(), uri.toString())
    }

    private fun save() {
        val array = JSONArray()
        entries.take(MAX_ENTRIES).forEach {
            array.put(JSONObject().put("id", it.id).put("fileName", it.fileName).put("url", it.url).put("mimeType", it.mimeType)
                .put("started", it.started).put("contentUri", it.contentUri ?: ""))
        }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(array.toString())
        tmp.renameTo(file)
    }

    companion object {
        private const val MAX_ENTRIES = 200

        fun suggestedFileName(url: String, contentDisposition: String, mimeType: String): String {
            // A name the server (or a download attribute) gives is used as-is; guessFileName() would swap its
            // extension for one matching the MIME type (report.txt served as JSON would become report.json).
            fileNameFromDisposition(contentDisposition)?.let { return sanitize(it) }
            if (url.startsWith("data:", ignoreCase = true)) {
                val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
                return sanitize("download" + (extension?.let { ".$it" } ?: ""))
            }
            return sanitize(URLUtil.guessFileName(url, null, mimeType.ifEmpty { null }))
        }

        private fun fileNameFromDisposition(contentDisposition: String): String? {
            // RFC 6266: filename*=UTF-8''percent-encoded takes precedence over filename="..." or filename=token.
            Regex("filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)", RegexOption.IGNORE_CASE).find(contentDisposition)?.let {
                val charset = it.groupValues[1].ifEmpty { "UTF-8" }
                return runCatching { URLDecoder.decode(it.groupValues[2].trim().replace("+", "%2B"), charset) }.getOrNull()
            }
            Regex("filename\\s*=\\s*(\"([^\"]*)\"|[^;]+)", RegexOption.IGNORE_CASE).find(contentDisposition)?.let {
                val quoted = it.groupValues[2]
                return (if (it.groupValues[1].startsWith("\"")) quoted else it.groupValues[1]).trim().ifEmpty { null }
            }
            return null
        }

        private fun guessMimeType(url: String): String {
            if (url.startsWith("data:", ignoreCase = true))
                return url.substring(5).substringBefore(',').substringBefore(';').ifEmpty { "text/plain" }
            val extension = MimeTypeMap.getFileExtensionFromUrl(url)
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: ""
        }

        private fun sanitize(name: String) = name.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_").trim().ifEmpty { "download" }.take(120)

        fun formatBytes(bytes: Long): String = when {
            bytes < 0 -> "?"
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
            else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
        }
    }
}
