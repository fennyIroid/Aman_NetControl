package com.example.childsafetyimpl.platform.alerts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.childsafetyimpl.MainActivity
import com.example.childsafetyimpl.R
import com.example.childsafetyimpl.domain.model.EventSeverity
import com.example.childsafetyimpl.platform.projection.CaptureConsentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parent alerts and the foreground-service notice.
 *
 * Three channels, deliberately separated so a parent can silence the persistent monitoring notice
 * without also silencing genuine alerts. The monitoring channel is set to low importance because
 * Android requires the notification to exist, not to be loud.
 */
@Singleton
class SafetyNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val notificationManager: NotificationManager,
) {

    init {
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        listOf(
            NotificationChannel(
                CHANNEL_ALERTS,
                "Safety alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Serious safety events that need a parent's attention." },
            NotificationChannel(
                CHANNEL_MONITORING,
                "Protection status",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "The ongoing notice Android requires while monitoring runs."
                setShowBadge(false)
            },
            NotificationChannel(
                CHANNEL_BLOCK,
                "Restrictions",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Shown to the child when a limit or schedule applies." },
        ).forEach(notificationManager::createNotificationChannel)
    }

    fun postSafetyAlert(title: String, detail: String, severity: EventSeverity) {
        if (!canPost()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(
                if (severity == EventSeverity.CRITICAL) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT,
            )
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        notificationManager.notify(alertId++, notification)
    }

    /** The ongoing notification the monitoring foreground service is required to show. */
    fun monitoringNotification(contentText: String): android.app.Notification =
        NotificationCompat.Builder(context, CHANNEL_MONITORING)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle("Child safety protection is on")
            .setContentText(contentText)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())
            .build()

    fun postBlockNotice(appLabel: String, reason: String) {
        if (!canPost()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_BLOCK)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle("$appLabel is paused")
            .setContentText(reason)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        notificationManager.notify(BLOCK_NOTICE_ID, notification)
    }

    fun clearBlockNotice() = notificationManager.cancel(BLOCK_NOTICE_ID)

    /**
     * Offers a finished capture session back.
     *
     * Screen capture consent lasts exactly one session and no app can hold it, so video blurring
     * stops on its own regularly: a reboot, a call, the parent switching to another capture app.
     * The protection screen saying "not watching" only helps a parent already looking at it, and
     * they are usually not. This notification is the only route back that reaches them where they
     * are, and it opens the consent dialog directly rather than the app.
     */
    fun postCaptureResumeNotice() {
        if (!canPost()) return
        val resume = PendingIntent.getActivity(
            context,
            1,
            CaptureConsentActivity.intent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val detail = "Android hands out screen capture one session at a time. Tap to start " +
            "watching video again."
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle("Video blurring stopped watching")
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(resume)
            .addAction(0, "Start watching", resume)
            .build()
        notificationManager.notify(CAPTURE_RESUME_ID, notification)
    }

    fun clearCaptureResumeNotice() = notificationManager.cancel(CAPTURE_RESUME_ID)

    /**
     * The child-facing notice while the internet is paused.
     *
     * Ongoing and not dismissible on purpose. Every app on the device is about to start failing
     * to reach the network, and without this the child is reading connection errors and drawing
     * the obvious wrong conclusion, which is that the phone is broken. This is the one thing
     * standing between a deliberate parental control and a support call.
     *
     * It is worded for the child, not the parent: what is happening and that it is temporary,
     * with no mention of rules, scores or which protection produced it.
     */
    fun postInternetPausedNotice() {
        if (!canPost()) return
        val detail = "A parent paused the internet on this device. Apps that work offline still " +
            "work. Nothing is broken and it can be turned back on at any time."
        val notification = NotificationCompat.Builder(context, CHANNEL_BLOCK)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle("Internet is paused")
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(openAppIntent())
            .build()
        notificationManager.notify(INTERNET_PAUSED_ID, notification)
    }

    fun clearInternetPausedNotice() = notificationManager.cancel(INTERNET_PAUSED_ID)

    private fun canPost(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private var alertId = 2000

    companion object {
        const val CHANNEL_ALERTS = "safety_alerts"
        const val CHANNEL_MONITORING = "safety_monitoring"
        const val CHANNEL_BLOCK = "safety_restrictions"
        const val MONITORING_NOTIFICATION_ID = 1001
        const val CAPTURE_NOTIFICATION_ID = 1002
        const val VPN_NOTIFICATION_ID = 1003
        private const val BLOCK_NOTICE_ID = 1500
        private const val CAPTURE_RESUME_ID = 1501
        private const val INTERNET_PAUSED_ID = 1502
    }
}
