package com.moodboard.keyboard.stickers

import android.content.Context
import com.moodboard.keyboard.emotion.Emotion
import com.moodboard.keyboard.emotion.EmotionResult
import com.moodboard.keyboard.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Random
import java.util.concurrent.TimeUnit

/**
 * Fetches stickers/GIFs for a detected mood, following the SPEC_V3 A.6 retrieval
 * order (supersedes SPEC_V2 B.5):
 *   1. The user's own stickers for [EmotionResult.emotion] - favourites first, plus the
 *      runner-up mood's stickers as a "related" fallback when tier 1 is thin. Unchanged
 *      from SPEC_V2 and never filtered - the user chose these deliberately.
 *   2. Pre-cache for that mood (workstream B) - served from [MemeCache] via [cacheProvider],
 *      synchronously off Dispatchers.IO, no network round trip.
 *   3. Online: the South Indian query pool (R2), falling back to the GENERIC pool if
 *      that yields nothing usable.
 *   4. Emoji fallback - handled by the caller when [search] returns an empty/failed result.
 *
 * A.4 relevance filtering and A.5 anti-repeat are applied to tiers 2+3 only.
 */
class StickerRepository(
    private val context: Context,
    private val prefs: Prefs,
    /** Tier-2 pre-cache seam (SPEC_V3 workstream B). Defaults to reading/touching [MemeCache];
     *  overridable for tests without a real disk cache. */
    private val cacheProvider: ((Emotion) -> List<StickerItem>)? = null
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val library = StickerLibrary(context)
    private val recentlyShown = RecentlyShownStore(prefs)
    private val memeCache = MemeCache(context)

    // Single Random per repository instance, seeded from wall-clock nanotime per SPEC_V3
    // A.3 - never a fixed seed, so query/offset/shuffle vary scan to scan.
    private val random = Random(System.nanoTime())

    /** Provider actually used for the most recent [search] call's online tier, for A.7 attribution. */
    var lastFetchProvider: String = ""
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
            lastFetchProvider = ""

            // Tier 1 - user's own stickers, unchanged from SPEC_V2, never filtered.
            val own = library.list(emotion)
            val related = if (own.size < RELATED_THRESHOLD) {
                val runnerUp = result.distribution.getOrNull(1)?.first
                if (runnerUp != null && runnerUp != emotion) library.list(runnerUp) else emptyList()
            } else emptyList()
            val ownCombined = if (prefs.preferOwnStickers) own + related else related + own

            // Tiers 2+3 - pre-cache seam + online, filtered and anti-repeated together.
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

    private fun fetchTier2And3(emotion: Emotion, limit: Int): List<StickerItem> {
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
        return unseen
    }

    /** A.6.3 - South Indian pool first, GENERIC pool if that yields nothing usable. */
    private fun fetchOnline(emotion: Emotion, limit: Int): List<StickerItem> = try {
        val primaryCulture = prefs.memeCulture
        var raw = queryOnce(emotion, primaryCulture, limit)
        if (raw.isEmpty() && primaryCulture == MemeCulture.SOUTH_INDIAN) {
            raw = queryOnce(emotion, MemeCulture.GENERIC, limit)
        }
        raw
    } catch (t: Throwable) {
        // Network failed outright - tier 1 (and tier 2, if present) can still fill the grid.
        emptyList()
    }

    /** A.3.1/A.3.2 - random query from the pool, random API offset 0..40. */
    private fun queryOnce(emotion: Emotion, culture: MemeCulture, limit: Int): List<StickerItem> {
        val pool = MemeQueryBank.queries(emotion, culture)
        if (pool.isEmpty()) return emptyList()
        val query = pool[random.nextInt(pool.size)]
        val offset = random.nextInt(MAX_OFFSET + 1)
        val items = when (prefs.provider.lowercase()) {
            "tenor" -> tenor(query, limit, offset)
            else -> giphyMerged(query, limit, offset, culture)
        }
        if (items.isNotEmpty()) lastFetchProvider = prefs.provider.lowercase()
        return items
    }

    /**
     * GIPHY has two relevant search endpoints: `/v1/stickers/search` (transparent,
     * but almost no South Indian content) and `/v1/gifs/search` (opaque, 10-100x more
     * South Indian hits per live measurement). For [MemeCulture.SOUTH_INDIAN] the gifs
     * endpoint is primary; for [MemeCulture.GENERIC] stickers stays primary (unchanged
     * behaviour). If the primary yields fewer than [limit], the secondary endpoint is
     * queried with the same query/offset and the results are merged.
     */
    private fun giphyMerged(query: String, limit: Int, offset: Int, culture: MemeCulture): List<StickerItem> {
        val primaryUrl = if (culture == MemeCulture.SOUTH_INDIAN) GIPHY_GIFS_URL else GIPHY_STICKERS_URL
        val secondaryUrl = if (culture == MemeCulture.SOUTH_INDIAN) GIPHY_STICKERS_URL else GIPHY_GIFS_URL

        val primary = try {
            giphy(query, limit, offset, primaryUrl)
        } catch (t: Throwable) {
            emptyList()
        }
        if (primary.size >= limit) return primary

        val secondary = try {
            giphy(query, limit - primary.size, offset, secondaryUrl)
        } catch (t: Throwable) {
            emptyList()
        }
        return primary + secondary
    }

    private fun successOrFailure(local: List<StickerItem>, cause: Throwable? = null): Result<List<StickerItem>> =
        if (local.isNotEmpty()) Result.success(local) else Result.failure(cause ?: RuntimeException("No stickers"))

    private fun giphy(query: String, limit: Int, offset: Int, endpoint: String = GIPHY_STICKERS_URL): List<StickerItem> {
        val url = endpoint.toHttpUrl().newBuilder()
            .addQueryParameter("api_key", prefs.stickerKey)
            .addQueryParameter("q", query)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("rating", "pg-13")
            .build()
        val json = JSONObject(get(url.toString()))
        val data = json.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<StickerItem>()
        for (i in 0 until data.length()) {
            val entry = data.getJSONObject(i)
            val images = entry.optJSONObject("images") ?: continue
            val preview = images.optJSONObject("fixed_width")?.optString("url").orEmpty()
            val full = images.optJSONObject("original")?.optString("url").orEmpty()
            if (preview.isNotEmpty() && full.isNotEmpty()) {
                val id = entry.optString("id")
                val title = entry.optString("title")
                val slug = entry.optString("slug")
                out.add(
                    StickerItem(
                        previewUrl = preview,
                        sendUrl = full,
                        mime = "image/gif",
                        providerId = id,
                        rawText = "$title $slug"
                    )
                )
            }
        }
        return out
    }

    private fun tenor(query: String, limit: Int, offset: Int): List<StickerItem> {
        val url = "https://tenor.googleapis.com/v2/search".toHttpUrl().newBuilder()
            .addQueryParameter("key", prefs.stickerKey)
            .addQueryParameter("q", query)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("pos", offset.toString())
            .addQueryParameter("media_filter", "gif,tinygif")
            .addQueryParameter("contentfilter", "medium")
            .build()
        val json = JSONObject(get(url.toString()))
        val results = json.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<StickerItem>()
        for (i in 0 until results.length()) {
            val entry = results.getJSONObject(i)
            val mf = entry.optJSONObject("media_formats") ?: continue
            val preview = mf.optJSONObject("tinygif")?.optString("url").orEmpty()
            val full = mf.optJSONObject("gif")?.optString("url").orEmpty()
            if (preview.isNotEmpty() && full.isNotEmpty()) {
                val id = entry.optString("id")
                val desc = entry.optString("content_description")
                val tagsArr = entry.optJSONArray("tags")
                val tags = tagsToString(tagsArr)
                out.add(
                    StickerItem(
                        previewUrl = preview,
                        sendUrl = full,
                        mime = "image/gif",
                        providerId = id,
                        rawText = "$desc $tags"
                    )
                )
            }
        }
        return out
    }

    private fun tagsToString(arr: JSONArray?): String {
        if (arr == null) return ""
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(arr.optString(i))
        }
        return sb.toString()
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw RuntimeException("Sticker API ${resp.code}: ${body.take(160)}")
            }
            return body
        }
    }

    companion object {
        private const val RELATED_THRESHOLD = 12
        private const val MAX_OFFSET = 40
        private const val GIPHY_STICKERS_URL = "https://api.giphy.com/v1/stickers/search"
        private const val GIPHY_GIFS_URL = "https://api.giphy.com/v1/gifs/search"
    }
}
