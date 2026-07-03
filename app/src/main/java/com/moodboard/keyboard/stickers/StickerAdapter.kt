package com.moodboard.keyboard.stickers

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.moodboard.keyboard.R
import java.io.File

class StickerAdapter(
    private val onClick: (StickerItem) -> Unit,
    private val onLongClick: ((StickerItem) -> Unit)? = null
) : RecyclerView.Adapter<StickerAdapter.VH>() {

    private val items = ArrayList<StickerItem>()

    fun submit(list: List<StickerItem>) {
        items.clear()
        items.addAll(list)
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
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener {
            onLongClick?.invoke(item)
            onLongClick != null
        }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.stickerImage)
    }
}
