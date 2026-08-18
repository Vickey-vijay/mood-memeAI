package com.moodboard.keyboard.stickers

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.moodboard.keyboard.emotion.Emotion
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Level-1 UI model: one card per mood (SPEC_V2 B.3). */
data class MoodBucket(val mood: Emotion, val count: Int, val coverFile: String?)

/**
 * Mood-bucketed sticker storage (SPEC_V2 Part B). Replaces the flat, metadata-less
 * `CustomStickerStore`. Layout on disk:
 *
 * ```
 * filesDir/stickers/
 *   index.json
 *   happy/      <uuid>.webp  <uuid>.png ...
 *   angry/      ...
 *   uncategorised/
 * ```
 *
 * Original bytes/mime are preserved for webp/gif (so animated stickers survive);
 * only large JPEG/PNG sources are re-encoded, clamped to a 512px long edge.
 * `index.json` is written atomically (temp file + rename) and is rebuilt from the
 * directory tree if missing or corrupt.
 */
class StickerLibrary(context: Context) {

    private data class Entry(
        val id: String,
        val mood: String,
        val file: String, // relative to rootDir, e.g. "happy/<uuid>.webp"
        val mime: String,
        val addedAt: Long,
        val source: String,
        val favorite: Boolean
    )

    private val appContext = context.applicationContext
    private val resolver: ContentResolver get() = appContext.contentResolver
    private val rootDir: File = File(appContext.filesDir, "stickers").apply { mkdirs() }
    private val indexFile = File(rootDir, "index.json")
    private val lock = Any()

    private val items = ArrayList<Entry>()
    private val covers = HashMap<String, String>() // mood key -> sticker id

    init {
        // P0 stability: loadOrRebuild()/migrateLegacyStore() already degrade gracefully on
        // their own (corrupt index -> rebuild from disk, unreadable legacy files -> skipped),
        // but this outer guard is the last line of defense - a genuinely unexpected disk/IO
        // failure here must never prevent every screen that constructs a StickerLibrary
        // (StickerManagerActivity, MoodStickersActivity, ReceiveStickerActivity, the IME's
        // StickerRepository, the overlay panel's StickerRepository) from opening at all.
        try {
            synchronized(lock) {
                loadOrRebuild()
                migrateLegacyStore()
                repairOrphanMoods()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "StickerLibrary init failed - starting with an empty library", t)
            synchronized(lock) {
                items.clear()
                covers.clear()
            }
        }
    }

    /**
     * One-time migration out of v1's flat `custom_stickers/` folder. Those stickers
     * predate mood buckets, so they land in NEUTRAL — which [moods] surfaces as soon
     * as it is non-empty, so they stay visible and the user can move them to the
     * right mood from the level-2 grid. Removing the legacy folder is what stops
     * this running a second time; anything that failed to copy is left in place.
     */
    private fun migrateLegacyStore() {
        val legacy = File(appContext.filesDir, "custom_stickers")
        if (!legacy.isDirectory) return
        var moved = 0
        for (f in legacy.listFiles { file: File -> file.isFile } ?: emptyArray()) {
            val bytes = try { f.readBytes() } catch (_: Throwable) { continue }
            if (addBytes(bytes, "image/png", Emotion.NEUTRAL, "legacy_v1") != null) {
                moved++
                f.delete()
            }
        }
        if (legacy.listFiles()?.isEmpty() != false) legacy.delete()
        if (moved > 0) Log.i(TAG, "Migrated $moved sticker(s) from the v1 flat store into NEUTRAL")
    }

