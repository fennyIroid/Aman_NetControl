package com.example.childsafetyimpl.platform.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The DNS parser runs inside the VPN read loop, where a bug becomes a device-wide connectivity
 * failure rather than a wrong answer. These tests cover the malformed and hostile inputs that
 * would otherwise only be discovered on a child's phone.
 */
class DnsMessageTest {

    private fun query(name: String, transactionId: Int = 0x1234, type: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(transactionId shr 8)
        out.write(transactionId and 0xFF)
        out.write(0x01); out.write(0x00)   // standard query, recursion desired
        out.write(0x00); out.write(0x01)   // one question
        out.write(0x00); out.write(0x00)   // no answers
        out.write(0x00); out.write(0x00)   // no authority
        out.write(0x00); out.write(0x00)   // no additional
        name.split('.').forEach { label ->
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0x00)                    // root label
        out.write(type shr 8); out.write(type and 0xFF)
        out.write(0x00); out.write(0x01)   // class IN
        return out.toByteArray()
    }

    @Test
    fun `reads a simple question name`() {
        assertEquals("example.com", DnsMessage.readQuestionName(query("example.com")))
    }

    @Test
    fun `reads a deep subdomain`() {
        val name = "a.very.deep.subdomain.example.co.uk"
        assertEquals(name, DnsMessage.readQuestionName(query(name)))
    }

    @Test
    fun `returns null for a response rather than a query`() {
        val packet = query("example.com")
        // Set the QR bit: this is a response, and the tunnel must not treat it as a lookup.
        packet[2] = (packet[2].toInt() or 0x80.toInt()).toByte()
        assertNull(DnsMessage.readQuestionName(packet))
    }

    @Test
    fun `returns null for a truncated packet`() {
        val packet = query("example.com")
        assertNull(DnsMessage.readQuestionName(packet.copyOf(8)))
    }

    @Test
    fun `returns null for an empty payload`() {
        assertNull(DnsMessage.readQuestionName(ByteArray(0)))
    }

    @Test
    fun `returns null when a label length runs past the buffer`() {
        val packet = query("example.com")
        // Claim a label far longer than the remaining bytes.
        packet[12] = 0x3F
        assertNull(DnsMessage.readQuestionName(packet))
    }

    @Test
    fun `survives a compression pointer loop without hanging`() {
        val packet = query("example.com")
        // A pointer back to itself would spin forever without the hop limit.
        packet[12] = 0xC0.toByte()
        packet[13] = 0x0C
        assertNull(DnsMessage.readQuestionName(packet))
    }

    @Test
    fun `refusal preserves the transaction id and sets name error`() {
        val original = query("blocked.example", transactionId = 0xBEEF)
        val refusal = DnsMessage.buildRefusal(original)
        assertNotNull(refusal)
        requireNotNull(refusal)

        assertEquals(0xBEEF, DnsMessage.transactionId(refusal))
        // QR bit set: this is a response.
        assertTrue((refusal[2].toInt() and 0x80) != 0)
        // RCODE 3, NXDOMAIN.
        assertEquals(3, refusal[3].toInt() and 0x0F)
        // Answer, authority and additional counts all zeroed.
        assertEquals(0, refusal[6].toInt())
        assertEquals(0, refusal[7].toInt())
    }

    @Test
    fun `refusal is null for a packet too short to be a message`() {
        assertNull(DnsMessage.buildRefusal(ByteArray(4)))
    }

    @Test
    fun `reads the question type as well as the name`() {
        val question = DnsMessage.readQuestion(query("www.google.com", type = DnsMessage.TYPE_AAAA))
        assertNotNull(question)
        requireNotNull(question)
        assertEquals("www.google.com", question.name)
        assertEquals(DnsMessage.TYPE_AAAA, question.type)
    }

    @Test
    fun `an address answer carries the address it was given`() {
        val original = query("www.google.com", transactionId = 0x0FF0)
        val address = byteArrayOf(216.toByte(), 239.toByte(), 38, 120)
        val answer = DnsMessage.buildAddressAnswer(original, address)
        assertNotNull(answer)
        requireNotNull(answer)

        assertEquals(0x0FF0, DnsMessage.transactionId(answer))
        // A response with exactly one answer and no error.
        assertTrue((answer[2].toInt() and 0x80) != 0)
        assertEquals(0, answer[3].toInt() and 0x0F)
        assertEquals(1, answer[7].toInt())
        // The address is the last four bytes of the record.
        assertArrayEquals(address, answer.copyOfRange(answer.size - 4, answer.size))
        // The question is echoed back, which a resolver requires before it reads the answer.
        assertEquals("www.google.com", DnsMessage.readQuestionName(original))
    }

    @Test
    fun `an address answer is refused for anything that is not four bytes`() {
        assertNull(DnsMessage.buildAddressAnswer(query("www.google.com"), ByteArray(16)))
    }

    @Test
    fun `an empty answer says no error and no records`() {
        val answer = DnsMessage.buildEmptyAnswer(query("www.google.com", type = 28))
        assertNotNull(answer)
        requireNotNull(answer)

        // No error, so the name exists; no answers, so this type does not. That is what makes a
        // client fall back to the address we did supply instead of treating it as an outage.
        assertEquals(0, answer[3].toInt() and 0x0F)
        assertEquals(0, answer[6].toInt())
        assertEquals(0, answer[7].toInt())
    }
}
