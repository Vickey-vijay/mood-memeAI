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
    // P0 stability: StickerLibrary's constructor does a full index load + legacy migration
    // off disk. Nullable + loaded on Dispatchers.IO (see loadLibrary()) so onCreate never
    // blocks the main thread; every access below guards against it still being null.
    private var library: StickerLibrary? = null
    private lateinit var adapter: MoodBucketAdapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var pendingImportMood: Emotion? = null
    // First onResume() fires right after onCreate()'s own loadLibrary() call - skip the
    // reload there so a cold start doesn't read index.json twice back to back.
    private var isFirstResume = true

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

    /** Client bug fix companion: picks individual sticker files via SAF (see
     *  [buildOpenDocumentIntent]) instead of the whole-folder-only tree picker. */
    private val pickDocuments = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = collectPickedUris(result.data)
        val mood = pendingImportMood
        pendingImportMood = null
        if (uris.isNotEmpty() && mood != null) importGallery(uris, mood)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStickerManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = MoodBucketAdapter { bucket ->
            startActivity(
                Intent(this, MoodStickersActivity::class.java)
                    .putExtra(MoodStickersActivity.EXTRA_MOOD_KEY, bucket.mood.key)
            )
        }
        binding.moodGrid.layoutManager = GridLayoutManager(this, 2)
        binding.moodGrid.adapter = adapter

        binding.btnImportGallery.setOnClickListener {
            if (library == null) { toastLoading(); return@setOnClickListener }
            pickImages.launch("image/*")
        }
        binding.btnImportFolder.setOnClickListener {
            if (library == null) { toastLoading(); return@setOnClickListener }
            showImportFolderSheet(this) { source ->
                showMoodPickerDialog(this) { mood ->
                    pendingImportMood = mood
                    try {
                        when (source) {
                            ImportSource.PICK_FILES ->
                                pickDocuments.launch(buildOpenDocumentIntent(WHATSAPP_STICKERS_URI))
                            ImportSource.WHATSAPP_FOLDER ->
                                openTree.launch(buildOpenTreeIntent(WHATSAPP_STICKERS_URI))
                            ImportSource.OTHER_FOLDER ->
                                openTree.launch(buildOpenTreeIntent(null))
                        }
                    } catch (t: Throwable) {
                        Toast.makeText(this, R.string.import_folder_unavailable, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        loadLibrary()
    }

    override fun onResume() {
        super.onResume()
        if (isFirstResume) {
            isFirstResume = false
        } else {
            // Bulk move/delete in MoodStickersActivity writes straight to that screen's own
            // StickerLibrary instance and its own disk index - reload ours so the mood grid
            // and per-mood counts here reflect it when the user comes back.
            loadLibrary()
        }
        refresh()
    }

    /** P0 stability: full index load + legacy migration off the main thread (see [library]). */
    private fun loadLibrary() {
        binding.progress.visibility = View.VISIBLE
        scope.launch {
            library = try {
                withContext(Dispatchers.IO) { StickerLibrary(this@StickerManagerActivity) }
            } catch (t: Throwable) {
                Toast.makeText(this@StickerManagerActivity, R.string.sticker_library_load_failed, Toast.LENGTH_LONG).show()
                null
            }
            binding.progress.visibility = View.GONE
            refresh()
        }
    }

    private fun toastLoading() {
        Toast.makeText(this, R.string.sticker_library_loading, Toast.LENGTH_SHORT).show()
    }

    private fun refresh() {
        val lib = library ?: return
        adapter.submit(lib.moods())
        binding.totalCountText.text = getString(R.string.total_stickers_count, lib.totalCount())
    }

    private fun importGallery(uris: List<Uri>, mood: Emotion) {
        val lib = library ?: run { toastLoading(); return }
        binding.progress.visibility = View.VISIBLE
        scope.launch {
            val count = withContext(Dispatchers.IO) { lib.addAll(uris, mood) }
            binding.progress.visibility = View.GONE
            refresh()
            Toast.makeText(this@StickerManagerActivity, getString(R.string.imported_count, count), Toast.LENGTH_SHORT).show()
        }
    }

    private fun importTree(treeUri: Uri, mood: Emotion) {
        val lib = library ?: run { toastLoading(); return }
        binding.progress.visibility = View.VISIBLE
        scope.launch {
            val count = withContext(Dispatchers.IO) { lib.importTree(treeUri, mood) }
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
