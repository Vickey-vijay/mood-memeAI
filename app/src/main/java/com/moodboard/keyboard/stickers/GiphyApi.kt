package com.moodboard.keyboard.stickers

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/** Shared GIPHY response parsing for [GiphyGifsSource], [GiphyStickersSource] and [GiphyTrendingSource]. */
internal object GiphyApi {

    /** `/v1/gifs/search` and `/v1/stickers/search` - both take `q`/`offset`. */
    fun search(endpoint: String, query: String, limit: Int, offset: Int, apiKey: String, sourceId: String): List<StickerItem> {
        val url = endpoint.toHttpUrl().newBuilder()
            .addQueryParameter("api_key", apiKey)
            .addQueryParameter("q", query)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("rating", "pg-13")
            .build()
        return parse(MemeHttpClient.get(url.toString()), sourceId)
    }

    /** `/v1/gifs/trending` - no `q`, but still paginates via `offset`. */
    fun trending(endpoint: String, limit: Int, offset: Int, apiKey: String, sourceId: String): List<StickerItem> {
        val url = endpoint.toHttpUrl().newBuilder()
            .addQueryParameter("api_key", apiKey)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("rating", "pg-13")
            .build()
        return parse(MemeHttpClient.get(url.toString()), sourceId)
    }

    private fun parse(body: String, sourceId: String): List<StickerItem> {
        val json = JSONObject(body)
        val data = json.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<StickerItem>(data.length())
        for (i in 0 until data.length()) {
            val entry = data.getJSONObject(i)
            val images = entry.optJSONObject("images") ?: continue
            val preview = images.optJSONObject("fixed_width")?.optString("url").orEmpty()
            val full = images.optJSONObject("original")?.optString("url").orEmpty()
            if (preview.isEmpty() || full.isEmpty()) continue
            val id = entry.optString("id")
            val title = entry.optString("title")
            val slug = entry.optString("slug")
            out.add(
                StickerItem(
                    previewUrl = preview,
                    sendUrl = full,
                    mime = "image/gif",
                    providerId = id,
                    rawText = "$title $slug",
                    source = sourceId
                )
            )
        }
        return out
    }
}
