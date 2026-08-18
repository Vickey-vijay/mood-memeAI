package com.moodboard.keyboard.stickers

import com.moodboard.keyboard.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * `/get_memes` - ~100 popular static meme templates (Drake Hotline Bling, Distracted
 * Boyfriend, ...), no API key required. This genuinely broadens "all kinds of memes"
 * beyond animated GIFs (client requirement), so it is kept even though it is the only
 * source with no per-query search: the whole template list is fetched once and cached
 * in memory for the process (verified live: `curl -s https://api.imgflip.com/get_memes`
 * returns `{"success":true,"data":{"memes":[...]}}`), then filtered locally by matching
 * template names against emotion keyword stems.
 *
 * Because Imgflip's catalogue is generic-English meme culture, not South Indian film
 * comedy, [MemeAggregator] deliberately hands this source [MemeQueryBank.keywords] text
 * (e.g. "annoy irritat frustrat eye roll fed up cringe") rather than the culture-flavoured
 * actor-name query used for the search-based sources - actor names would never match a
 * template named "Distracted Boyfriend".
 */
object ImgflipSource : MemeSource {
    const val ID = "imgflip"

    override val id = ID

    override fun isAvailable(prefs: Prefs): Boolean = prefs.isMemeSourceEnabled(id)

    private data class Template(val id: String, val name: String, val url: String)

    @Volatile private var cache: List<Template>? = null
    private val lock = Any()

    override suspend fun fetch(query: String, limit: Int, offset: Int, prefs: Prefs): List<StickerItem> =
        withContext(Dispatchers.IO) {
            val templates = loadTemplates()
            if (templates.isEmpty()) return@withContext emptyList()

            val terms = query.lowercase().split(Regex("\\s+")).filter { it.length >= 3 }
            val matched = if (terms.isEmpty()) {
                templates
            } else {
                templates.filter { t -> terms.any { term -> t.name.lowercase().contains(term) } }
            }
            if (matched.isEmpty()) return@withContext emptyList()

            // No API-side offset for a static local list - rotate instead, so repeated
            // scans for the same mood don't always surface the same handful of templates
            // (SPEC_V3 A.3.2's intent, applied locally).
            val start = offset % matched.size
            val rotated = matched.subList(start, matched.size) + matched.subList(0, start)

            rotated.take(limit).map { t ->
                StickerItem(
                    previewUrl = t.url,
                    sendUrl = t.url,
                    mime = "image/jpeg",
                    providerId = t.id,
                    rawText = t.name,
                    source = ID
                )
            }
        }

    private fun loadTemplates(): List<Template> {
        cache?.let { return it }
        synchronized(lock) {
            cache?.let { return it }
            val loaded = try {
                fetchTemplates()
            } catch (t: Throwable) {
                emptyList()
            }
            cache = loaded
            return loaded
        }
    }

    private fun fetchTemplates(): List<Template> {
        val json = JSONObject(MemeHttpClient.get("https://api.imgflip.com/get_memes"))
        if (!json.optBoolean("success", false)) return emptyList()
        val memes = json.optJSONObject("data")?.optJSONArray("memes") ?: return emptyList()
        val out = ArrayList<Template>(memes.length())
        for (i in 0 until memes.length()) {
            val m = memes.getJSONObject(i)
            val url = m.optString("url")
            val name = m.optString("name")
            val templateId = m.optString("id")
            if (url.isNotEmpty() && name.isNotEmpty()) out.add(Template(templateId, name, url))
        }
        return out
    }
}
