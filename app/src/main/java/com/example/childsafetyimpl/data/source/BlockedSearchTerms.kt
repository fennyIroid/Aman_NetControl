package com.example.childsafetyimpl.data.source

/**
 * What a child must not be able to search for, whichever browser they use.
 *
 * SafeSearch handles explicit imagery, and it handles it better than any list here could. It does
 * nothing about a child researching how to buy a drug, hurt themselves or get a weapon, because
 * those are not adult content and the search engine is right not to treat them as such. This list
 * covers that gap and nothing else.
 *
 * Two rules shaped it, and both exist to keep the false-positive rate low enough that a parent
 * leaves it switched on:
 *
 *  - A single ambiguous word is never enough. "Acid", "shot", "grass" and "high" all appear here
 *    only inside a phrase, because the standalone word belongs to chemistry homework as often as
 *    to anything else.
 *  - Matching is on whole words. Without that, "assessment" matches, "grape" matches, and the
 *    protection is switched off within a day by a parent who has stopped believing it.
 *
 * This is not a claim to be exhaustive. It is a floor, and the screen that claims it says so.
 */
object BlockedSearchTerms {

    private val whitespace = Regex("\\s+")

    /**
     * Categories of its own rather than the web-filtering ones.
     *
     * A site blocklist and a search list are answering different questions. "Drugs" is not a
     * website category any licensed feed would recognise, and mapping it onto the nearest one
     * would make the event log say something untrue about what matched.
     */
    enum class SearchCategory(val label: String, val severe: Boolean = false) {
        ADULT("Adult content"),
        DRUGS("Drugs and alcohol"),
        SELF_HARM("Self-harm", severe = true),
        WEAPONS("Weapons and violence", severe = true),
        GAMBLING("Gambling"),
    }

    data class Term(val text: String, val category: SearchCategory) {
        val isPhrase: Boolean get() = text.contains(' ')
    }

    private val adult = listOf(
        "porn", "porno", "pornhub", "xnxx", "xvideos", "xhamster", "redtube", "youporn",
        "xxx", "nsfw", "hentai", "rule34", "onlyfans", "camgirl", "cam girl", "chaturbate",
        "nude", "nudes", "nudity", "naked", "topless", "bikini", "lingerie", "underwear model",
        "sex", "sexy", "sexting", "boobs", "tits", "nipples", "cleavage", "butt pics",
        "blowjob", "handjob", "orgasm", "masturbate", "masturbation", "erotic", "erotica",
        "milf", "incest", "escort service", "strip club", "stripper", "sugar daddy",
        "hookup app", "dating hookup", "leaked photos", "leaked video",
    )

    private val drugs = listOf(
        "cocaine", "heroin", "methamphetamine", "crystal meth", "fentanyl", "ketamine",
        "mdma", "ecstasy pills", "lsd", "acid tabs", "magic mushrooms", "shrooms",
        "marijuana", "cannabis", "weed", "hash oil", "bong", "dab rig", "vape", "vaping",
        "juul", "nicotine pouches", "how to get high", "how to roll a joint", "buy drugs",
        "buy weed online", "dark web drugs", "drug dealer", "xanax", "oxycodone", "adderall",
        "codeine syrup", "lean drink", "steroids for sale", "how to make alcohol",
    )

    private val selfHarm = listOf(
        "suicide", "kill myself", "how to die", "want to die", "self harm", "self-harm",
        "cutting myself", "hang myself", "overdose on", "painless death", "suicide methods",
        "pro ana", "pro mia", "thinspo", "how to starve",
    )

    private val weapons = listOf(
        "how to make a bomb", "pipe bomb", "molotov", "ghost gun", "buy a gun", "buy a knife",
        "3d printed gun", "how to make a weapon", "silencer for sale", "ammo for sale",
    )

    private val gambling = listOf(
        "online casino", "betting site", "sports betting", "real money poker", "online roulette",
        "csgo gambling", "crypto casino", "free bet",
    )

    /**
     * Terms are normalised as they are declared, so a hyphen or an apostrophe written here can
     * never quietly stop the entry matching anything at all.
     */
    val terms: List<Term> = buildList {
        adult.forEach { add(Term(normalise(it), SearchCategory.ADULT)) }
        drugs.forEach { add(Term(normalise(it), SearchCategory.DRUGS)) }
        selfHarm.forEach { add(Term(normalise(it), SearchCategory.SELF_HARM)) }
        weapons.forEach { add(Term(normalise(it), SearchCategory.WEAPONS)) }
        gambling.forEach { add(Term(normalise(it), SearchCategory.GAMBLING)) }
    }.distinctBy { it.text }

    val count: Int get() = terms.size

    fun countIn(category: SearchCategory): Int = terms.count { it.category == category }

    /**
     * The first term the text matches, or null.
     *
     * A phrase matches as a run of words; a single word matches only as a whole word, so
     * "sextant", "Essex" and "assessment" are left alone. Punctuation and the plus signs a search
     * URL uses are flattened to spaces first, because "buy+weed+online" is the same search as the
     * one typed with spaces.
     */
    fun match(text: String): Term? {
        if (text.isBlank()) return null
        val normalised = normalise(text)
        if (normalised.isBlank()) return null
        val padded = " $normalised "

        return terms.firstOrNull { term ->
            if (term.isPhrase) {
                padded.contains(" ${term.text} ") || normalised.contains(term.text)
            } else {
                padded.contains(" ${term.text} ")
            }
        }
    }

    /** Lowercased, with everything that is not a letter or a digit turned into a separator. */
    fun normalise(text: String): String = buildString {
        text.lowercase().forEach { character ->
            append(if (character.isLetterOrDigit()) character else ' ')
        }
    }.trim().replace(whitespace, " ")
}
