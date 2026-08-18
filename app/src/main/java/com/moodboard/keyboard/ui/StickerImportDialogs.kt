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
 * cannot read from WhatsApp's private storage (SPEC_V2 B.4), then offers the two
 * SAF-based routes: the pre-pointed WhatsApp stickers folder, or any other folder.
 */
fun showImportFolderSheet(context: Context, onChoice: (useWhatsAppFolder: Boolean) -> Unit) {
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
    val btnWhatsApp = Button(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        text = context.getString(R.string.btn_import_whatsapp)
        setBackgroundResource(R.drawable.mood_btn_bg)
        setTextColor(ContextCompat.getColor(context, R.color.md_on_primary))
        isAllCaps = false
        setOnClickListener { dialog.dismiss(); onChoice(true) }
    }
    val btnOther = Button(context).apply {
        text = context.getString(R.string.btn_import_other_folder)
        setBackgroundResource(R.drawable.mood_btn_bg)
        setTextColor(ContextCompat.getColor(context, R.color.md_on_primary))
        isAllCaps = false
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = gap
        layoutParams = lp
        setOnClickListener { dialog.dismiss(); onChoice(false) }
    }
    root.addView(title)
    root.addView(explain)
    root.addView(btnWhatsApp)
    root.addView(btnOther)
    dialog.setContentView(root)
    dialog.show()
}
