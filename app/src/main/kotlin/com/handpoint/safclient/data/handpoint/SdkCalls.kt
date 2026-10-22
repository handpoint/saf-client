package com.handpoint.safclient.data.handpoint

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Subscribes to [events] now, then runs [block] with everything received from this moment.
 *
 * Call the SDK inside [block]. A callback that the SDK delivers on another thread while the
 * SDK call is still running is kept in a buffer, so [block] cannot miss it. The subscription
 * ends when [block] returns or is cancelled.
 */
internal suspend fun <E, R> listenTo(events: Flow<E>, block: suspend (received: Flow<E>) -> R): R =
    coroutineScope {
        val received = Channel<E>(Channel.UNLIMITED)
        // UNDISPATCHED: the collector subscribes before launch() returns, not later.
        val listener = launch(start = CoroutineStart.UNDISPATCHED) {
            events.collect { received.send(it) }
        }
        try {
            block(received.receiveAsFlow())
        } finally {
            listener.cancel()
        }
    }
