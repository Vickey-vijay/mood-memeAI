package com.moodboard.keyboard.stickers

import com.moodboard.keyboard.emotion.Emotion
import com.moodboard.keyboard.util.Prefs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Random

/**
 * SPEC_V3 A.3/A.6 online tier, generalised from a single provider to N (client
 * requirement: "i dont want to limit my application to tenor or gif whatever, i need all
 * kind of memes to be pulled to my application").
 *
 * Fans one scan out to every [MemeSource] whose [MemeSource.isAvailable] is true,
 * concurrently, each under its own [SOURCE_TIMEOUT_MS] budget so one slow/dead provider
 * can never stall the grid - a source that fails or times out is skipped silently, never
 * fails the whole fetch. Results are interleaved round-robin across sources before
 * dedupe/return: [MemeRelevance.filterRelevant]'s re-admission step downstream uses a
 * *stable* sort, so whichever source happened to dominate the front of a plain
 * concatenation would systematically win every re-admission tie; interleaving first
 * removes that bias and is also what gives the grid visible variety instead of
 * "20 GIPHY gifs followed by everything else". Finally deduped by URL and by provider id.
 *
 * [StickerRepository] layers [MemeRelevance] and [RecentlyShownStore] on top of what this
 * returns, exactly as it did for the old single-provider path.
 */
class MemeAggregator(
    private val sources: List<MemeSource> = DEFAULT_SOURCES
) {

    suspend fun fetch(
        emotion: Emotion,
        culture: MemeCulture,
        prefs: Prefs,
        limit: Int,
        random: Random
    ): List<StickerItem> = coroutineScope {
        val available = sources.filter { it.isAvailable(prefs) }
        if (available.isEmpty()) return@coroutineScope emptyList()

        // A.3.1/A.3.2 - one random query + one random offset per scan, shared by every
        // search-based source so a single scan reads as one coherent "pull" rather than N
        // independent random picks (also preserves pre-aggregator behaviour exactly).
        val pool = MemeQueryBank.queries(emotion, culture)
        val searchQuery = if (pool.isNotEmpty()) pool[random.nextInt(pool.size)] else emotion.query
        val offset = random.nextInt(MAX_OFFSET + 1)
        // Imgflip's catalogue is generic-English meme culture, not culture-flavoured - it
        // gets the emotion's keyword stems instead of the actor-name query (see
        // ImgflipSource's kdoc for why).
        val keywordQuery = MemeQueryBank.keywords(emotion).joinToString(" ")

        val deferred = available.map { source ->
            async {
                val query = if (source.id == ImgflipSource.ID) keywordQuery else searchQuery
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
