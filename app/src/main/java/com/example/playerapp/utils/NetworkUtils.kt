package com.example.playerapp.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Synchronous check — safe to call anywhere but reflects only the current link-layer state.
 * For UI, prefer [observeNetworkState] which updates reactively.
 */
fun isOnline(context: Context): Boolean {
    return try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = cm.activeNetworkInfo
            networkInfo != null && networkInfo.isConnected
        }
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

/**
 * Returns a cold [Flow] that emits `true` when an internet-capable network is available and
 * `false` when all such networks are lost.  Uses [ConnectivityManager.NetworkCallback] so the
 * composable receives updates reactively instead of polling.
 *
 * Usage in a Composable:
 * ```
 * val isOnline by observeNetworkState(context).collectAsState(initial = isOnline(context))
 * ```
 */
fun observeNetworkState(context: Context): Flow<Boolean> = callbackFlow {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            trySend(true)
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            val internet  = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            trySend(internet)
        }

        override fun onLost(network: Network) {
            // Re-check: there may be another active network
            trySend(isOnline(context))
        }
    }

    val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()

    // Emit the current state immediately before the callback fires
    trySend(isOnline(context))

    cm.registerNetworkCallback(request, callback)

    awaitClose {
        try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) {}
    }
}.distinctUntilChanged()

