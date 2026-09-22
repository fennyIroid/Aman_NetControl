package com.example.childsafetyimpl.core

import com.example.childsafetyimpl.core.permission.SafetyPermission
import com.example.childsafetyimpl.core.protection.HealthState
import com.example.childsafetyimpl.core.protection.ProtectionFeature
import com.example.childsafetyimpl.core.protection.ProtectionHealth
import com.example.childsafetyimpl.core.protection.ProtectionState
import com.example.childsafetyimpl.core.protection.ProtectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The health model, which is the thing that decides whether a parent is told the truth.
 *
 * The bug worth testing for is not a crash. It is a calm green screen while nothing is running,
 * because that is the state in which a parent stops checking.
 */
class ProtectionHealthTest {

    private val now = 1_000_000L

    private fun status(
        feature: ProtectionFeature,
        enabled: Boolean = true,
        state: ProtectionState = ProtectionState.ACTIVE,
        missing: List<SafetyPermission> = emptyList(),
    ) = ProtectionStatus(
        feature = feature,
        enabled = enabled,
        state = state,
        missingRequired = missing,
        missingOptional = emptyList(),
        headline = "",
        detail = "",
    )

    private fun health(
        protectionEnabled: Boolean = true,
        statuses: List<ProtectionStatus> = listOf(status(ProtectionFeature.APP_RESTRICTION)),
        monitoringRunning: Boolean = true,
        lastHeartbeat: Long = now - 1_000,
        foreignVpn: Boolean = false,
        privateDnsBypassing: Boolean = false,
        revoked: Set<SafetyPermission> = emptySet(),
        pauseRequested: Boolean = false,
        pauseEnforced: Boolean = false,
        advancedProtection: Boolean = false,
        privateDnsIsTheFilter: Boolean = false,
        privateDnsHost: String? = if (privateDnsBypassing) "dns.google" else null,
    ) = ProtectionHealth.from(
        protectionEnabled = protectionEnabled,
        statuses = statuses,
        monitoringRunning = monitoringRunning,
        lastHeartbeatMillis = lastHeartbeat,
        nowMillis = now,
        foreignVpnActive = foreignVpn,
        privateDnsBypassing = privateDnsBypassing,
        privateDnsHost = privateDnsHost,
        recentlyRevokedGrants = revoked,
        internetPauseRequested = pauseRequested,
        internetPauseEnforced = pauseEnforced,
        advancedProtectionOn = advancedProtection,
        privateDnsIsTheFilter = privateDnsIsTheFilter,
    )

