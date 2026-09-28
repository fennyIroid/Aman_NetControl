package com.example.childsafetyimpl.data.source

/**
 * A large host list that answers "is this host, or a domain above it, on the list" in a handful
 * of hash probes.
 *
 * The rules repository used to test every rule against every host, which is fine for the dozens a
 * parent types in and ruinous for a real category list: measured on a desktop JVM, a linear scan
 * over 77k entries took 1.8 ms per lookup and one over 237k took 8.2 ms, against ~0.1 µs for this.
 * A phone is several times slower, and a page makes dozens of lookups on the VPN read loop.
 *
 * The semantics are exactly `host == entry || host.endsWith(".$entry")`: walking the host from
 * each dot visits precisely the suffixes that test can match. Entries are held as sorted 64-bit
 * hashes rather than strings, which keeps 64k entries to half a megabyte instead of several. A
 * false match needs a 64-bit collision against one of a few suffixes, which will not happen at
 * this size in the lifetime of the product.
 */
class HostSet private constructor(private val hashes: LongArray) {

    val size: Int get() = hashes.size

    /** The listed entry covering [host], or null. [host] must already be normalised. */
    fun matchOf(host: String): String? {
        if (hashes.isEmpty() || host.isEmpty()) return null
        var start = 0
        while (true) {
            if (hashes.binarySearch(hash(host, start)) >= 0) return host.substring(start)
            val dot = host.indexOf('.', start)
            if (dot < 0) return null
            start = dot + 1
        }
    }

    companion object {
        val EMPTY = HostSet(LongArray(0))

        fun of(hosts: Iterable<String>): HostSet = build(hosts.asSequence())

        /**
         * Reads the bundled format: one host per line, `#` comments and blank lines ignored.
         * Anything in [leaveOut] is dropped, so an entry removed on purpose stays removed even if
         * a regenerated file brings it back.
         */
        fun parse(lines: Sequence<String>, leaveOut: Set<String> = emptySet()): HostSet =
            build(lines.mapNotNull { normalise(it) }.filter { it !in leaveOut })

        /**
         * The same format and result as [parse], hashed straight from the file's bytes.
         *
         * This is the one the app uses. Decoding 64k lines into strings took 0.6 s on a Pixel 4,
         * against 7 ms to read the bytes, and it happens on the first lookup of every process,
         * which can be the tunnel's first DNS query. The bundled lists are ASCII by construction
         * (internationalised names are in their `xn--` form); a line that is not is skipped.
         */
        fun parse(bytes: ByteArray, leaveOut: Set<String> = emptySet()): HostSet {
            val excluded = leaveOut.mapNotNull { normalise(it) }.map { hash(it, 0) }.toLongArray()
            excluded.sort()
            var out = LongArray(maxOf(1024, bytes.size / 16))
            var n = 0
            var i = 0
            while (i < bytes.size) {
                var end = i
                while (end < bytes.size && bytes[end] != NEWLINE) end++
                var start = i
                var stop = end
                while (start < stop && bytes[start] <= SPACE) start++
                while (stop > start && bytes[stop - 1] <= SPACE) stop--
                if (start < stop && bytes[start] != HASH) {
                    if (stop - start >= 4 && isWww(bytes, start)) start += 4
                    while (stop > start && bytes[stop - 1] == DOT) stop--
                    val h = if (start < stop) hashAscii(bytes, start, stop) else null
                    if (h != null && excluded.binarySearch(h) < 0) {
                        if (n == out.size) out = out.copyOf(n * 2)
                        out[n++] = h
                    }
                }
                i = end + 1
            }
            out = out.copyOf(n)
            out.sort()
            return HostSet(out.distinctSorted())
        }

        private const val NEWLINE = '\n'.code.toByte()
        private const val SPACE = ' '.code.toByte()
        private const val HASH = '#'.code.toByte()
        private const val DOT = '.'.code.toByte()

        private fun isWww(bytes: ByteArray, at: Int): Boolean =
            (bytes[at].toInt() or 0x20) == 'w'.code && (bytes[at + 1].toInt() or 0x20) == 'w'.code &&
                (bytes[at + 2].toInt() or 0x20) == 'w'.code && bytes[at + 3] == DOT

        /** [hash] of the lower-cased ASCII in `bytes[from, to)`, or null if it is not ASCII. */
        private fun hashAscii(bytes: ByteArray, from: Int, to: Int): Long? {
            var h = FNV_OFFSET
            for (k in from until to) {
                var c = bytes[k].toInt()
                if (c < 0) return null
                if (c in 'A'.code..'Z'.code) c += 32
                h = (h xor c.toLong()) * FNV_PRIME
            }
            return h
        }

        private fun build(hosts: Sequence<String>): HostSet {
            var out = LongArray(1024)
            var n = 0
            for (host in hosts) {
                if (host.isEmpty()) continue
                if (n == out.size) out = out.copyOf(n * 2)
                out[n++] = hash(host, 0)
            }
            out = out.copyOf(n)
            out.sort()
            return HostSet(out.distinctSorted())
        }

        private fun normalise(line: String): String? {
            var host = line.trim()
            if (host.isEmpty() || host[0] == '#') return null
            if (host.any { it.isUpperCase() }) host = host.lowercase()
            host = host.removePrefix("www.").trimEnd('.')
            return host.ifEmpty { null }
        }

        /** FNV-1a over the chars of [s] from [start], so a suffix is hashed without a copy. */
        private fun hash(s: String, start: Int): Long {
            var h = FNV_OFFSET
            for (i in start until s.length) {
                h = h xor s[i].code.toLong()
                h *= FNV_PRIME
            }
            return h
        }

        private const val FNV_OFFSET = -0x340d631b7bdddcdbL
        private const val FNV_PRIME = 0x100000001b3L

        private fun LongArray.distinctSorted(): LongArray {
            if (size < 2) return this
            var n = 1
            for (i in 1 until size) if (this[i] != this[n - 1]) this[n++] = this[i]
            return if (n == size) this else copyOf(n)
        }
    }
}
