package com.moodboard.keyboard.stickers

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.moodboard.keyboard.R

/** Simple grid of emojis; tapping one inserts it as text. */
class EmojiAdapter(
    private val onClick: (String) -> Unit
) : RecyclerView.Adapter<EmojiAdapter.VH>() {

    private val items = ArrayList<String>()

    fun submit(list: List<String>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_emoji, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val emoji = items[position]
        holder.text.text = emoji
        holder.itemView.setOnClickListener { onClick(emoji) }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.emojiText)
    }
}
