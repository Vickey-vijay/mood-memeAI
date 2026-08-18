package com.moodboard.keyboard.stickers

import com.moodboard.keyboard.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `/v1/gifs/search` - the best-populated GIPHY endpoint for South Indian content (10-100x
 * more hits than `/v1/stickers/search` per live measurement, SPEC_V3 A.6.3), kept
 * primary. Always available: GIPHY ships a working public default key
 * ([com.moodboard.keyboard.util.BuildDefaults.DEFAULT_GIPHY_KEY]), so this source only
 * needs the Setup toggle to be on.
 */
object GiphyGifsSource : MemeSource {
    const val ID = "giphy_gifs"
    private const val URL = "https://api.giphy.com/v1/gifs/search"

    override val id = ID

    override fun isAvailable(prefs: Prefs): Boolean = prefs.isMemeSourceEnabled(id)

    override suspend fun fetch(query: String, limit: Int, offset: Int, prefs: Prefs): List<StickerItem> =
        withContext(Dispatchers.IO) {
            GiphyApi.search(URL, query, limit, offset, prefs.stickerKey, id)
        }
}
