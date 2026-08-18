package com.moodboard.keyboard.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.moodboard.keyboard.R
import com.moodboard.keyboard.emotion.Emotion
import com.moodboard.keyboard.stickers.StickerLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Required outcome 4 (client bug fix - "whenever I find some good sticker I wanted to
 * add that to my app but still not working"). The keyboard is an [android.inputmethodservice.InputMethodService]
 * and cannot show a dialog itself, so a long press on any sticker in the keyboard's (or
 * the floating overlay's) result grid launches this small transparent activity - the same
 * pattern [com.moodboard.keyboard.camera.PermissionActivity] and
 * [ReceiveStickerActivity] already use - to ask which mood to file it under (defaulting
 * to the mood that was actually detected for this scan) and save it into the user's own
 * [StickerLibrary]. Handles both a local file (already in the sticker library or the
 * meme cache) and an online GIPHY/Tenor URL, downloading the latter in place.
 */
class SaveStickerActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sendUrl = intent.getStringExtra(EXTRA_SEND_URL)
        val mime = intent.getStringExtra(EXTRA_MIME)
        val isLocal = intent.getBooleanExtra(EXTRA_IS_LOCAL, false)
        val detectedKey = intent.getStringExtra(EXTRA_DETECTED_MOOD)
        val detectedMood = Emotion.values().find { it.key == detectedKey }

        if (sendUrl.isNullOrBlank()) {
            Toast.makeText(this, R.string.save_sticker_failed, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        showMoodPickerDialog(this, detectedMood) { mood ->
            Toast.makeText(this, R.string.save_sticker_saving, Toast.LENGTH_SHORT).show()
            scope.launch {
                // P0 stability, same discipline as ReceiveStickerActivity: the read/download
                // and the StickerLibrary write both happen off the main thread; any
                // unexpected failure (bad URL, corrupt bytes, disk error) degrades to the
                // existing "failed" toast rather than crashing this activity.
                val saved = withContext(Dispatchers.IO) {
                    try {
                        val bytes: ByteArray? = if (isLocal) File(sendUrl).readBytes() else download(sendUrl)
                        if (bytes == null || bytes.isEmpty()) null
                        else StickerLibrary(this@SaveStickerActivity)
                            .addBytes(bytes, mime, mood, source = "keyboard_save")
                    } catch (t: Throwable) {
                        null
                    }
                }
                val msg = if (saved != null) {
                    getString(R.string.save_sticker_saved, mood.label)
                } else {
                    getString(R.string.save_sticker_failed)
                }
                Toast.makeText(this@SaveStickerActivity, msg, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun download(url: String): ByteArray? {
        val request = Request.Builder().url(url).get().build()
        return client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.bytes()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SEND_URL = "send_url"
        const val EXTRA_MIME = "mime"
        const val EXTRA_IS_LOCAL = "is_local"
        const val EXTRA_DETECTED_MOOD = "detected_mood"
    }
}
