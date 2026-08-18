package com.moodboard.keyboard.stickers

import com.moodboard.keyboard.emotion.Emotion

/**
 * SPEC_V4 (client bug report, 2026-08-18) — regional culture packs for meme sourcing.
 *
 * The client is in Tamil Nadu, not a Telugu state. The pre-SPEC_V4 [MemeCulture] had a
 * single `SOUTH_INDIAN` bucket whose per-emotion query pools mixed Tamil, Telugu,
 * Malayalam and Kannada actor names together — a random pick would just as often surface
 * a Telugu actor ("some comedy actor reaction, I don't even know him") as a Tamil one.
 * [MemeCulture.TAMIL] is now the default and is built from Tamil/Kollywood sources only;
 * the other regional packs still exist (so a Telugu/Malayalam/Kannada user isn't left
 * with nothing) but are never mixed into TAMIL's pool.
 */
enum class MemeCulture { TAMIL, TELUGU, MALAYALAM, KANNADA, ALL_SOUTH_INDIAN, GLOBAL }

object MemeQueryBank {

    /** Pool of candidate search queries for [emotion] in the given [culture] (A.2/A.3). */
    fun queries(emotion: Emotion, culture: MemeCulture): List<String> = when (culture) {
        MemeCulture.GLOBAL -> listOf(emotion.query)
        MemeCulture.TAMIL -> tamilPool[emotion] ?: listOf(emotion.query)
        MemeCulture.TELUGU -> teluguPool[emotion] ?: listOf(emotion.query)
        MemeCulture.MALAYALAM -> malayalamPool[emotion] ?: listOf(emotion.query)
        MemeCulture.KANNADA -> kannadaPool[emotion] ?: listOf(emotion.query)
        MemeCulture.ALL_SOUTH_INDIAN -> (tamilPool[emotion].orEmpty() +
            teluguPool[emotion].orEmpty() + malayalamPool[emotion].orEmpty() +
            kannadaPool[emotion].orEmpty()).ifEmpty { listOf(emotion.query) }
    }

    /** Emotion synonym stems used by [MemeRelevance] (A.4). Lowercase, `.contains` stems. */
    fun keywords(emotion: Emotion): List<String> = emotionKeywords[emotion] ?: listOf(emotion.label.lowercase())

    /**
     * Culture marker tokens used by [MemeRelevance]'s hard gate (SPEC_V4): for any
     * non-[MemeCulture.GLOBAL] culture, a result must contain at least one of these in its
     * title/tags to survive at all. [MemeCulture.GLOBAL] has no markers - nothing is gated.
     */
    fun cultureKeywords(culture: MemeCulture): List<String> = when (culture) {
        MemeCulture.TAMIL -> tamilCultureKeywords
        MemeCulture.TELUGU -> teluguCultureKeywords
        MemeCulture.MALAYALAM -> malayalamCultureKeywords
        MemeCulture.KANNADA -> kannadaCultureKeywords
        MemeCulture.ALL_SOUTH_INDIAN -> tamilCultureKeywords + teluguCultureKeywords +
            malayalamCultureKeywords + kannadaCultureKeywords
        MemeCulture.GLOBAL -> emptyList()
    }

    // Validated live against GIPHY /v1/gifs/search (app's own key) 2026-08-18 - see the
    // sweep table in the delivery notes. Every query below measured >= 52% of its titles
    // carrying a genuine Tamil marker (mostly 60-96%); several plausible-looking
    // actor+emotion combos measured badly and were deliberately left out - "yogi babu
    // shock/angry/reaction/comedy/happy" (16-20%: GIPHY fuzzy-matches "yogi" against Yogi
    // Adityanath/Yogi Bear and "babu" against Mahesh Babu), "vivek shock/angry/reaction"
    // (28-36%: collides with the US Surgeon General and the "grown-ish" character both
    // named Vivek), "senthil shock/angry/reaction" (36%: collides with unrelated
    // "Superstar Rajinikanth ... GIF by RajiniGifs" filler and generic "Animated GIF"),
    // "sathyaraj sad/angry/comedy/reaction" (32-44%) and "vijay angry" (32%: dominated by
    // an unrelated Hindi "Web Series Omg GIF by ZEE5" result set). Where an actor name
    // alone was too weak a signal, it was paired with "tamil" or dropped as a query term
    // entirely (it can still appear incidentally and will pass the culture gate).
    private val tamilCultureKeywords = listOf(
        "tamil", "tamilmeme", "kollywood", "vadivelu", "vadivel", "goundamani", "senthil",
        "santhanam", "yogi babu", "soori", "vivek", "sathyaraj", "rajinikanth", "rajini",
        "ajith", "vijay", "kamal", "dhanush", "sivakarthikeyan", "kovaisarala",
        "loosu paithiyam", "thalaiva", "superstar"
    )

