package uk.org.retallack.dronecamera

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PatternMatcher
import androidx.annotation.RequiresApi

/**
 * Handles automatic WiFi connection to the HASAKEE drone.
 * API 29+: uses WifiNetworkSpecifier for programmatic connection.
 * API 26-28: checks if already on drone WiFi, otherwise prompts user.
 */
class WifiConnector(private val context: Context) {

    companion object {
        const val SSID_PREFIX = "HASAKEE"
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun connect(onNetworkAvailable: (Network) -> Unit, onUnavailable: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectWithSpecifier(onNetworkAvailable, onUnavailable)
        } else {
            checkExistingConnection(onNetworkAvailable, onUnavailable)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun connectWithSpecifier(onNetworkAvailable: (Network) -> Unit, onUnavailable: () -> Unit) {
        val specifier = android.net.wifi.WifiNetworkSpecifier.Builder()
            .setSsidPattern(PatternMatcher(SSID_PREFIX, PatternMatcher.PATTERN_PREFIX))
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onNetworkAvailable(network)
            override fun onUnavailable() = onUnavailable()
        }
        networkCallback = callback
        connectivityManager.requestNetwork(request, callback)
    }

    @Suppress("DEPRECATION")
    private fun checkExistingConnection(onNetworkAvailable: (Network) -> Unit, onUnavailable: () -> Unit) {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ssid = wifiManager.connectionInfo?.ssid?.replace("\"", "") ?: ""
        if (ssid.startsWith(SSID_PREFIX)) {
            connectivityManager.activeNetwork?.let(onNetworkAvailable) ?: onUnavailable()
        } else {
            onUnavailable()
        }
    }

    fun disconnect() {
        networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        networkCallback = null
    }
}
