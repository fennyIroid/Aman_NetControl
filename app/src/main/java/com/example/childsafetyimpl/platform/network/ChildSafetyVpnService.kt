package com.example.childsafetyimpl.platform.network

import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.example.childsafetyimpl.core.protection.ProtectionFeature
import com.example.childsafetyimpl.data.prefs.SafetyPreferences
import com.example.childsafetyimpl.di.ApplicationScope
import com.example.childsafetyimpl.domain.model.DnsQueryRecord
import com.example.childsafetyimpl.domain.model.EventAction
import com.example.childsafetyimpl.domain.model.EventSeverity
import com.example.childsafetyimpl.domain.model.SafetyEventType
import com.example.childsafetyimpl.domain.repository.SafetyEventRepository
import com.example.childsafetyimpl.domain.repository.SafetyRulesRepository
import com.example.childsafetyimpl.platform.alerts.SafetyNotifier
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.inject.Inject

/**
 * The device-wide filtering tunnel.
 *
 * The important design decision is what the tunnel carries. A VpnService can route every packet
 * on the device; this one deliberately routes only DNS. The interface advertises a virtual
 * resolver and adds a route for that address alone, so name lookups enter the tunnel and all
 * other traffic keeps its normal path.
 *
 * That choice is the difference between a filter and an outage. A parsing bug in a tunnel that
 * carries everything takes a child's device offline, and a device a parent has to factory-reset
 * is worse than one running without a filter.
 *
 * The tunnel does three jobs. It refuses blocked destinations for every app on the device, it
 * refuses ad and tracker hosts so ads disappear from apps this app does not own, and by holding
 * the single tunnel Android allows, it keeps a third-party VPN from taking it. That last one is
 * the enforcement behind the VPN restriction: reclaiming this tunnel ends whichever VPN currently
 * holds it.
 *
 * The honest limit: an app resolving names over DNS-over-HTTPS never sends a query here. That is
 * detected separately, by watching the Private DNS setting, and reported rather than hidden.
 */
@AndroidEntryPoint
class ChildSafetyVpnService : VpnService() {

    @Inject lateinit var rulesRepository: SafetyRulesRepository
    @Inject lateinit var eventRepository: SafetyEventRepository
    @Inject lateinit var notifier: SafetyNotifier
    @Inject lateinit var preferences: SafetyPreferences
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    private var tunnelInterface: ParcelFileDescriptor? = null
    private var pumpJob: Job? = null
    private var holdReleaseJob: Job? = null

    /**
     * Cached rather than read per packet. The pump handles a query in well under a millisecond
     * and a DataStore read inside that path would dominate it; the refresh below keeps the value
     * current without paying that cost on every lookup.
     */
    @Volatile private var adBlockEnabled: Boolean = true

    /** Cached for the same reason: the rewrite decision happens on every single lookup. */
    @Volatile private var safeSearchEnabled: Boolean = true

    /**
     * What the tunnel is currently for.
     *
     * Held here rather than read from preferences in the pump for the same reason the two flags
     * above are cached: this is consulted on every packet.
     */
    @Volatile private var mode: TunnelMode = TunnelMode.FILTER

    /** One writer at a time into the tunnel descriptor, now that queries are handled in parallel. */
    private val writeMutex = Mutex()

    /**
     * A ceiling on lookups in flight.
     *
     * High enough that a page opening thirty connections is not slowed by us, low enough that a
     * misbehaving app cannot make this service hold hundreds of sockets.
     */
    private val forwardLimit = Semaphore(MAX_CONCURRENT_LOOKUPS)

    private val upstreamAddress: InetAddress by lazy { InetAddress.getByName(UPSTREAM_DNS) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            teardown()
            return Service.START_NOT_STICKY
        }

        // What the parent has asked for, read once per start rather than trusted from memory.
        // The in-memory mode starts as FILTER in every new process, so a service that Android
        // restarted after killing it — a null intent, START_STICKY — or one that is reclaiming the
        // slot from another VPN used to come back filtering while the parent's pause was still
        // in force: the internet quietly returned and nothing said so. Not a hot path: this runs
        // once per start command, never per packet.
        val settings = runBlocking { preferences.settings.first() }
        val resting = TunnelMode.restingFor(settings.filteringMode, settings.internetPaused)

