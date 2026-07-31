package com.moodboard.keyboard.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.moodboard.keyboard.R
import com.moodboard.keyboard.databinding.ActivityStickerManagerBinding
import com.moodboard.keyboard.emotion.Emotion
import com.moodboard.keyboard.stickers.MoodBucketAdapter
import com.moodboard.keyboard.stickers.StickerLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Level 1 of the mood-categorised sticker library (SPEC_V2 B.3): a grid of mood
 * cards. Tapping one opens [MoodStickersActivity] for that mood's stickers.
 */
class StickerManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStickerManagerBinding
    private lateinit var library: StickerLibrary
    private lateinit var adapter: MoodBucketAdapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var pendingImportMood: Emotion? = null

    private val pickImages = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            showMoodPickerDialog(this) { mood -> importGallery(uris, mood) }
        }
    }

    private val openTree = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val treeUri = result.data?.data
        val mood = pendingImportMood
        pendingImportMood = null
        if (treeUri != null && mood != null) {
            try {
                contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Some providers don't support persistable grants; the one-shot
                // read permission from the picker result is still enough for this import.
            }
            importTree(treeUri, mood)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStickerManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        library = StickerLibrary(this)

        adapter = MoodBucketAdapter { bucket ->
            startActivity(
                Intent(this, MoodStickersActivity::class.java)
                    .putExtra(MoodStickersActivity.EXTRA_MOOD_KEY, bucket.mood.key)
            )
        }
        binding.moodGrid.layoutManager = GridLayoutManager(this, 2)
        binding.moodGrid.adapter = adapter

        binding.btnImportGallery.setOnClickListener { pickImages.launch("image/*") }
        binding.btnImportFolder.setOnClickListener {
            showImportFolderSheet(this) { useWhatsApp ->
                showMoodPickerDialog(this) { mood ->
                    pendingImportMood = mood
                    openTree.launch(buildOpenTreeIntent(if (useWhatsApp) WHATSAPP_STICKERS_URI else null))
                }
            }
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        adapter.submit(library.moods())
        binding.totalCountText.text = getString(R.string.total_stickers_count, library.totalCount())
    }

    private fun importGallery(uris: List<Uri>, mood: Emotion) {
        binding.progress.visibility = View.VISIBLE
        scope.launch {
            val count = withContext(Dispatchers.IO) { library.addAll(uris, mood) }
            binding.progress.visibility = View.GONE
            refresh()
            Toast.makeText(this@StickerManagerActivity, getString(R.string.imported_count, count), Toast.LENGTH_SHORT).show()
        }
    }

    private fun importTree(treeUri: Uri, mood: Emotion) {
        binding.progress.visibility = View.VISIBLE
        scope.launch {
            val count = withContext(Dispatchers.IO) { library.importTree(treeUri, mood) }
            binding.progress.visibility = View.GONE
            refresh()
            val msg = if (count > 0) getString(R.string.imported_count, count) else getString(R.string.import_none_found)
            Toast.makeText(this@StickerManagerActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
