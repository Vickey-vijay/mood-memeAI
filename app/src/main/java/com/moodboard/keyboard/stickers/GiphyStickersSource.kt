package com.moodboard.keyboard.stickers

import com.moodboard.keyboard.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `/v1/stickers/search` - transparent GIPHY stickers. Thinner catalogue than
 * `/v1/gifs/search` (SPEC_V3 A.6.3), especially for South Indian queries, but a
 * meaningfully different visual style (transparent PNG/GIF vs opaque video-style GIFs),
 * so it stays as a secondary contributor rather than being dropped.
 */
object GiphyStickersSource : MemeSource {
    const val ID = "giphy_stickers"
    private const val URL = "https://api.giphy.com/v1/stickers/search"

    override val id = ID

    override fun isAvailable(prefs: Prefs): Boolean = prefs.isMemeSourceEnabled(id)

    override suspend fun fetch(query: String, limit: Int, offset: Int, prefs: Prefs): List<StickerItem> =
        withContext(Dispatchers.IO) {
            GiphyApi.search(URL, query, limit, offset, prefs.stickerKey, id)
        }
}
