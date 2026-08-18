package com.moodboard.keyboard.stickers

import android.content.Context
import android.util.Log
import com.moodboard.keyboard.emotion.Emotion
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * SPEC_V3 B.2 — disk cache of pre-fetched memes, keyed per emotion, so tier 2 of
 * [StickerRepository]'s retrieval order can serve instantly and offline. Layout:
 *
 * ```
 * filesDir/meme_cache/
 *   cache_index.json
 *   happy/<sha1>.gif ...
 * ```
 *
 * `cache_index.json` holds exactly the fields SPEC_V3 documents: id, mood, file, mime,
 * query, fetchedAt, lastUsedAt, bytes. Written atomically (temp + rename) and rebuilt
 * from the directory tree if missing or corrupt — same discipline as [StickerLibrary].
 *
 * Caps: [MAX_FILES] files or [MAX_BYTES] bytes, whichever hits first, evicted LRU by
 * `lastUsedAt`. TTL: [TTL_MILLIS] (7 days) — expired entries are still returned by
 * [readItems] (stale-but-instant); [isStale] tells a caller a background refresh is due.
 */
class MemeCache(context: Context) {

    private data class Entry(
        val id: String,
        val mood: String,
        val file: String, // relative to rootDir, e.g. "happy/<sha1>.gif"
        val mime: String,
        val query: String,
        val fetchedAt: Long,
        var lastUsedAt: Long,
        val bytes: Long,
        /** [MemeSource.id] this entry was fetched from (provider-aggregator architecture).
         *  Empty for entries written before this field existed - back-compat via [optString]. */
        val source: String = ""
    )

    data class CacheStats(
        val itemCount: Int,
        val totalBytes: Long,
        val lastRefreshAt: Long
    )

    private val appContext = context.applicationContext
    private val rootDir: File = File(appContext.filesDir, "meme_cache").apply { mkdirs() }
    private val indexFile = File(rootDir, "cache_index.json")
    private val lock = Any()

    private val items = ArrayList<Entry>()

    init {
        // P0 stability: same belt-and-suspenders guard as StickerLibrary - loadOrRebuild()
        // already recovers from a corrupt index on its own, this just ensures a truly
        // unexpected failure can never stop MemeCache() from constructing, since it's on the
        // path of SetupActivity, the IME, and the overlay panel alike.
        try {
            synchronized(lock) { loadOrRebuild() }
        } catch (t: Throwable) {
            Log.w(TAG, "MemeCache init failed - starting with an empty cache", t)
            synchronized(lock) { items.clear() }
        }
    }

    // ---------------- B.2/B.4 public API ----------------

    /** Read path (B.4) — cached items for [emotion], most-recently-used first. Never touches the network. */
    fun readItems(emotion: Emotion, limit: Int = 10): List<StickerItem> = synchronized(lock) {
        items.filter { it.mood == emotion.key }
            .sortedByDescending { it.lastUsedAt }
            .take(limit)
            .map { toItem(it) }
    }

    /** Marks [servedItems] as just-used, refreshing their LRU rank (B.4). */
    fun recordUsage(servedItems: List<StickerItem>) {
        if (servedItems.isEmpty()) return
        synchronized(lock) {
            val now = System.currentTimeMillis()
            var touched = false
            for (served in servedItems) {
                if (!served.isLocal || served.id.isEmpty() || served.mood.isEmpty()) continue
                val idx = items.indexOfFirst { it.id == served.id && it.mood == served.mood }
                if (idx >= 0) {
                    items[idx] = items[idx].copy(lastUsedAt = now)
                    touched = true
                }
            }
            if (touched) persist()
        }
    }

    /**
     * Inserts a freshly-fetched meme (B.3 worker) for [emotion], sourced from [query].
     * Content-addressed by sha1(bytes): re-fetching the same file just refreshes its
     * timestamps rather than duplicating storage. Returns null on any I/O failure or
     * empty payload — the caller (the worker) treats that emotion as skipped, not fatal.
     */
    fun insert(emotion: Emotion, query: String, mime: String, bytes: ByteArray, source: String = ""): StickerItem? {
        if (bytes.isEmpty()) return null
        return try {
            val id = sha1(bytes)
            val ext = extFor(mime)
            val moodDir = File(rootDir, emotion.key).apply { mkdirs() }
            val dest = File(moodDir, "$id.$ext")
            val now = System.currentTimeMillis()

            synchronized(lock) {
                val existingIdx = items.indexOfFirst { it.id == id && it.mood == emotion.key }
                if (existingIdx >= 0 && dest.exists()) {
                    // Same content already cached - just mark it fresh.
                    items[existingIdx] = items[existingIdx].copy(fetchedAt = now, lastUsedAt = now, source = source)
                } else {
                    val tmp = File(moodDir, "$id.$ext.tmp")
                    tmp.writeBytes(bytes)
                    if (!tmp.renameTo(dest)) {
                        dest.writeBytes(tmp.readBytes())
                        tmp.delete()
                    }
                    val entry = Entry(
                        id = id,
                        mood = emotion.key,
                        file = "${emotion.key}/${dest.name}",
                        mime = mime,
                        query = query,
                        fetchedAt = now,
                        lastUsedAt = now,
                        bytes = dest.length(),
                        source = source
                    )
                    if (existingIdx >= 0) items[existingIdx] = entry else items.add(entry)
                }
                evictLocked()
                persist()
                toItem(items.first { it.id == id && it.mood == emotion.key })
            }
        } catch (t: Throwable) {
            Log.w(TAG, "insert() failed", t)
            null
        }
    }

