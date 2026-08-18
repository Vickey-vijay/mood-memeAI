package com.moodboard.keyboard.stickers

import com.moodboard.keyboard.emotion.Emotion

/**
 * SPEC_V4 (client bug report, 2026-08-18) — scores an online candidate's title/tags text
 * for relevance to the detected emotion and selected [MemeCulture].
 *
 * Client complaint: "the memes it has been showing is not actually appropriate ... some
 * comedy actor reaction, I don't even know him ... the whole meme world is around
 * Telugu ... I'm in TAMIL NADU, not Telugu states." Empirically, GIPHY's `total_count` is
 * useless as a relevance signal (it returns near-500 for almost any query by relaxing
 * terms) - actual titles are what matter, and generic/Western/brand junk ("Wendy's",
 * "Bundesliga", "CBS") is interleaved with genuinely Tamil results even for
 * Tamil-flavoured queries. See [MemeQueryBank]'s kdoc for the live sweep that measured
 * this.
 *
 * The pre-SPEC_V4 version scored emotion above culture (2x vs 1x) and then re-admitted
 * rejects to pad the grid up to a minimum count - which is exactly why generic "sleepy"
 * junk could outrank genuine Tamil content and why brand junk reached the grid at all.
 * SPEC_V4 inverts that: culture is a **hard gate**, not just a scoring bonus. For any
 * culture other than [MemeCulture.GLOBAL], a candidate with zero culture-marker hits is
 * discarded outright and never re-admitted. It is correct for the grid to end up with
 * fewer online results than before - the user's own library (tier 1, unfiltered) fills
 * the rest; quality beats quantity.
 */
object MemeRelevance {

    /**
     * Brand/corporate sticker-pack noise, regardless of emotion or culture. Union of the
     * original generic blocklist and the brands actually observed leaking into
     * Tamil-flavoured queries during the SPEC_V4 sweep (Wendy's, Bundesliga, CBS, NBC,
     * HULU, NETFLIX, ZEE5/ZEE TV all showed up for supposedly-Tamil queries).
     */
    private val blocklist = listOf(
        "birthday", "logo", "brand", "advertisement", "promo", "advert", "sponsored",
        "sponsor", "wendy", "bundesliga", "cbs", "nfl", "nba", "nike", "netflix",
        "disney", "google", "amazon", "coca", "pepsi", "mcdonald"
    )

    /**
     * score = 3 * (culture keyword hits) + 2 * (emotion keyword hits) - 5 * (blocklist hits)
     *
     * Culture now outweighs emotion (was the reverse pre-SPEC_V4): a genuinely Tamil
     * result that only loosely matches the mood should still beat a perfectly
     * mood-matched but culturally generic/Western result, because tier 1 (the user's own
     * stickers) already covers "perfectly matches the mood" - what's missing online is
     * "actually Tamil".
     */
    fun score(text: String, emotion: Emotion, culture: MemeCulture): Int {
        val lower = text.lowercase()
        val cultureHits = MemeQueryBank.cultureKeywords(culture).count { lower.contains(it) }
        val emotionHits = MemeQueryBank.keywords(emotion).count { lower.contains(it) }
        val blockHits = blocklist.count { lower.contains(it) }
        return 3 * cultureHits + 2 * emotionHits - 5 * blockHits
    }

    /**
     * Filters candidates for [emotion]/[culture] relevance.
     *
     * Hard culture gate: for any [culture] other than [MemeCulture.GLOBAL], a candidate
     * whose text contains **no** culture marker is discarded immediately - it is never
     * re-admitted, even if that leaves the result list smaller than the caller would
     * like. [MemeCulture.GLOBAL] has no markers ([MemeQueryBank.cultureKeywords] returns
     * an empty list for it) so nothing is gated for that culture.
     *
     * Survivors are then required to have a strictly positive [score] (so a culturally-OK
     * result that's still dominated by blocklist hits doesn't make it through either),
     * and are returned ordered by descending score. Callers apply their own shuffle (A.3)
     * on top of this if randomised display order is wanted.
     */
    fun <T> filterRelevant(
        candidates: List<T>,
        textOf: (T) -> String,
        emotion: Emotion,
        culture: MemeCulture
    ): List<T> {
        if (candidates.isEmpty()) return emptyList()

        val cultureMarkers = MemeQueryBank.cultureKeywords(culture)
        val emotionMarkers = MemeQueryBank.keywords(emotion)
        val requiresCultureMatch = culture != MemeCulture.GLOBAL

        val scored = candidates.mapNotNull { item ->
            val lower = textOf(item).lowercase()
            val cultureHits = cultureMarkers.count { lower.contains(it) }
            // Hard gate - discard, do not re-admit (SPEC_V4).
            if (requiresCultureMatch && cultureHits == 0) return@mapNotNull null

            val emotionHits = emotionMarkers.count { lower.contains(it) }
            val blockHits = blocklist.count { lower.contains(it) }
            val itemScore = 3 * cultureHits + 2 * emotionHits - 5 * blockHits
            if (itemScore <= 0) return@mapNotNull null

            item to itemScore
        }

        return scored.sortedByDescending { it.second }.map { it.first }
    }
}
