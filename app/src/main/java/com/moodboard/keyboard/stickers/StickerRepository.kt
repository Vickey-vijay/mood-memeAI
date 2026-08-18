package com.moodboard.keyboard.stickers

import android.content.Context
import com.moodboard.keyboard.emotion.Emotion
import com.moodboard.keyboard.emotion.EmotionResult
import com.moodboard.keyboard.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Random

/**
 * Fetches stickers/GIFs for a detected mood, following the SPEC_V3 A.6 retrieval
 * order (supersedes SPEC_V2 B.5):
 *   1. The user's own stickers for [EmotionResult.emotion] - favourites first, plus the
 *      runner-up mood's stickers as a "related" fallback when tier 1 is thin, plus - if
 *      the grid is *still* thin - the user's stickers from ANY mood, ranked below the
 *      first two. Unchanged from SPEC_V2 and never filtered - the user chose these
 *      deliberately. This three-step tier is the fix for the client-reported bug where a
 *      populated personal library could still show zero of the user's own stickers, e.g.
 *      because every import landed in one mood bucket that doesn't match today's scan.
 *   2. Pre-cache for that mood (workstream B) - served from [MemeCache] via [cacheProvider],
 *      synchronously off Dispatchers.IO, no network round trip.
 *   3. Online: every available [MemeSource] fetched concurrently via [aggregator]
 *      (provider-aggregator architecture - client requirement: "i dont want to limit my
 *      application to tenor or gif whatever, i need all kind of memes to be pulled to my
 *      application"), South Indian query pool (R2) first, falling back to the GENERIC
 *      pool if that yields nothing usable.
 *   4. Emoji fallback - handled by the caller when [search] returns an empty/failed result.
 *
 * A.4 relevance filtering and A.5 anti-repeat are applied to tiers 2+3 only.
 */