    /**
     * Client-blocking bug fix: any item whose stored `mood` isn't a real [Emotion.key] —
     * most concretely the `uncategorised/` bucket [rebuildFromDisk] falls back to for a
     * directory it doesn't recognise — is invisible forever, because [moods] only ever
     * enumerates real [Emotion] values and [list] can only be called with one. That is a
     * real defect: a sticker can be saved successfully and still be permanently
     * unreachable from the UI. Fold every such orphan onto NEUTRAL (same destination
     * [migrateLegacyStore] already uses for pre-v2 stickers) so it is always reachable
     * from the Manage Stickers grid, then let the user move it from there. Runs on every
     * construction, so it also heals an index that was hand-edited or produced by a
     * future bug, not just the one known path.
     */
    private fun repairOrphanMoods() {
        val validKeys = Emotion.values().mapTo(HashSet()) { it.key }
        var changed = false
        for (i in items.indices) {
            val entry = items[i]
            if (entry.mood !in validKeys) {
                Log.w(TAG, "Orphan mood bucket '${entry.mood}' on sticker ${entry.id} - moving to NEUTRAL")
                items[i] = entry.copy(mood = Emotion.NEUTRAL.key)
                changed = true
            }
        }
        // Covers become dangling if they pointed at an id whose mood just changed under it.
        if (changed) {
            covers.entries.removeAll { (moodKey, id) -> items.none { it.id == id && it.mood == moodKey } }
            persist()
        }
    }

    // ---------------- B.2 public API ----------------

    fun moods(): List<MoodBucket> = synchronized(lock) {
        Emotion.values().mapNotNull { e ->
            val count = items.count { it.mood == e.key }
            if (e == Emotion.NEUTRAL && count == 0) return@mapNotNull null
            val coverEntry = covers[e.key]?.let { id -> items.find { it.id == id && it.mood == e.key } }
                ?: pickForMood(e.key).firstOrNull()
            MoodBucket(e, count, coverEntry?.let { absolutePath(it) })
        }
    }

    fun list(mood: Emotion): List<StickerItem> = synchronized(lock) {
        pickForMood(mood.key).map { toItem(it) }
    }

    /**
     * Every stored sticker regardless of mood, favourites first then newest first.
     * Required outcome 1 (client bug fix): [StickerRepository] uses this as a last-resort
     * "any mood" tier so a populated personal library is never invisible in the result
     * grid just because none of its stickers happen to be filed under the detected mood
     * or its runner-up.
     */
    fun listAll(): List<StickerItem> = synchronized(lock) {
        items.sortedWith(compareByDescending<Entry> { it.favorite }.thenByDescending { it.addedAt })
            .map { toItem(it) }
    }

    fun add(uri: Uri, mood: Emotion, source: String = "gallery"): StickerItem? {
        return try {
            val declaredMime = resolver.getType(uri)
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            addBytes(bytes, declaredMime, mood, source)
        } catch (t: Throwable) {
            Log.w(TAG, "add() failed", t)
            null
        }
    }

    fun addAll(uris: List<Uri>, mood: Emotion, source: String = "gallery"): Int {
        var count = 0
        for (uri in uris) if (add(uri, mood, source) != null) count++
        return count
    }