    private val teluguCultureKeywords = listOf(
        "telugu", "tollywood", "brahmanandam", "ali", "sunil", "venu madhav",
        "allu arjun", "prabhas", "mahesh babu", "pawan kalyan"
    )

    private val malayalamCultureKeywords = listOf(
        "malayalam", "mollywood", "jagathy", "salim kumar", "suraj venjaramoodu",
        "mohanlal", "mammootty"
    )

    private val kannadaCultureKeywords = listOf(
        "kannada", "sandalwood", "sadhu kokila", "sharan", "yash"
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
        Emotion.PUFFED to listOf("puff", "cheek", "silly face", "pout", "funny face", "mokka"),
        Emotion.SILLY to listOf("silly", "goofy", "tongue", "funny face", "wacky", "crazy face", "mokka"),
        Emotion.NEUTRAL to listOf("ok", "cool", "calm", "chill", "fine", "swag")
    )

    // TAMIL pool - Kollywood only. Every query here measured >= 52% Tamil-marker hit rate
    // live against GIPHY (see sweep notes above); most measure 70-96%. "mokka" (Tamil
    // slang for a corny/silly joke) tests very well for PUFFED/SILLY (80-96%).
    private val tamilPool: Map<Emotion, List<String>> = mapOf(
        Emotion.HAPPY to listOf(
            "ajith happy", "dhanush happy", "sivakarthikeyan happy", "vadivelu happy",
            "goundamani happy", "tamil meme", "kollywood comedy"
        ),
        Emotion.LAUGHING to listOf(
            "vadivelu comedy", "goundamani comedy", "santhanam comedy", "soori comedy",
            "dhanush comedy", "sivakarthikeyan comedy", "tamilmeme"
        ),
        Emotion.EXCITED to listOf(
            "rajinikanth mass", "ajith mass", "vijay reaction", "sivakarthikeyan reaction",
            "kollywood comedy"
        ),
        Emotion.SURPRISED to listOf(
            "vadivelu shock", "goundamani shock", "santhanam shock", "soori shock",
            "tamil meme"
        ),
        Emotion.SHOCKED to listOf(
            "vadivelu shock", "goundamani shock", "santhanam shock", "soori shock",
            "kollywood shock"
        ),
        Emotion.FEARFUL to listOf(
            "goundamani shock", "santhanam shock", "soori shock", "vadivelu shock",
            "tamil troll"
        ),
        Emotion.SAD to listOf(
            "rajinikanth sad", "ajith sad", "vijay sad", "dhanush sad",
            "kamal haasan sad", "sivakarthikeyan sad", "kollywood sad"
        ),
        Emotion.ANGRY to listOf(
            "ajith angry", "rajinikanth angry", "vadivelu angry", "tamil angry"
        ),
        Emotion.ANNOYED to listOf(
            "goundamani angry", "santhanam angry", "soori angry", "vadivelu angry",
            "tamil angry"
        ),
        Emotion.FRUSTRATED to listOf(
            "santhanam angry", "soori angry", "goundamani angry", "tamil angry"
        ),
        Emotion.DISGUST to listOf(
            "vadivelu shock", "goundamani shock", "santhanam shock", "kollywood shock"
        ),
        Emotion.CONTEMPT to listOf(
            "goundamani angry", "santhanam angry", "rajinikanth angry", "ajith angry"
        ),
        Emotion.SKEPTICAL to listOf(
            "santhanam shock", "vadivelu shock", "goundamani shock", "tamil troll"
        ),
        Emotion.SLEEPY to listOf(
            "vadivelu sleepy", "vadivelu sad", "goundamani sad", "santhanam sad",
            "kollywood sad"
        ),
        Emotion.KISS to listOf(
            "kollywood love", "tamil love", "ajith happy", "dhanush happy"
        ),
        Emotion.WINK to listOf(
            "vijay wink", "vadivelu funny", "sivakarthikeyan happy", "tamil love"
        ),
        Emotion.PUFFED to listOf(
            "ajith mokka", "santhanam mokka", "vadivelu mokka", "goundamani happy"
        ),
        Emotion.SILLY to listOf(
            "vadivelu funny", "santhanam mokka", "vadivelu mokka", "ajith mokka",
            "tamilmeme"
        ),
        Emotion.NEUTRAL to listOf(
            "ajith reaction", "rajinikanth reaction", "vijay reaction", "dhanush reaction",
            "kamal haasan reaction", "tamil comedy"
        )
    )

