package com.handpoint.safclient.testutil

import com.handpoint.safclient.domain.NetworkMonitor
import com.handpoint.safclient.domain.model.NetworkStatus
import kotlinx.coroutines.flow.MutableStateFlow

/** [NetworkMonitor] whose (already debounced) status a test sets by hand. */
class FakeNetworkMonitor(initial: NetworkStatus = NetworkStatus.OFFLINE) : NetworkMonitor {
    override val status = MutableStateFlow(initial)
}
