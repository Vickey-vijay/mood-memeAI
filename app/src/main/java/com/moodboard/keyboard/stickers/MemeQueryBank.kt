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

    private val southIndianPool: Map<Emotion, List<String>> = mapOf(
        Emotion.HAPPY to listOf(
            "vadivelu happy", "goundamani smile", "brahmanandam joyful",
            "yogi babu happy reaction", "tamil comedy happy reaction",
            "telugu comedy cheerful reaction", "mohanlal smile reaction",
            "prabhas happy fan moment"
        ),
        Emotion.LAUGHING to listOf(
            "vadivelu laughing", "goundamani lol", "senthil laughing scene",
            "brahmanandam laughing", "yogi babu lol reaction",
            "tamil comedy laughing scene", "telugu comedy lol reaction",
            "salim kumar laughing"
        ),
        Emotion.EXCITED to listOf(
            "vijay excited fan", "sivakarthikeyan excited", "allu arjun hype",
            "rajinikanth style excited", "tamil mass excited reaction",
            "telugu comedy excited reaction", "yash excited fan moment"
        ),
        Emotion.SURPRISED to listOf(
            "vadivelu surprised", "goundamani shocked wow", "brahmanandam surprised",
            "ali surprised reaction", "tamil comedy surprised reaction",
            "telugu comedy wow reaction", "mammootty surprised look"
        ),
        Emotion.SHOCKED to listOf(
            "vadivelu shocked face", "santhanam shocked", "brahmanandam omg reaction",
            "venu madhav shocked", "tamil comedy shocked reaction",
            "telugu comedy omg reaction", "sadhu kokila shocked"
        ),
        Emotion.FEARFUL to listOf(
            "vivek scared reaction", "goundamani afraid", "ali scared comedy",
            "sunil scared reaction", "tamil comedy scared reaction",
            "telugu comedy afraid reaction", "salim kumar scared"
        ),
        Emotion.SAD to listOf(
            "mohanlal sad scene", "mammootty emotional scene", "ajith sad dialogue",
            "rajinikanth emotional scene", "tamil movie sad scene",
            "telugu movie sad scene", "malayalam movie emotional scene"
        ),
        Emotion.ANGRY to listOf(
            "rajinikanth angry dialogue", "ajith angry scene", "vijay angry mass dialogue",
            "mammootty angry scene", "tamil movie angry dialogue",
            "telugu movie angry scene", "yash angry scene kannada"
        ),
        Emotion.ANNOYED to listOf(
            "vadivelu annoyed", "goundamani irritated", "tamil comedy irritated reaction",
            "brahmanandam annoyed", "yogi babu irritated",
            "telugu comedy annoyed", "mohanlal irritated"
        ),
        Emotion.FRUSTRATED to listOf(
            "soori frustrated reaction", "vivek ugh reaction", "sunil frustrated comedy",
            "tamil comedy frustrated reaction", "telugu comedy ugh reaction",
            "sharan frustrated scene", "malayalam comedy frustrated reaction"
        ),
        Emotion.DISGUST to listOf(
            "yogi babu disgusted reaction", "vadivelu eww reaction", "venu madhav disgusted",
            "tamil comedy disgusted reaction", "telugu comedy eww reaction",
            "suraj venjaramoodu disgusted", "kannada comedy disgusted reaction"
        ),
        Emotion.CONTEMPT to listOf(
            "santhanam smug reaction", "goundamani side eye", "ali smug comedy",
            "tamil comedy smug reaction", "telugu comedy side eye reaction",
            "mammootty smug look", "sharan smug reaction"
        ),
        Emotion.SKEPTICAL to listOf(
            "vivek skeptical reaction", "senthil really reaction", "sunil skeptical comedy",
            "tamil comedy skeptical reaction", "telugu comedy really reaction",
            "mohanlal skeptical look", "sadhu kokila skeptical"
        ),
        Emotion.SLEEPY to listOf(
            "goundamani sleepy comedy", "vadivelu tired reaction", "venu madhav sleepy",
            "tamil comedy sleepy reaction", "telugu comedy tired reaction",
            "salim kumar bored comedy", "kannada comedy sleepy reaction"
        ),
        Emotion.KISS to listOf(
            "vijay romantic scene", "mohanlal love scene", "mammootty romantic dialogue",
            "ajith kiss scene", "tamil movie romantic scene",
            "telugu movie love scene", "malayalam movie romantic scene"
        ),
        Emotion.WINK to listOf(
            "vijay wink style", "sivakarthikeyan playful wink", "allu arjun wink style",
            "tamil movie wink scene", "telugu movie playful reaction",
            "mohanlal wink expression", "yash playful style"
        ),
        Emotion.PUFFED to listOf(
            "vadivelu cheek puff comedy", "goundamani silly face", "yogi babu funny face",
            "tamil comedy funny face reaction", "telugu comedy silly face",
            "sharan funny face comedy", "kannada comedy silly reaction"
        ),
        Emotion.SILLY to listOf(
            "vadivelu silly comedy", "santhanam goofy reaction", "soori silly face",
            "tamil comedy goofy reaction", "telugu comedy silly reaction",
            "venu madhav goofy comedy", "sadhu kokila silly reaction"
        ),
        Emotion.NEUTRAL to listOf(
            "rajinikanth style cool", "ajith cool look", "vijay swag cool reaction",
            "tamil movie cool style", "telugu movie swag reaction",
            "mammootty calm look", "yash cool style reaction"
        )
    )
}
