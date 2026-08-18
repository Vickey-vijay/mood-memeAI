package com.moodboard.keyboard.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.moodboard.keyboard.R
import com.moodboard.keyboard.databinding.ActivityMoodStickersBinding
import com.moodboard.keyboard.emotion.Emotion
import com.moodboard.keyboard.stickers.StickerAdapter
import com.moodboard.keyboard.stickers.StickerItem
import com.moodboard.keyboard.stickers.StickerLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Level 2 of the mood-categorised sticker library (SPEC_V2 B.3): the grid of one
 * mood's stickers, with Add / Import folder / long-press actions.
 */
class MoodStickersActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMoodStickersBinding
    // P0 stability: same reasoning as StickerManagerActivity - nullable, loaded off the main
    // thread in loadLibrary(); every access guards against it still being null.
    private var library: StickerLibrary? = null
    private lateinit var adapter: StickerAdapter
    private lateinit var mood: Emotion
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val pickImages = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> if (uris.isNotEmpty()) importGallery(uris) }

    private val openTree = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val treeUri = result.data?.data ?: return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some providers don't support persistable grants; the one-shot read
            // permission from the picker result is still enough for this import.
        }
        importTree(treeUri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMoodStickersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val key = intent.getStringExtra(EXTRA_MOOD_KEY)
        mood = Emotion.values().find { it.key == key } ?: Emotion.NEUTRAL
        title = mood.label

        adapter = StickerAdapter(
            onClick = { /* preview only; sending happens from the keyboard */ },
            onLongClick = { item -> showActionSheet(item) }
        )
        binding.stickerGrid.layoutManager = GridLayoutManager(this, 3)
        binding.stickerGrid.adapter = adapter

        binding.fabAdd.setOnClickListener {
            if (library == null) { toastLoading(); return@setOnClickListener }
            pickImages.launch("image/*")
        }
        binding.btnImportFolder.setOnClickListener {
            if (library == null) { toastLoading(); return@setOnClickListener }
            showImportFolderSheet(this) { useWhatsApp ->
                try {
                    openTree.launch(buildOpenTreeIntent(if (useWhatsApp) WHATSAPP_STICKERS_URI else null))
                } catch (t: Throwable) {
                    Toast.makeText(this, R.string.import_folder_unavailable, Toast.LENGTH_SHORT).show()
                }
            }
        }

        loadLibrary()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** P0 stability: full index load off the main thread (see [library]). */
    private fun loadLibrary() {
        binding.progress.visibility = View.VISIBLE
        scope.launch {
            library = try {
                withContext(Dispatchers.IO) { StickerLibrary(this@MoodStickersActivity) }
            } catch (t: Throwable) {
                Toast.makeText(this@MoodStickersActivity, R.string.sticker_library_load_failed, Toast.LENGTH_LONG).show()
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
        val items = lib.list(mood)
        adapter.submit(items)
        binding.moodTitleText.text = "${mood.emoji} ${mood.label} · ${items.size}"
        binding.emptyLabel.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun importGallery(uris: List<Uri>) {
        val lib = library ?: run { toastLoading(); return }
        binding.progress.visibility = View.VISIBLE
        scope.launch {
            val count = withContext(Dispatchers.IO) { lib.addAll(uris, mood) }
            binding.progress.visibility = View.GONE
            refresh()
            Toast.makeText(this@MoodStickersActivity, getString(R.string.imported_count, count), Toast.LENGTH_SHORT).show()
        }
    }

    private fun importTree(treeUri: Uri) {
        val lib = library ?: run { toastLoading(); return }
        binding.progress.visibility = View.VISIBLE
        scope.launch {
            val count = withContext(Dispatchers.IO) { lib.importTree(treeUri, mood) }
            binding.progress.visibility = View.GONE
            refresh()
            val msg = if (count > 0) getString(R.string.imported_count, count) else getString(R.string.import_none_found)
            Toast.makeText(this@MoodStickersActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showActionSheet(item: StickerItem) {
        val favLabel = if (item.favorite) getString(R.string.sticker_action_unfavorite)
        else getString(R.string.sticker_action_favorite)
        val actions = arrayOf(
            getString(R.string.sticker_action_set_cover),
            getString(R.string.sticker_action_move),
            favLabel,
            getString(R.string.sticker_action_delete)
        )
        AlertDialog.Builder(this)
            .setItems(actions) { _, which ->
                val lib = library ?: run { toastLoading(); return@setItems }
                when (which) {
                    0 -> { lib.setCover(mood, item.id); refresh() }
                    1 -> showMoodPickerDialog(this) { target ->
                        lib.move(item.id, target)
                        refresh()
                        if (target != mood) {
                            Toast.makeText(this, "${item.mood} -> ${target.label}", Toast.LENGTH_SHORT).show()
                        }
                    }
                    2 -> { lib.setFavorite(item.id, !item.favorite); refresh() }
                    3 -> confirmDelete(item)
                }
            }
            .show()
    }

    private fun confirmDelete(item: StickerItem) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_sticker_title)
            .setPositiveButton(R.string.btn_delete) { _, _ -> library?.delete(item.id); refresh() }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MOOD_KEY = "mood_key"
    }
}
