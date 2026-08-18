package com.moodboard.keyboard.stickers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.moodboard.keyboard.emotion.Emotion
import com.moodboard.keyboard.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * SPEC_V3 B.3 — periodic (every 12h) background pre-cache. Resolves the prefetch
 * target list from [Prefs.moodUsageCounts] (top 10 by usage, falling back to
 * [DEFAULT_TARGETS] until usage data exists), fetches ~10 South Indian-pack items per
 * emotion (A.2/A.4 relevance), downloads the bytes, and inserts them into [MemeCache].
 *
 * Resilience: zero results for an emotion (empty pool, nothing relevant after
 * filtering, all downloads skipped) is a normal outcome, not a failure — it is
 * reported as [Result.success]. Only a genuine transport error (network I/O failure
 * or a non-2xx HTTP response, see [TransportError]) counts toward a retry, and only
 * when *every* attempted emotion hit one.
 */
class MemePrefetchWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Thrown only for genuine transport failures - network I/O errors or non-2xx HTTP. */
    private class TransportError(message: String, cause: Throwable? = null) : Exception(message, cause)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = Prefs(applicationContext)
        if (!prefs.prefetchEnabled) return@withContext Result.success()

        val cache = MemeCache(applicationContext)
        val targets = resolveTargets(prefs)

        var attempted = 0
        var succeeded = 0
        var transportErrors = 0
        var totalInserted = 0
        val perEmotion = StringBuilder()

        for (emotion in targets) {
            attempted++
            try {
                val inserted = prefetchEmotion(emotion, prefs, cache)
                totalInserted += inserted
                if (inserted > 0) succeeded++
                perEmotion.append("${emotion.key}=$inserted ")
            } catch (t: TransportError) {
                transportErrors++
                perEmotion.append("${emotion.key}=ERR ")
                Log.w(TAG, "prefetch transport error for ${emotion.key}", t)
            } catch (t: Throwable) {
                // Non-transport failure (bad JSON, empty pool, etc.) - zero results
                // for this emotion, not a network failure. Skip and keep going.
                perEmotion.append("${emotion.key}=0 ")
                Log.w(TAG, "prefetch skipped for ${emotion.key}", t)
            }
        }

        Log.i(
            TAG,
            "Prefetch summary: attempted=$attempted succeeded=$succeeded " +
                "totalInserted=$totalInserted transportErrors=$transportErrors [${perEmotion.trim()}]"
        )

        // Only a wholesale network outage - every single emotion hit a transport
        // error - should be retried. Zero *results* with successful requests is a
        // legitimate empty outcome and must not be treated as failure.
        return@withContext if (attempted > 0 && transportErrors == attempted) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    /** SPEC_V3 B.1 - top 10 emotions by usage count, falling back to the default list. */
    private fun resolveTargets(prefs: Prefs): List<Emotion> {
        val counts = prefs.moodUsageCounts
        if (counts.isEmpty()) return DEFAULT_TARGETS

        val byKey = Emotion.values().associateBy { it.key }
        val ranked = counts.entries
            .sortedByDescending { it.value }
            .mapNotNull { byKey[it.key] }
            .take(10)
        return ranked.ifEmpty { DEFAULT_TARGETS }
    }

    /** Fetches, filters and caches up to [ITEMS_PER_EMOTION] items for [emotion]. Returns count inserted. */
    private fun prefetchEmotion(emotion: Emotion, prefs: Prefs, cache: MemeCache): Int {
        val pool = MemeQueryBank.queries(emotion, MemeCulture.SOUTH_INDIAN)
        if (pool.isEmpty()) return 0
        val query = pool.random()

        val candidates = when (prefs.provider.lowercase()) {
            "tenor" -> tenor(query, prefs)
            else -> giphyMerged(query, prefs)
        }
        if (candidates.isEmpty()) return 0

        val relevant = MemeRelevance.filterRelevant(
            candidates, { it.text }, emotion, MemeCulture.SOUTH_INDIAN
        )

        var inserted = 0
        for (candidate in relevant.take(ITEMS_PER_EMOTION)) {
            val bytes = try {
                downloadBytes(candidate.url)
            } catch (t: Throwable) {
                null
            } ?: continue
            if (cache.insert(emotion, query, "image/gif", bytes) != null) inserted++
        }
        return inserted
    }

    private data class Candidate(val url: String, val text: String)

    /**
     * The worker only ever prefetches [MemeCulture.SOUTH_INDIAN], so `/v1/gifs/search`
     * (10-100x more South Indian hits, per live measurement) is primary; the
     * transparent `/v1/stickers/search` endpoint tops up if the primary is thin.
     * A transport failure on the primary is retried against the secondary before
     * being surfaced - only if both endpoints fail does the [TransportError] propagate.
     */
    private fun giphyMerged(query: String, prefs: Prefs): List<Candidate> {
        val primary = try {
            giphy(query, prefs, GIPHY_GIFS_URL)
        } catch (t: TransportError) {
            return try {
                giphy(query, prefs, GIPHY_STICKERS_URL)
            } catch (t2: TransportError) {
                throw t
            }
        }
        if (primary.size >= QUERY_FETCH_LIMIT) return primary

        val secondary = try {
            giphy(query, prefs, GIPHY_STICKERS_URL)
        } catch (t: Throwable) {
            emptyList()
        }
        return primary + secondary
    }

    private fun giphy(query: String, prefs: Prefs, endpoint: String): List<Candidate> {
        val url = endpoint.toHttpUrl().newBuilder()
            .addQueryParameter("api_key", prefs.stickerKey)
            .addQueryParameter("q", query)
            .addQueryParameter("limit", QUERY_FETCH_LIMIT.toString())
            .addQueryParameter("offset", "0")
            .addQueryParameter("rating", "pg-13")
            .build()
        val json = JSONObject(get(url.toString()))
        val data = json.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<Candidate>()
        for (i in 0 until data.length()) {
            val entry = data.getJSONObject(i)
            val images = entry.optJSONObject("images") ?: continue
            val full = images.optJSONObject("original")?.optString("url").orEmpty()
            if (full.isEmpty()) continue
            val title = entry.optString("title")
            val slug = entry.optString("slug")
            out.add(Candidate(full, "$title $slug"))
        }
        return out
    }

    private fun tenor(query: String, prefs: Prefs): List<Candidate> {
        val url = "https://tenor.googleapis.com/v2/search".toHttpUrl().newBuilder()
            .addQueryParameter("key", prefs.stickerKey)
            .addQueryParameter("q", query)
            .addQueryParameter("limit", QUERY_FETCH_LIMIT.toString())
            .addQueryParameter("pos", "0")
            .addQueryParameter("media_filter", "gif,tinygif")
            .addQueryParameter("contentfilter", "medium")
            .build()
        val json = JSONObject(get(url.toString()))
        val results = json.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Candidate>()
        for (i in 0 until results.length()) {
            val entry = results.getJSONObject(i)
            val mf = entry.optJSONObject("media_formats") ?: continue
            val full = mf.optJSONObject("gif")?.optString("url").orEmpty()
            if (full.isEmpty()) continue
            val desc = entry.optString("content_description")
            val tags = tagsToString(entry.optJSONArray("tags"))
            out.add(Candidate(full, "$desc $tags"))
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
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw TransportError("Meme prefetch network error: ${e.message}", e)
        }
        response.use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw TransportError("Meme prefetch ${resp.code}: ${body.take(160)}")
            }
            return body
        }
    }

    private fun downloadBytes(url: String): ByteArray? {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body?.bytes()
        }
    }

    companion object {
        private const val TAG = "MemePrefetchWorker"
        private const val ITEMS_PER_EMOTION = 10
        private const val QUERY_FETCH_LIMIT = 20 // over-fetch so relevance filtering has something to pick from
        private const val GIPHY_GIFS_URL = "https://api.giphy.com/v1/gifs/search"
        private const val GIPHY_STICKERS_URL = "https://api.giphy.com/v1/stickers/search"

        /** SPEC_V3 B.1 fallback list, used until real usage data exists. */
        val DEFAULT_TARGETS: List<Emotion> = listOf(
            Emotion.HAPPY, Emotion.LAUGHING, Emotion.SAD, Emotion.ANGRY, Emotion.ANNOYED,
            Emotion.SURPRISED, Emotion.EXCITED, Emotion.SLEEPY, Emotion.KISS, Emotion.SKEPTICAL
        )
    }
}
