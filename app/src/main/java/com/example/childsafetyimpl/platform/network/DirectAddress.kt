package com.example.childsafetyimpl.platform.network

/**
 * Recognises a site opened by its address rather than its name — the one way round a DNS filter
 * that needs no setting changed and no app installed.
 *
 * Private DNS judges name lookups, and an address has none: `http://66.254.114.41` goes straight
 * to the server and the resolver never hears of it. So a typed address is refused here, on the
 * device, wherever this app can read the address bar (the built-in browser, and other browsers
 * through the accessibility service). The rule is "a public address in a browser", not a list of
 * bad addresses, because no list of them can be kept: an adult site moves between addresses, and
 * one address carries thousands of unrelated sites behind a CDN.
 *
 * What makes refusing every public address acceptable is that nobody browses that way. A person
 * reaches sites by name; a bare public address in an address bar is either a bypass or a network
 * engineer. Addresses that *are* typed in ordinary use — a home router at 192.168.1.1, a printer,
 * a school's intranet — are private ranges and always allowed. A parent's own allow rule for an
 * address outranks this, the same as for a name.
 *
 * Three disguises are recognised, because each is one search away for a child:
 * - the same IPv4 address written as one integer, in hex or in octal (`http://1123971625`,
 *   `http://0x42.0xfe.0x72.0x29`), which every browser still accepts;
 * - IPv6 literals in brackets;
 * - wildcard-DNS services such as nip.io and sslip.io, which turn any address into a name
 *   (`66-254-114-41.sslip.io`). Those *are* looked up, but a public resolver answers them as the
 *   ordinary names they look like.
 *
 * The integer and hex forms are only recognised with a scheme in front of them. Without one,
 * "3.5" in a search box would read as the address 3.0.0.5 and close the browser on a maths
 * question. Typed with no scheme, only a full four-part address counts.
 *
 * What this cannot do: an app other than a browser that connects to an address it was built with
 * is invisible to every resolver-based filter, this one included. Seeing that takes a tunnel.
 */
object DirectAddress {

    enum class Form {
        /** `a.b.c.d`, the way an address is normally written. */
        DOTTED,

        /** The same IPv4 address as an integer, hex, octal, or fewer than four parts. */
        DISGUISED,
        IPV6,

        /** A name from a wildcard-DNS service that encodes the address inside it. */
        EMBEDDED_IN_NAME,
    }

    data class Literal(
        /** The address in canonical form: dotted IPv4, or IPv6 without brackets. */
        val address: String,
        val form: Form,
        /** False for loopback, private, link-local, carrier-grade NAT and reserved ranges. */
        val isPublic: Boolean,
    )

    /**
     * Wildcard-DNS services whose names resolve to the address written in them. Kept narrow:
     * each one exists for this and nothing else, so refusing its public addresses refuses no site.
     */
    val embeddingServices: Set<String> = setOf(
        "nip.io", "sslip.io", "xip.io", "traefik.me", "nip.direct", "ip.addr.tools",
    )

    /**
     * A host that is an address, in the forms a browser shows once it has navigated: dotted
     * IPv4, IPv6 with or without brackets, or a wildcard-DNS name. Never the integer or hex forms,
     * since a host given to this has already been through a browser or [fromAddressBar].
     */
    fun fromHost(host: String): Literal? {
        val name = host.trim().lowercase().trimEnd('.')
        if (name.isEmpty()) return null
        if (':' in name) return ipv6(name.removePrefix("[").removeSuffix("]"))?.let { literal(it) }
        dottedQuad(name)?.let { return literal(it, Form.DOTTED) }
        return embedded(name)
    }

    /**
     * An address typed or shown in an address bar, with or without a scheme, path, port or a
     * `user@` prefix. The `user@` form matters: `http://example.com@1.2.3.4` opens 1.2.3.4.
     */
    fun fromAddressBar(text: String): Literal? {
        val trimmed = text.trim().lowercase()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        val hasScheme = trimmed.startsWith("http://") || trimmed.startsWith("https://")
        val authority = trimmed.substringAfter("://")
            .substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@')
        if (authority.isEmpty()) return null

        if (authority.startsWith('[')) {
            val inside = authority.substringAfter('[').substringBefore(']')
            return ipv6(inside)?.let { literal(it) }
        }
        val host = authority.substringBefore(':')
        dottedQuad(host)?.let { return literal(it, Form.DOTTED) }
        embedded(host)?.let { return it }
        if (!hasScheme) return null
        return looseIpv4(host)?.let { literal(it, Form.DISGUISED) }
    }

    // --- IPv4 ----------------------------------------------------------------------------------

