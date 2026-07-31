package com.moodboard.keyboard.emotion

/**
 * The 18 detectable emotions plus NEUTRAL (see SPEC_V2 A.5). Each entry keeps a
 * display [label], a sticker-search [query], an [emojis] fallback set, and a
 * stable lowercase [key] (ASCII, no spaces) used as a sticker-library folder name.
 * Detection is FACS-prototype matching against MediaPipe blendshapes — see
 * EmotionAnalyzer / ExpressionClassifier.
 */
enum class Emotion(
    val label: String,
    val query: String,
    val emojis: List<String>,
    val key: String
) {
    HAPPY("Happy", "happy smile reaction sticker",
        listOf("😀", "😄", "😊", "🙂", "😁", "🤗"), "happy"),
    LAUGHING("Laughing", "laughing lol reaction sticker",
        listOf("😂", "🤣", "😆", "😹", "😅", "😁"), "laughing"),
    EXCITED("Excited", "excited yay reaction sticker",
        listOf("🤩", "🥳", "😆", "✨", "🙌", "😄"), "excited"),
    SURPRISED("Surprised", "surprised wow reaction sticker",
        listOf("😮", "😲", "😯", "😳", "😦", "😧"), "surprised"),
    SHOCKED("Shocked", "shocked omg reaction sticker",
        listOf("😱", "🤯", "😨", "😵", "🙀", "😬"), "shocked"),
    FEARFUL("Fearful", "scared afraid reaction sticker",
        listOf("😨", "😰", "😧", "🙈", "😬", "😖"), "fearful"),
    SAD("Sad", "sad crying reaction sticker",
        listOf("😢", "😭", "😔", "🥺", "😞", "💔"), "sad"),
    ANGRY("Angry", "angry mad reaction sticker",
        listOf("😠", "😡", "🤬", "😤", "👿", "😾"), "angry"),
    ANNOYED("Annoyed", "annoyed eye roll reaction sticker",
        listOf("🙄", "😒", "😑", "😤", "🫤", "😠"), "annoyed"),
    FRUSTRATED("Frustrated", "frustrated ugh reaction sticker",
        listOf("😤", "😫", "😩", "🙄", "😖", "😣"), "frustrated"),
    DISGUST("Disgust", "disgusted eww reaction sticker",
        listOf("🤢", "🤮", "😖", "🤧", "😝", "😬"), "disgust"),
    CONTEMPT("Contempt", "smug side eye reaction sticker",
        listOf("😏", "🙄", "😒", "😌", "🤨", "😑"), "contempt"),
    SKEPTICAL("Skeptical", "skeptical really reaction sticker",
        listOf("🤨", "🧐", "🤔", "😏", "🙃", "😶"), "skeptical"),
    SLEEPY("Sleepy", "sleepy tired bored reaction sticker",
        listOf("😴", "🥱", "😪", "😌", "💤", "😑"), "sleepy"),
    KISS("Kiss", "kiss love reaction sticker",
        listOf("😘", "😚", "😍", "🥰", "💋", "😙"), "kiss"),
    WINK("Wink", "wink playful reaction sticker",
        listOf("😉", "😜", "😏", "😘", "😆", "🙃"), "wink"),
    PUFFED("Puffed", "cheek puff silly reaction sticker",
        listOf("😤", "🐡", "😶‍🌫️", "😑", "🙃", "😬"), "puffed"),
    SILLY("Silly", "silly goofy tongue reaction sticker",
        listOf("😜", "🤪", "😝", "🤭", "😛", "🙃"), "silly"),
    NEUTRAL("Neutral", "ok cool reaction sticker",
        listOf("😐", "😶", "🙂", "👍", "🆗", "🤷"), "neutral");


    val emoji: String get() = emojis.first()

    companion object {
        fun fromText(raw: String?): Emotion {
            val t = raw?.lowercase()?.trim().orEmpty()
            return when {
                t.contains("laugh") -> LAUGHING
                t.contains("happy") || t.contains("joy") || t.contains("smil") -> HAPPY
                t.contains("excit") -> EXCITED
                t.contains("frustrat") -> FRUSTRATED
                t.contains("sad") || t.contains("cry") -> SAD
                t.contains("ang") || t.contains("mad") -> ANGRY
                t.contains("annoy") -> ANNOYED
                t.contains("disgust") -> DISGUST
                t.contains("contempt") || t.contains("smug") -> CONTEMPT
                t.contains("shock") -> SHOCKED
                t.contains("fear") || t.contains("scare") || t.contains("afraid") -> FEARFUL
                t.contains("surpris") || t.contains("wow") -> SURPRISED
                t.contains("skeptic") -> SKEPTICAL
                t.contains("sleep") || t.contains("tired") || t.contains("bored") -> SLEEPY
                t.contains("kiss") -> KISS
                t.contains("wink") -> WINK
                t.contains("puff") -> PUFFED
                t.contains("silly") || t.contains("goofy") || t.contains("tongue") -> SILLY
                else -> NEUTRAL
            }
        }
    }
}
