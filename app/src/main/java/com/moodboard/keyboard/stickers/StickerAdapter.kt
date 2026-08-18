package com.moodboard.keyboard.stickers

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.moodboard.keyboard.R
import java.io.File

/**
 * Shared grid adapter for the keyboard toolbar, the floating-overlay panel, and the
 * mood-management screen ([com.moodboard.keyboard.ui.MoodStickersActivity]). Only the
 * last of those turns on multi-select ([setSelection]); it defaults off, so the other
 * two consumers (which never call it) render exactly as before.
 */
class StickerAdapter(
    private val onClick: (StickerItem) -> Unit,
    private val onLongClick: ((StickerItem) -> Unit)? = null,
    private val onToggleSelect: ((StickerItem) -> Unit)? = null
) : RecyclerView.Adapter<StickerAdapter.VH>() {

    private val items = ArrayList<StickerItem>()
    private var selectionMode = false
    private var selectedIds: Set<String> = emptySet()

    fun submit(list: List<StickerItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    /** Multi-select mode (client bug fix): [selected] is ignored while [enabled] is false. */
    fun setSelection(enabled: Boolean, selected: Set<String> = emptySet()) {
        selectionMode = enabled
        selectedIds = selected
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sticker, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val model: Any = if (item.isLocal) File(item.previewUrl) else item.previewUrl
        Glide.with(holder.image).load(model).into(holder.image)
        val selected = selectionMode && item.id in selectedIds
        holder.overlay.visibility = if (selected) View.VISIBLE else View.GONE
        holder.check.visibility = if (selected) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener {
            if (selectionMode) onToggleSelect?.invoke(item) else onClick(item)
        }
        holder.itemView.setOnLongClickListener {
            if (selectionMode) onToggleSelect?.invoke(item) else onLongClick?.invoke(item)
            true
        }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.stickerImage)
        val overlay: View = view.findViewById(R.id.selectionOverlay)
        val check: View = view.findViewById(R.id.selectionCheck)
    }
}