    /** Recursive SAF folder import (B.4 route 2), images only. Never throws. */
    fun importTree(treeUri: Uri, mood: Emotion, source: String = "folder"): Int {
        var count = 0
        val rootDocId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (t: Throwable) {
            Log.w(TAG, "importTree: bad tree uri", t)
            return 0
        }
        val stack = ArrayDeque<String>()
        stack.addLast(rootDocId)
        while (stack.isNotEmpty()) {
            val docId = stack.removeLast()
            val childrenUri = try {
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            } catch (t: Throwable) { continue }
            try {
                resolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    ),
                    null, null, null
                )?.use { cursor ->
                    val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    while (cursor.moveToNext()) {
                        val childId = cursor.getString(idIdx)
                        val childMime = cursor.getString(mimeIdx)
                        if (childMime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            stack.addLast(childId)
                        } else if (childMime != null && childMime.startsWith("image/")) {
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                            try {
                                val bytes = resolver.openInputStream(docUri)?.use { it.readBytes() }
                                if (bytes != null && addBytes(bytes, childMime, mood, source) != null) count++
                            } catch (t: Throwable) {
                                Log.w(TAG, "importTree: skip file", t)
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "importTree: skip folder", t)
            }
        }
        return count
    }

    fun move(id: String, mood: Emotion): Boolean = synchronized(lock) {
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) return@synchronized false
        val entry = items[idx]
        if (entry.mood == mood.key) return@synchronized true
        val src = File(rootDir, entry.file)
        val destDir = File(rootDir, mood.key).apply { mkdirs() }
        val dest = File(destDir, src.name)
        val moved = try {
            src.copyTo(dest, overwrite = true)
            src.delete()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "move() failed", t); false
        }
        if (!moved) return@synchronized false
        items[idx] = entry.copy(mood = mood.key, file = "${mood.key}/${src.name}")
        persist()
        true
    }

    fun setFavorite(id: String, fav: Boolean): Boolean = synchronized(lock) {
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) return@synchronized false
        items[idx] = items[idx].copy(favorite = fav)
        persist()
        true
    }

    /** Long-press "Set as cover" (B.3). Not part of the literal B.2 signature list
     *  but required to persist the action; a thin, additive extension. */
    fun setCover(mood: Emotion, id: String): Boolean = synchronized(lock) {
        if (items.none { it.id == id && it.mood == mood.key }) return@synchronized false
        covers[mood.key] = id
        persist()
        true
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) return@synchronized false
        val entry = items[idx]
        File(rootDir, entry.file).delete()
        items.removeAt(idx)
        if (covers[entry.mood] == id) covers.remove(entry.mood)
        persist()
        true
    }

    fun totalCount(): Int = synchronized(lock) { items.size }

    // ---------------- internals ----------------

    private fun pickForMood(moodKey: String): List<Entry> =
        items.filter { it.mood == moodKey }
            .sortedWith(compareByDescending<Entry> { it.favorite }.thenByDescending { it.addedAt })

    private fun absolutePath(entry: Entry): String = File(rootDir, entry.file).absolutePath

    private fun toItem(entry: Entry): StickerItem = StickerItem(
        previewUrl = absolutePath(entry),
        sendUrl = absolutePath(entry),
        mime = entry.mime,
        isLocal = true,
        id = entry.id,
        mood = entry.mood,
        favorite = entry.favorite
    )

    /**
     * Shared write path for add/addAll/importTree/ReceiveStickerActivity, and also called
     * directly by [com.moodboard.keyboard.ui.SaveStickerActivity] (required outcome 4: the
     * in-keyboard "save this sticker I found" flow) once it has the raw bytes in hand —
     * downloaded from an online meme's URL, or read straight off disk for one already in
     * the cache/library. Format is byte-sniffed first ([sniffFormat]); [declaredMime] is
     * only a fallback, so a source with a missing/wrong Content-Type still saves correctly.
     */
    fun addBytes(bytes: ByteArray, declaredMime: String?, mood: Emotion, source: String): StickerItem? {
        if (bytes.isEmpty()) return null
        val format = sniffFormat(bytes) ?: mimeToFormat(declaredMime) ?: return null
        val id = UUID.randomUUID().toString()
        val moodDir = File(rootDir, mood.key).apply { mkdirs() }

        val (finalBytes, ext, mime) = when (format) {
            "webp" -> Triple(bytes, "webp", "image/webp")
            "gif" -> Triple(bytes, "gif", "image/gif")
            "png", "jpeg" -> {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                val long = maxOf(opts.outWidth, opts.outHeight)
                if (long in 1..MAX_KEEP_LONG_EDGE) {
                    // Small enough - preserve original bytes/format untouched.
                    val origExt = if (format == "png") "png" else "jpg"
                    val origMime = if (format == "png") "image/png" else "image/jpeg"
                    Triple(bytes, origExt, origMime)
                } else {
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                    val clamped = clampLongEdge(bmp, 512)
                    val out = java.io.ByteArrayOutputStream()
                    clamped.compress(Bitmap.CompressFormat.PNG, 100, out)
                    if (clamped !== bmp) bmp.recycle()
                    Triple(out.toByteArray(), "png", "image/png")
                }
            }
            else -> return null
        }

        val file = File(moodDir, "$id.$ext")
        try {
            FileOutputStream(file).use { it.write(finalBytes) }
        } catch (t: Throwable) {
            Log.w(TAG, "addBytes: write failed", t)
            return null
        }

        val entry = Entry(
            id = id,
            mood = mood.key,
            file = "${mood.key}/${file.name}",
            mime = mime,
            addedAt = System.currentTimeMillis(),
            source = source,
            favorite = false
        )
        synchronized(lock) {
            items.add(entry)
            persist()
        }
        return toItem(entry)
    }

    private fun clampLongEdge(src: Bitmap, max: Int): Bitmap {
        val w = src.width; val h = src.height
        if (w <= max && h <= max) return src
        val scale = max.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(src, (w * scale).coerceAtLeast(1f).toInt(), (h * scale).coerceAtLeast(1f).toInt(), true)
    }

    private fun sniffFormat(b: ByteArray): String? {
        if (b.size >= 12 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() &&
            b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte() &&
            b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() && b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte()
        ) return "webp"
        if (b.size >= 3 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte()) return "gif"
        if (b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()) return "png"
        if (b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) return "jpeg"
        return null
    }

    private fun mimeToFormat(mime: String?): String? = when (mime?.lowercase()) {
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/png" -> "png"
        "image/jpeg", "image/jpg" -> "jpeg"
        else -> null
    }

    // ---------------- index persistence (B.1) ----------------

    private fun loadOrRebuild() {
        val loaded = try {
            if (indexFile.exists()) parseIndex(indexFile.readText()) else null
        } catch (t: Throwable) {
            Log.w(TAG, "index.json corrupt, rebuilding from disk", t)
            null
        }
        if (loaded != null) {
            items.clear(); items.addAll(loaded.first)
            covers.clear(); covers.putAll(loaded.second)
            // Drop entries whose backing file vanished, keep the rest honest.
            val before = items.size
            items.removeAll { !File(rootDir, it.file).exists() }
            if (items.size != before) persist()
        } else {
            rebuildFromDisk()
        }
    }

    private fun parseIndex(text: String): Pair<List<Entry>, Map<String, String>> {
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
                    mime = o.optString("mime", "image/png"),
                    addedAt = o.optLong("addedAt", 0L),
                    source = o.optString("source", "gallery"),
                    favorite = o.optBoolean("favorite", false)
                )
            )
        }
        val coversObj = json.optJSONObject("covers")
        val coversMap = HashMap<String, String>()
        if (coversObj != null) {
            coversObj.keys().forEach { k -> coversMap[k] = coversObj.getString(k) }
        }
        return out to coversMap
    }

    private fun rebuildFromDisk() {
        items.clear()
        covers.clear()
        val moodKeys = Emotion.values().map { it.key }.toSet()
        rootDir.listFiles { f -> f.isDirectory }?.forEach { dir ->
            val moodKey = if (dir.name in moodKeys) dir.name else UNCATEGORISED
            dir.listFiles { f -> f.isFile }?.forEach { f ->
                val ext = f.extension.lowercase()
                val mime = when (ext) {
                    "webp" -> "image/webp"
                    "gif" -> "image/gif"
                    "png" -> "image/png"
                    "jpg", "jpeg" -> "image/jpeg"
                    else -> null
                }
                if (mime != null) {
                    items.add(
                        Entry(
                            id = f.nameWithoutExtension,
                            mood = moodKey,
                            file = "${dir.name}/${f.name}",
                            mime = mime,
                            addedAt = f.lastModified(),
                            source = "recovered",
                            favorite = false
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
                o.put("addedAt", e.addedAt)
                o.put("source", e.source)
                o.put("favorite", e.favorite)
                arr.put(o)
            }
            json.put("items", arr)
            val coversObj = JSONObject()
            covers.forEach { (k, v) -> coversObj.put(k, v) }
            json.put("covers", coversObj)

            val tmp = File(rootDir, "index.json.tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(indexFile)) {
                // Cross-filesystem fallback: copy + delete.
                indexFile.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "persist() failed", t)
        }
    }

    companion object {
        private const val TAG = "StickerLibrary"
        private const val MAX_KEEP_LONG_EDGE = 512
        private const val UNCATEGORISED = "uncategorised"
    }
}
