package com.example.childsafetyimpl.platform.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What stays usable while another VPN is up.
 *
 * The lockout blocks every app on the phone, so the failures worth guarding against are the two
 * that make it unbearable or pointless: locking the way out, and locking a child out of calling
 * for help.
 */
class VpnLockoutTest {

    private val launchers = setOf("com.google.android.apps.nexuslauncher")

    @Test
    fun `Settings stays open, because that is where the VPN is switched off`() {
        listOf("com.android.settings", "com.samsung.android.settings").forEach {
            assertTrue(it, VpnLockout.isExempt(it, launchers))
        }
    }

    @Test
    fun `calling and emergency are never locked`() {
        listOf(
            "com.android.phone",
            "com.google.android.dialer",
            "com.samsung.android.dialer",
            "com.android.server.telecom",
            "com.android.emergency",
            "com.google.android.contacts",
        ).forEach { assertTrue(it, VpnLockout.isExempt(it, launchers)) }
    }

    @Test
    fun `the home screen is never locked, whatever launcher the device has`() {
        assertTrue(VpnLockout.isExempt("com.google.android.apps.nexuslauncher", launchers))
        assertTrue(VpnLockout.isExempt("com.example.customlauncher", setOf("com.example.customlauncher")))
        assertTrue(VpnLockout.isExempt("com.android.systemui", emptySet()))
    }

    @Test
    fun `everything a VPN would be used for is locked`() {
        listOf(
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.google.android.youtube",
            "com.instagram.android",
            "com.zhiliaoapp.musically",
            "com.whatsapp",
            "com.nordvpn.android",
        ).forEach { assertFalse("$it must be locked", VpnLockout.isExempt(it, launchers)) }
    }

    @Test
    fun `a prefix never swallows an unrelated package`() {
        // "com.android.phone" must not exempt "com.android.phonebrowser". A sloppy startsWith
        // would turn the emergency exemption into a way around the lock.
        assertFalse(VpnLockout.isExempt("com.android.phonebrowser", launchers))
        assertFalse(VpnLockout.isExempt("com.google.android.dialerfun", launchers))
    }
}