    // TELUGU/MALAYALAM/KANNADA pools kept available (not shown to a TAMIL-default user,
    // never mixed into [tamilPool]) but not swept with the same rigor as TAMIL - the
    // client's complaint was specifically about Tamil accuracy.
    private val teluguPool: Map<Emotion, List<String>> = mapOf(
        Emotion.HAPPY to listOf("brahmanandam happy", "mahesh babu happy", "telugu comedy reaction", "tollywood"),
        Emotion.LAUGHING to listOf("brahmanandam reaction", "ali comedy", "venu madhav comedy", "telugu comedy reaction"),
        Emotion.EXCITED to listOf("allu arjun reaction", "prabhas reaction", "mahesh babu mass", "tollywood"),
        Emotion.SURPRISED to listOf("brahmanandam shock", "sunil shock", "telugu comedy reaction"),
        Emotion.SHOCKED to listOf("brahmanandam shock", "venu madhav shock", "telugu comedy reaction"),
        Emotion.FEARFUL to listOf("sunil shock", "venu madhav shock", "telugu comedy reaction"),
        Emotion.SAD to listOf("mahesh babu sad", "prabhas sad", "pawan kalyan sad", "telugu sad"),
        Emotion.ANGRY to listOf("pawan kalyan angry", "mahesh babu angry", "telugu angry"),
        Emotion.ANNOYED to listOf("brahmanandam angry", "sunil angry", "telugu comedy reaction"),
        Emotion.FRUSTRATED to listOf("brahmanandam angry", "venu madhav angry", "telugu comedy reaction"),
        Emotion.DISGUST to listOf("venu madhav shock", "brahmanandam shock", "telugu comedy reaction"),
        Emotion.CONTEMPT to listOf("ali angry", "sunil angry", "telugu comedy reaction"),
        Emotion.SKEPTICAL to listOf("sunil shock", "venu madhav shock", "telugu comedy reaction"),
        Emotion.SLEEPY to listOf("venu madhav sad", "sunil sad", "telugu sad"),
        Emotion.KISS to listOf("allu arjun happy", "prabhas happy", "tollywood love"),
        Emotion.WINK to listOf("allu arjun happy", "mahesh babu happy", "telugu comedy reaction"),
        Emotion.PUFFED to listOf("brahmanandam happy", "venu madhav happy", "telugu comedy reaction"),
        Emotion.SILLY to listOf("brahmanandam comedy", "ali comedy", "telugu comedy reaction"),
        Emotion.NEUTRAL to listOf("mahesh babu reaction", "prabhas reaction", "telugu comedy reaction")
    )

