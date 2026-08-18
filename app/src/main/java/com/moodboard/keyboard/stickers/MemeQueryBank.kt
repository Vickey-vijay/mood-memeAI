package com.moodboard.keyboard.stickers

import com.moodboard.keyboard.emotion.Emotion

/**
 * SPEC_V3 A.2 — culture packs for meme sourcing.
 *
 * [MemeCulture.SOUTH_INDIAN] draws on well-known Tamil/Telugu/Malayalam/Kannada comedy
 * and reaction sources (R2). [MemeCulture.GENERIC] falls back to the existing single
 * [Emotion.query] string, unchanged from SPEC_V2.
 */
enum class MemeCulture { SOUTH_INDIAN, GENERIC }

object MemeQueryBank {

    /** Pool of candidate search queries for [emotion] in the given [culture] (A.2/A.3). */
    fun queries(emotion: Emotion, culture: MemeCulture): List<String> = when (culture) {
        MemeCulture.GENERIC -> listOf(emotion.query)
        MemeCulture.SOUTH_INDIAN -> southIndianPool[emotion] ?: listOf(emotion.query)
    }

    /** Emotion synonym stems used by [MemeRelevance] (A.4). Lowercase, `.contains` stems. */
    fun keywords(emotion: Emotion): List<String> = emotionKeywords[emotion] ?: listOf(emotion.label.lowercase())

    /** Actor/industry names used for the culture-relevance bonus in [MemeRelevance] (A.4). */
    fun cultureKeywords(culture: MemeCulture): List<String> = when (culture) {
        MemeCulture.SOUTH_INDIAN -> southIndianCultureKeywords
        MemeCulture.GENERIC -> emptyList()
    }

    private val southIndianCultureKeywords = listOf(
        "vadivelu", "goundamani", "senthil", "santhanam", "yogi babu", "soori", "vivek",
        "rajinikanth", "ajith", "vijay", "sivakarthikeyan",
        "brahmanandam", "ali", "sunil", "venu madhav", "allu arjun", "prabhas",
        "jagathy", "salim kumar", "suraj venjaramoodu", "mohanlal", "mammootty",
        "sadhu kokila", "sharan", "yash",
        "tamil", "telugu", "malayalam", "kannada"
    )

    private val emotionKeywords: Map<Emotion, List<String>> = mapOf(
        Emotion.HAPPY to listOf("happy", "smile", "joy", "cheer", "glad", "delight"),
        Emotion.LAUGHING to listOf("laugh", "lol", "haha", "giggle", "chuckle", "rofl"),
        Emotion.EXCITED to listOf("excite", "yay", "hype", "thrill", "pumped", "mass"),
        Emotion.SURPRISED to listOf("surpris", "wow", "whoa", "astonish", "unexpected"),
        Emotion.SHOCKED to listOf("shock", "omg", "stun", "gasp", "jaw drop", "speechless"),
        Emotion.FEARFUL to listOf("scare", "afraid", "fear", "fright", "terrified", "spooked"),
        Emotion.SAD to listOf("sad", "cry", "sorrow", "upset", "heartbroken", "emotional"),
        Emotion.ANGRY to listOf("angry", "mad", "rage", "furious", "fury", "outrage"),
        Emotion.ANNOYED to listOf("annoy", "irritat", "frustrat", "eye roll", "fed up", "cringe"),
        Emotion.FRUSTRATED to listOf("frustrat", "ugh", "argh", "exasperat", "fed up", "ranting"),
        Emotion.DISGUST to listOf("disgust", "eww", "gross", "yuck", "nasty", "ew"),
        Emotion.CONTEMPT to listOf("smug", "side eye", "scoff", "sneer", "disdain", "mock"),
        Emotion.SKEPTICAL to listOf("skeptic", "really", "doubt", "suspicious", "hmm", "unconvinced"),
        Emotion.SLEEPY to listOf("sleep", "tired", "bored", "yawn", "drowsy", "nap"),
        Emotion.KISS to listOf("kiss", "love", "romantic", "smooch", "affection", "cute couple"),
        Emotion.WINK to listOf("wink", "playful", "flirt", "tease", "cheeky", "naughty"),
        Emotion.PUFFED to listOf("puff", "cheek", "silly face", "pout", "funny face"),
        Emotion.SILLY to listOf("silly", "goofy", "tongue", "funny face", "wacky", "crazy face"),
        Emotion.NEUTRAL to listOf("ok", "cool", "calm", "chill", "fine", "swag")
    )

