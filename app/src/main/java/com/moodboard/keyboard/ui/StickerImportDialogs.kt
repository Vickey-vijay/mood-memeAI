package com.moodboard.keyboard.ui

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.moodboard.keyboard.R
import com.moodboard.keyboard.databinding.DialogMoodPickerBinding
import com.moodboard.keyboard.emotion.Emotion
import com.moodboard.keyboard.stickers.MoodPickAdapter

/**
 * Shared import/mood-picker UI glue used by both [StickerManagerActivity] (level 1)
 * and [MoodStickersActivity] (level 2) -- kept in one place so the two activities
 * don't duplicate this logic (SPEC_V2 B.3/B.4).
 */

/** SAF folder pre-pointed at WhatsApp's readable received-stickers folder (B.4 route 2). */
val WHATSAPP_STICKERS_URI: Uri = Uri.parse(
    "content://com.android.externalstorage.documents/document/primary%3AAndroid%2Fmedia%2Fcom.whatsapp%2FWhatsApp%2FMedia%2FWhatsApp%20Stickers"
)

/**
 * Builds an ACTION_OPEN_DOCUMENT_TREE intent, optionally pre-pointed at [initialUri].
 * If that folder doesn't exist on the device, the system picker just opens at its
 * own default location -- this never crashes (SPEC_V2 B.4).
 */
fun buildOpenTreeIntent(initialUri: Uri?): Intent {
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
    intent.addFlags(
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
    )
    if (initialUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
    }
    return intent
}

/**
 * Client bug fix: ACTION_OPEN_DOCUMENT_TREE (above) can only ever return "use this
 * folder" -- it selects a directory, never individual files, by design. This is the
 * real file-browser SAF route: it opens with checkboxes and lets the user multi-select
 * (or pick just one) sticker file, optionally pre-navigated into [initialUri].
 */
fun buildOpenDocumentIntent(initialUri: Uri?): Intent {
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
    intent.addCategory(Intent.CATEGORY_OPENABLE)
    intent.type = "image/*"
    intent.putExtra(
        Intent.EXTRA_MIME_TYPES,
        arrayOf("image/webp", "image/gif", "image/png", "image/jpeg")
    )
    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (initialUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
    }
    return intent
}

/**
 * ACTION_OPEN_DOCUMENT with EXTRA_ALLOW_MULTIPLE reports its result in one of two
 * shapes depending on how many files were picked: a single selection lands in
 * `data.data` and leaves `clipData` null, while two-or-more land only in
 * `data.clipData` and leave `data.data` null. Missing the clipData branch is the
 * classic bug here -- multi-select would silently import nothing.
 */
fun collectPickedUris(data: Intent?): List<Uri> {
    if (data == null) return emptyList()
    val clip = data.clipData
    if (clip != null && clip.itemCount > 0) {
        return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    }
    return data.data?.let { listOf(it) } ?: emptyList()
}

/** The three routes [showImportFolderSheet] offers. */
enum class ImportSource { WHATSAPP_FOLDER, OTHER_FOLDER, PICK_FILES }

/**
 * Mood picker used for import targets, "move to another mood" (SPEC_V2 B.3), and the
 * in-keyboard "save this sticker" flow (required outcome 4). When [defaultMood] is given
 * (the mood detected for the current scan) it is moved to the top of the list and marked,
 * so the picker defaults sensibly without forcing the choice - the user can still pick
 * any other mood in one tap.
 */
fun showMoodPickerDialog(context: Context, defaultMood: Emotion? = null, onPick: (Emotion) -> Unit) {
    val binding = DialogMoodPickerBinding.inflate(LayoutInflater.from(context))
    val dialog = BottomSheetDialog(context)
    dialog.setContentView(binding.root)
    binding.pickerGrid.layoutManager = LinearLayoutManager(context)
    val ordered = if (defaultMood != null) {
        listOf(defaultMood) + Emotion.values().filter { it != defaultMood }
    } else {
        Emotion.values().toList()
    }
    binding.pickerGrid.adapter = MoodPickAdapter(ordered, defaultMood) { mood ->
        dialog.dismiss()
        onPick(mood)
    }
    dialog.show()
}

/**
 * The "Import a folder" entry point. States -- honestly -- what MoodBoard can and
 * cannot read from WhatsApp's private storage (SPEC_V2 B.4), then offers three
 * routes: pick individual sticker files (client bug fix -- a real SAF file browser
 * with checkboxes, pre-navigated into WhatsApp's stickers folder), or the two
 * whole-folder SAF routes that were already here: the pre-pointed WhatsApp stickers
 * folder, or any other folder.
 */
fun showImportFolderSheet(context: Context, onChoice: (ImportSource) -> Unit) {
    val dialog = BottomSheetDialog(context)
    val density = context.resources.displayMetrics.density
    val pad = (20 * density).toInt()
    val gap = (10 * density).toInt()

    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad, pad, pad)
    }
    val matchWrap = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    )
    val title = TextView(context).apply {
        layoutParams = matchWrap
        text = context.getString(R.string.import_folder_title)
        setTextColor(ContextCompat.getColor(context, R.color.kb_text))
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
    }
    val explain = TextView(context).apply {
        layoutParams = matchWrap
        text = context.getString(R.string.import_folder_explanation)
        setTextColor(ContextCompat.getColor(context, R.color.kb_text_muted))
        textSize = 13f
        setPadding(0, gap, 0, pad)
    }
    fun importButton(labelRes: Int, topGap: Int, choice: ImportSource) = Button(context).apply {
        text = context.getString(labelRes)
        setBackgroundResource(R.drawable.mood_btn_bg)
        setTextColor(ContextCompat.getColor(context, R.color.md_on_primary))
        isAllCaps = false
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = topGap
        layoutParams = lp
        setOnClickListener { dialog.dismiss(); onChoice(choice) }
    }
    // Individual-file picking listed first: it's the direct fix for the client's
    // "I can only import the whole folder" complaint, not a buried third option.
    val btnPickFiles = importButton(R.string.btn_import_pick_files, 0, ImportSource.PICK_FILES)
    val btnWhatsApp = importButton(R.string.btn_import_whatsapp, gap, ImportSource.WHATSAPP_FOLDER)
    val btnOther = importButton(R.string.btn_import_other_folder, gap, ImportSource.OTHER_FOLDER)
    root.addView(title)
    root.addView(explain)
    root.addView(btnPickFiles)
    root.addView(btnWhatsApp)
    root.addView(btnOther)
    dialog.setContentView(root)
    dialog.show()
}
