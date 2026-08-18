package com.moodboard.keyboard.stickers

import com.moodboard.keyboard.util.Prefs

/**
 * One meme/sticker/GIF provider in the aggregator architecture (client requirement:
 * "i dont want to limit my application to tenor or gif whatever, i need all kind of
 * memes to be pulled to my application"). [MemeAggregator] fans a single scan out to
 * every available [MemeSource] concurrently and merges the results.
 *
 * Deliberate deviation from the "roughly" interface sketched in the brief: [fetch] also
 * takes [Prefs] (in addition to [isAvailable] taking it) because GIPHY and Tenor both
 * need an API key at call time, and the user can edit the Tenor key from Setup between
 * scans - reading it fresh on every fetch is simpler and safer than caching it on the
 * (stateless, singleton) source instances.
 */
interface MemeSource {

    /** Stable id, also used as the [StickerItem.source] tag and the Setup per-source toggle key. */
    val id: String

    /** True if this source is turned on and has everything it needs (e.g. an API key) to be called. */
    fun isAvailable(prefs: Prefs): Boolean

    /**
     * Fetches up to [limit] candidates for [query] at [offset]. Must never throw for a
     * routine failure (network error, bad JSON, empty result) - the caller
     * ([MemeAggregator]) treats an empty list as "this source had nothing this time", not
     * an error. Must do its own I/O off the caller's thread (implementations dispatch to
     * [kotlinx.coroutines.Dispatchers.IO]).
     */
    suspend fun fetch(query: String, limit: Int, offset: Int, prefs: Prefs): List<StickerItem>
}
