package com.example.childsafetyimpl.data.source

import com.example.childsafetyimpl.domain.model.WebCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Checks the lists actually shipped in `assets/blocklists`, not a fixture.
 *
 * The failure this guards against is quiet: a regenerated list that suddenly refuses a CDN, a
 * search engine or a government domain does not crash anything. Pages just stop loading, and the
 * parent switches the category off. Every host below was either measured being refused by an
 * upstream list or is one the phone or ordinary browsing cannot do without.
 */
class BundledHostListsTest {

    private fun load(category: WebCategory): HostSet {
        // Unit tests run with the module directory as the working directory.
        val file = File("src/main/assets", BundledHostLists.ASSETS.getValue(category))
        return file.useLines { HostSet.parse(it, BundledHostLists.NEVER_BLOCK) }
    }

    private val adult by lazy { load(WebCategory.ADULT) }
    private val violence by lazy { load(WebCategory.VIOLENCE) }

    @Test
    fun `the adult list is the real list, not an empty or truncated file`() {
        assertTrue("adult list has ${adult.size} entries", adult.size > 60_000)
        assertTrue(violence.size > 0)
    }

    @Test
    fun `well-known adult sites are covered`() {
        listOf("pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com", "chaturbate.com",
            "stripchat.com", "spankbang.com", "eporner.com", "cdn.xvideos.com").forEach {
            assertNotNull("$it should be on the adult list", adult.matchOf(it))
        }
    }

    @Test
    fun `nothing held back on purpose is on either list`() {
        BundledHostLists.NEVER_BLOCK.forEach {
            assertNull("$it is in NEVER_BLOCK", adult.matchOf(it))
            assertNull("$it is in NEVER_BLOCK", violence.matchOf(it))
        }
    }

    @Test
    fun `popular and system hosts are never refused by a bundled list`() {
        val mustStayReachable = listOf(
            // System plumbing (SystemTrafficExemptions) and Google services
            "connectivitycheck.gstatic.com", "mtalk.google.com", "play.googleapis.com",
            "gvt1.com", "time.android.com", "accounts.google.com", "google.com", "googlevideo.com",
            "youtube.com", "ytimg.com", "gstatic.com", "googleusercontent.com",
            // CDNs and hosting, where one entry takes unrelated sites down with it
            "b-cdn.net", "cloudfront.net", "akamaized.net", "fastly.net", "jsdelivr.net",
            "cloudflare.com", "amazonaws.com", "github.io", "blogspot.com", "wordpress.com",
            "herokuapp.com", "netlify.app", "vercel.app", "pages.dev", "web.app",
            // Everyday sites measured or expected on the edge of adult lists
            "wikipedia.org", "whatsapp.net", "whatsapp.com", "instagram.com", "tiktok.com",
            "reddit.com", "x.com", "discord.com", "telegram.org", "yandex.ru", "yandex.by",
            "vk.com", "ok.ru", "mail.ru", "bing.com", "yahoo.com", "apple.com", "icloud.com",
            "microsoft.com", "gob.mx", "sat.gob.mx", "deviantart.com", "cosmopolitan.com",
        )
        mustStayReachable.forEach {
            assertNull("$it must not be refused as adult", adult.matchOf(it))
            assertNull("$it must not be refused as violence", violence.matchOf(it))
        }
    }

    @Test
    fun `the byte loader the app uses reads the shipped file exactly as the line reader does`() {
        BundledHostLists.ASSETS.values.forEach { path ->
            val file = File("src/main/assets", path)
            val fromBytes = HostSet.parse(file.readBytes(), BundledHostLists.NEVER_BLOCK)
            val fromLines = file.useLines { HostSet.parse(it, BundledHostLists.NEVER_BLOCK) }
            assertEquals(path, fromLines.size, fromBytes.size)
            file.useLines { lines ->
                lines.filter { !it.startsWith("#") && it.isNotBlank() }.forEach {
                    assertEquals(it, fromLines.matchOf(it), fromBytes.matchOf(it))
                }
            }
        }
    }

    @Test
    fun `the shipped lists are plain ASCII, which the byte loader relies on`() {
        BundledHostLists.ASSETS.values.forEach { path ->
            assertTrue(path, File("src/main/assets", path).readBytes().all { it >= 0 })
        }
    }

    @Test
    fun `the lists are kept to the two categories they were generated for`() {
        assertEquals(setOf(WebCategory.ADULT, WebCategory.VIOLENCE), BundledHostLists.ASSETS.keys)
    }
}
