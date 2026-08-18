package com.moodboard.keyboard.stickers

import android.content.Context
import com.moodboard.keyboard.R

/**
 * SPEC_V3 A.7, extended for the multi-source aggregator: turns the set of
 * [MemeSource.id]s that actually contributed items to the currently displayed grid into
 * a single "Powered by …" label (GIPHY's three endpoints collapse into one "GIPHY"
 * credit since they're the same provider/terms). [StickerRepository.lastFetchSources] is
 * built from the *final*, post-filter/anti-repeat grid, not the raw fetch, so the label
 * always matches what the user is actually looking at.
 */
object MemeAttribution {

    fun label(context: Context, sourceIds: Set<String>): String {
        if (sourceIds.isEmpty()) return ""
        val names = LinkedHashSet<String>()
        if (sourceIds.any { it.startsWith("giphy") }) names.add(context.getString(R.string.source_name_giphy))
        if (TenorSource.ID in sourceIds) names.add(context.getString(R.string.source_name_tenor))
        if (ImgflipSource.ID in sourceIds) names.add(context.getString(R.string.source_name_imgflip))
        if (names.isEmpty()) return ""
        return context.getString(R.string.attribution_powered_by, names.joinToString(", "))
    }
}
