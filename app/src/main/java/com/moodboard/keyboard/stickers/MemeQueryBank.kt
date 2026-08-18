package com.moodboard.keyboard.stickers

import com.moodboard.keyboard.emotion.Emotion

/**
 * MAJOR SIMPLIFICATION (client bug report, 2026-08-19). This file used to be a hardcoded
 * bank of Tamil-actor-name query pools per emotion ("vadivelu sleepy", "ajith mass", ...),
 * paired with [MemeRelevance] hard gates that discarded any online result whose title
 * lacked both a culture marker and an emotion word. Client's own words: "tell me how this
 * works, how you're scraping the memes from online ... tell them the mood exactly, and
 * give us some options to type our fav! ... Make it dynamic." The user now drives the
 * query directly through the Setup "Meme style" category picker (or their own free text)
 * instead of a hidden actor-name lookup table.
 *
 * The query fed to every [MemeSource] is simply:
 *     "<emotion search word> <category term or custom text>"
 * e.g. mood SAD + category Cartoons -> "sad cartoon"; mood HAPPY + category Cinema ->
 * "happy cinema"; mood ANGRY + custom text "vadivelu" -> "angry vadivelu". Plain English
 * mood words are used because that's what GIPHY/Tenor actually index titles/tags against.
 */
object MemeQueryBank {

    /** One plain-English search word per [Emotion] — what these APIs index against. */
    fun searchWord(emotion: Emotion): String = moodWords[emotion] ?: emotion.label.lowercase()

    /**
     * Builds the dynamic query: the emotion's search word, plus [category]'s term, or
     * [customText] instead if the user typed something (custom always overrides the preset).
     */
    fun buildQuery(emotion: Emotion, category: MemeCategory, customText: String): String {
        val word = searchWord(emotion)
        val extra = customText.trim().ifBlank { category.term }
        return if (extra.isBlank()) word else "$word $extra"
    }

    private val moodWords: Map<Emotion, String> = mapOf(
        Emotion.HAPPY to "happy",
        Emotion.LAUGHING to "laughing",
        Emotion.EXCITED to "excited",
        Emotion.SURPRISED to "surprised",
        Emotion.SHOCKED to "shocked",
        Emotion.FEARFUL to "scared",
        Emotion.SAD to "sad",
        Emotion.ANGRY to "angry",
        Emotion.ANNOYED to "annoyed",
        Emotion.FRUSTRATED to "frustrated",
        Emotion.DISGUST to "disgusted",
        Emotion.CONTEMPT to "smirk",
        Emotion.SKEPTICAL to "confused",
        Emotion.SLEEPY to "sleepy",
        Emotion.KISS to "kiss",
        Emotion.WINK to "wink",
        Emotion.PUFFED to "funny",
        Emotion.SILLY to "silly",
        Emotion.NEUTRAL to "neutral"
    )
}