    /** Exactly four decimal parts, each 0-255. The only form accepted without a scheme. */
    private fun dottedQuad(text: String): IntArray? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        for ((i, part) in parts.withIndex()) {
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
            // A leading zero means octal to a browser, so "010.0.0.1" is 8.0.0.1, not 10.0.0.1.
            if (part.length > 1 && part[0] == '0') return null
            out[i] = part.toInt().takeIf { it <= 255 } ?: return null
        }
        return out
    }

    /**
     * The address a browser's URL parser reads from one to four parts, each decimal, `0x` hex or
     * `0`-prefixed octal, where the last part fills the remaining bytes. This is the WHATWG URL
     * standard's IPv4 parser, which Chrome and Firefox both follow.
     */
    private fun looseIpv4(text: String): IntArray? {
        val parts = text.removeSuffix(".").split('.')
        if (parts.isEmpty() || parts.size > 4) return null
        val numbers = parts.map { number(it) ?: return null }
        val last = numbers.last()
        val leading = numbers.dropLast(1)
        if (leading.any { it > 255 }) return null
        val lastBytes = 4 - leading.size
        if (last >= 1L shl (8 * lastBytes)) return null
        var value = last
        for ((i, part) in leading.withIndex()) value += part shl (8 * (3 - i))
        return IntArray(4) { ((value shr (8 * (3 - it))) and 0xFF).toInt() }
    }

    private fun number(part: String): Long? {
        if (part.isEmpty()) return null
        val (digits, radix) = when {
            part.startsWith("0x") -> part.drop(2) to 16
            part.length > 1 && part.startsWith("0") -> part.drop(1) to 8
            else -> part to 10
        }
        if (digits.isEmpty()) return if (radix == 16) 0L else null
        if (digits.length > 12) return null
        return digits.toLongOrNull(radix)?.takeIf { it <= 0xFFFFFFFFL }
    }

    /** `1.2.3.4.nip.io`, `app.1-2-3-4.sslip.io`, `app-1-2-3-4.nip.io`. */
    private fun embedded(host: String): Literal? {
        val service = embeddingServices.firstOrNull { host.endsWith(".$it") } ?: return null
        val prefix = host.removeSuffix(".$service")
        // Dotted: the last four labels before the service.
        val labels = prefix.split('.')
        if (labels.size >= 4) {
            dottedQuad(labels.takeLast(4).joinToString("."))
                ?.let { return literal(it, Form.EMBEDDED_IN_NAME) }
        }
        // Dashed: four numbers at the end of the last label.
        val dashed = labels.last().split('-')
        if (dashed.size >= 4) {
            dottedQuad(dashed.takeLast(4).joinToString("."))
                ?.let { return literal(it, Form.EMBEDDED_IN_NAME) }
        }
        return null
    }

    private fun literal(bytes: IntArray, form: Form) = Literal(
        address = bytes.joinToString("."),
        form = form,
        isPublic = isPublicV4(bytes),
    )

    private fun isPublicV4(b: IntArray): Boolean {
        val (a, second) = b[0] to b[1]
        return when {
            a == 0 || a == 10 || a == 127 -> false
            a == 100 && second in 64..127 -> false // carrier-grade NAT
            a == 169 && second == 254 -> false // link-local
            a == 172 && second in 16..31 -> false
            a == 192 && second == 168 -> false
            a == 192 && second == 0 && b[2] == 0 -> false
            a == 198 && second in 18..19 -> false // benchmarking
            a >= 224 -> false // multicast and reserved
            else -> true
        }
    }

    // --- IPv6 ----------------------------------------------------------------------------------

    /** Eight 16-bit groups, or null if [text] is not an IPv6 literal. Zone ids are refused. */
    private fun ipv6(text: String): IntArray? {
        if (text.isEmpty() || '%' in text) return null
        if (!text.all { it in '0'..'9' || it in 'a'..'f' || it == ':' || it == '.' }) return null

        // A trailing dotted IPv4 (::ffff:1.2.3.4) supplies the last two groups.
        var body = text
        var tail = IntArray(0)
        if ('.' in text) {
            val v4 = dottedQuad(text.substringAfterLast(':')) ?: return null
            tail = intArrayOf((v4[0] shl 8) or v4[1], (v4[2] shl 8) or v4[3])
            // "::ffff:1.2.3.4" leaves "::ffff", "::1.2.3.4" leaves "::".
            body = (text.substringBeforeLast(':') + ":").let { if (it.endsWith("::")) it else it.dropLast(1) }
        }

        val halves = body.split("::")
        if (halves.size > 2) return null
        fun groups(s: String) = if (s.isEmpty()) emptyList() else s.split(':')
        val head = groups(halves[0])
        val rest = if (halves.size == 2) groups(halves[1]) else emptyList()
        val parsed = (head + rest).map { g ->
            if (g.isEmpty() || g.length > 4) return null
            g.toInt(16)
        }
        val total = parsed.size + tail.size
        if (halves.size == 1 && total != 8) return null
        if (halves.size == 2 && total > 7) return null

        val out = IntArray(8)
        head.indices.forEach { out[it] = parsed[it] }
        val back = parsed.drop(head.size) + tail.toList()
        back.indices.forEach { out[8 - back.size + it] = back[it] }
        return out
    }

    private fun literal(groups: IntArray): Literal {
        val mappedV4 = groups.take(5).all { it == 0 } && groups[5] == 0xFFFF
        val isPublic = if (mappedV4) {
            isPublicV4(intArrayOf(groups[6] shr 8, groups[6] and 0xFF, groups[7] shr 8, groups[7] and 0xFF))
        } else {
            isPublicV6(groups)
        }
        return Literal(
            address = groups.joinToString(":") { it.toString(16) },
            form = Form.IPV6,
            isPublic = isPublic,
        )
    }

    private fun isPublicV6(g: IntArray): Boolean = when {
        g.all { it == 0 } -> false // unspecified
        g.take(7).all { it == 0 } && g[7] == 1 -> false // loopback
        g[0] and 0xFE00 == 0xFC00 -> false // unique local
        g[0] and 0xFFC0 == 0xFE80 -> false // link-local
        g[0] and 0xFF00 == 0xFF00 -> false // multicast
        g[0] == 0x2001 && g[1] == 0x0DB8 -> false // documentation
        else -> true
    }
}
