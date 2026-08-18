package com.moodboard.keyboard.stickers

import android.util.Log
import com.moodboard.keyboard.emotion.Emotion
import com.moodboard.keyboard.util.Prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * SPEC_V3 A.5 — anti-repeat store (R3). Per emotion, keeps a ring buffer of the last
 * [MAX_PER_MOOD] shown identities (provider id if available, else [StickerItem.sendUrl]).
 * Persisted as JSON in [Prefs] under `recently_shown_v1`.
 */
class RecentlyShownStore(private val prefs: Prefs) {

    /** identity -> lastShownAt, ordered oldest-first, per mood key. */
    private val store: MutableMap<String, MutableList<Pair<String, Long>>> =
        // loadFromPrefs() already resets to empty on a corrupt JSON blob; this outer guard
        // covers anything else unexpected so RecentlyShownStore's constructor - on the path
        // of every sticker search - can never throw.
        try { loadFromPrefs() } catch (t: Throwable) { mutableMapOf() }

    /**
     * Drops items whose identity was recently shown for [emotion]. If that leaves fewer
     * than [MIN_UNSEEN], re-admits the oldest-seen identities first so any forced repeats
     * are at least the least-recently-seen ones.
     */
    @Synchronized
    fun filterUnseen(emotion: Emotion, items: List<StickerItem>): List<StickerItem> {
        if (items.isEmpty()) return items
        val seen = store[emotion.key].orEmpty()
        val seenIds = seen.map { it.first }.toHashSet()

        val unseen = items.filter { identity(it) !in seenIds }
        if (unseen.size >= MIN_UNSEEN || unseen.size == items.size) return unseen

        val need = MIN_UNSEEN - unseen.size
        // Oldest-seen first.
        val oldestSeenIds = seen.sortedBy { it.second }.map { it.first }
        val seenItemsById = items.filter { identity(it) in seenIds }.associateBy { identity(it) }
        val reAdmitted = oldestSeenIds.mapNotNull { seenItemsById[it] }.take(need)

        return unseen + reAdmitted
    }

    /** Records [items] as shown for [emotion] just now (whatever was actually rendered). */
    @Synchronized
    fun markShown(emotion: Emotion, items: List<StickerItem>) {
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        val list = store.getOrPut(emotion.key) { mutableListOf() }
        for (item in items) {
            val id = identity(item)
            if (id.isEmpty()) continue
            list.removeAll { it.first == id }
            list.add(id to now)
        }
        // Ring buffer: keep only the most recent MAX_PER_MOOD, oldest-first order preserved.
        if (list.size > MAX_PER_MOOD) {
            list.sortBy { it.second }
            while (list.size > MAX_PER_MOOD) list.removeAt(0)
        }
        enforceHardCap()
        persist()
    }

    private fun identity(item: StickerItem): String =
        item.providerId.ifEmpty { item.id.ifEmpty { item.sendUrl } }

    /** Hard cap across all 19 emotions so the structure cannot grow unbounded. */
    private fun enforceHardCap() {
        var total = store.values.sumOf { it.size }
        if (total <= MAX_TOTAL) return
        // Evict globally-oldest entries first, mood by mood, until back under the cap.
        while (total > MAX_TOTAL) {
            val moodWithOldest = store.entries
                .filter { it.value.isNotEmpty() }
                .minByOrNull { entry -> entry.value.minOf { it.second } } ?: break
            moodWithOldest.value.sortBy { it.second }
            moodWithOldest.value.removeAt(0)
            total--
        }
    }

    private fun loadFromPrefs(): MutableMap<String, MutableList<Pair<String, Long>>> {
        val raw = prefs.recentlyShownJson
        if (raw.isBlank()) return mutableMapOf()
        return try {
            val json = JSONObject(raw)
            val moods = json.optJSONObject("moods") ?: JSONObject()
            val out = mutableMapOf<String, MutableList<Pair<String, Long>>>()
            moods.keys().forEach { moodKey ->
                val arr = moods.optJSONArray(moodKey) ?: JSONArray()
                val list = mutableListOf<Pair<String, Long>>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(o.getString("id") to o.optLong("ts", 0L))
                }
                out[moodKey] = list
            }
            out
        } catch (t: Throwable) {
            Log.w(TAG, "recently_shown_v1 corrupt, resetting", t)
            mutableMapOf()
        }
    }

    private fun persist() {
        try {
            val json = JSONObject()
            json.put("version", 1)
            val moods = JSONObject()
            store.forEach { (moodKey, list) ->
                val arr = JSONArray()
                for ((id, ts) in list) {
                    val o = JSONObject()
                    o.put("id", id)
                    o.put("ts", ts)
                    arr.put(o)
                }
                moods.put(moodKey, arr)
            }
            json.put("moods", moods)
            prefs.recentlyShownJson = json.toString()
        } catch (t: Throwable) {
            Log.w(TAG, "persist() failed", t)
        }
    }

    companion object {
        private const val TAG = "RecentlyShownStore"
        private const val MAX_PER_MOOD = 40
        private const val MIN_UNSEEN = 6
        // 19 emotions * 40 = 760 in the worst case already; this is an extra safety cap.
        private const val MAX_TOTAL = 800
    }
}
