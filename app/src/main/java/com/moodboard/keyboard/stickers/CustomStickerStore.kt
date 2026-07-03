package com.moodboard.keyboard.stickers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/** Stores user-imported stickers (from gallery / downloaded packs) as PNG files. */
class CustomStickerStore(private val context: Context) {

    private fun dir(): File =
        File(context.filesDir, "custom_stickers").apply { if (!exists()) mkdirs() }

    fun list(): List<StickerItem> =
        dir().listFiles { f -> f.isFile }?.sortedByDescending { it.lastModified() }
            ?.map {
                StickerItem(
                    previewUrl = it.absolutePath,
                    sendUrl = it.absolutePath,
                    mime = "image/png",
                    isLocal = true
                )
            } ?: emptyList()

    /** Import an image picked from the gallery. Returns the saved file or null on failure. */
    fun importFrom(uri: Uri): File? {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            val bmp: Bitmap = input.use { BitmapFactory.decodeStream(it) } ?: return null
            val resized = clampSize(bmp, 512)
            val out = File(dir(), "sticker_${System.currentTimeMillis()}.png")
            FileOutputStream(out).use { resized.compress(Bitmap.CompressFormat.PNG, 100, it) }
            out
        } catch (t: Throwable) {
            null
        }
    }

    fun delete(item: StickerItem): Boolean =
        item.isLocal && File(item.sendUrl).let { it.exists() && it.delete() }

    private fun clampSize(src: Bitmap, max: Int): Bitmap {
        val w = src.width; val h = src.height
        if (w <= max && h <= max) return src
        val scale = max.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(src, (w * scale).toInt(), (h * scale).toInt(), true)
    }
}