class StickerRepository(
    private val context: Context,
    private val prefs: Prefs,
    /** Tier-2 pre-cache seam (SPEC_V3 workstream B). Defaults to reading/touching [MemeCache];
     *  overridable for tests without a real disk cache. */
    private val cacheProvider: ((Emotion) -> List<StickerItem>)? = null,
    /** Tier-3 online seam (provider-aggregator architecture). Overridable for tests without real network. */
    private val aggregator: MemeAggregator = MemeAggregator()
) {

    private val library = StickerLibrary(context)
    private val recentlyShown = RecentlyShownStore(prefs)
    private val memeCache = MemeCache(context)

    // Single Random per repository instance, seeded from wall-clock nanotime per SPEC_V3
    // A.3 - never a fixed seed, so query/offset/shuffle vary scan to scan.
    private val random = Random(System.nanoTime())

    /**
     * [MemeSource.id]s that actually contributed an item to the *final* grid of the most
     * recent [search] call (post relevance-filter and anti-repeat, i.e. what the user is
     * actually looking at) - drives the multi-source "Powered by …" attribution label
     * via [MemeAttribution]. Empty when nothing online (or from the pre-cache) survived.
     */
    var lastFetchSources: Set<String> = emptySet()
        private set

    /** True if the most recent [search] call returned any online (tier 2/3) results. */
    var lastFetchHadOnlineResults: Boolean = false
        private set

    /** Preferred entry point (SPEC_V3 A.6): full detection result, not just the label,
     *  so the runner-up mood in [EmotionResult.distribution] can be used as fallback. */
    suspend fun search(result: EmotionResult, limit: Int = 30): Result<List<StickerItem>> =
        withContext(Dispatchers.IO) {
            val emotion = result.emotion
            lastFetchHadOnlineResults = false
            lastFetchSources = emptySet()

            // Tier 1 - user's own stickers, unchanged from SPEC_V2, never filtered.
            val own = library.list(emotion)
            val runnerUp = result.distribution.getOrNull(1)?.first
            val related = if (own.size < RELATED_THRESHOLD) {
                if (runnerUp != null && runnerUp != emotion) library.list(runnerUp) else emptyList()
            } else emptyList()
            val exactAndRelated = own + related

            // Required outcome 1: never let a populated personal library produce a grid
            // with none of the user's stickers. If exact-mood + runner-up is still thin,
            // backfill from the user's stickers in ANY mood - still ranked below both,
            // and excluding anything already picked up above.
            val anyMood = if (exactAndRelated.size < RELATED_THRESHOLD) {
                val usedIds = exactAndRelated.mapNotNullTo(HashSet()) { it.id.ifEmpty { null } }
                val coveredMoods = setOfNotNull(emotion.key, runnerUp?.takeIf { related.isNotEmpty() }?.key)
                library.listAll().filter { it.id !in usedIds && it.mood !in coveredMoods }
            } else emptyList()

            val ownCombined = if (prefs.preferOwnStickers) {
                exactAndRelated + anyMood
            } else {
                anyMood + exactAndRelated
            }

            // Tiers 2+3 - pre-cache seam + online (aggregator), filtered and anti-repeated together.
            val tier23 = try {
                fetchTier2And3(emotion, limit)
            } catch (t: Throwable) {
                emptyList()
            }

            val combined = if (prefs.preferOwnStickers) ownCombined + tier23 else tier23 + ownCombined
            successOrFailure(combined)
        }

    /** B.4 default tier-2 read: cache hit off Dispatchers.IO, LRU-touches what it serves. */
    private fun defaultCacheRead(emotion: Emotion): List<StickerItem> {
        val cached = memeCache.readItems(emotion)
        if (cached.isNotEmpty()) memeCache.recordUsage(cached)
        return cached
    }

    private suspend fun fetchTier2And3(emotion: Emotion, limit: Int): List<StickerItem> {
        val cached = (cacheProvider ?: ::defaultCacheRead).invoke(emotion)
        val online = if (prefs.onlineStickers) fetchOnline(emotion, limit) else emptyList()
        val candidates = cached + online
        if (candidates.isEmpty()) return emptyList()

        val relevant = MemeRelevance.filterRelevant(
            candidates, { it.rawText }, emotion, prefs.memeCulture
        )
        val shuffled = relevant.shuffled(random) // A.3.3 - shuffle before display
        val unseen = recentlyShown.filterUnseen(emotion, shuffled) // A.5
        recentlyShown.markShown(emotion, unseen)

        lastFetchHadOnlineResults = online.isNotEmpty()
        // A.7 (multi-source) - attribution reflects what actually made it into the grid
        // the user sees, not just what the raw fetch happened to return.
        lastFetchSources = unseen.mapNotNullTo(LinkedHashSet()) { it.source.ifEmpty { null } }
        return unseen
    }

    /** A.6.3 - South Indian pool first, GENERIC pool if that yields nothing usable. */
    private suspend fun fetchOnline(emotion: Emotion, limit: Int): List<StickerItem> = try {
        val primaryCulture = prefs.memeCulture
        var raw = aggregator.fetch(emotion, primaryCulture, prefs, limit, random)
        if (raw.isEmpty() && primaryCulture == MemeCulture.SOUTH_INDIAN) {
            raw = aggregator.fetch(emotion, MemeCulture.GENERIC, prefs, limit, random)
        }
        raw
    } catch (t: Throwable) {
        // Every source failed outright, or the aggregator itself blew up - tier 1 (and
        // tier 2, if present) can still fill the grid. Individual source failures never
        // reach here; MemeAggregator already swallows those per-source.
        emptyList()
    }

    private fun successOrFailure(local: List<StickerItem>, cause: Throwable? = null): Result<List<StickerItem>> =
        if (local.isNotEmpty()) Result.success(local) else Result.failure(cause ?: RuntimeException("No stickers"))

    companion object {
        private const val RELATED_THRESHOLD = 12
    }
}
