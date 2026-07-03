package com.moodboard.keyboard.util

import android.content.ClipDescription
import android.content.Context
import android.net.Uri
import android.os.Build
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import com.moodboard.keyboard.stickers.StickerItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Sends a sticker/GIF into the focused chat app via the Commit Content API
 * (see docs/05_Rich_Content_Insertion.md).
 */
class RichContentSender(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Result of an attempt to send a sticker. */
    sealed class SendResult {
        object Success : SendResult()
        object Unsupported : SendResult()      // target editor can't accept this MIME
        data class Error(val message: String) : SendResult()
    }

    suspend fun send(
        item: StickerItem,
        ic: InputConnection?,
        editorInfo: EditorInfo?
    ): SendResult = withContext(Dispatchers.IO) {
        if (ic == null || editorInfo == null) return@withContext SendResult.Error("No input field")
        try {
            val file = if (item.isLocal) File(item.sendUrl) else download(item)
                ?: return@withContext SendResult.Error("Download failed")

            val uri: Uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )

            val supported = EditorInfoCompat.getContentMimeTypes(editorInfo)
            val accepts = supported.any { ClipDescription.compareMimeTypes(item.mime, it) } ||
                supported.any { ClipDescription.compareMimeTypes("image/*", it) }
            if (!accepts) return@withContext SendResult.Unsupported

            val info = InputContentInfoCompat(
                uri,
                ClipDescription("Sticker", arrayOf(item.mime)),
                null
            )
            var flags = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                flags = InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION
            }
            val ok = InputConnectionCompat.commitContent(ic, editorInfo, info, flags, null)
            if (ok) SendResult.Success else SendResult.Error("Editor rejected the sticker")
        } catch (t: Throwable) {
            SendResult.Error(t.message ?: "Send failed")
        }
    }

    private fun download(item: StickerItem): File? {
        val dir = File(context.cacheDir, "stickers").apply { if (!exists()) mkdirs() }
        val ext = when (item.mime) {
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            else -> "png"
        }
        val out = File(dir, "send_${System.currentTimeMillis()}.$ext")
        val request = Request.Builder().url(item.sendUrl).get().build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val bytes = resp.body?.bytes() ?: return null
            out.writeBytes(bytes)
        }
        return out
    }
}
