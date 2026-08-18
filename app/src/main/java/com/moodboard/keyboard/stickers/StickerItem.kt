package com.moodboard.keyboard.stickers

/**
 * One sticker/GIF result.
 * @param previewUrl small URL (or local file path) used in the grid
 * @param sendUrl    full URL (or local file path) downloaded & sent to the chat
 * @param mime       MIME type committed to the target app (image/gif, image/webp, image/png)
 * @param isLocal    true when [previewUrl]/[sendUrl] are local file paths
 * @param id         stable id for user-imported stickers (empty for online results)
 * @param mood       owning [com.moodboard.keyboard.emotion.Emotion.key] for user-imported
 *                   stickers (empty for online results)
 * @param favorite   true if the user has marked this (local) sticker as a favourite
 * @param providerId GIPHY/Tenor/Imgflip item id for online results (SPEC_V3 A.5/A.6
 *                   identity), empty for local stickers
 * @param rawText    provider title/tags/description text for online results, used by
 *                   [MemeRelevance] (SPEC_V3 A.4); empty for local stickers
 * @param source     [MemeSource.id] that produced this item ("giphy_gifs", "tenor",
 *                   "imgflip", ...) for online results, or the same tag carried through
 *                   from [MemeCache] for pre-cached results. Empty for local/user
 *                   stickers. Drives the multi-source "Powered by …" attribution label
 *                   (see [MemeAttribution]) - never used for relevance/dedupe.
 */
data class StickerItem(
    val previewUrl: String,
    val sendUrl: String,
    val mime: String,
    val isLocal: Boolean = false,
    val id: String = "",
    val mood: String = "",
    val favorite: Boolean = false,
    val providerId: String = "",
    val rawText: String = "",
    val source: String = ""
)
