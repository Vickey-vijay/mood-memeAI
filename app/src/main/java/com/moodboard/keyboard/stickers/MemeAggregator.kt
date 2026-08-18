package com.moodboard.keyboard.stickers

import com.moodboard.keyboard.emotion.Emotion
import com.moodboard.keyboard.util.Prefs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Random

/**
 * Fans one scan out to every [MemeSource] whose [MemeSource.isAvailable] is true,
 * concurrently, each under its own [SOURCE_TIMEOUT_MS] budget so one slow/dead provider can
 * never stall the grid — a source that fails or times out is skipped silently, never fails
 * the whole fetch. Every source (including [ImgflipSource] — MAJOR SIMPLIFICATION: its
 * plain-English local meme catalogue is exactly what the new dynamic query already is, so
 * the old actor-name/keyword special-casing is gone) gets the SAME dynamic query built by
 * [MemeQueryBank.buildQuery] from the user's Setup "Meme style" choice. Results are
 * interleaved round-robin across sources before dedupe/return, so the grid reads as a
 * genuine mix instead of "20 GIPHY gifs followed by everything else".
 *
 * [StickerRepository] layers [MemeRelevance]'s light junk filter and [RecentlyShownStore]
 * anti-repeat on top of what this returns.
 */
class MemeAggregator(
    private val sources: List<MemeSource> = DEFAULT_SOURCES
) {

    suspend fun fetch(
        emotion: Emotion,
        prefs: Prefs,
        limit: Int,
        random: Random
    ): List<StickerItem> = coroutineScope {
        val available = sources.filter { it.isAvailable(prefs) }
        if (available.isEmpty()) return@coroutineScope emptyList()

        // One dynamic query + one random offset per scan, shared by every source, so a
        // single scan reads as one coherent "pull" rather than N independent random picks.
        val query = MemeQueryBank.buildQuery(emotion, prefs.memeCategory, prefs.memeCategoryCustom)
        val offset = random.nextInt(MAX_OFFSET + 1)

        val deferred = available.map { source ->
            async {
                withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                    try {
                        source.fetch(query, limit, offset, prefs)
                    } catch (t: Throwable) {
                        emptyList()
                    }
                } ?: emptyList()
            }
        }

        val perSource = deferred.map { it.await() }
        dedupe(interleave(perSource))
    }

    /** Round-robin merge: item 0 of every source, then item 1 of every source, etc. */
    private fun interleave(lists: List<List<StickerItem>>): List<StickerItem> {
        if (lists.isEmpty()) return emptyList()
        val out = ArrayList<StickerItem>(lists.sumOf { it.size })
        var idx = 0
        var any = true
        while (any) {
            any = false
            for (list in lists) {
                if (idx < list.size) {
                    out.add(list[idx])
                    any = true
                }
            }
            idx++
        }
        return out
    }

    private fun dedupe(items: List<StickerItem>): List<StickerItem> {
        val seenUrls = HashSet<String>()
        val seenProviderIds = HashSet<String>()
        val out = ArrayList<StickerItem>(items.size)
        for (item in items) {
            // Both add() calls must run unconditionally (not short-circuited) so both
            // dedupe sets stay accurate regardless of which check trips first.
            val urlIsDup = item.sendUrl.isNotEmpty() && !seenUrls.add(item.sendUrl)
            val idIsDup = item.providerId.isNotEmpty() && !seenProviderIds.add(item.providerId)
            if (urlIsDup || idIsDup) continue
            out.add(item)
        }
        return out
    }

    companion object {
        private const val SOURCE_TIMEOUT_MS = 6000L
        private const val MAX_OFFSET = 40

        val DEFAULT_SOURCES: List<MemeSource> = listOf(
            GiphyGifsSource,
            GiphyStickersSource,
            GiphyTrendingSource,
            TenorSource,
            ImgflipSource
        )
    }
}