    /** Forces cap enforcement (300 files or 150 MB, LRU by lastUsedAt). Also run automatically on [insert]. */
    fun evict() = synchronized(lock) {
        evictLocked()
        persist()
    }

    /** True when every cached item for [emotion] is older than the 7-day TTL (or none exist). */
    fun isStale(emotion: Emotion): Boolean = synchronized(lock) {
        val moodItems = items.filter { it.mood == emotion.key }
        if (moodItems.isEmpty()) return@synchronized true
        val now = System.currentTimeMillis()
        moodItems.all { now - it.fetchedAt > TTL_MILLIS }
    }

    fun stats(): CacheStats = synchronized(lock) {
        CacheStats(
            itemCount = items.size,
            totalBytes = items.sumOf { it.bytes },
            lastRefreshAt = items.maxOfOrNull { it.fetchedAt } ?: 0L
        )
    }

    // ---------------- internals ----------------

    private fun evictLocked() {
        var totalBytes = items.sumOf { it.bytes }
        while (items.size > MAX_FILES || totalBytes > MAX_BYTES) {
            val victim = items.minByOrNull { it.lastUsedAt } ?: break
            File(rootDir, victim.file).delete()
            items.remove(victim)
            totalBytes -= victim.bytes
        }
    }

    private fun toItem(entry: Entry): StickerItem {
        val path = File(rootDir, entry.file).absolutePath
        return StickerItem(
            previewUrl = path,
            sendUrl = path,
            mime = entry.mime,
            isLocal = true,
            id = entry.id,
            mood = entry.mood,
            favorite = false,
            providerId = "",
            rawText = entry.query,
            source = entry.source
        )
    }

    private fun extFor(mime: String): String = when (mime.lowercase()) {
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/png" -> "png"
        "image/jpeg", "image/jpg" -> "jpg"
        else -> "bin"
    }

    private fun sha1(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    // ---------------- index persistence (B.2) ----------------

    private fun loadOrRebuild() {
        val loaded = try {
            if (indexFile.exists()) parseIndex(indexFile.readText()) else null
        } catch (t: Throwable) {
            Log.w(TAG, "cache_index.json corrupt, rebuilding from disk", t)
            null
        }
        if (loaded != null) {
            items.clear(); items.addAll(loaded)
            val before = items.size
            items.removeAll { !File(rootDir, it.file).exists() }
            if (items.size != before) persist()
        } else {
            rebuildFromDisk()
        }
    }

    private fun parseIndex(text: String): List<Entry> {
        val json = JSONObject(text)
        val arr = json.optJSONArray("items") ?: JSONArray()
        val out = ArrayList<Entry>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                Entry(
                    id = o.getString("id"),
                    mood = o.getString("mood"),
                    file = o.getString("file"),
                    mime = o.optString("mime", "image/gif"),
                    query = o.optString("query", ""),
                    fetchedAt = o.optLong("fetchedAt", 0L),
                    lastUsedAt = o.optLong("lastUsedAt", 0L),
                    bytes = o.optLong("bytes", 0L),
                    source = o.optString("source", "")
                )
            )
        }
        return out
    }

    private fun rebuildFromDisk() {
        items.clear()
        val moodKeys = Emotion.values().map { it.key }.toSet()
        rootDir.listFiles { f -> f.isDirectory }?.forEach { dir ->
            if (dir.name !in moodKeys) return@forEach
            dir.listFiles { f -> f.isFile && !f.name.endsWith(".tmp") }?.forEach { f ->
                val ext = f.extension.lowercase()
                val mime = when (ext) {
                    "gif" -> "image/gif"
                    "webp" -> "image/webp"
                    "png" -> "image/png"
                    "jpg", "jpeg" -> "image/jpeg"
                    else -> null
                }
                if (mime != null) {
                    items.add(
                        Entry(
                            id = f.nameWithoutExtension,
                            mood = dir.name,
                            file = "${dir.name}/${f.name}",
                            mime = mime,
                            query = "",
                            fetchedAt = f.lastModified(),
                            lastUsedAt = f.lastModified(),
                            bytes = f.length()
                        )
                    )
                }
            }
        }
        persist()
    }

    private fun persist() {
        try {
            val json = JSONObject()
            json.put("version", 1)
            val arr = JSONArray()
            for (e in items) {
                val o = JSONObject()
                o.put("id", e.id)
                o.put("mood", e.mood)
                o.put("file", e.file)
                o.put("mime", e.mime)
                o.put("query", e.query)
                o.put("fetchedAt", e.fetchedAt)
                o.put("lastUsedAt", e.lastUsedAt)
                o.put("bytes", e.bytes)
                o.put("source", e.source)
                arr.put(o)
            }
            json.put("items", arr)

            val tmp = File(rootDir, "cache_index.json.tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(indexFile)) {
                indexFile.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "persist() failed", t)
        }
    }

    companion object {
        private const val TAG = "MemeCache"
        private const val MAX_FILES = 300
        private const val MAX_BYTES = 150L * 1024 * 1024
        private const val TTL_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}
