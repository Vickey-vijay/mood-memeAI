package com.moodboard.keyboard.stickers

/**
 * Meme style categories the user can pick in Setup (client requirement: "give us some
 * options to type our fav! For example cartoons, cinema, baby, others — any category you
 * can provide, that's also fine. He can choose in settings"). The final search query fed
 * to every [MemeSource] is "<emotion word> <category term>" (see
 * [MemeQueryBank.buildQuery]) — [ANYTHING] carries no term, so the query is just the mood
 * word alone. A non-blank `Prefs.memeCategoryCustom` free-text value always overrides
 * whichever preset is selected here.
 *
 * Declaration order MUST stay in sync with `R.array.meme_category_options` in
 * strings.xml — [ui.SetupActivity]'s spinner maps position <-> enum by ordinal.
 */
enum class MemeCategory(val term: String) {
    ANYTHING(""),
    CINEMA("cinema"),
    CARTOONS("cartoon"),
    ANIME("anime"),
    BABIES("baby"),
    ANIMALS("animal"),
    CELEBRITIES("celebrity"),
    SPORTS("sports"),
    REACTIONS("reaction")
}
