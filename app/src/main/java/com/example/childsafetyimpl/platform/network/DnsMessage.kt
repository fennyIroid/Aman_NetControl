package com.example.childsafetyimpl.platform.network

import java.nio.ByteBuffer

/**
 * A minimal DNS message reader and response writer.
 *
 * Written by hand rather than pulled from a library because the tunnel only needs two operations:
 * read the queried name out of an outbound packet, and synthesise a refusal for it. A full
 * resolver would be a liability inside a VpnService, where any parsing bug becomes a device-wide
 * connectivity outage.
 */
object DnsMessage {

    private const val HEADER_BYTES = 12
    private const val MAX_LABEL_HOPS = 12

    /** name pointer + type + class + ttl + rdlength + a four byte address. */
    private const val ANSWER_BYTES = 16

    /** Extracts the queried name, or null when the buffer is not a parsable standard query. */
    fun readQuestionName(payload: ByteArray): String? {
        if (payload.size < HEADER_BYTES + 5) return null

        val buffer = ByteBuffer.wrap(payload)
        buffer.position(2)
        val flags = buffer.short.toInt() and 0xFFFF
        // Bit 15 set means this is a response, not a query.
        if (flags and 0x8000 != 0) return null

        val questionCount = buffer.short.toInt() and 0xFFFF
        if (questionCount < 1) return null

        buffer.position(HEADER_BYTES)
        return readName(buffer, payload)
    }

    private fun readName(buffer: ByteBuffer, payload: ByteArray): String? {
        val labels = mutableListOf<String>()
        var hops = 0

        while (buffer.hasRemaining()) {
            val length = buffer.get().toInt() and 0xFF
            when {
                length == 0 -> return if (labels.isEmpty()) null else labels.joinToString(".")

                // Compression pointer. Present in responses; tolerated here so a malformed query
                // cannot spin the loop.
                length and 0xC0 == 0xC0 -> {
                    if (!buffer.hasRemaining() || hops++ > MAX_LABEL_HOPS) return null
                    val low = buffer.get().toInt() and 0xFF
                    val offset = ((length and 0x3F) shl 8) or low
                    if (offset >= payload.size) return null
                    buffer.position(offset)
                }

                else -> {
                    if (buffer.remaining() < length || length > 63) return null
                    val label = ByteArray(length)
                    buffer.get(label)
                    labels += String(label, Charsets.US_ASCII)
                }
            }
        }
        return null
    }

    /**
     * Builds an NXDOMAIN response for the given query.
     *
     * Refusing by name rather than by dropping the packet matters: a dropped query makes the app
     * hang until it times out, while a refusal fails fast and looks to the child like the site
     * simply does not exist, which is the intended experience for a blocked destination.
     */
    fun buildRefusal(query: ByteArray): ByteArray? {
        if (query.size < HEADER_BYTES) return null

        val response = query.copyOf()
        // QR = 1 (response), RA = 1 (recursion available), RCODE = 3 (name error).
        response[2] = ((response[2].toInt() and 0x01) or 0x84).toByte()
        response[3] = ((response[3].toInt() and 0x70) or 0x03).toByte()
        // Zero the answer, authority and additional counts.
        response[6] = 0; response[7] = 0
        response[8] = 0; response[9] = 0
        response[10] = 0; response[11] = 0
        return response
    }


    /**
     * The question section of a query: the name asked about, the record type, and where the
     * question ends.
     *
     * The end offset is the part that matters for anything beyond a refusal. Building an answer
     * means echoing the question back before the answer record, and that is impossible without
     * knowing where the question stopped.
     *
     * Compression pointers are rejected here rather than followed. They are legal in a response
     * and meaningless in a question, so one appearing in a query is a malformed packet, and
     * forwarding it untouched is safer than interpreting it.
     */
    data class Question(val name: String, val type: Int, val endOffset: Int)

    const val TYPE_A = 1
    const val TYPE_AAAA = 28

