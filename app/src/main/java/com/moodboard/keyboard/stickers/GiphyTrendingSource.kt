package com.moodboard.keyboard.stickers

import com.moodboard.keyboard.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `/v1/gifs/trending` - ignores [fetch]'s `query` entirely (the endpoint takes none) and
 * just returns whatever is currently trending on GIPHY. Exists to "top up when a search
 * is thin": it contributes candidates unconditionally on every scan, interleaved with the
 * query-based sources by [MemeAggregator] and screened for junk by [MemeRelevance] like
 * everything else.
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
