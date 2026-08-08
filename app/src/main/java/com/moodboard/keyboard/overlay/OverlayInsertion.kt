package com.moodboard.keyboard.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.moodboard.keyboard.stickers.StickerItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Insertion for the floating overlay (SPEC_V3 C.5).
 *
 * ## The honest limit
 * The overlay is **not** an IME. It has no [android.view.inputmethod.InputConnection] and
 * no [android.view.inputmethod.EditorInfo] for the focused field of whatever app is in
 * front, so it *cannot* commit rich content into that field the way
 * [com.moodboard.keyboard.util.RichContentSender] does from the keyboard. There is no
 * Android API that lets a `TYPE_APPLICATION_OVERLAY` window type into, or paste into,
 * another app's editor — by design, since that would be a keylogger/injection primitive.
 *
 * So there are exactly two real routes, and both are implemented here:
 *  1. [copyToClipboard] — primary. The image goes on the clipboard as a content URI; the
 *     user long-presses the chat box and taps Paste. Most modern chat apps (WhatsApp,
 *     Telegram, Signal, Gmail, Messages) accept an image paste.
 *  2. [shareTo] — secondary. `ACTION_SEND` chooser, the user picks the target chat.
 *
 * Both build the URI through the same `${packageName}.fileprovider` authority that
 * `RichContentSender` uses (see `res/xml/file_paths.xml`) and both carry
 * [Intent.FLAG_GRANT_READ_URI_PERMISSION] semantics — for the clipboard route the grant is
 * issued by the system clipboard service to whichever app reads the primary clip, which is
 * only possible because the provider declares `android:grantUriPermissions="true"`.
 */
class OverlayInsertion(private val context: Context) {

    /** Outcome of an insertion attempt. */
    sealed class Result {
        /** On the clipboard; the user must paste. */
        object Copied : Result()
        /** Share chooser launched. */
        object Shared : Result()
        data class Error(val message: String) : Result()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * SPEC_V3 C.5 route 1 (primary). Materialises [item] to a file off the main thread,
     * then puts a `content://` URI on the clipboard with the correct MIME type.
     */
    suspend fun copyToClipboard(item: StickerItem): Result {
        val uri = materialiseUri(item) ?: return Result.Error("Couldn't prepare that meme")
        return try {
            // ClipData.newUri() reads the MIME type back off the ContentResolver, so the
            // clip description ends up as image/gif | image/webp | image/png automatically
            // (FileProvider derives it from the file extension). Doing it this way rather
            // than newPlainText/newIntent is what makes chat apps offer "Paste" as an image.
            val clip = ClipData.newUri(context.contentResolver, CLIP_LABEL, uri)
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(clip)
            Result.Copied
        } catch (t: Throwable) {
            Result.Error(t.message ?: "Copy failed")
        }
    }

    /**
     * SPEC_V3 C.5 route 2 (secondary). `ACTION_SEND` chooser with an explicit read grant.
     * Launched from a service context, so both the chooser and the payload intent need
     * [Intent.FLAG_ACTIVITY_NEW_TASK].
     */
    suspend fun shareTo(item: StickerItem): Result {
        val uri = materialiseUri(item) ?: return Result.Error("Couldn't prepare that meme")
        return try {
            val mime = mimeOf(item)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                // setClipData + FLAG_GRANT_READ_URI_PERMISSION is what actually grants the
                // receiving app read access to our FileProvider URI. EXTRA_STREAM alone is
                // not covered by the flag on all OEM share sheets.
                clipData = ClipData.newUri(context.contentResolver, CLIP_LABEL, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, null).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(chooser)
            Result.Shared
        } catch (t: Throwable) {
            Result.Error(t.message ?: "Share failed")
        }
    }

    /** Downloads (or resolves, for local/cached items) and wraps in a FileProvider URI. */
    private suspend fun materialiseUri(item: StickerItem): Uri? = withContext(Dispatchers.IO) {
        try {
            val file = if (item.isLocal) File(item.sendUrl) else download(item)
            if (file == null || !file.exists()) return@withContext null
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (_: Throwable) {
            null
        }
    }

    /** Mirrors [com.moodboard.keyboard.util.RichContentSender.download]'s cache location so
     *  `<cache-path name="shared_stickers" path="stickers/"/>` already covers it. */
    private fun download(item: StickerItem): File? {
        val dir = File(context.cacheDir, "stickers").apply { if (!exists()) mkdirs() }
        val out = File(dir, "overlay_${System.currentTimeMillis()}.${extOf(item)}")
        val request = Request.Builder().url(item.sendUrl).get().build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val bytes = resp.body?.bytes() ?: return null
            out.writeBytes(bytes)
        }
        return out
    }

    private fun mimeOf(item: StickerItem): String = when {
        item.mime.isNotBlank() -> item.mime
        item.sendUrl.endsWith(".webp", true) -> "image/webp"
        item.sendUrl.endsWith(".png", true) -> "image/png"
        else -> "image/gif"
    }

    private fun extOf(item: StickerItem): String = when (mimeOf(item)) {
        "image/webp" -> "webp"
        "image/png" -> "png"
        else -> "gif"
    }

    private companion object {
        const val CLIP_LABEL = "meme"
    }
}