        when (intent?.action) {
            // Another VPN took the tunnel. Tearing our own descriptor down first makes the
            // rebuild unconditional, and Android hands the single allowed tunnel to whoever
            // established last, which is what ends the other app's VPN.
            //
            // In the Private DNS postures there is no resting tunnel to rebuild, so the slot is
            // taken with one that carries nothing and given back shortly afterwards. A filtering
            // tunnel would be wrong there: it refuses the very resolver Private DNS is pointed at.
            ACTION_RECLAIM -> {
                dropTunnel()
                State.recordReclaim()
                mode = resting ?: TunnelMode.HOLD
                if (resting == null) scheduleHoldRelease()
            }

            // Pause and resume both rebuild the interface, because the routes differ between
            // the two modes and Android will not let an established interface be re-routed in
            // place. Rebuilding is a sub-second interruption and it is the only honest way to
            // move between "carry DNS only" and "carry everything so it can be refused".
            ACTION_PAUSE -> {
                mode = TunnelMode.PAUSED
                dropTunnel()
            }

            ACTION_RESUME -> {
                dropTunnel()
                // In the Private DNS postures the tunnel existed only for the pause, so lifting
                // the pause is the end of it. declineStart rather than teardown, because this
                // intent may have arrived through startForegroundService.
                if (resting == null) {
                    declineStart()
                    return Service.START_NOT_STICKY
                }
                mode = resting
            }

            // A plain start, or Android restarting the service after its process was killed.
            else -> {
                if (resting == null) {
                    declineStart()
                    return Service.START_NOT_STICKY
                }
                // Rebuilt only when the posture differs, so a start while filtering is the
                // no-op it always was.
                if (resting != mode) {
                    dropTunnel()
                    mode = resting
                }
            }
        }
        startTunnel()
        return Service.START_STICKY
    }

    /**
     * Ends a start this posture has no tunnel for.
     *
     * Foreground first, then down: a service started with startForegroundService that stops
     * without ever calling startForeground is treated by Android as a broken promise and crashes
     * the app, which is a far worse outcome than a notification that appears for a moment.
     */
    private fun declineStart() {
        startAsForeground()
        teardown()
    }

    /**
     * Gives the slot back after a reclaim in the Private DNS postures.
     *
     * The other VPN ended when this one established, and holding an empty tunnel beyond that
     * would put the VPN key back in the status bar and the VPN transport back in front of every
     * banking app, which are the two costs those postures exist to avoid.
     */
    private fun scheduleHoldRelease() {
        holdReleaseJob?.cancel()
        holdReleaseJob = scope.launch {
            delay(HOLD_RELEASE_MILLIS)
            withContext(Dispatchers.Main) {
                if (mode == TunnelMode.HOLD && tunnelInterface != null) teardown()
            }
        }
    }

    /**
     * Installed financial and identity apps, resolved against what is really on the device.
     *
     * addDisallowedApplication throws for a package that is not installed and one throw abandons
     * the whole tunnel build, so this filters before it is called rather than relying on the
     * runCatching at the call site to absorb a predictable failure.
     */
    private fun excludedPackages(): List<String> = runCatching {
        val installed = packageManager.getInstalledPackages(0).map { it.packageName }
        TunnelExclusions.installedExclusions(installed)
    }.getOrDefault(emptyList())

    /** Closes the interface and stops the pump without ending the service. */
    private fun dropTunnel() {
        runCatching { tunnelInterface?.close() }
        tunnelInterface = null
        pumpJob?.cancel()
        pumpJob = null
    }

    private fun startTunnel() {
        if (tunnelInterface != null) return

        val builder = Builder()
            .setSession(
                when (mode) {
                    TunnelMode.PAUSED -> "Child Safety pause"
                    TunnelMode.HOLD -> "Child Safety hold"
                    TunnelMode.FILTER -> "Child Safety filter"
                },
            )
            .addAddress(TUNNEL_ADDRESS, 32)
            .setBlocking(true)

        // The virtual resolver. Apps are told to use it, so their queries enter the tunnel. Not
        // advertised by the hold tunnel, which exists to occupy the slot and nothing else, so
        // Private DNS keeps resolving exactly as it did.
        if (mode != TunnelMode.HOLD) builder.addDnsServer(VIRTUAL_DNS)

        when (mode) {
            TunnelMode.FILTER -> {
                // The resolver itself, so name lookups arrive here and can be filtered.
                builder.addRoute(VIRTUAL_DNS, 32)

                // And one host route per well-known encrypted-DNS resolver, so a browser that
                // was configured with a bootstrap address rather than a name still has nowhere
                // to go. These are single addresses, not ranges: the tunnel keeps carrying
                // almost nothing, which is the property that stops a bug here becoming an
                // outage. See EncryptedDnsPolicy for why the name defence alone is not enough.
                EncryptedDnsPolicy.blockedAddresses.forEach { address ->
                    runCatching { builder.addRoute(address, 32) }
                }
            }

            // Pause deliberately inverts the rule the rest of this service follows. Here the
            // tunnel does carry everything, because refusing everything is the entire point.
            TunnelMode.PAUSED -> {
                builder.addRoute("0.0.0.0", 0)
            }

            // One route to an address nothing uses, because an interface needs somewhere to
            // point. No app traffic is carried.
            TunnelMode.HOLD -> {
                builder.addRoute(VIRTUAL_DNS, 32)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Excluding ourselves keeps upstream forwarding from looping back into the tunnel.
            runCatching { builder.addDisallowedApplication(packageName) }
        }

        // Banking and identity apps are kept out of the filtering posture entirely. Not a
        // filtering decision: many of them refuse to run when they can see a VPN transport, and a
        // parent whose bank stops working uninstalls the product and loses every protection with
        // it. TunnelExclusions carries the reasoning and the cost.
        //
        // Not applied while paused, deliberately. The exclusion exists to stop a permanently
        // present tunnel breaking an unrelated app; a pause is temporary, total, and a parent
        // asking for a pause means all of it.
        if (mode != TunnelMode.PAUSED) {
            excludedPackages().forEach { excluded ->
                runCatching { builder.addDisallowedApplication(excluded) }
            }
        }

        val descriptor = try {
            builder.establish()
        } catch (e: Exception) {
            State.setError("Could not establish the tunnel: ${e.message}")
            stopSelf()
            return
        }

        if (descriptor == null) {
            State.setError("Android refused to establish the tunnel. VPN consent may have been revoked.")
            stopSelf()
            return
        }

        tunnelInterface = descriptor
        startAsForeground()
        State.setRunning(true)
        State.setPaused(mode == TunnelMode.PAUSED)

        pumpJob = scope.launch { pumpPackets(descriptor) }
        scope.launch {
            preferences.settings.collect {
                adBlockEnabled = it.adBlockEnabled
                safeSearchEnabled = it.safeSearchEnabled
            }
        }
        // Resolving the filtered search addresses before the first query arrives, rather than
        // inside the pump, where a name lookup of our own would stall every app behind it.
        scope.launch(Dispatchers.IO) { SafeSearchDns.warm() }
    }

    private fun startAsForeground() {
        val text = when (mode) {
            TunnelMode.PAUSED -> "Internet is paused by a parent"
            TunnelMode.HOLD -> "Keeping the device's VPN slot"
            TunnelMode.FILTER -> "Web filtering is active"
        }
        val notification = notifier.monitoringNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                SafetyNotifier.VPN_NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(SafetyNotifier.VPN_NOTIFICATION_ID, notification)
        }
    }

    /**
     * The read loop.
     *
     * Blocking reads on the tunnel descriptor, one packet at a time. Each DNS query is either
     * refused locally or forwarded to a real upstream resolver through a protected socket, whose
     * answer is written back into the tunnel.
     */
    private suspend fun pumpPackets(descriptor: ParcelFileDescriptor) {
        val input = FileInputStream(descriptor.fileDescriptor)
        val output = FileOutputStream(descriptor.fileDescriptor)
        val buffer = ByteArray(MAX_PACKET_BYTES)

        while (scope.isActive && tunnelInterface != null) {
            val length = try {
                input.read(buffer)
            } catch (_: Exception) {
                break
            }
            if (length <= 0) continue

            // The payload is copied out by the parser, so handing the datagram to another
            // coroutine cannot race the next read overwriting this buffer.
            val datagram = Ipv4Packet.parseUdp(buffer, length)

            // Nothing is routed here in the hold posture; anything that arrives is dropped.
            if (mode == TunnelMode.HOLD) continue

            if (mode == TunnelMode.PAUSED) {
                // Everything on the device now arrives here. TCP, QUIC and UDP that is not a
                // name lookup are dropped by simply not answering, which is what makes the
                // pause a pause.
                //
                // Name lookups are the exception, and answering them with a refusal rather
                // than dropping them is a deliberate product decision. A dropped lookup leaves
                // every app spinning for thirty seconds and the phone reads as broken; a
                // refused one fails in milliseconds, so the child reaches the screen that says
                // a parent paused the internet instead of a connection timeout. The paused
                // notice is raised by InternetPauseController, not from here.
                if (datagram != null && datagram.destinationPort == DNS_PORT) {
                    val refusal = DnsMessage.buildRefusal(datagram.payload)
                    if (refusal != null) {
                        scope.launch(Dispatchers.IO) {
                            writeToTunnel(output, Ipv4Packet.buildUdpResponse(datagram, refusal))
                        }
                    }
                }
                State.recordPausedDrop()
                continue
            }

            if (datagram == null) continue

            // A packet addressed to an encrypted-DNS resolver by IP. It only reaches the tunnel
            // because of the host routes added in startTunnel, and dropping it is the address
            // half of the defence described in EncryptedDnsPolicy. Dropped rather than refused
            // on purpose: a resolver that times out makes the browser fall back to the system
            // resolver, which is the one being filtered.
            if (datagram.destinationPort != DNS_PORT) {
                State.recordEncryptedDnsDrop()
                continue
            }

            // Handled off the read loop. A browser resolves a name, its IPv6 form and its HTTPS
            // record at the same moment, and for several hosts at once; answering those one at a
            // time behind a four second timeout each is the difference between a page loading and
            // a page never loading at all.
            scope.launch(Dispatchers.IO) { handleQuery(datagram, output) }
        }
    }

    /**
     * Writes one packet back into the tunnel.
     *
     * Serialised, because several queries are now in flight at once and two interleaved writes to
     * the tunnel descriptor would produce a packet that is neither of them.
     */
    private suspend fun writeToTunnel(output: FileOutputStream, packet: ByteArray) {
        writeMutex.withLock {
            runCatching {
                output.write(packet)
                output.flush()
            }
        }
    }

    private suspend fun handleQuery(
        datagram: Ipv4Packet.UdpDatagram,
        output: FileOutputStream,
    ) {
        val question = DnsMessage.readQuestion(datagram.payload)
        if (question == null) {
            // Unparsable, so forward it untouched rather than dropping a query we do not understand.
            forward(datagram, output)
            return
        }
        val host = question.name

        // Ad hosts are refused before the rule set is consulted: the list is a set lookup and
        // ad traffic is the bulk of what a filtering tunnel sees.
        if (adBlockEnabled && AdBlockList.blocksHost(host)) {
            State.recordQuery(
                DnsQueryRecord(
                    host = host,
                    blocked = true,
                    category = null,
                    atMillis = System.currentTimeMillis(),
                )
            )
            State.recordAdBlocked()
            val adRefusal = DnsMessage.buildRefusal(datagram.payload)
            if (adRefusal != null) {
                writeToTunnel(output, Ipv4Packet.buildUdpResponse(datagram, adRefusal))
                return
            }
        }

        // A browser that resolves names over HTTPS never asks us anything, so every filter in
        // this app stops applying to it. Refusing the endpoints those lookups go to is what
        // keeps the browser on the plain resolver this tunnel is holding.
        if (SafeSearchDns.blocksEncryptedDns(host)) {
            val refused = DnsMessage.buildRefusal(datagram.payload)
            if (refused != null) {
                State.recordQuery(
                    DnsQueryRecord(
                        host = host,
                        blocked = true,
                        category = null,
                        atMillis = System.currentTimeMillis(),
                    )
                )
                writeToTunnel(output, Ipv4Packet.buildUdpResponse(datagram, refused))
                eventRepository.record(
                    type = SafetyEventType.BYPASS_ATTEMPT,
                    title = "An encrypted DNS service was refused",
                    detail = "$host is a DNS-over-HTTPS endpoint. Answering it would let the app " +
                        "asking resolve names outside this filter, so the lookup was refused and " +
                        "the app falls back to the filtered resolver.",
                    severity = EventSeverity.MEDIUM,
                    action = EventAction.BLOCKED,
                    capabilityId = ProtectionFeature.SEARCH_FILTERING.id,
                    dedupeWindowMillis = 30 * 60_000L,
                )
                return
            }
        }

        // SafeSearch, forced for every app rather than only inside our own browser.
        val alias = if (safeSearchEnabled) SafeSearchDns.aliasFor(host) else null
        if (alias != null) {
            val rewritten = when (question.type) {
                DnsMessage.TYPE_A ->
                    SafeSearchDns.addressFor(alias)
                        ?.let { DnsMessage.buildAddressAnswer(datagram.payload, it) }

                // No IPv6 and no HTTPS record for a name being pointed elsewhere: an empty answer
                // sends the client to the filtered IPv4 address instead of around it.
                DnsMessage.TYPE_AAAA, DnsMessage.TYPE_HTTPS ->
                    DnsMessage.buildEmptyAnswer(datagram.payload)

                else -> null
            }

            if (rewritten != null) {
                State.recordQuery(
                    DnsQueryRecord(
                        host = host,
                        blocked = false,
                        category = null,
                        atMillis = System.currentTimeMillis(),
                    )
                )
                if (question.type == DnsMessage.TYPE_A) State.recordSafeSearchForced()
                writeToTunnel(output, Ipv4Packet.buildUdpResponse(datagram, rewritten))
                return
            }
        }

        val verdict = rulesRepository.evaluateHost(host)
        State.recordQuery(
            DnsQueryRecord(
                host = host,
                blocked = !verdict.allowed,
                category = verdict.category,
                atMillis = System.currentTimeMillis(),
            )
        )

        if (verdict.allowed) {
            forward(datagram, output)
            return
        }

        val refusal = DnsMessage.buildRefusal(datagram.payload)
        if (refusal == null) {
            forward(datagram, output)
            return
        }

        writeToTunnel(output, Ipv4Packet.buildUdpResponse(datagram, refusal))

        eventRepository.record(
            type = SafetyEventType.RESTRICTED_WEBSITE,
            title = "Blocked a restricted destination",
            // Only the host and the matched rule. No full URL and no query string.
            detail = "$host was refused at the DNS layer. " +
                "Matched ${verdict.matchedPattern ?: "a category rule"}" +
                (verdict.category?.let { " in ${it.label}" } ?: "") + ".",
            action = EventAction.BLOCKED,
            capabilityId = "dns_filtering",
            dedupeWindowMillis = 60_000L,
        )
    }

    /**
     * Relays a permitted query to the real resolver and writes the answer back into the tunnel.
     *
     * A socket per query, rather than one shared socket for the tunnel. Sharing one is what the
     * first version did, and it is wrong in a way that is invisible until an app asks two things
     * at once: the answers come back in whatever order the network delivers them, so a receive
     * after sending query B can return the answer to query A, which is then written back as the
     * answer to B. The asking app sees a transaction id that is not the one it sent, drops the
     * packet, and waits for an answer that has already been spent. One page load becomes a
     * timeout. A socket per query makes each answer belong to its own question.
     */
    private suspend fun forward(datagram: Ipv4Packet.UdpDatagram, output: FileOutputStream) {
        forwardLimit.withPermit {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                socket.soTimeout = UPSTREAM_TIMEOUT_MILLIS
                // Protecting the socket keeps our own upstream queries outside the tunnel.
                if (!protect(socket)) {
                    State.setError("Could not protect the upstream socket; forwarding would loop.")
                    return
                }

                socket.send(
                    DatagramPacket(
                        datagram.payload,
                        datagram.payload.size,
                        upstreamAddress,
                        DNS_PORT,
                    )
                )

                val responseBuffer = ByteArray(MAX_PACKET_BYTES)
                val response = DatagramPacket(responseBuffer, responseBuffer.size)
                socket.receive(response)

                val payload = responseBuffer.copyOf(response.length)
                writeToTunnel(output, Ipv4Packet.buildUdpResponse(datagram, payload))
                State.recordForwarded()
            } catch (_: Exception) {
                // A timeout or a network drop. The asking app retries on its own; a stuck loop
                // here would be far worse than one lost lookup.
                State.recordUpstreamFailure()
            } finally {
                runCatching { socket?.close() }
            }
        }
    }

    private fun teardown() {
        holdReleaseJob?.cancel()
        holdReleaseJob = null
        pumpJob?.cancel()
        pumpJob = null
        runCatching { tunnelInterface?.close() }
        tunnelInterface = null
        State.setRunning(false)
        State.setPaused(false)
        notifier.clearBlockNotice()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Android revoked our tunnel: the user turned it off, or another VPN app claimed it.
     *
     * Either way this is the bypass the VPN restriction exists to answer, so the service records
     * it and then takes the tunnel back. The short delay is not cosmetic: re-establishing inside
     * the revoke callback races the platform's own teardown and is dropped, while a moment later
     * it succeeds and the other VPN is the one that ends.
     */
    override fun onRevoke() {
        val shouldReclaim = runBlocking {
            eventRepository.record(
                type = SafetyEventType.BYPASS_ATTEMPT,
                title = "Web filtering was switched off",
                detail = "Android revoked this app's tunnel, which happens when protection is " +
                    "turned off in Settings or another VPN app claims it.",
                severity = EventSeverity.CRITICAL,
                action = EventAction.BLOCKED,
                capabilityId = "vpn_restriction",
            )
            preferences.settings.first().let { it.protectionEnabled && it.reclaimTunnel }
        }

        teardown()

        if (shouldReclaim) {
            scope.launch {
                delay(RECLAIM_DELAY_MILLIS)
                runCatching {
                    val intent = Intent(applicationContext, ChildSafetyVpnService::class.java)
                        .setAction(ACTION_RECLAIM)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        applicationContext.startForegroundService(intent)
                    } else {
                        applicationContext.startService(intent)
                    }
                }
            }
        }
        super.onRevoke()
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    /** Observable tunnel state, shared with the network lab. */
    object State {
        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        private val _error = MutableStateFlow<String?>(null)
        val error: StateFlow<String?> = _error.asStateFlow()

        private val _queries = MutableStateFlow<List<DnsQueryRecord>>(emptyList())
        val queries: StateFlow<List<DnsQueryRecord>> = _queries.asStateFlow()

        private val _counters = MutableStateFlow(Counters())
        val counters: StateFlow<Counters> = _counters.asStateFlow()

        fun setRunning(value: Boolean) {
            _running.value = value
            if (value) _error.value = null
        }

        fun setError(message: String) {
            _error.value = message
            _running.value = false
        }

        fun recordQuery(record: DnsQueryRecord) {
            _queries.update { (listOf(record) + it).take(60) }
            _counters.update {
                it.copy(
                    total = it.total + 1,
                    blocked = it.blocked + if (record.blocked) 1 else 0,
                )
            }
        }

        fun recordForwarded() = _counters.update { it.copy(forwarded = it.forwarded + 1) }

        fun recordAdBlocked() = _counters.update { it.copy(adsBlocked = it.adsBlocked + 1) }

        fun recordSafeSearchForced() =
            _counters.update { it.copy(safeSearchForced = it.safeSearchForced + 1) }

        fun recordReclaim() = _counters.update { it.copy(reclaims = it.reclaims + 1) }

        fun recordUpstreamFailure() =
            _counters.update { it.copy(upstreamFailures = it.upstreamFailures + 1) }

        /** A packet refused because the parent has the internet paused. */
        fun recordPausedDrop() = _counters.update { it.copy(pausedDrops = it.pausedDrops + 1) }

        /** A packet addressed to a known encrypted-DNS resolver and dropped. */
        fun recordEncryptedDnsDrop() =
            _counters.update { it.copy(encryptedDnsDrops = it.encryptedDnsDrops + 1) }

        private val _paused = MutableStateFlow(false)
        val paused: StateFlow<Boolean> = _paused.asStateFlow()

        fun setPaused(value: Boolean) { _paused.value = value }

        fun reset() {
            _queries.value = emptyList()
            _counters.value = Counters()
        }

        data class Counters(
            val total: Int = 0,
            val blocked: Int = 0,
            val forwarded: Int = 0,
            val upstreamFailures: Int = 0,
            val pausedDrops: Int = 0,
            val encryptedDnsDrops: Int = 0,
            val adsBlocked: Int = 0,
            val safeSearchForced: Int = 0,
            val reclaims: Int = 0,
        ) {
            val blockedShare: Float get() = if (total == 0) 0f else blocked.toFloat() / total
        }
    }

    companion object {
        const val ACTION_STOP = "com.example.childsafetyimpl.STOP_VPN"
        const val ACTION_RECLAIM = "com.example.childsafetyimpl.RECLAIM_VPN"
        const val ACTION_PAUSE = "com.example.childsafetyimpl.PAUSE_INTERNET"
        const val ACTION_RESUME = "com.example.childsafetyimpl.RESUME_INTERNET"

        /** Mirrors [State.running] for callers that only need a snapshot. */
        val isRunning: StateFlow<Boolean> get() = State.running

        private const val TUNNEL_ADDRESS = "10.111.222.1"
        private const val VIRTUAL_DNS = "10.111.222.2"
        private val UPSTREAM_DNS = EncryptedDnsPolicy.UPSTREAM_RESOLVER
        private const val DNS_PORT = 53
        private const val MAX_PACKET_BYTES = 4096
        private const val UPSTREAM_TIMEOUT_MILLIS = 4000
        private const val MAX_CONCURRENT_LOOKUPS = 32

        /** Long enough for Android to finish revoking, short enough that the gap is a blink. */
        private const val RECLAIM_DELAY_MILLIS = 1200L

        /** How long the hold tunnel keeps the slot after a reclaim in the Private DNS postures. */
        private const val HOLD_RELEASE_MILLIS = 15_000L
    }
}

/**
 * What the tunnel is carrying.
 *
 * These are genuinely different network postures rather than a flag on one behaviour, which is
 * why they are named. [FILTER] carries name lookups and the handful of resolver addresses that
 * would otherwise be used to escape them, and nothing else — a filter that cannot cause an
 * outage. [PAUSED] carries everything precisely so that it can refuse everything. [HOLD] carries
 * nothing: it occupies the one VPN slot Android allows, which is how another VPN is ended in the
 * Private DNS postures without putting a filter in front of the resolver they rely on.
 */
enum class TunnelMode {
    FILTER, PAUSED, HOLD;

    companion object {
        /**
         * The posture the tunnel should settle into, or null when it should not be running.
         *
         * A pause wins in every filtering mode: a parent who paused and then changed how
         * filtering is delivered still paused. Pure, so the rule that restores a pause after a
         * killed process is tested without a device.
         */
        fun restingFor(filtering: FilteringMode, internetPaused: Boolean): TunnelMode? = when {
            internetPaused -> PAUSED
            filtering.keepsTunnelUp -> FILTER
            else -> null
        }
    }
}
