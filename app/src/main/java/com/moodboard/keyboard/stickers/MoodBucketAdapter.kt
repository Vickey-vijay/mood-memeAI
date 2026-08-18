package com.moodboard.keyboard.stickers

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.moodboard.keyboard.R
import com.moodboard.keyboard.emotion.Emotion
import java.io.File

/** Level-1 mood grid: emoji + label + count + cover thumbnail (SPEC_V2 B.3). */
class MoodBucketAdapter(
    private val onClick: (MoodBucket) -> Unit
) : RecyclerView.Adapter<MoodBucketAdapter.VH>() {

    private val items = ArrayList<MoodBucket>()

    fun submit(list: List<MoodBucket>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_mood_bucket, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val bucket = items[position]
        holder.emoji.text = bucket.mood.emoji
        holder.label.text = bucket.mood.label
        holder.count.text = bucket.count.toString()
        holder.itemView.alpha = if (bucket.count == 0) 0.45f else 1f
        if (bucket.coverFile != null) {
            holder.cover.visibility = View.VISIBLE
            Glide.with(holder.cover).load(File(bucket.coverFile)).into(holder.cover)
        } else {
            holder.cover.visibility = View.GONE
            Glide.with(holder.cover).clear(holder.cover)
        }
        holder.itemView.setOnClickListener { onClick(bucket) }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val emoji: TextView = view.findViewById(R.id.bucketEmoji)
        val label: TextView = view.findViewById(R.id.bucketLabel)
        val count: TextView = view.findViewById(R.id.bucketCount)
        val cover: ImageView = view.findViewById(R.id.bucketCover)
    }
}

/**
 * Simple mood-picker list used by [com.moodboard.keyboard.ui.showMoodPickerDialog] for
 * import-target and move-to-another-mood selection (SPEC_V2 B.3/B.4). Rows are built
 * in code rather than inflating a per-row layout, since a plain emoji+label line needs
 * nothing more.
 */
class MoodPickAdapter(
    private val moods: List<Emotion>,
    /** Save-sticker flow (required outcome 4): the currently-detected mood, if any,
     *  shown first and visually marked so the picker defaults to it without forcing it. */
    private val highlighted: Emotion? = null,
    private val onPick: (Emotion) -> Unit
) : RecyclerView.Adapter<MoodPickAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = TextView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(24, 26, 24, 26)
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.kb_text))
        }
        return VH(tv)
    }

    override fun getItemCount(): Int = moods.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = moods[position]
        val isDefault = e == highlighted
        holder.tv.text = if (isDefault) {
            holder.tv.context.getString(R.string.mood_pick_detected_row, e.emoji, e.label)
        } else {
            "${e.emoji}  ${e.label}"
        }
        holder.tv.setTypeface(holder.tv.typeface, if (isDefault) Typeface.BOLD else Typeface.NORMAL)
        holder.tv.setOnClickListener { onPick(e) }
    }

    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)
}
