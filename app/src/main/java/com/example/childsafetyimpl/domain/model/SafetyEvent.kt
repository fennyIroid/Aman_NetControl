package com.example.childsafetyimpl.domain.model

/**
 * The event taxonomy.
 *
 * Every detection in the app resolves to one of these, which is what lets the activity screen stay
 * readable no matter which protection produced the entry.
 */
enum class SafetyEventType(val label: String, val defaultSeverity: EventSeverity) {
    VPN_DETECTED("VPN detected", EventSeverity.HIGH),
    RESTRICTED_APP_OPENED("Restricted app opened", EventSeverity.MEDIUM),
    USAGE_LIMIT_REACHED("Usage limit reached", EventSeverity.MEDIUM),
    RESTRICTED_WEBSITE("Restricted website blocked", EventSeverity.MEDIUM),
    RESTRICTED_SEARCH("Restricted search blocked", EventSeverity.HIGH),
    UNSAFE_IMAGE("Potentially unsafe image", EventSeverity.HIGH),
    UNSAFE_SCREEN("Potentially unsafe screen", EventSeverity.HIGH),
    RESTRICTED_CONTENT_ATTEMPT("Restricted content attempted", EventSeverity.MEDIUM),
    MONITORING_PERMISSION_LOST("Monitoring permission disabled", EventSeverity.CRITICAL),
    MONITORING_STOPPED("Monitoring service stopped", EventSeverity.CRITICAL),
    BYPASS_ATTEMPT("Possible bypass attempt", EventSeverity.CRITICAL),
    CLONE_APP_DETECTED("Cloned app environment detected", EventSeverity.HIGH),
    SHORT_FORM_DETECTED("Short-form feed detected", EventSeverity.LOW),
    COMMUNICATION_SIGNAL("Communication signal", EventSeverity.LOW),
    SCHEDULE_BLOCK("Blocked by schedule", EventSeverity.LOW),
    INTERNET_PAUSED("Internet pause", EventSeverity.INFO),
    ROUTINE_RESTRICTION("Restricted by a routine", EventSeverity.LOW),
    PROTECTION_DEGRADED("Protection degraded", EventSeverity.HIGH),
    FEASIBILITY_PROBE("Feasibility probe run", EventSeverity.INFO),
}

enum class EventSeverity(val label: String, val weight: Int) {
    INFO("Info", 0),
    LOW("Low", 1),
    MEDIUM("Medium", 2),
    HIGH("High", 3),
    CRITICAL("Critical", 4),
}

/** What the app did about the event, recorded separately from what it saw. */
enum class EventAction(val label: String) {
    LOGGED("Logged only"),
    BLOCKED("Blocked"),
    WARNED("Warning shown"),
    BLURRED("Content covered"),
    NOT_ENFORCEABLE("Detected, not enforceable"),
}

data class SafetyEvent(
    val id: Long = 0,
    val type: SafetyEventType,
    val severity: EventSeverity,
    val occurredAt: Long,
    val packageName: String? = null,
    val appLabel: String? = null,
    val title: String,
    /**
     * A short, non-sensitive description. Message bodies, screen content and full URLs never
     * land here; a verdict or a host name does.
     */
    val detail: String,
    val action: EventAction = EventAction.LOGGED,
    val parentNotified: Boolean = false,
    /** Links the event back to the protection that produced it, so the row can be opened. */
    val capabilityId: String? = null,
)
