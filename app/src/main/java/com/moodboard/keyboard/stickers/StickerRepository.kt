package com.moodboard.keyboard.stickers

import android.content.Context
import com.moodboard.keyboard.emotion.Emotion
import com.moodboard.keyboard.emotion.EmotionResult
import com.moodboard.keyboard.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Random

/**
 * Fetches stickers/GIFs for a detected mood. MAJOR SIMPLIFICATION (client bug report,
 * 2026-08-19) retrieval order:
 *   1. The user's own stickers for [EmotionResult.emotion] — favourites first, always
 *      shown first, never filtered. If the exact mood has ANY of the user's own stickers,
 *      this tier is ONLY that mood — no runner-up mood, no any-mood backfill mixed in
 *      beneath it ("I'm getting the stickers which I saved for Laughing, below the ones
 *      saved for Kiss ... that's not acceptable"). The runner-up-mood / any-mood fallback
 *      fires ONLY when the exact mood has ZERO of the user's own stickers, so a populated
 *      library filed entirely under some other mood still surfaces something here.
 *   2. Online: every available [MemeSource] fetched concurrently via [aggregator], all fed
 *      the same dynamic "<mood word> <category>" query built by [MemeQueryBank] from the
 *      user's Setup "Meme style" choice (client requirement: "i dont want to limit my
 *      application to tenor or gif whatever, i need all kind of memes to be pulled to my
 *      application" — "first you should show the stickers he has imported for the mood,
 *      next to that this searched one will appear. Make it dynamic").
 *   3. Emoji fallback — handled by the caller when [search] returns an empty/failed result.
 *
 * The client explicitly does not want anything downloaded/cached in the background, so
 * there is no pre-cache tier here any more — stickers are fetched live on every scan (a
 * meme's bytes are still downloaded at *send* time by
 * [com.moodboard.keyboard.util.RichContentSender], which is unaffected).
 *
 * [MemeRelevance]'s light junk filter and [RecentlyShownStore] anti-repeat are applied to
 * tier 2 only.
 */
class StickerRepository(
    private val context: Context,
    private val prefs: Prefs,
    /** Online tier seam (provider-aggregator architecture). Overridable for tests without real network. */
    private val aggregator: MemeAggregator = MemeAggregator()
) {

    private val library = StickerLibrary(context)
    private val recentlyShown = RecentlyShownStore(prefs)

    // Single Random per repository instance, seeded from wall-clock nanotime — never a
    // fixed seed, so query offset/shuffle vary scan to scan.
    private val random = Random(System.nanoTime())

    /**
     * [MemeSource.id]s that actually contributed an item to the *final* grid of the most
     * recent [search] call (post junk-filter and anti-repeat, i.e. what the user is
     * actually looking at) — drives the multi-source "Powered by …" attribution label via
     * [MemeAttribution]. Empty when nothing online survived.
     */
    var lastFetchSources: Set<String> = emptySet()
        private set

    /** True if the most recent [search] call returned any online results. */
    var lastFetchHadOnlineResults: Boolean = false
        private set

    /** Preferred entry point: full detection result, not just the label, so the runner-up
     *  mood in [EmotionResult.distribution] can be used as fallback. */
    suspend fun search(result: EmotionResult, limit: Int = 30): Result<List<StickerItem>> =
        withContext(Dispatchers.IO) {
            val emotion = result.emotion
            lastFetchHadOnlineResults = false
            lastFetchSources = emptySet()

            // Tier 1 - user's own stickers, always shown first, never filtered.
            val own = library.list(emotion)
            val runnerUp = result.distribution.getOrNull(1)?.first
            val related = if (own.isEmpty() && runnerUp != null && runnerUp != emotion) {
                library.list(runnerUp)
            } else emptyList()
            val exactAndRelated = own + related

            // Any-mood backfill: only when the exact mood AND the runner-up both came up
            // empty, so a personal library filed entirely under some other mood (e.g.
            // everything under Happy) still surfaces something in tier 1.
            val anyMood = if (exactAndRelated.isEmpty()) {
                val coveredMoods = setOfNotNull(emotion.key, runnerUp?.takeIf { related.isNotEmpty() }?.key)
                library.listAll().filter { it.mood !in coveredMoods }
            } else emptyList()

            val ownCombined = if (prefs.preferOwnStickers) {
                exactAndRelated + anyMood
            } else {
                anyMood + exactAndRelated
            }

            // Tier 2 - online search for the dynamic mood+category query.
            val online = try {
                fetchOnline(emotion, limit)
            } catch (t: Throwable) {
                emptyList()
            }

            val combined = if (prefs.preferOwnStickers) ownCombined + online else online + ownCombined
            successOrFailure(combined)
        }

    private suspend fun fetchOnline(emotion: Emotion, limit: Int): List<StickerItem> {
        if (!prefs.onlineStickers) return emptyList()
        val raw = try {
            aggregator.fetch(emotion, prefs, limit, random)
        } catch (t: Throwable) {
            // Every source failed outright, or the aggregator itself blew up - tier 1 can
            // still fill the grid. Individual source failures never reach here;
            // MemeAggregator already swallows those per-source.
            emptyList()
        }
        if (raw.isEmpty()) return emptyList()

        val junkFiltered = MemeRelevance.filterJunk(raw) { it.rawText }
        val shuffled = junkFiltered.shuffled(random) // shuffle before display
        val unseen = recentlyShown.filterUnseen(emotion, shuffled)
        recentlyShown.markShown(emotion, unseen)

        lastFetchHadOnlineResults = unseen.isNotEmpty()
        // Multi-source attribution reflects what actually made it into the grid the user
        // sees, not just what the raw fetch happened to return.
        lastFetchSources = unseen.mapNotNullTo(LinkedHashSet()) { it.source.ifEmpty { null } }
        return unseen
    }

    private fun successOrFailure(local: List<StickerItem>, cause: Throwable? = null): Result<List<StickerItem>> =
        if (local.isNotEmpty()) Result.success(local) else Result.failure(cause ?: RuntimeException("No stickers"))
}
