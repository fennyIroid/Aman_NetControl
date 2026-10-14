package com.example.childsafetyimpl.platform.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recognising a site opened by its address.
 *
 * The two failures that matter pull in opposite directions: a disguised address read as not an
 * address is the bypass left open, and a number in a search box read as an address closes the
 * browser on a child's homework.
 */
class DirectAddressTest {

    @Test
    fun `every way of writing one IPv4 address resolves to the same address`() {
        listOf(
            "http://66.254.114.41",
            "https://66.254.114.41:8443/path?q=1",
            "66.254.114.41",
            "66.254.114.41/videos",
            "http://1123971625",
            "http://0x42fe7229",
            "http://0x42.0xfe.0x72.0x29",
            "http://0102.0376.0162.051",
            "http://66.16675369",
            "http://66.254.29225",
            "http://google.com@66.254.114.41/",
            "http://user:pass@66.254.114.41",
        ).forEach {
            val literal = DirectAddress.fromAddressBar(it)
            assertEquals(it, "66.254.114.41", literal?.address)
            assertTrue(it, literal!!.isPublic)
        }
    }

    @Test
    fun `a number in a search box is not an address`() {
        listOf("3.5", "1123971625", "2024", "1.2.3", "0x1f", "12.5.2024", "v1.2.3.4", "3.14159")
            .forEach { assertNull(it, DirectAddress.fromAddressBar(it)) }
    }

    @Test
    fun `an out-of-range or malformed address is not an address`() {
        listOf("256.1.1.1", "1.2.3.4.5", "http://4294967296", "http://1.2.3.256", "http://0x1.0x2.0x3.0x100")
            .forEach { assertNull(it, DirectAddress.fromAddressBar(it)) }
    }

    @Test
    fun `a leading zero is octal, as a browser reads it`() {
        // 010 is 8, so a bare four-part form with a leading zero is not the address it looks like
        // and is only accepted with a scheme, where it is read the way the browser will read it.
        assertNull(DirectAddress.fromAddressBar("010.0.0.1"))
        assertEquals("8.0.0.1", DirectAddress.fromAddressBar("http://010.0.0.1")?.address)
    }

    @Test
    fun `private, loopback and reserved addresses are not public`() {
        listOf(
            "192.168.1.1", "10.0.0.1", "172.16.0.1", "172.31.255.255", "127.0.0.1", "169.254.1.1",
            "100.64.0.1", "0.0.0.0", "224.0.0.251", "255.255.255.255",
        ).forEach { assertFalse(it, DirectAddress.fromHost(it)!!.isPublic) }
        listOf("172.15.0.1", "172.32.0.1", "100.128.0.1", "8.8.8.8")
            .forEach { assertTrue(it, DirectAddress.fromHost(it)!!.isPublic) }
    }

    @Test
    fun `IPv6 literals are recognised with or without brackets`() {
        val full = DirectAddress.fromAddressBar("http://[2606:4700:0:0:0:0:6810:84e5]:443/")
        assertEquals("2606:4700:0:0:0:0:6810:84e5", full?.address)
        assertTrue(full!!.isPublic)
        assertEquals(full.address, DirectAddress.fromHost("[2606:4700::6810:84e5]")?.address)
        assertEquals(full.address, DirectAddress.fromHost("2606:4700::6810:84e5")?.address)

        listOf("::1", "fe80::1", "fd12:3456::1", "ff02::1", "::", "2001:db8::1")
            .forEach { assertFalse(it, DirectAddress.fromHost(it)!!.isPublic) }
    }

    @Test
    fun `an IPv4 address mapped into IPv6 is judged as the IPv4 address`() {
        assertTrue(DirectAddress.fromHost("::ffff:66.254.114.41")!!.isPublic)
        assertFalse(DirectAddress.fromHost("::ffff:192.168.1.1")!!.isPublic)
    }

    @Test
    fun `not an IPv6 address`() {
        listOf("1:2:3:4:5:6:7:8:9", "1::2::3", "12345::1", "fe80::1%wlan0", "g::1")
            .forEach { assertNull(it, DirectAddress.fromHost(it)) }
    }

    @Test
    fun `wildcard-DNS names are read as the address they encode`() {
        mapOf(
            "66.254.114.41.nip.io" to "66.254.114.41",
            "app.66.254.114.41.nip.io" to "66.254.114.41",
            "66-254-114-41.sslip.io" to "66.254.114.41",
            "site-66-254-114-41.sslip.io" to "66.254.114.41",
        ).forEach { (host, address) -> assertEquals(host, address, DirectAddress.fromHost(host)?.address) }
        assertNull(DirectAddress.fromHost("nip.io"))
        assertNull(DirectAddress.fromHost("docs.sslip.io"))
    }

    @Test
    fun `ordinary names are not addresses`() {
        listOf("example.com", "1password.com", "123movies.example", "web.archive.org", "3.cn")
            .forEach { assertNull(it, DirectAddress.fromHost(it)) }
    }
}
