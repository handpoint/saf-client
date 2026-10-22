package com.handpoint.safclient.domain

import com.handpoint.safclient.domain.model.NetworkStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformLatest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Port over the device's internet connection.
 *
 * [status] is debounced (see [debounceNetwork]): a short drop or a network that comes and goes
 * does not change it, because each change can make the app switch payment APIs, which takes
 * several seconds.
 *
 * The production implementation is [com.handpoint.safclient.data.network.AndroidNetworkMonitor].
 */
interface NetworkMonitor {
    val status: StateFlow<NetworkStatus>
}

/** Time without a validated network before the status turns [NetworkStatus.OFFLINE]. */
val OFFLINE_AFTER: Duration = 3.seconds

/** Time of stable, validated network before the status turns [NetworkStatus.ONLINE]. */
val ONLINE_AFTER: Duration = 5.seconds

/**
 * Turns raw readings ("a validated internet connection is available now") into a debounced
 * [NetworkStatus].
 *
 * The first reading is reported at once. After that, a change is reported only when the new
 * reading has held for [offlineAfter] (going offline) or [onlineAfter] (going online). A
 * reading that flips back before then cancels the change, so a flapping network does not
 * switch the status.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun Flow<Boolean>.debounceNetwork(
    offlineAfter: Duration = OFFLINE_AFTER,
    onlineAfter: Duration = ONLINE_AFTER,
): Flow<NetworkStatus> = flow {
    // Per collection: the last status this collector reported.
    var reported: NetworkStatus? = null
    emitAll(
        distinctUntilChanged().transformLatest { available ->
            val reading = if (available) NetworkStatus.ONLINE else NetworkStatus.OFFLINE
            if (reading == reported) return@transformLatest
            // transformLatest cancels this wait when a newer reading arrives.
            if (reported != null) delay(if (available) onlineAfter else offlineAfter)
            reported = reading
            emit(reading)
        },
    )
}
