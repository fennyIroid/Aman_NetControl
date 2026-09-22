package com.example.childsafetyimpl.platform.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariants on the bypass catalog.
 *
 * The catalog is a contract deliverable, so the failure mode worth guarding against is not a bug
 * in the app. It is the catalog quietly drifting into a marketing document: a scenario losing its
 * mechanism, a limitation being re-labelled as prevented, or a group silently emptying out. Each
 * of those produces a Sprint 0 report that overclaims, which is the one outcome the whole
 * validation exercise exists to prevent.
 */
class BypassCatalogTest {

    @Test
    fun `every scenario has a unique id`() {
        val ids = BypassScenarios.all.map { it.id }
        assertEquals("Duplicate scenario ids make results impossible to reconcile", ids.size, ids.toSet().size)
    }

    @Test
    fun `every scenario names the mechanism that produces its behaviour`() {
        BypassScenarios.all.forEach { scenario ->
            assertTrue(
                "${scenario.id} has no mechanism, so a failing test points nowhere",
                scenario.mechanism.isNotBlank(),
            )
        }
    }

    @Test
    fun `every group has at least one scenario`() {
        val covered = BypassScenarios.byGroup().keys
        ScenarioGroup.entries.forEach { group ->
            assertTrue(
                "${group.label} has no scenarios, so POC 2 does not actually cover it",
                group in covered,
            )
        }
    }

    @Test
    fun `every POC 2 area named in the contract is represented`() {
        // The contract lists these by name. If one disappears from the catalog, the deliverable
        // is incomplete regardless of how many other scenarios exist.
        val required = setOf(
            ScenarioGroup.WEBSITE_BLOCKING,
            ScenarioGroup.SAFE_SEARCH,
            ScenarioGroup.LIST_RULES,
            ScenarioGroup.VPN,
            ScenarioGroup.DNS_BYPASS,
            ScenarioGroup.INTERNET_PAUSE,
            ScenarioGroup.PERSISTENCE,
            ScenarioGroup.TAMPERING,
        )
        assertTrue(BypassScenarios.byGroup().keys.containsAll(required))
    }

    @Test
    fun `anything not prevented explains what would strengthen it or why nothing would`() {
        BypassScenarios.all
            .filter { it.classification != Classification.PREVENTED }
            .forEach { scenario ->
                assertFalse(
                    "${scenario.id} is not prevented but offers no path and no explanation. " +
                        "A limitation with no stated remedy is an unanswered question, not a result.",
                    scenario.strongerWith.isNullOrBlank(),
                )
            }
    }

    @Test
    fun `the device owner question is material to the result`() {
        // The single most important architectural finding in POC 2: a meaningful share of what
        // cannot be prevented today becomes preventable under provisioning. If this ever drops to
        // zero the recommendation section needs rewriting, not the test relaxing.
        assertTrue(
            "Device Owner should change the answer for several scenarios",
            BypassScenarios.deviceOwnerWouldStrengthen.size >= 5,
        )
    }

    @Test
    fun `internet pause is proven across apply, message, reboot and revoke`() {
        val pause = BypassScenarios.byGroup()[ScenarioGroup.INTERNET_PAUSE].orEmpty()
        val ids = pause.map { it.id }.toSet()
        assertTrue(ids.contains("pause.applies"))
        assertTrue(ids.contains("pause.child_message"))
        assertTrue(ids.contains("pause.survives_reboot"))
        // The revoke case must stay classified as detected. Promoting it to prevented without
        // Device Owner would be the exact overclaim this catalog exists to stop.
        val revoke = pause.first { it.id == "pause.revoke_vpn" }
        assertEquals(Classification.DETECTED, revoke.classification)
    }

    @Test
    fun `tampering is never claimed as prevented`() {
        // A Play-installed app has no authority over the person using the device. Every tamper
        // scenario is detection at best, and a PREVENTED here means somebody has misunderstood
        // what the app is allowed to do.
        BypassScenarios.byGroup()[ScenarioGroup.TAMPERING].orEmpty().forEach { scenario ->
            assertFalse(
                "${scenario.id} claims prevention of a tamper action, which no unmanaged app can do",
                scenario.classification == Classification.PREVENTED,
            )
        }
    }
}
