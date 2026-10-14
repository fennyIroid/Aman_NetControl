package com.example.childsafetyimpl.platform.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VPN and encrypted-DNS detection.
 *
 * Two independent checks, on purpose. The transport capability is the documented route; the tun
 * interface scan catches configurations the capability check has been observed to miss on some
 * builds. Neither can tell you which app owns the tunnel, so this app has to exclude its own
 * service by asking it directly rather than by inspecting the network.
 */
@Singleton
class VpnDetector @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val connectivityManager: ConnectivityManager,
) {

    data class VpnStatus(
        val vpnActive: Boolean,
        val ownTunnelActive: Boolean,
        val detectedByCapability: Boolean,
        val detectedByInterface: Boolean,
        val tunnelInterfaces: List<String>,
        val privateDnsMode: String?,
        val privateDnsHost: String?,
    ) {
        /** A tunnel that is not ours is the bypass signal worth raising. */
        val thirdPartyVpnActive: Boolean get() = vpnActive && !ownTunnelActive

        val encryptedDnsActive: Boolean
            get() = privateDnsMode != null && privateDnsMode != "off" && privateDnsMode != "opportunistic"
    }

    fun currentStatus(): VpnStatus {
        val byCapability = hasVpnTransport()
        val interfaces = tunnelInterfaces()
        // The only tunnel this app ever raises is the pause, so that is the one to discount.
        val ownTunnel = PauseTunnelService.State.running.value
        return VpnStatus(
            vpnActive = byCapability || interfaces.isNotEmpty(),
            ownTunnelActive = ownTunnel,
            detectedByCapability = byCapability,
            detectedByInterface = interfaces.isNotEmpty(),
            tunnelInterfaces = interfaces,
            privateDnsMode = privateDnsMode(),
            privateDnsHost = privateDnsHost(),
        )
    }

    /** The documented check: does any network this app can see carry the VPN transport? */
    private fun hasVpnTransport(): Boolean = try {
        connectivityManager.allNetworks.any { network ->
            connectivityManager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    } catch (_: SecurityException) {
        false
    }

    /**
     * Corroborating check: a running tunnel produces an up interface named tun or ppp.
     *
     * Kept because the capability check depends on the app being able to see the network at all,
     * and that has proven inconsistent on a few OEM builds.
     */
    private fun tunnelInterfaces(): List<String> = try {
        NetworkInterface.getNetworkInterfaces()
            ?.toList()
            ?.filter { it.isUp && (it.name.startsWith("tun") || it.name.startsWith("ppp")) }
            ?.map { it.name }
            .orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Private DNS mode, read from global settings.
     *
     * The key is not a public constant, so this is defensive by necessity. Private DNS is how this
     * product filters, so whether the resolver it points at is a filtering one is judged by the
     * caller against [PrivateDnsGuard], not here.
     */
    private fun privateDnsMode(): String? = try {
        Settings.Global.getString(context.contentResolver, "private_dns_mode")
    } catch (_: Exception) {
        null
    }

    private fun privateDnsHost(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return try {
            val active = connectivityManager.activeNetwork ?: return null
            connectivityManager.getLinkProperties(active)?.privateDnsServerName
        } catch (_: Exception) {
            null
        }
    }

    /** Emits whenever the VPN picture changes, so the monitor can raise an event promptly. */
    fun observeStatus(): Flow<VpnStatus> = callbackFlow {
        trySend(currentStatus())

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(currentStatus()) }
            override fun onLost(network: Network) { trySend(currentStatus()) }
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                trySend(currentStatus())
            }
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                trySend(currentStatus())
            }
        }

        try {
            connectivityManager.registerDefaultNetworkCallback(callback)
        } catch (_: Exception) {
            // A registration failure leaves the initial snapshot in place rather than crashing.
        }

        awaitClose {
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
                // Already unregistered.
            }
        }
    }.distinctUntilChanged()
}
