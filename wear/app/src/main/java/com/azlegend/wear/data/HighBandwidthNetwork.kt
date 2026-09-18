package com.azlegend.wear.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Wear OS keeps Wi-Fi/LTE off unless an app asks for a high-bandwidth network. This requests one
 * (Wi-Fi or cellular with internet) and hands back the [Network] to open connections on. Call
 * [release] when the transfer is finished so the radio can go back to sleep.
 */
class HighBandwidthNetwork(context: Context) {

    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** Returns a high-bandwidth network, or null if none came up within [timeoutMs]. */
    suspend fun acquire(timeoutMs: Int = DEFAULT_TIMEOUT_MS): Network? {
        release()
        val cm = connectivity ?: return null
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()
        return suspendCancellableCoroutine { continuation ->
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (continuation.isActive) continuation.resume(network)
                }

                override fun onUnavailable() {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
            callback = cb
            try {
                cm.requestNetwork(request, cb, timeoutMs)
            } catch (e: RuntimeException) {
                // Too many callbacks registered, or permission problems: fall back to the default network.
                callback = null
                if (continuation.isActive) continuation.resume(null)
            }
            continuation.invokeOnCancellation { release() }
        }
    }

    /** Best-effort description of the network currently used for internet, for the UI. */
    fun describeActiveNetwork(): String? {
        val cm = connectivity ?: return null
        return describe(cm.activeNetwork ?: return null)
    }

    /** "Wi-Fi", "LTE", ... for [network]; "phone link" when we fell back to the default network. */
    fun describe(network: Network?): String {
        val cm = connectivity
        val caps = network?.let { cm?.getNetworkCapabilities(it) } ?: return "phone link"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "LTE"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "phone link"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Internet"
        }
    }

    fun hasInternet(): Boolean {
        val cm = connectivity ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun release() {
        val cb = callback ?: return
        callback = null
        runCatching { connectivity?.unregisterNetworkCallback(cb) }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000
    }
}
