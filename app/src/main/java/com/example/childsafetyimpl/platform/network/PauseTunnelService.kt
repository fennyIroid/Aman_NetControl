package com.example.childsafetyimpl.platform.network

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.example.childsafetyimpl.data.prefs.SafetyPreferences
import com.example.childsafetyimpl.di.ApplicationScope
import com.example.childsafetyimpl.domain.model.EventAction
import com.example.childsafetyimpl.domain.model.EventSeverity
import com.example.childsafetyimpl.domain.model.SafetyEventType
import com.example.childsafetyimpl.domain.repository.SafetyEventRepository
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
import kotlinx.coroutines.sync.withLock
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.inject.Inject

/**
 * The tunnel that exists only while a parent has the internet paused.
 *
 * Filtering does not run through here. It runs through Android's Private DNS ([PrivateDnsGuard]),
 * which needs no tunnel, no VPN key in the status bar and nothing for a banking app to object to.
 * What a resolver cannot do is stop a connection, so a pause is the one thing this product still
 * holds the device's VPN slot for, and only for as long as the pause lasts.
 *
 * While up, it carries everything — a default route for IPv4 and IPv6 — precisely so that it can
 * refuse everything. Nothing is ever forwarded. Name lookups are answered with a refusal rather
 * than dropped, because a refusal fails in milliseconds and a drop hangs for half a minute; the
 * child reaches the screen that says a parent paused the internet instead of a connection timeout.
 *
 * This app is not exempt from its own pause. It used to be, because the filtering tunnel had to
 * keep its own upstream lookups out of the tunnel, and the side effect was that the built-in
 * browser and player stayed online through a pause. With nothing forwarded there is no upstream
 * to protect, and a pause a parent asked for means all of it.
 *
 * The posture comes from what the parent asked for on every start, never from memory (rule 30):
 * a service Android restarts after killing it reads the saved pause and either holds the pause or
 * stands down.
 */
@AndroidEntryPoint
class PauseTunnelService : VpnService() {

    @Inject lateinit var eventRepository: SafetyEventRepository
    @Inject lateinit var notifier: SafetyNotifier
    @Inject lateinit var preferences: SafetyPreferences
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    private var tunnelInterface: ParcelFileDescriptor? = null
    private var pumpJob: Job? = null

    /** One writer at a time into the tunnel descriptor; refusals are written from coroutines. */
    private val writeMutex = Mutex()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Read once per start rather than trusted from memory. Not a hot path: this runs once per
        // start command, never per packet.
        val paused = runBlocking { preferences.settings.first().internetPaused }

        if (intent?.action == ACTION_STOP) {
            teardown()
            return Service.START_NOT_STICKY
        }
        if (!paused) {
            // Foreground first, then down: a service started with startForegroundService that
            // stops without ever calling startForeground is treated by Android as a broken
            // promise and crashes the app.
            startAsForeground()
            teardown()
            return Service.START_NOT_STICKY
        }