    @Test
    fun `everything running reports active with no issues`() {
        val result = health()
        assertEquals(HealthState.ACTIVE, result.state)
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun `a revoked grant is reported as tampered rather than merely degraded`() {
        // The distinction the product is built on. Somebody took an action to remove a
        // protection, and that is a different sentence from a protection failing on its own.
        val result = health(revoked = setOf(SafetyPermission.ACCESSIBILITY))
        assertEquals(HealthState.TAMPERED, result.state)
    }

    @Test
    fun `a stopped service is never reported as active`() {
        val result = health(monitoringRunning = false)
        assertTrue(result.state != HealthState.ACTIVE)
        assertEquals(ProtectionHealth.IssueKind.SERVICE_STOPPED, result.mostSevere?.kind)
    }

    @Test
    fun `a pause the parent asked for but which is not in force is its own issue`() {
        // Invisible from the parent's side otherwise: they tapped pause, the screen said paused,
        // and the child is online.
        val result = health(pauseRequested = true, pauseEnforced = false)
        assertTrue(result.issues.any { it.kind == ProtectionHealth.IssueKind.PAUSE_NOT_ENFORCED })
    }

    @Test
    fun `a pause that is in force raises nothing`() {
        val result = health(pauseRequested = true, pauseEnforced = true)
        assertEquals(HealthState.ACTIVE, result.state)
    }

    @Test
    fun `private DNS pointed elsewhere is surfaced with the steps to undo it`() {
        val result = health(privateDnsBypassing = true)
        val issue = result.issues.first { it.kind == ProtectionHealth.IssueKind.PRIVATE_DNS_BYPASS }
        assertNotNull("A bypass with no instructions leaves the parent stuck", issue.resolution)
        assertTrue(issue.resolution!!.contains("Private DNS"))
    }

    @Test
    fun `a stale heartbeat is reported before it becomes a silent gap`() {
        val result = health(lastHeartbeat = now - ProtectionHealth.STALE_HEARTBEAT_MILLIS - 1)
        assertTrue(result.issues.any { it.kind == ProtectionHealth.IssueKind.SERVICE_STALLED })
    }

    @Test
    fun `a protection switched on but missing its grant is counted as impaired not off`() {
        val result = health(
            statuses = listOf(
                status(
                    ProtectionFeature.SHORT_FORM_CONTROL,
                    state = ProtectionState.NEEDS_PERMISSION,
                    missing = listOf(SafetyPermission.ACCESSIBILITY),
                ),
            ),
        )
        assertEquals(1, result.impairedCount)
        assertEquals(0, result.activeCount)
        assertEquals(HealthState.DEGRADED, result.state)
    }

    @Test
    fun `protection switched off by the parent is not reported as tampering`() {
        // A parent's own decision must never be shown back to them as an attack.
        val result = health(protectionEnabled = false)
        assertEquals(HealthState.DISABLED, result.state)
    }

    @Test
    fun `issues are ordered with the most serious first`() {
        val result = health(
            monitoringRunning = false,
            foreignVpn = true,
            revoked = setOf(SafetyPermission.USAGE_ACCESS),
        )
        assertEquals(ProtectionHealth.IssueKind.PERMISSION_REVOKED, result.issues.first().kind)
    }

    @Test
    fun `every issue says what it means for the child`() {
        val result = health(
            monitoringRunning = false,
            foreignVpn = true,
            privateDnsBypassing = true,
            revoked = setOf(SafetyPermission.ACCESSIBILITY),
            pauseRequested = true,
        )
        result.issues.forEach {
            assertTrue("${it.kind} has no explanation of why it matters", it.why.isNotBlank())
        }
    }

    @Test
    fun `accessibility withdrawn by Advanced Protection is not reported as tampering`() {
        // The operating system took it, by design. Telling a parent their child tampered with
        // the phone would be an accusation the product has no grounds for.
        val result = health(
            revoked = setOf(SafetyPermission.ACCESSIBILITY),
            advancedProtection = true,
        )
        assertTrue(result.state != HealthState.TAMPERED)
        assertTrue(result.issues.none { it.kind == ProtectionHealth.IssueKind.PERMISSION_REVOKED })
        val issue = result.issues.first { it.kind == ProtectionHealth.IssueKind.ADVANCED_PROTECTION }
        assertNotNull(issue.resolution)
    }

    @Test
    fun `Advanced Protection does not excuse a grant it does not touch`() {
        val result = health(
            revoked = setOf(SafetyPermission.ACCESSIBILITY, SafetyPermission.USAGE_ACCESS),
            advancedProtection = true,
        )
        assertEquals(HealthState.TAMPERED, result.state)
    }

    @Test
    fun `when Private DNS is the filter an unset setting says to set it, not to clear it`() {
        // "Set it back to Automatic" is right for the tunnel and exactly wrong here, where
        // Automatic is no filter at all.
        val result = health(
            privateDnsBypassing = true,
            privateDnsHost = null,
            privateDnsIsTheFilter = true,
        )
        val issue = result.issues.first { it.kind == ProtectionHealth.IssueKind.PRIVATE_DNS_BYPASS }
        assertEquals("Private DNS is not set", issue.title)
        assertTrue(issue.resolution!!.contains("hostname"))
        assertTrue(!issue.resolution!!.contains("Automatic"))
    }
}