    /** Type 65, which Chrome asks for on every navigation to discover HTTP/3 and ECH endpoints. */
    const val TYPE_HTTPS = 65

    fun readQuestion(payload: ByteArray): Question? {
        if (payload.size < HEADER_BYTES + 5) return null

        val buffer = ByteBuffer.wrap(payload)
        buffer.position(2)
        val flags = buffer.short.toInt() and 0xFFFF
        if (flags and 0x8000 != 0) return null
        if (buffer.short.toInt() and 0xFFFF < 1) return null

        buffer.position(HEADER_BYTES)
        val labels = mutableListOf<String>()

        while (true) {
            if (!buffer.hasRemaining()) return null
            val length = buffer.get().toInt() and 0xFF
            if (length == 0) break
            if (length and 0xC0 == 0xC0) return null
            if (length > 63 || buffer.remaining() < length) return null
            val label = ByteArray(length)
            buffer.get(label)
            labels += String(label, Charsets.US_ASCII)
        }

        if (labels.isEmpty() || buffer.remaining() < 4) return null
        val type = buffer.short.toInt() and 0xFFFF
        buffer.short // class, always IN in practice and not worth branching on
        return Question(labels.joinToString("."), type, buffer.position())
    }

    /**
     * Answers a query with an address of our choosing.
     *
     * This is what forces SafeSearch. Google, Bing, DuckDuckGo and YouTube each publish an
     * address that serves the same site with filtering switched on and no way to switch it off
     * from inside the browser, and answering the lookup with that address is the documented way
     * to enforce it for every app on a device rather than only inside our own browser.
     */
    fun buildAddressAnswer(query: ByteArray, address: ByteArray, ttlSeconds: Int = 300): ByteArray? {
        if (address.size != 4) return null
        val question = readQuestion(query) ?: return null
        if (question.endOffset > query.size) return null

        val header = respondingHeader(query, answerCount = 1) ?: return null
        val questionBytes = query.copyOfRange(HEADER_BYTES, question.endOffset)

        val answer = ByteBuffer.allocate(ANSWER_BYTES)
        // A pointer back to the name in the question, which starts immediately after the header.
        answer.putShort(0xC00C.toShort())
        answer.putShort(TYPE_A.toShort())
        answer.putShort(1) // class IN
        answer.putInt(ttlSeconds)
        answer.putShort(4) // rdlength
        answer.put(address)

        return header + questionBytes + answer.array()
    }

    /**
     * Answers with no records at all: the name exists, this type does not.
     *
     * Used for the IPv6 and HTTPS lookups of a name being pointed somewhere else. Refusing those
     * outright would look like an outage, while an empty answer makes the client fall back to the
     * IPv4 address we did supply, which is the one carrying the filtering.
     */
    fun buildEmptyAnswer(query: ByteArray): ByteArray? {
        val question = readQuestion(query) ?: return null
        if (question.endOffset > query.size) return null
        val header = respondingHeader(query, answerCount = 0) ?: return null
        return header + query.copyOfRange(HEADER_BYTES, question.endOffset)
    }

    private fun respondingHeader(query: ByteArray, answerCount: Int): ByteArray? {
        if (query.size < HEADER_BYTES) return null
        val header = query.copyOf(HEADER_BYTES)
        // QR = 1 (response), AA = 1, RD preserved, RA = 1, RCODE = 0 (no error).
        header[2] = ((header[2].toInt() and 0x01) or 0x84).toByte()
        header[3] = ((header[3].toInt() and 0x70) or 0x80).toByte()
        header[6] = 0; header[7] = answerCount.toByte()
        header[8] = 0; header[9] = 0
        // The additional section is dropped, which discards any EDNS record the client sent. A
        // client that offered EDNS and gets an answer without it simply proceeds without it.
        header[10] = 0; header[11] = 0
        return header
    }

    /** The transaction id, used only for logging in the network lab. */
    fun transactionId(payload: ByteArray): Int =
        if (payload.size < 2) 0
        else ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
}
