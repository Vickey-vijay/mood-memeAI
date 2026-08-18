package com.moodboard.keyboard.stickers

import com.moodboard.keyboard.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `/v1/gifs/trending` - ignores [fetch]'s `query` entirely (the endpoint takes none) and
 * just returns whatever is currently trending on GIPHY. Exists to "top up when a search
 * is thin" (brief item 2): it contributes candidates unconditionally on every scan, but
 * downstream [MemeRelevance] scoring means trending items with no emotion/culture keyword
 * hits only survive the cut when the other sources didn't produce enough on-topic results
 * to fill [MemeRelevance.filterRelevant]'s `minKeep` floor - i.e. exactly when a search
 * was thin.
 */
object GiphyTrendingSource : MemeSource {
    const val ID = "giphy_trending"
    private const val URL = "https://api.giphy.com/v1/gifs/trending"

    override val id = ID

    override fun isAvailable(prefs: Prefs): Boolean = prefs.isMemeSourceEnabled(id)

    override suspend fun fetch(query: String, limit: Int, offset: Int, prefs: Prefs): List<StickerItem> =
        withContext(Dispatchers.IO) {
            GiphyApi.trending(URL, limit, offset, prefs.stickerKey, id)
        }
}
