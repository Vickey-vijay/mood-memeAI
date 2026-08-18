package com.moodboard.keyboard.stickers

import com.moodboard.keyboard.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tenor v2 search. Unlike GIPHY, Tenor has no usable public default key, so this source
 * is only available once the user pastes their own key into the Setup "Meme sources"
 * card ([Prefs.tenorApiKey]) - [isAvailable] is what makes it degrade silently otherwise.
 */
object TenorSource : MemeSource {
    const val ID = "tenor"

    override val id = ID

    override fun isAvailable(prefs: Prefs): Boolean =
        prefs.isMemeSourceEnabled(id) && prefs.tenorApiKey.isNotBlank()

    override suspend fun fetch(query: String, limit: Int, offset: Int, prefs: Prefs): List<StickerItem> =
        withContext(Dispatchers.IO) {
            val key = prefs.tenorApiKey
            if (key.isBlank()) return@withContext emptyList()
            val url = "https://tenor.googleapis.com/v2/search".toHttpUrl().newBuilder()
                .addQueryParameter("key", key)
                .addQueryParameter("q", query)
                .addQueryParameter("limit", limit.toString())
                .addQueryParameter("pos", offset.toString())
                .addQueryParameter("media_filter", "gif,tinygif")
                .addQueryParameter("contentfilter", "medium")
                .build()
            parse(MemeHttpClient.get(url.toString()))
        }

    private fun parse(body: String): List<StickerItem> {
        val json = JSONObject(body)
        val results = json.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<StickerItem>(results.length())
        for (i in 0 until results.length()) {
            val entry = results.getJSONObject(i)
            val mf = entry.optJSONObject("media_formats") ?: continue
            val preview = mf.optJSONObject("tinygif")?.optString("url").orEmpty()
            val full = mf.optJSONObject("gif")?.optString("url").orEmpty()
            if (preview.isEmpty() || full.isEmpty()) continue
            val id = entry.optString("id")
            val desc = entry.optString("content_description")
            val tags = tagsToString(entry.optJSONArray("tags"))
            out.add(
                StickerItem(
                    previewUrl = preview,
                    sendUrl = full,
                    mime = "image/gif",
                    providerId = id,
                    rawText = "$desc $tags",
                    source = ID
                )
            )
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
}
