package com.example.childsafetyimpl.platform.enforcement

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.childsafetyimpl.data.prefs.SafetyPreferences
import com.example.childsafetyimpl.di.ApplicationScope
import com.example.childsafetyimpl.domain.model.EventAction
import com.example.childsafetyimpl.domain.model.SafetyEventType
import com.example.childsafetyimpl.domain.repository.SafetyEventRepository
import com.example.childsafetyimpl.platform.network.InternetPauseController
import com.example.childsafetyimpl.platform.permission.PermissionInspector
import com.example.childsafetyimpl.ux.protection.ProtectionRunner
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Restarts protection after a reboot or an app update.
 *
 * Worth having, and worth being honest about: the gap between the device booting and this receiver
 * firing is real, and on manufacturer builds with aggressive startup management the receiver may
 * not fire at all until someone opens the app. The app reports that gap in its event log rather
 * than assuming protection resumed.
 */
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    @Inject lateinit var preferences: SafetyPreferences
    @Inject lateinit var eventRepository: SafetyEventRepository
    @Inject lateinit var permissionInspector: PermissionInspector
    @Inject lateinit var protectionRunner: ProtectionRunner
    @Inject lateinit var internetPauseController: InternetPauseController
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val pendingResult = goAsync()
        scope.launch {
            try {
                val settings = preferences.settings.first()
                if (!settings.protectionEnabled) return@launch

                if (!permissionInspector.hasUsageAccess()) {
                    eventRepository.record(
                        type = SafetyEventType.MONITORING_PERMISSION_LOST,
                        title = "Protection could not restart",
                        detail = "Usage access is no longer granted, so enforcement did not " +
                            "resume after the device restarted.",
                        action = EventAction.NOT_ENFORCEABLE,
                        capabilityId = "app_restriction",
                        dedupeWindowMillis = 60 * 60_000L,
                    )
                    return@launch
                }

                protectionRunner.apply(true)

                // A pause that a restart lifts teaches a child that restarting is how you get
                // the internet back, and the control is worthless from then on.
                internetPauseController.restoreAfterBoot()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
