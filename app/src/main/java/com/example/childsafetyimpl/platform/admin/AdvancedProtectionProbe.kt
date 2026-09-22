package com.example.childsafetyimpl.platform.admin

import android.content.Context
import android.os.Build
import android.security.advancedprotection.AdvancedProtectionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether Android's Advanced Protection Mode is switched on.
 *
 * Advanced Protection is a one-switch hardening mode, from Android 16. Among other things it
 * withdraws accessibility access from every app that is not a genuine accessibility tool, and it
 * will not let that access be granted again while the mode is on. A parental control is not an
 * accessibility tool, so every protection that reads the screen stops.
 *
 * Nothing about that is tampering and nothing this app does can prevent it, so the product's only
 * job is to know it happened and say so. Without this probe the revoked grant is indistinguishable
 * from a child switching accessibility off, and the parent is told their child tampered with the
 * phone when a security setting did it.
 */
@Singleton
class AdvancedProtectionProbe @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    enum class Mode {
        ON,
        OFF,

        /** The Android version has no Advanced Protection Mode. */
        UNSUPPORTED,

        /** The mode exists but could not be read on this build. Never reported as off. */
        UNKNOWN,
    }

    fun current(): Mode {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return Mode.UNSUPPORTED
        return runCatching {
            val manager = context.getSystemService(AdvancedProtectionManager::class.java)
                ?: return Mode.UNKNOWN
            if (manager.isAdvancedProtectionEnabled) Mode.ON else Mode.OFF
        }.getOrDefault(Mode.UNKNOWN)
    }
}
