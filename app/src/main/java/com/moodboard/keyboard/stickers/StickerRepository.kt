package com.moodboard.keyboard.stickers

import android.content.Context
import com.moodboard.keyboard.emotion.Emotion
import com.moodboard.keyboard.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches stickers/GIFs for a detected [Emotion] from GIPHY or Tenor, and always
 * prepends the user's own imported stickers so the grid is never empty offline.
 */
class StickerRepository(private val context: Context, private val prefs: Prefs) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val customStore = CustomStickerStore(context)

    suspend fun search(emotion: Emotion, limit: Int = 30): Result<List<StickerItem>> =
        withContext(Dispatchers.IO) {
            val local = customStore.list()
            try {
                val online = when (prefs.provider.lowercase()) {
                    "tenor" -> tenor(emotion.query, limit)
                    else -> giphy(emotion.query, limit)
                }
                Result.success(local + online)
            } catch (t: Throwable) {
                // Network failed but we can still show the user's own stickers.
                if (local.isNotEmpty()) Result.success(local) else Result.failure(t)
            }
        }

    private fun giphy(query: String, limit: Int): List<StickerItem> {
        val url = "https://api.giphy.com/v1/stickers/search".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", prefs.stickerKey)
            .addQueryParameter("q", query)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("rating", "pg-13")
            .build()
        val json = JSONObject(get(url.toString()))
        val data = json.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<StickerItem>()
        for (i in 0 until data.length()) {
            val images = data.getJSONObject(i).optJSONObject("images") ?: continue
            val preview = images.optJSONObject("fixed_width")?.optString("url").orEmpty()
            val full = images.optJSONObject("original")?.optString("url").orEmpty()
            if (preview.isNotEmpty() && full.isNotEmpty()) {
                out.add(StickerItem(preview, full, "image/gif"))
            }
        }
        return out
    }

    private fun tenor(query: String, limit: Int): List<StickerItem> {
        val url = "https://tenor.googleapis.com/v2/search".toHttpUrl().newBuilder()
            .addQueryParameter("key", prefs.stickerKey)
            .addQueryParameter("q", query)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("media_filter", "gif,tinygif")
            .addQueryParameter("contentfilter", "medium")
            .build()
        val json = JSONObject(get(url.toString()))
        val results = json.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<StickerItem>()
        for (i in 0 until results.length()) {
            val mf = results.getJSONObject(i).optJSONObject("media_formats") ?: continue
            val preview = mf.optJSONObject("tinygif")?.optString("url").orEmpty()
            val full = mf.optJSONObject("gif")?.optString("url").orEmpty()
            if (preview.isNotEmpty() && full.isNotEmpty()) {
                out.add(StickerItem(preview, full, "image/gif"))
            }
        }
        return out
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw RuntimeException("Sticker API ${resp.code}: ${body.take(160)}")
            }
            return body
        }
    }
}
