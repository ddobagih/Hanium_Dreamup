package kr.co.hanium.dreamup.walksafe.session.gateway

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kr.co.hanium.dreamup.walksafe.network.ActiveNetworkTransport

/** Creates the same platform callback at registration time; construction reads no service. */
internal class AndroidGatewayCapacityObserver(private val context: Context) : GatewaySessionController.NetworkObserver {
    override fun register(events: GatewaySessionController.NetworkEvents): GatewaySessionController.Registration? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = events.available()
            override fun onLost(network: Network) = events.lost()
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val transport = when {
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ActiveNetworkTransport.WIFI
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ActiveNetworkTransport.CELLULAR
                    else -> ActiveNetworkTransport.OTHER
                }
                events.capabilitiesChanged(network, transport,
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            }
        }
        return runCatching {
            manager.registerDefaultNetworkCallback(callback)
            GatewaySessionController.Registration { manager.unregisterNetworkCallback(callback) }
        }.getOrNull()
    }
}
