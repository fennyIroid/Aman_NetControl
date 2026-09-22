package com.example.childsafetyimpl.platform.network

import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Just enough IPv4 and UDP to read a DNS query off the tunnel and write an answer back.
 *
 * The tunnel is configured to carry DNS only, so this handles exactly that case and refuses
 * anything else rather than half-parsing it. Inside a VpnService the cost of a wrong guess is the
 * child's device losing connectivity, which makes a narrow, strict parser the correct choice.
 */
object Ipv4Packet {

    const val PROTOCOL_UDP = 17
    private const val UDP_HEADER_BYTES = 8

    data class UdpDatagram(
        val sourceAddress: ByteArray,
        val destinationAddress: ByteArray,
        val sourcePort: Int,
        val destinationPort: Int,
        val payload: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /** Returns the UDP datagram carried by an IPv4 packet, or null if it is not one. */
    fun parseUdp(packet: ByteArray, length: Int): UdpDatagram? {
        if (length < 28) return null

        val version = (packet[0].toInt() and 0xF0) ushr 4
        if (version != 4) return null

        val headerLength = (packet[0].toInt() and 0x0F) * 4
        if (headerLength < 20 || length < headerLength + UDP_HEADER_BYTES) return null

        val protocol = packet[9].toInt() and 0xFF
        if (protocol != PROTOCOL_UDP) return null

        val source = packet.copyOfRange(12, 16)
        val destination = packet.copyOfRange(16, 20)

        val buffer = ByteBuffer.wrap(packet, headerLength, length - headerLength)
        val sourcePort = buffer.short.toInt() and 0xFFFF
        val destinationPort = buffer.short.toInt() and 0xFFFF
        val udpLength = buffer.short.toInt() and 0xFFFF
        buffer.short // checksum, not validated: the tunnel is a local interface

        val payloadLength = (udpLength - UDP_HEADER_BYTES)
            .coerceAtMost(buffer.remaining())
            .coerceAtLeast(0)
        val payload = ByteArray(payloadLength)
        buffer.get(payload)

        return UdpDatagram(source, destination, sourcePort, destinationPort, payload)
    }

    /**
     * Builds a UDP-over-IPv4 packet, with source and destination reversed from the request so the
     * answer is delivered back to the app that asked.
     *
     * The UDP checksum is left at zero, which IPv4 permits and every Android resolver accepts. The
     * IPv4 header checksum is computed properly, because that one is mandatory.
     */
    fun buildUdpResponse(request: UdpDatagram, payload: ByteArray): ByteArray {
        val totalLength = 20 + UDP_HEADER_BYTES + payload.size
        val packet = ByteBuffer.allocate(totalLength)

        packet.put(0x45.toByte())                       // version 4, header length 5 words
        packet.put(0)                                    // DSCP / ECN
        packet.putShort(totalLength.toShort())
        packet.putShort(0)                               // identification
        packet.putShort(0x4000.toShort())                // don't fragment
        packet.put(64)                                   // TTL
        packet.put(PROTOCOL_UDP.toByte())
        packet.putShort(0)                               // checksum placeholder
        packet.put(request.destinationAddress)           // reversed
        packet.put(request.sourceAddress)

        val headerChecksum = checksum(packet.array(), 0, 20)
        packet.putShort(10, headerChecksum.toShort())

        packet.position(20)
        packet.putShort(request.destinationPort.toShort())
        packet.putShort(request.sourcePort.toShort())
        packet.putShort((UDP_HEADER_BYTES + payload.size).toShort())
        packet.putShort(0)                               // UDP checksum, optional over IPv4
        packet.put(payload)

        return packet.array()
    }

    /** Standard internet checksum: one's complement of the one's complement sum. */
    private fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var index = offset
        val end = offset + length

        while (index + 1 < end) {
            sum += ((data[index].toInt() and 0xFF) shl 8) or (data[index + 1].toInt() and 0xFF)
            index += 2
        }
        if (index < end) sum += (data[index].toInt() and 0xFF) shl 8

        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    fun addressToString(address: ByteArray): String = try {
        InetAddress.getByAddress(address).hostAddress ?: "?"
    } catch (_: Exception) {
        "?"
    }
}
