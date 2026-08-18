package com.moodboard.keyboard.stickers

/**
 * MAJOR SIMPLIFICATION (client bug report, 2026-08-19). Pre-simplification, this scored
 * every online candidate against emotion-keyword and culture-marker word lists and
 * hard-gated on both being present. The dynamic query built by [MemeQueryBank] now carries
 * the user's actual intent (mood word + their chosen category/free text), so that heavy
 * relevance scoring is unnecessary — and was actively harmful, since it discarded results
 * the API itself judged relevant to the exact query the user asked for.
 *
 * All that is left is a light screen for obvious brand/corporate/advertisement noise.
 * Dedupe ([MemeAggregator]), shuffle and [RecentlyShownStore] anti-repeat are unchanged,
 * applied by the caller ([StickerRepository]) on top of what this returns.
 */
object MemeRelevance {

    private val blocklist = listOf(
        "advertisement", "advert", "promo", "promotional", "sponsored", "sponsor", "logo",
        "brand", "wendy", "bundesliga", "cbs", "nbc", "hulu", "nfl", "nba", "nike",
        "netflix", "disney", "google", "amazon", "coca", "pepsi", "mcdonald"
    )

    /** Drops candidates whose [textOf] text looks like brand/corporate/ad junk. */
    fun <T> filterJunk(candidates: List<T>, textOf: (T) -> String): List<T> {
        if (candidates.isEmpty()) return emptyList()
        return candidates.filter { item ->
            val lower = textOf(item).lowercase()
            blocklist.none { lower.contains(it) }
        }
    }
}
