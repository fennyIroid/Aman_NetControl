package com.example.childsafetyimpl.core

import com.example.childsafetyimpl.core.protection.ProtectionFeature
import com.example.childsafetyimpl.core.protection.ProtectionState
import com.example.childsafetyimpl.data.prefs.SafetySettings
import com.example.childsafetyimpl.domain.model.RestrictedSurface
import com.example.childsafetyimpl.navigation.Routes
import com.example.childsafetyimpl.platform.accessibility.SurfaceSignatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The protection catalog is the single source the UI, the enforcement services and the permission
 * screen all read. These tests pin down the invariants each of them assumes, so a protection can
 * never be added in a way that leaves one of the three quietly wrong.
 */
class ProtectionCatalogTest {

    @Test
    fun `every protection appears in exactly one group`() {
        val grouped = ProtectionFeature.primary + ProtectionFeature.secondary
        assertEquals(ProtectionFeature.entries.size, grouped.size)
        assertEquals(grouped.size, grouped.distinct().size)
    }

    @Test
    fun `the brief's first-priority list is the primary group, in order`() {
        assertEquals(
            listOf(
                ProtectionFeature.VPN_RESTRICTION,
                ProtectionFeature.CLONE_CONTROL,
                ProtectionFeature.SHORT_FORM_CONTROL,
                ProtectionFeature.STATUS_CONTROL,
                ProtectionFeature.AI_GAME_MONITORING,
            ),
            ProtectionFeature.primary,
        )
    }

    @Test
    fun `ids are unique and stable, because events reference them`() {
        val ids = ProtectionFeature.entries.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `every protection resolves back from its id`() {
        ProtectionFeature.entries.forEach { feature ->
            assertEquals(feature, ProtectionFeature.find(feature.id))
        }
    }

    @Test
    fun `every protection has a screen to open`() {
        ProtectionFeature.entries.forEach { feature ->
            assertTrue(Routes.screenFor(feature).isNotBlank())
        }
    }

    @Test
    fun `a permission is never both required and merely strengthening`() {
        ProtectionFeature.entries.forEach { feature ->
            val overlap = feature.requires.intersect(feature.strengthenedBy.toSet())
            assertTrue("${feature.id} lists ${overlap.joinToString()} twice", overlap.isEmpty())
        }
    }

    @Test
    fun `protections are on by default, so the app protects before it is configured`() {
        val settings = SafetySettings(protectionEnabled = true)
        ProtectionFeature.entries.forEach { feature ->
            assertTrue(settings.isEnabled(feature))
        }
    }

    @Test
    fun `the master switch overrides every individual switch`() {
        val settings = SafetySettings(protectionEnabled = false)
        ProtectionFeature.entries.forEach { feature ->
            assertFalse(settings.isEnabled(feature))
        }
    }

    @Test
    fun `a surface with no allowance is refused outright`() {
        val settings = SafetySettings(shortFormAllowanceMinutes = null)
        assertEquals(null, settings.allowanceFor(RestrictedSurface.SHORT_FORM_FEED))
    }

    @Test
    fun `an allowance is carried through to the surface that owns it`() {
        val settings = SafetySettings(shortFormAllowanceMinutes = 15, statusAllowanceMinutes = 5)
        assertEquals(15, settings.allowanceFor(RestrictedSurface.SHORT_FORM_FEED))
        assertEquals(5, settings.allowanceFor(RestrictedSurface.STATUS_STORY))
    }

    @Test
    fun `only a running state counts as running`() {
        assertTrue(ProtectionState.ACTIVE.isRunning)
        assertTrue(ProtectionState.PARTIAL.isRunning)
        assertFalse(ProtectionState.NEEDS_PERMISSION.isRunning)
        assertFalse(ProtectionState.OFF.isRunning)
    }

    /**
     * The screen-reading protections are useless without signatures, and a signature list that
     * quietly emptied would leave the UI reporting a protection as active while it recognised
     * nothing at all.
     */
    @Test
    fun `every surface the app enforces has signatures to recognise it by`() {
        listOf(
            RestrictedSurface.SHORT_FORM_FEED,
            RestrictedSurface.STATUS_STORY,
            RestrictedSurface.AI_CONVERSATION,
            RestrictedSurface.VPN_SETTINGS,
        ).forEach { surface ->
            assertTrue(
                "$surface has no signatures",
                SurfaceSignatures.signalCountFor(surface) > 0,
            )
            assertTrue(SurfaceSignatures.packagesFor(surface).isNotEmpty())
        }
    }

    @Test
    fun `the apps named in the brief are watched for short-form feeds`() {
        val packages = SurfaceSignatures.packagesFor(RestrictedSurface.SHORT_FORM_FEED)
        listOf(
            "com.google.android.youtube",
            "com.instagram.android",
            "com.zhiliaoapp.musically",
            "com.facebook.katana",
        ).forEach { assertTrue("$it is not watched", it in packages) }
    }

    @Test
    fun `WhatsApp and Instagram are watched for status surfaces`() {
        val packages = SurfaceSignatures.packagesFor(RestrictedSurface.STATUS_STORY)
        assertTrue("com.whatsapp" in packages)
        assertTrue("com.instagram.android" in packages)
    }

    @Test
    fun `a package with signatures resolves them, and one without resolves none`() {
        assertTrue(SurfaceSignatures.forPackage("com.google.android.youtube").isNotEmpty())
        assertTrue(SurfaceSignatures.forPackage("com.example.nothing").isEmpty())
        assertFalse(SurfaceSignatures.watches("com.example.nothing"))
    }

    @Test
    fun `Instagram carries both a reels and a stories signature`() {
        // The same package hosts two different restricted surfaces, and the guard has to be able
        // to tell them apart to allowance them separately.
        val surfaces = SurfaceSignatures.surfacesIn("com.instagram.android")
        assertTrue(RestrictedSurface.SHORT_FORM_FEED in surfaces)
        assertTrue(RestrictedSurface.STATUS_STORY in surfaces)
    }

    @Test
    fun `the reels tab label is excluded so the home feed does not match`() {
        val instagram = SurfaceSignatures.forPackage("com.instagram.android")
            .first { it.surface == RestrictedSurface.SHORT_FORM_FEED }
        assertNotNull(instagram.excludeLabels.firstOrNull { it.equals("Reels tab", true) })
    }
}
