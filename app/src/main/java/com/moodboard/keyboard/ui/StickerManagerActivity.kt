package com.moodboard.keyboard.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.moodboard.keyboard.databinding.ActivityStickerManagerBinding
import com.moodboard.keyboard.stickers.CustomStickerStore
import com.moodboard.keyboard.stickers.StickerAdapter

/** Import and delete the user's own stickers (from gallery / downloaded packs). */
class StickerManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStickerManagerBinding
    private lateinit var store: CustomStickerStore
    private lateinit var adapter: StickerAdapter

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val saved = store.importFrom(uri)
            if (saved != null) refresh()
            else Toast.makeText(this, "Couldn't import that image", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStickerManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = CustomStickerStore(this)

        adapter = StickerAdapter(
            onClick = { /* tapping in the manager just previews; no-op for now */ },
            onLongClick = { item ->
                AlertDialog.Builder(this)
                    .setTitle("Delete sticker?")
                    .setPositiveButton("Delete") { _, _ ->
                        store.delete(item); refresh()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )
        binding.customGrid.layoutManager = GridLayoutManager(this, 3)
        binding.customGrid.adapter = adapter

        binding.btnAdd.setOnClickListener { pickImage.launch("image/*") }
        refresh()
    }

    private fun refresh() {
        val items = store.list()
        adapter.submit(items)
        binding.emptyLabel.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }
}
