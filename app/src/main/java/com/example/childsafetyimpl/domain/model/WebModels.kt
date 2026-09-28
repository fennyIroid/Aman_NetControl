package com.example.childsafetyimpl.domain.model

/** Content groupings a parent can switch on without naming individual sites. */
enum class WebCategory(val label: String, val description: String) {
    ADULT(
        "Adult content",
        "Explicit and pornographic sites: about 64,000 from public lists, plus names that are " +
            "plainly adult. Well-known sites are covered; obscure and brand-new ones often are not.",
    ),
    GAMBLING("Gambling", "Betting, casino and wagering destinations."),
    VIOLENCE("Violence & gore", "Graphic violence and shock content."),
    MALWARE("Malware & scams", "Known malicious and phishing destinations."),
    DATING("Dating", "Dating and hookup services."),
    SOCIAL("Social networks", "Feed-based social platforms."),
    STREAMING("Video streaming", "Video and short-form streaming platforms."),
    UNCATEGORISED("Uncategorised", "Everything the local lists do not classify."),
}

/** Whether a rule permits or refuses, and where the rule came from. */
enum class RuleAction { BLOCK, ALLOW }

enum class RuleSource(val label: String) {
    SEEDED_LIST("Bundled list"),
    PARENT("Added by parent"),
}

data class DomainRule(
    val id: Long = 0,
    val pattern: String,
    val category: WebCategory,
    val action: RuleAction,
    val source: RuleSource,
    val enabled: Boolean = true,
)

/** Result of evaluating one host against the rule set. */
data class FilterVerdict(
    val host: String,
    val allowed: Boolean,
    val matchedPattern: String?,
    val category: WebCategory?,
    val reason: String,
)

/** One DNS query the tunnel actually handled, kept in memory for the network lab. */
data class DnsQueryRecord(
    val host: String,
    val blocked: Boolean,
    val category: WebCategory?,
    val atMillis: Long,
)
