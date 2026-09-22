package com.example.childsafetyimpl.platform.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

/**
 * The IPv4 and UDP handling that carries answers back into the tunnel. A malformed response here
 * would leave the asking app hanging until it timed out, so the header has to be exactly right.
 */
class Ipv4PacketTest {

    private fun udpPacket(payload: ByteArray): ByteArray {
        val total = 20 + 8 + payload.size
        val buffer = ByteBuffer.allocate(total)
        buffer.put(0x45)                      // version 4, IHL 5
        buffer.put(0)
        buffer.putShort(total.toShort())
        buffer.putShort(0)
        buffer.putShort(0x4000.toShort())
        buffer.put(64)
        buffer.put(17)                        // UDP
        buffer.putShort(0)
        buffer.put(byteArrayOf(10, 0, 0, 2))  // source
        buffer.put(byteArrayOf(10, 0, 0, 1))  // destination
        buffer.putShort(40000.toShort())      // source port
        buffer.putShort(53)                   // destination port
        buffer.putShort((8 + payload.size).toShort())
        buffer.putShort(0)
        buffer.put(payload)
        return buffer.array()
    }

    @Test
    fun `parses a UDP datagram`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val packet = udpPacket(payload)

        val parsed = Ipv4PacketTestAccess.parse(packet, packet.size)
        assertNotNull(parsed)
        requireNotNull(parsed)

        assertEquals(53, parsed.destinationPort)
        assertEquals(40000, parsed.sourcePort)
        assertArrayEquals(payload, parsed.payload)
        assertArrayEquals(byteArrayOf(10, 0, 0, 2), parsed.sourceAddress)
    }

    @Test
    fun `rejects a non-UDP protocol`() {
        val packet = udpPacket(byteArrayOf(1, 2, 3, 4, 5))
        packet[9] = 6 // TCP
        assertNull(Ipv4PacketTestAccess.parse(packet, packet.size))
    }

    @Test
    fun `rejects an IPv6 packet`() {
        val packet = udpPacket(byteArrayOf(1, 2, 3, 4, 5))
        packet[0] = 0x60.toByte()
        assertNull(Ipv4PacketTestAccess.parse(packet, packet.size))
    }

    @Test
    fun `rejects a packet shorter than a header`() {
        assertNull(Ipv4PacketTestAccess.parse(ByteArray(12), 12))
    }

    @Test
    fun `response reverses the addresses and ports`() {
        val request = Ipv4PacketTestAccess.parse(
            udpPacket(byteArrayOf(9, 9)),
            28 + 2,
        )
        requireNotNull(request)

        val payload = byteArrayOf(7, 7, 7)
        val response = Ipv4Packet.buildUdpResponse(request, payload)

        assertEquals(20 + 8 + payload.size, response.size)
        // Source and destination swapped.
        assertArrayEquals(byteArrayOf(10, 0, 0, 1), response.copyOfRange(12, 16))
        assertArrayEquals(byteArrayOf(10, 0, 0, 2), response.copyOfRange(16, 20))

        val reparsed = Ipv4PacketTestAccess.parse(response, response.size)
        requireNotNull(reparsed)
        assertEquals(40000, reparsed.destinationPort)
        assertEquals(53, reparsed.sourcePort)
        assertArrayEquals(payload, reparsed.payload)
    }

    @Test
    fun `response header checksum is valid`() {
        val request = Ipv4PacketTestAccess.parse(udpPacket(byteArrayOf(1)), 29)
        requireNotNull(request)
        val response = Ipv4Packet.buildUdpResponse(request, byteArrayOf(1, 2, 3))

        // Summing a correct header, checksum included, must come out as all ones.
        var sum = 0
        var index = 0
        while (index < 20) {
            sum += ((response[index].toInt() and 0xFF) shl 8) or (response[index + 1].toInt() and 0xFF)
            index += 2
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        assertEquals(0xFFFF, sum)
    }
}

/** Thin indirection so the test reads clearly against the object's single entry point. */
private object Ipv4PacketTestAccess {
    fun parse(packet: ByteArray, length: Int) = Ipv4Packet.parseUdp(packet, length)
}