        // A reclaim after another VPN took the slot, or a restart after the process was killed.
        // Either way the interface is rebuilt from nothing, and Android hands the single allowed
        // tunnel to whoever established last.
        if (intent?.action == ACTION_RECLAIM) dropTunnel()
        startTunnel()
        return Service.START_STICKY
    }

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
            .setSession("Child Safety pause")
            .addAddress(TUNNEL_ADDRESS, 32)
            .addDnsServer(VIRTUAL_DNS)
            .addRoute("0.0.0.0", 0)
            .setBlocking(true)

        // IPv6 as well, or a pause would hold on a v4-only network and leak on every network
        // that offers v6. Optional because a build that refuses the address must still pause v4.
        runCatching {
            builder.addAddress(TUNNEL_ADDRESS_V6, 128)
            builder.addRoute("::", 0)
        }

        val descriptor = try {
            builder.establish()
        } catch (e: Exception) {
            State.setError("Could not establish the pause: ${e.message}")
            stopSelf()
            return
        }

        if (descriptor == null) {
            State.setError(
                "Android refused to establish the pause. Connection permission may have been " +
                    "withdrawn, or another VPN is set as always-on.",
            )
            stopSelf()
            return
        }

        tunnelInterface = descriptor
        startAsForeground()
        State.setRunning(true)
        pumpJob = scope.launch(Dispatchers.IO) { pumpPackets(descriptor) }
    }

    private fun startAsForeground() {
        val notification = notifier.monitoringNotification("Internet is paused by a parent")
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
     * Everything on the device arrives here. TCP, QUIC and UDP that is not a name lookup are
     * dropped by simply not answering, which is what makes the pause a pause. Name lookups are
     * refused; see the class comment for why.
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
            if (datagram != null && datagram.destinationPort == DNS_PORT) {
                val refusal = DnsMessage.buildRefusal(datagram.payload)
                if (refusal != null) {
                    scope.launch(Dispatchers.IO) {
                        writeToTunnel(output, Ipv4Packet.buildUdpResponse(datagram, refusal))
                    }
                }
            }
            State.recordRefused()
        }
    }

    private suspend fun writeToTunnel(output: FileOutputStream, packet: ByteArray) {
        writeMutex.withLock {
            runCatching {
                output.write(packet)
                output.flush()
            }
        }
    }

    private fun teardown() {
        pumpJob?.cancel()
        pumpJob = null
        runCatching { tunnelInterface?.close() }
        tunnelInterface = null
        State.setRunning(false)
        notifier.clearBlockNotice()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Android revoked the pause: the connection permission was switched off, or another VPN app
     * claimed the slot.
     *
     * While the parent still wants the pause, that is a child getting the internet back, so it is
     * recorded and the pause is taken back. The short delay is not cosmetic: re-establishing
     * inside the revoke callback races the platform's own teardown and is dropped, while a moment
     * later it succeeds and the other VPN is the one that ends.
     */
    override fun onRevoke() {
        val stillWanted = runBlocking {
            preferences.settings.first().let { it.protectionEnabled && it.internetPaused }
        }
        teardown()

        if (stillWanted) {
            scope.launch {
                eventRepository.record(
                    type = SafetyEventType.BYPASS_ATTEMPT,
                    title = "The internet pause was interrupted",
                    detail = "Android withdrew the pause, which happens when the connection " +
                        "permission is switched off in Settings or another VPN app claims it. " +
                        "The pause is being put back.",
                    severity = EventSeverity.CRITICAL,
                    action = EventAction.NOT_ENFORCEABLE,
                    capabilityId = InternetPauseController.CAPABILITY_ID,
                )
                delay(RECLAIM_DELAY_MILLIS)
                runCatching { start(applicationContext, ACTION_RECLAIM) }
            }
        }
        super.onRevoke()
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    /** Observable pause state, for the dashboard, Controls and the monitoring loop. */
    object State {
        private val _running = MutableStateFlow(false)

        /** True only while the pause tunnel is established, which is the pause being in force. */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        private val _error = MutableStateFlow<String?>(null)
        val error: StateFlow<String?> = _error.asStateFlow()

        private val _refused = MutableStateFlow(0)

        /** Packets refused while paused, so a parent can see the pause doing something. */
        val refused: StateFlow<Int> = _refused.asStateFlow()

        fun setRunning(value: Boolean) {
            _running.value = value
            if (value) _error.value = null
        }

        fun setError(message: String) {
            _error.value = message
            _running.value = false
        }

        fun recordRefused() = _refused.update { it + 1 }
    }

    companion object {
        const val ACTION_STOP = "com.example.childsafetyimpl.STOP_PAUSE"
        const val ACTION_RECLAIM = "com.example.childsafetyimpl.RECLAIM_PAUSE"

        /** Starts or re-asserts the pause. The service stands down by itself if none is saved. */
        fun start(context: Context, action: String? = null) {
            val intent = Intent(context, PauseTunnelService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Lifts the pause. A plain start rather than a foreground one, because a stop has no
         * notification to promise; it can be refused while the app is in the background, which is
         * why callers treat it as best effort and the service also stands down by itself on its
         * next start once the saved pause is gone.
         */
        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, PauseTunnelService::class.java).setAction(ACTION_STOP),
                )
            }
        }

        private const val TUNNEL_ADDRESS = "10.111.222.1"
        private const val TUNNEL_ADDRESS_V6 = "fd00:111:222::1"
        private const val VIRTUAL_DNS = "10.111.222.2"
        private const val DNS_PORT = 53
        private const val MAX_PACKET_BYTES = 4096

        /** Long enough for Android to finish revoking, short enough that the gap is a blink. */
        private const val RECLAIM_DELAY_MILLIS = 1200L
    }
}
