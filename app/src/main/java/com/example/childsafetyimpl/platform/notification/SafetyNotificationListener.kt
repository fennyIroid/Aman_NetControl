package com.example.childsafetyimpl.platform.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.childsafetyimpl.data.local.CommunicationSignalDao
import com.example.childsafetyimpl.data.local.CommunicationSignalEntity
import com.example.childsafetyimpl.data.prefs.SafetyPreferences
import com.example.childsafetyimpl.data.source.KnownPackages
import com.example.childsafetyimpl.di.ApplicationScope
import com.example.childsafetyimpl.domain.model.EventAction
import com.example.childsafetyimpl.domain.model.EventSeverity
import com.example.childsafetyimpl.domain.model.SafetyEventType
import com.example.childsafetyimpl.domain.repository.SafetyEventRepository
import com.example.childsafetyimpl.platform.usage.AppInventoryProvider
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalTime
import javax.inject.Inject

/**
 * Communication signals from notifications.
 *
 * The design constraint here is stricter than the platform's. Android hands over the full title
 * and text of every notification; this service reduces each one to a shape and a verdict before
 * anything is stored. The message body is scored in memory and then dropped. The rule is to avoid storing message content at all, and the only way to honour that is to
 * never write it in the first place.
 *
 * The screen for this reports which apps actually expose usable content, which post
 * placeholders instead, and how much of the picture is lost either way.
 */
@AndroidEntryPoint
class SafetyNotificationListener : NotificationListenerService() {

    @Inject lateinit var signalDao: CommunicationSignalDao
    @Inject lateinit var eventRepository: SafetyEventRepository
    @Inject lateinit var inventory: AppInventoryProvider
    @Inject lateinit var preferences: SafetyPreferences
    @Inject lateinit var riskScorer: MessageRiskScorer

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onListenerConnected() {
        super.onListenerConnected()
        State.setConnected(true)
    }

    override fun onListenerDisconnected() {
        State.setConnected(false)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val packageName = sbn.packageName ?: return
        if (packageName == this.packageName) return

        val isCommunicationApp = packageName in KnownPackages.communication
        State.recordSeen(packageName, isCommunicationApp)
        if (!isCommunicationApp) return

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

        // Group summaries collapse several messages into one useless string. Counting them as
        // individual signals would inflate every number in the communication report.
        val isGroupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        if (isGroupSummary) {
            State.recordSuppressed(SuppressionReason.GROUP_SUMMARY)
            return
        }

        // Apps that hide sensitive content post a placeholder instead of the message. This is the
        // single most common reason notification monitoring returns nothing useful.
        if (isPlaceholder(text)) {
            State.recordSuppressed(SuppressionReason.CONTENT_HIDDEN)
            return
        }

        val verdict = riskScorer.score(text)
        val now = System.currentTimeMillis()
        val hour = LocalTime.now().hour
        val lateNight = hour >= 23 || hour < 6

        scope.launch {
            if (!preferences.settings.first().notificationSignalsEnabled) return@launch

            signalDao.insert(
                CommunicationSignalEntity(
                    packageName = packageName,
                    appLabel = inventory.labelFor(packageName),
                    postedAt = now,
                    // A stable hash, so repeat contact can be counted without keeping the name.
                    senderHash = title.hashCode().toString(16),
                    messageLength = text.length,
                    riskScore = verdict.score,
                    riskTags = verdict.tags.joinToString(","),
                    isLateNight = lateNight,
                )
            )

            if (verdict.score >= MessageRiskScorer.ALERT_THRESHOLD) {
                eventRepository.record(
                    type = SafetyEventType.COMMUNICATION_SIGNAL,
                    title = "Concerning language in a message",
                    // The message itself is deliberately absent. Only the categories that matched.
                    detail = "Risk indicators matched: ${verdict.tags.joinToString(", ")}. " +
                        "The message text is not stored.",
                    packageName = packageName,
                    appLabel = inventory.labelFor(packageName),
                    severity = EventSeverity.HIGH,
                    action = EventAction.LOGGED,
                    capabilityId = "notification_monitoring",
                    dedupeWindowMillis = 5 * 60_000L,
                )
            }
        }
    }

    private fun isPlaceholder(text: String): Boolean {
        if (text.isBlank()) return true
        val normalised = text.lowercase()
        return PLACEHOLDERS.any { normalised.contains(it) }
    }

    /**
     * Observable state for the notification lab.
     *
     * Held statically because the system, not Hilt, decides when this service exists, and the lab
     * needs to show what happened even when the service is not currently bound.
     */
    object State {
        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        private val _observations = MutableStateFlow(Observations())
        val observations: StateFlow<Observations> = _observations.asStateFlow()

        fun setConnected(value: Boolean) { _connected.value = value }

        fun recordSeen(packageName: String, isCommunicationApp: Boolean) {
            _observations.update {
                it.copy(
                    totalNotifications = it.totalNotifications + 1,
                    communicationNotifications = it.communicationNotifications +
                        if (isCommunicationApp) 1 else 0,
                    sourcePackages = (it.sourcePackages + packageName).take(60).toSet(),
                )
            }
        }

        fun recordSuppressed(reason: SuppressionReason) {
            _observations.update {
                when (reason) {
                    SuppressionReason.GROUP_SUMMARY ->
                        it.copy(groupSummariesSkipped = it.groupSummariesSkipped + 1)
                    SuppressionReason.CONTENT_HIDDEN ->
                        it.copy(hiddenContentSkipped = it.hiddenContentSkipped + 1)
                }
            }
        }

        fun reset() { _observations.value = Observations() }
    }

    enum class SuppressionReason { GROUP_SUMMARY, CONTENT_HIDDEN }

    data class Observations(
        val totalNotifications: Int = 0,
        val communicationNotifications: Int = 0,
        val groupSummariesSkipped: Int = 0,
        val hiddenContentSkipped: Int = 0,
        val sourcePackages: Set<String> = emptySet(),
    ) {
        /** The share of communication notifications that yielded nothing readable. */
        val lossRate: Float
            get() {
                val attempted = communicationNotifications
                if (attempted == 0) return 0f
                return (groupSummariesSkipped + hiddenContentSkipped).toFloat() / attempted
            }
    }

    private companion object {
        val PLACEHOLDERS = listOf(
            "new message", "sent you a message", "messages from",
            "content hidden", "notification hidden", "checking for new messages",
            "you have a new message", "new messages",
        )
    }
}
