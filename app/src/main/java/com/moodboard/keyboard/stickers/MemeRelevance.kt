package com.moodboard.keyboard.stickers

import com.moodboard.keyboard.emotion.Emotion

/**
 * SPEC_V3 A.4 — scores an online candidate's title/tags text for relevance to the
 * detected emotion and selected [MemeCulture], then filters/re-admits so the caller
 * never ends up with an empty grid because the filter was too strict.
 */
object MemeRelevance {

    /** Obvious off-topic noise that should be penalised regardless of emotion. */
    private val blocklist = listOf(
        "birthday", "logo", "brand", "advertisement", "promo", "advert", "sponsored"
    )

    /**
     * score = 2 * (emotion keyword hits) + 1 * (culture keyword hits) - 3 * (blocklist hits)
     */
    fun score(text: String, emotion: Emotion, culture: MemeCulture): Int {
        val lower = text.lowercase()
        val emotionHits = MemeQueryBank.keywords(emotion).count { lower.contains(it) }
        val cultureHits = MemeQueryBank.cultureKeywords(culture).count { lower.contains(it) }
        val blockHits = blocklist.count { lower.contains(it) }
        return 2 * emotionHits + 1 * cultureHits - 3 * blockHits
    }

    /**
     * Keeps items with score > 0. If fewer than [minKeep] survive, re-admits the
     * highest-scoring rejects until [minKeep] survive (or candidates run out) —
     * never returns an empty grid because the filter was too strict.
     *
     * Result is ordered by descending score; callers apply their own shuffle (A.3)
     * on top of this if randomised display order is wanted.
     */
    fun <T> filterRelevant(
        candidates: List<T>,
        textOf: (T) -> String,
        emotion: Emotion,
        culture: MemeCulture,
        minKeep: Int = 8
    ): List<T> {
        if (candidates.isEmpty()) return emptyList()
        val scored = candidates.map { it to score(textOf(it), emotion, culture) }
        val kept = scored.filter { it.second > 0 }.sortedByDescending { it.second }
        if (kept.size >= minKeep) return kept.map { it.first }

        val rejects = scored.filter { it.second <= 0 }.sortedByDescending { it.second }
        val reAdmitted = rejects.take(minKeep - kept.size)
        return (kept + reAdmitted).map { it.first }
    }
}
