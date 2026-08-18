package com.moodboard.keyboard.stickers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.moodboard.keyboard.emotion.Emotion
import com.moodboard.keyboard.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Random

/**
 * SPEC_V3 B.3 — periodic (every 12h) background pre-cache. Resolves the prefetch
 * target list from [Prefs.moodUsageCounts] (top 10 by usage, falling back to
 * [DEFAULT_TARGETS] until usage data exists), fetches ~10 South Indian-pack items per
 * emotion through [MemeAggregator] - the same multi-source fan-out the live search uses,
 * so the cache holds a mix of sources rather than just whichever single provider used to
 * be configured (A.2/A.4 relevance) - downloads the bytes, and inserts them into
 * [MemeCache] tagged with their originating [MemeSource.id] so pre-cached items get
 * correct "Powered by …" attribution too (see [MemeAttribution]).
 *
 * Resilience: zero results for an emotion (empty pool, nothing relevant after
 * filtering, all downloads skipped) is a normal outcome, not a failure. [MemeAggregator]
 * already swallows every individual source's transport failures silently - skip that
 * source, keep going - so this worker can no longer distinguish "this emotion's
 * provider had a network error" from "this emotion legitimately had nothing new" at
 * the fine grain the single-provider version used to (that per-request TransportError
 * bookkeeping is gone, superseded by the aggregator's own per-source resilience).
 * What's left is a coarser but still meaningful signal: if literally nothing was
 * inserted across every attempted emotion, that smells like a wholesale connectivity
 * problem and is worth a [Result.retry]; any nonzero insert count means at least some
 * of the pipeline is working, so a handful of thin/empty emotions among it is treated
 * as a legitimate quiet period, not a failure.
 */
class MemePrefetchWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val aggregator = MemeAggregator()
    private val random = Random(System.nanoTime())

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = Prefs(applicationContext)
        if (!prefs.prefetchEnabled) return@withContext Result.success()

        val cache = MemeCache(applicationContext)
        val targets = resolveTargets(prefs)

        var attempted = 0
        var totalInserted = 0
        val perEmotion = StringBuilder()

        for (emotion in targets) {
            attempted++
            val inserted = try {
                prefetchEmotion(emotion, prefs, cache)
            } catch (t: Throwable) {
                Log.w(TAG, "prefetch skipped for ${emotion.key}", t)
                0
            }
            totalInserted += inserted
            perEmotion.append("${emotion.key}=$inserted ")
        }

        Log.i(TAG, "Prefetch summary: attempted=$attempted totalInserted=$totalInserted [${perEmotion.trim()}]")

        return@withContext if (attempted > 0 && totalInserted == 0) {
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

    /** Fetches (via the aggregator), filters and caches up to [ITEMS_PER_EMOTION] items for [emotion]. Returns count inserted. */
    private suspend fun prefetchEmotion(emotion: Emotion, prefs: Prefs, cache: MemeCache): Int {
        val candidates = aggregator.fetch(emotion, MemeCulture.SOUTH_INDIAN, prefs, QUERY_FETCH_LIMIT, random)
        if (candidates.isEmpty()) return 0

        val relevant = MemeRelevance.filterRelevant(
            candidates, { it.rawText }, emotion, MemeCulture.SOUTH_INDIAN
        )

        var inserted = 0
        for (candidate in relevant.take(ITEMS_PER_EMOTION)) {
            val bytes = MemeHttpClient.getBytes(candidate.sendUrl) ?: continue
            val mime = candidate.mime.ifBlank { "image/gif" }
            if (cache.insert(emotion, candidate.rawText, mime, bytes, candidate.source) != null) inserted++
        }
        return inserted
    }

    companion object {
        private const val TAG = "MemePrefetchWorker"
        private const val ITEMS_PER_EMOTION = 10
        private const val QUERY_FETCH_LIMIT = 20 // over-fetch so relevance filtering has something to pick from

        /** SPEC_V3 B.1 fallback list, used until real usage data exists. */
        val DEFAULT_TARGETS: List<Emotion> = listOf(
            Emotion.HAPPY, Emotion.LAUGHING, Emotion.SAD, Emotion.ANGRY, Emotion.ANNOYED,
            Emotion.SURPRISED, Emotion.EXCITED, Emotion.SLEEPY, Emotion.KISS, Emotion.SKEPTICAL
        )
    }
}
