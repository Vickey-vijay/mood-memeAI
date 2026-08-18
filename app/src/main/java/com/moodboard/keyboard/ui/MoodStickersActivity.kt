package com.moodboard.keyboard.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
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

    // Multi-select mode (client bug fix: bulk move/delete for ~900 mis-filed stickers).
    // Activity is the single source of truth; the adapter is just told what to render
    // via adapter.setSelection() whenever this changes.
    private var selectionMode = false
    private val selectedIds = LinkedHashSet<String>()
    private var currentItems: List<StickerItem> = emptyList()

    private val backPressCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = exitSelectionMode()
    }

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

    /** Client bug fix companion: picks individual sticker files via SAF (see
     *  [buildOpenDocumentIntent]) instead of the whole-folder-only tree picker. */
    private val pickDocuments = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = collectPickedUris(result.data)
        if (uris.isNotEmpty()) importGallery(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMoodStickersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val key = intent.getStringExtra(EXTRA_MOOD_KEY)
        mood = Emotion.values().find { it.key == key } ?: Emotion.NEUTRAL
        title = mood.label

        // Tap opens the single-item sheet (set cover / move / favourite / delete) when not
        // selecting; long-press now enters multi-select instead of opening that sheet
        // directly - the standard Android gallery pattern, and what makes the client's
        // ~900 mis-filed stickers actually bulk-sortable.
        adapter = StickerAdapter(
            onClick = { item -> showActionSheet(item) },
            onLongClick = { item -> enterSelectionMode(item.id) },
            onToggleSelect = { item -> toggleSelected(item.id) }
        )
        binding.stickerGrid.layoutManager = GridLayoutManager(this, 3)
        binding.stickerGrid.adapter = adapter
        onBackPressedDispatcher.addCallback(this, backPressCallback)

        binding.fabAdd.setOnClickListener {
            if (library == null) { toastLoading(); return@setOnClickListener }
            pickImages.launch("image/*")
        }
        binding.btnImportFolder.setOnClickListener {
            if (library == null) { toastLoading(); return@setOnClickListener }
            showImportFolderSheet(this) { source ->
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
        binding.btnSelectMode.setOnClickListener {
            if (library == null) { toastLoading(); return@setOnClickListener }
            enterSelectionMode()
        }
        binding.btnSelectAll.setOnClickListener {
            selectedIds.clear()
            selectedIds.addAll(currentItems.map { it.id })
            updateSelectionChrome()
        }
        binding.btnClearSelection.setOnClickListener { exitSelectionMode() }
        binding.btnMoveSelected.setOnClickListener { moveSelected() }
        binding.btnDeleteSelected.setOnClickListener { confirmDeleteSelected() }

        loadLibrary()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---------------- multi-select mode (client bug fix) ----------------

    /** Enters selection mode, optionally pre-selecting [initialId] (the long-pressed item). */
    private fun enterSelectionMode(initialId: String? = null) {
        selectionMode = true
        if (initialId != null) selectedIds.add(initialId)
        updateSelectionChrome()
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selectedIds.clear()
        updateSelectionChrome()
    }

    private fun toggleSelected(id: String) {
        if (!selectedIds.add(id)) selectedIds.remove(id)
        if (selectedIds.isEmpty()) exitSelectionMode() else updateSelectionChrome()
    }

    private fun updateSelectionChrome() {
        binding.normalHeaderRow.visibility = if (selectionMode) View.GONE else View.VISIBLE
        binding.selectionHeaderRow.visibility = if (selectionMode) View.VISIBLE else View.GONE
        binding.selectionActionBar.visibility = if (selectionMode) View.VISIBLE else View.GONE
        binding.fabAdd.visibility = if (selectionMode) View.GONE else View.VISIBLE
        binding.selectionCountText.text = getString(R.string.selected_count, selectedIds.size)
        backPressCallback.isEnabled = selectionMode
        adapter.setSelection(selectionMode, selectedIds.toSet())
    }

    /** Bulk move (client bug fix): one index write for the whole selection, not one per
     *  item - see [StickerLibrary.moveAll]. Must not block the UI for ~900 items. */
    private fun moveSelected() {
        val lib = library ?: run { toastLoading(); return }
        val ids = selectedIds.toList()
        if (ids.isEmpty()) return
        showMoodPickerDialog(this) { target ->
            binding.progress.visibility = View.VISIBLE
            scope.launch {
                val moved = withContext(Dispatchers.IO) { lib.moveAll(ids, target) }
                binding.progress.visibility = View.GONE
                exitSelectionMode()
                refresh()
                Toast.makeText(this@MoodStickersActivity, getString(R.string.moved_count, moved), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmDeleteSelected() {
        val ids = selectedIds.toList()
        if (ids.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_selected_title, ids.size))
            .setPositiveButton(R.string.btn_delete) { _, _ -> deleteSelected(ids) }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /** Bulk delete (client bug fix): one index write for the whole selection - see
     *  [StickerLibrary.deleteAll]. Must not block the UI for ~900 items. */
    private fun deleteSelected(ids: List<String>) {
        val lib = library ?: run { toastLoading(); return }
        binding.progress.visibility = View.VISIBLE
        scope.launch {
            val deleted = withContext(Dispatchers.IO) { lib.deleteAll(ids) }
            binding.progress.visibility = View.GONE
            exitSelectionMode()
            refresh()
            Toast.makeText(this@MoodStickersActivity, getString(R.string.deleted_count, deleted), Toast.LENGTH_SHORT).show()
        }
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
        currentItems = items
        // Drop selections for stickers that no longer exist in this mood (e.g. moved out
        // from elsewhere) so "N selected" and the bulk actions never operate on stale ids.
        selectedIds.retainAll(items.mapTo(HashSet()) { it.id })
        adapter.submit(items)
        adapter.setSelection(selectionMode, selectedIds.toSet())
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
