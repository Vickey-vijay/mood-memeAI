package com.moodboard.keyboard.stickers

/**
 * One sticker/GIF result.
 * @param previewUrl small URL (or local file path) used in the grid
 * @param sendUrl    full URL (or local file path) downloaded & sent to the chat
 * @param mime       MIME type committed to the target app (image/gif, image/webp, image/png)
 * @param isLocal    true when [previewUrl]/[sendUrl] are local file paths
 */
data class StickerItem(
    val previewUrl: String,
    val sendUrl: String,
    val mime: String,
    val isLocal: Boolean = false
)
