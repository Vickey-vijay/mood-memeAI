package com.moodboard.keyboard.emotion

/**
 * Emotions MoodBoard can detect, each with a GIPHY sticker query and emoji set.
 * Detection picks the highest-scoring emotion from MediaPipe blendshapes
 * (see EmotionAnalyzer), so the full range is reachable.
 */
enum class Emotion(
    val label: String,
    val query: String,
    val emojis: List<String>
) {
    HAPPY("Happy", "happy smile reaction sticker",
        listOf("😀", "😄", "😊", "🙂", "😁", "🤗")),
    LAUGHING("Laughing", "laughing lol reaction sticker",
        listOf("😂", "🤣", "😆", "😹", "😅", "😁")),
    EXCITED("Excited", "excited yay reaction sticker",
        listOf("🤩", "🥳", "😆", "✨", "🙌", "😄")),
    SURPRISED("Surprised", "surprised wow reaction sticker",
        listOf("😮", "😲", "😯", "😳", "😦", "😧")),
    SHOCKED("Shocked", "shocked omg reaction sticker",
        listOf("😱", "🤯", "😨", "😵", "🙀", "😬")),
    SAD("Sad", "sad crying reaction sticker",
        listOf("😢", "😭", "😔", "🥺", "😞", "💔")),
    ANGRY("Angry", "angry mad reaction sticker",
        listOf("😠", "😡", "🤬", "😤", "👿", "😾")),
    ANNOYED("Annoyed", "annoyed eye roll reaction sticker",
        listOf("🙄", "😒", "😑", "😤", "🫤", "😠")),
    DISGUST("Disgust", "disgusted eww reaction sticker",
        listOf("🤢", "🤮", "😖", "🤧", "😝", "😬")),
    SKEPTICAL("Skeptical", "skeptical really reaction sticker",
        listOf("🤨", "🧐", "🤔", "😏", "🙃", "😶")),
    SLEEPY("Sleepy", "sleepy tired bored reaction sticker",
        listOf("😴", "🥱", "😪", "😌", "💤", "😑")),
    KISS("Kiss", "kiss love reaction sticker",
        listOf("😘", "😚", "😍", "🥰", "💋", "😙")),
    NEUTRAL("Neutral", "ok cool reaction sticker",
        listOf("😐", "😶", "🙂", "👍", "🆗", "🤷"));


    val emoji: String get() = emojis.first()

    companion object {
        fun fromText(raw: String?): Emotion {
            val t = raw?.lowercase()?.trim().orEmpty()
            return when {
                t.contains("laugh") -> LAUGHING
                t.contains("happy") || t.contains("joy") || t.contains("smil") -> HAPPY
                t.contains("excit") -> EXCITED
                t.contains("sad") || t.contains("cry") -> SAD
                t.contains("ang") || t.contains("mad") -> ANGRY
                t.contains("annoy") -> ANNOYED
                t.contains("disgust") -> DISGUST
                t.contains("shock") -> SHOCKED
                t.contains("surpris") || t.contains("wow") -> SURPRISED
                else -> NEUTRAL
            }
        }
    }
}