    private val malayalamPool: Map<Emotion, List<String>> = mapOf(
        Emotion.HAPPY to listOf("mohanlal happy", "jagathy comedy", "malayalam comedy reaction", "mollywood"),
        Emotion.LAUGHING to listOf("jagathy comedy", "salim kumar comedy", "malayalam comedy reaction"),
        Emotion.EXCITED to listOf("mammootty reaction", "mohanlal reaction", "mollywood"),
        Emotion.SURPRISED to listOf("jagathy shock", "suraj venjaramoodu shock", "malayalam comedy reaction"),
        Emotion.SHOCKED to listOf("jagathy shock", "salim kumar shock", "malayalam comedy reaction"),
        Emotion.FEARFUL to listOf("salim kumar shock", "suraj venjaramoodu shock", "malayalam comedy reaction"),
        Emotion.SAD to listOf("mohanlal sad", "mammootty sad", "malayalam sad"),
        Emotion.ANGRY to listOf("mohanlal angry", "mammootty angry", "malayalam angry"),
        Emotion.ANNOYED to listOf("jagathy angry", "salim kumar angry", "malayalam comedy reaction"),
        Emotion.FRUSTRATED to listOf("suraj venjaramoodu angry", "jagathy angry", "malayalam comedy reaction"),
        Emotion.DISGUST to listOf("suraj venjaramoodu shock", "jagathy shock", "malayalam comedy reaction"),
        Emotion.CONTEMPT to listOf("mammootty angry", "mohanlal angry", "malayalam comedy reaction"),
        Emotion.SKEPTICAL to listOf("jagathy shock", "salim kumar shock", "malayalam comedy reaction"),
        Emotion.SLEEPY to listOf("salim kumar sad", "jagathy sad", "malayalam sad"),
        Emotion.KISS to listOf("mohanlal happy", "mammootty happy", "mollywood love"),
        Emotion.WINK to listOf("mohanlal happy", "mammootty happy", "malayalam comedy reaction"),
        Emotion.PUFFED to listOf("jagathy happy", "salim kumar happy", "malayalam comedy reaction"),
        Emotion.SILLY to listOf("jagathy comedy", "suraj venjaramoodu comedy", "malayalam comedy reaction"),
        Emotion.NEUTRAL to listOf("mohanlal reaction", "mammootty reaction", "malayalam comedy reaction")
    )

    private val kannadaPool: Map<Emotion, List<String>> = mapOf(
        Emotion.HAPPY to listOf("sharan happy", "yash happy", "kannada comedy reaction", "sandalwood"),
        Emotion.LAUGHING to listOf("sadhu kokila comedy", "sharan comedy", "kannada comedy reaction"),
        Emotion.EXCITED to listOf("yash reaction", "sandalwood"),
        Emotion.SURPRISED to listOf("sadhu kokila shock", "sharan shock", "kannada comedy reaction"),
        Emotion.SHOCKED to listOf("sadhu kokila shock", "sharan shock", "kannada comedy reaction"),
        Emotion.FEARFUL to listOf("sadhu kokila shock", "kannada comedy reaction"),
        Emotion.SAD to listOf("yash sad", "kannada sad"),
        Emotion.ANGRY to listOf("yash angry", "kannada angry"),
        Emotion.ANNOYED to listOf("sharan angry", "sadhu kokila angry", "kannada comedy reaction"),
        Emotion.FRUSTRATED to listOf("sharan angry", "kannada comedy reaction"),
        Emotion.DISGUST to listOf("sadhu kokila shock", "kannada comedy reaction"),
        Emotion.CONTEMPT to listOf("sharan angry", "kannada comedy reaction"),
        Emotion.SKEPTICAL to listOf("sadhu kokila shock", "kannada comedy reaction"),
        Emotion.SLEEPY to listOf("sharan sad", "kannada sad"),
        Emotion.KISS to listOf("yash happy", "sandalwood love"),
        Emotion.WINK to listOf("yash happy", "kannada comedy reaction"),
        Emotion.PUFFED to listOf("sharan happy", "sadhu kokila happy", "kannada comedy reaction"),
        Emotion.SILLY to listOf("sadhu kokila comedy", "sharan comedy", "kannada comedy reaction"),
        Emotion.NEUTRAL to listOf("yash reaction", "kannada comedy reaction")
    )
}