    // A.2/A.3 pool — every entry below was swept live against GIPHY's /v1/gifs/search
    // with the app's own key and confirmed total_count >= 25 (nearly all hit the 500
    // cap). Over-specific phrasing (e.g. "vadivelu annoyed") returns total_count: 0 on
    // that endpoint, so queries here deliberately favour the patterns that measured
    // well: "<name> reaction", "<name> comedy", "<name> <simple emotion word>"
    // (angry/sad/happy/laugh/shock/cry only), and "<industry> comedy reaction" /
    // "kollywood" / "tollywood". [MemeRelevance] re-ranks the results by emotion
    // keyword afterward, so the query itself doesn't need to carry the precise mood.
    private val southIndianPool: Map<Emotion, List<String>> = mapOf(
        Emotion.HAPPY to listOf(
            "vadivelu reaction", "goundamani happy", "brahmanandam happy",
            "santhanam reaction", "yogi babu happy",
            "tamil comedy reaction", "telugu comedy reaction", "kollywood reaction"
        ),
        Emotion.LAUGHING to listOf(
            "vadivelu laugh", "goundamani laugh", "senthil laugh",
            "brahmanandam reaction", "santhanam laugh",
            "tamil comedy reaction", "telugu comedy reaction", "tollywood reaction"
        ),
        Emotion.EXCITED to listOf(
            "vijay reaction", "sivakarthikeyan reaction", "allu arjun reaction",
            "rajinikanth happy",
            "tamil comedy reaction", "telugu comedy reaction", "kollywood reaction"
        ),
        Emotion.SURPRISED to listOf(
            "vadivelu shock", "goundamani shock", "santhanam shock",
            "brahmanandam reaction",
            "tamil comedy reaction", "telugu comedy reaction", "kollywood reaction"
        ),
        Emotion.SHOCKED to listOf(
            "vadivelu shock", "brahmanandam shock", "senthil shock", "soori shock",
            "tamil comedy reaction", "telugu comedy reaction", "tollywood reaction"
        ),
        Emotion.FEARFUL to listOf(
            "vivek shock", "goundamani shock", "salim kumar shock", "sunil shock",
            "tamil comedy reaction", "telugu comedy reaction", "kollywood reaction"
        ),
        Emotion.SAD to listOf(
            "mohanlal sad", "mammootty sad", "rajinikanth sad", "ajith sad",
            "vijay sad", "mammootty cry", "ajith cry", "rajinikanth cry"
        ),
        Emotion.ANGRY to listOf(
            "ajith angry", "mammootty angry", "rajinikanth angry", "vijay angry",
            "yash angry",
            "tamil comedy reaction", "telugu comedy reaction", "kollywood reaction"
        ),
        Emotion.ANNOYED to listOf(
            "vadivelu angry", "goundamani angry", "santhanam angry", "senthil angry",
            "soori angry", "vivek angry", "yogi babu angry", "tamil comedy reaction"
        ),
        Emotion.FRUSTRATED to listOf(
            "brahmanandam angry", "sharan angry", "soori angry", "sunil angry",
            "vivek angry",
            "tamil comedy reaction", "telugu comedy reaction"
        ),
        Emotion.DISGUST to listOf(
            "vadivelu shock", "venu madhav shock", "yogi babu shock",
            "suraj venjaramoodu shock",
            "tamil comedy reaction", "telugu comedy reaction", "kollywood reaction"
        ),
        Emotion.CONTEMPT to listOf(
            "ali angry", "goundamani angry", "mammootty angry", "santhanam angry",
            "sharan angry",
            "tamil comedy reaction", "telugu comedy reaction", "kollywood reaction"
        ),
        Emotion.SKEPTICAL to listOf(
            "mohanlal shock", "sadhu kokila shock", "senthil shock", "sunil shock",
            "vivek shock",
            "tamil comedy reaction", "telugu comedy reaction"
        ),
        Emotion.SLEEPY to listOf(
            "goundamani sad", "salim kumar sad", "vadivelu sad", "venu madhav sad",
            "tamil comedy reaction", "telugu comedy reaction", "kollywood reaction"
        ),
        Emotion.KISS to listOf(
            "ajith happy", "mammootty happy", "mohanlal happy", "vijay reaction",
            "tamil comedy reaction", "telugu comedy reaction", "kollywood reaction"
        ),
        Emotion.WINK to listOf(
            "allu arjun happy", "mohanlal happy", "sivakarthikeyan happy",
            "vijay reaction",
            "tamil comedy reaction", "telugu comedy reaction", "kollywood reaction"
        ),
        Emotion.PUFFED to listOf(
            "goundamani happy", "sharan happy", "vadivelu happy", "yogi babu happy",
            "tamil comedy reaction", "telugu comedy reaction", "kollywood reaction"
        ),
        Emotion.SILLY to listOf(
            "sadhu kokila laugh", "santhanam laugh", "soori laugh", "vadivelu laugh",
            "venu madhav laugh",
            "tamil comedy reaction", "telugu comedy reaction", "kollywood reaction"
        ),
        Emotion.NEUTRAL to listOf(
            "ajith happy", "mammootty happy", "rajinikanth happy", "vijay reaction",
            "tamil comedy reaction", "telugu comedy reaction", "kollywood reaction"
        )
    )
}
