package com.onyx.browser.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Monitors network state transitions using ConnectivityManager.NetworkCallback.
 * Enables auto-pause on disconnection / Wi-Fi loss and auto-resume when online.
 */
class NetworkMonitor(
    context: Context,
    private val onNetworkChanged: (isAvailable: Boolean, isWifi: Boolean) -> Unit
) {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    @Volatile
    private var isConnected = false
    @Volatile
    private var isWifi = false
    private var isRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            checkCurrentNetwork()
        }

        override fun onLost(network: Network) {
            checkCurrentNetwork()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            checkCurrentNetwork()
        }
    }

    fun start() {
        val cm = connectivityManager ?: return
        if (isRegistered) return
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, networkCallback)
            isRegistered = true
            checkCurrentNetwork()
        } catch (_: Exception) {}
    }

    fun stop() {
        val cm = connectivityManager ?: return
        if (!isRegistered) return
        try {
            cm.unregisterNetworkCallback(networkCallback)
            isRegistered = false
        } catch (_: Exception) {}
    }

    private fun checkCurrentNetwork() {
        val cm = connectivityManager ?: return
        val activeNetwork = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(activeNetwork)
        val connected = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val wifi = caps != null && (
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        )

        val changed = (connected != isConnected || wifi != isWifi)
        isConnected = connected
        isWifi = wifi

        if (changed) {
            onNetworkChanged(connected, wifi)
        }
    }

    fun isOnline(): Boolean = isConnected
    fun isWifiConnected(): Boolean = isWifi
}
