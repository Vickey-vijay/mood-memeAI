package com.moodboard.keyboard.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.moodboard.keyboard.R
import com.moodboard.keyboard.stickers.StickerLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Share target for "Share into MoodBoard" (SPEC_V2 B.4 route 1). Handles
 * ACTION_SEND (one sticker) and ACTION_SEND_MULTIPLE (several), any image MIME
 * type. Shows a mood picker and saves into the chosen mood, then finishes.
 */
class ReceiveStickerActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uris = extractUris(intent)
        if (uris.isEmpty()) {
            Toast.makeText(this, R.string.receive_sticker_failed, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        showMoodPickerDialog(this) { mood ->
            scope.launch {
                // P0 stability: both the StickerLibrary construction (index load + legacy
                // migration) and the import happen off the main thread; a truly unexpected
                // failure degrades to the existing "failed" toast rather than propagating.
                val count = withContext(Dispatchers.IO) {
                    try {
                        StickerLibrary(this@ReceiveStickerActivity).addAll(uris, mood, source = "share")
                    } catch (t: Throwable) {
                        0
                    }
                }
                // Required outcome 3: show a clear confirmation of what was saved and
                // where, not just a bare "saved" toast - the client's whole complaint was
                // not being able to tell whether/where an import landed.
                val msg = if (count > 0) {
                    getString(R.string.receive_sticker_saved_detail, count, mood.emoji, mood.label)
                } else {
                    getString(R.string.receive_sticker_failed)
                }
                Toast.makeText(this@ReceiveStickerActivity, msg, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun extractUris(intent: Intent): List<Uri> {
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                listOfNotNull(uri)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                list.orEmpty()
            }
            else -> emptyList()
        }
    }
}
