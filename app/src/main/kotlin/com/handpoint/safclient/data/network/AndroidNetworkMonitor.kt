package com.handpoint.safclient.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.handpoint.safclient.domain.NetworkMonitor
import com.handpoint.safclient.domain.debounceNetwork
import com.handpoint.safclient.domain.model.NetworkStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn

/**
 * [NetworkMonitor] over Android's [ConnectivityManager].
 *
 * It follows the default network with `registerDefaultNetworkCallback` and counts it as
 * available only when it has [NetworkCapabilities.NET_CAPABILITY_INTERNET] and
 * [NetworkCapabilities.NET_CAPABILITY_VALIDATED]: Android has checked that it reaches the
 * internet. A Wi-Fi without internet is not enough to take online sales. The readings are
 * debounced in domain code ([debounceNetwork]). Needs the `ACCESS_NETWORK_STATE` permission.
 *
 * @param scope keeps the callback registered for as long as the app runs.
 */
class AndroidNetworkMonitor(context: Context, scope: CoroutineScope) : NetworkMonitor {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    private val validated = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(capabilities.isValidatedInternet())
            }

            override fun onLost(network: Network) {
                trySend(false)
            }
        }
        // The callback reports the current default network soon after registering; send the
        // current state first so the status is right from the start.
        trySend(isAvailableNow())
        connectivity.registerDefaultNetworkCallback(callback)
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }

    override val status: StateFlow<NetworkStatus> = validated
        .debounceNetwork()
        .stateIn(scope, SharingStarted.Eagerly, if (isAvailableNow()) NetworkStatus.ONLINE else NetworkStatus.OFFLINE)

    private fun isAvailableNow(): Boolean =
        connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.isValidatedInternet() == true

    private fun NetworkCapabilities.isValidatedInternet(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
