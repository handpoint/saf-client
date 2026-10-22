package com.handpoint.safclient.domain.usecase

import com.handpoint.safclient.domain.ApplicationScope
import com.handpoint.safclient.domain.SalesJournal
import com.handpoint.safclient.domain.StoreAndForwardTerminal
import com.handpoint.safclient.domain.model.ForwardingError
import com.handpoint.safclient.domain.model.ForwardingEvent
import com.handpoint.safclient.domain.model.ForwardingResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends every sale stored on the terminal to Handpoint in one forwarding run.
 *
 * The run belongs to the app, not to the screen: it runs in [appScope], so each sent sale is
 * marked in the journal, and the journal is reconciled at the end, even when nobody collects.
 *
 * Only one run at a time. Calling this while a run is active does not start a second one: it
 * returns the active run's events. Each returned flow replays the run from its start, so a
 * screen that comes back sees the progress so far. The flow completes after
 * [ForwardingEvent.Finished] or [ForwardingEvent.NothingToSend]. Use [isRunning] to disable
 * "Send now" while a run is active.
 */
@Singleton
class SendStoredSales @Inject constructor(
    private val terminal: StoreAndForwardTerminal,
    private val journal: SalesJournal,
    private val reconcile: ReconcileStoredSales,
    private val clock: Clock,
    @param:ApplicationScope private val appScope: CoroutineScope,
) {
    private val lock = Any()
    private var activeRun: Flow<ForwardingEvent>? = null // guarded by lock
    private val running = MutableStateFlow(false)

    /** `true` from the start of a run until its journal updates are done. */
    val isRunning: StateFlow<Boolean> = running.asStateFlow()

    operator fun invoke(): Flow<ForwardingEvent> = synchronized(lock) {
        activeRun ?: startRun().also { activeRun = it }
    }

    private fun startRun(): Flow<ForwardingEvent> {
        val events = MutableSharedFlow<ForwardingEvent>(replay = Int.MAX_VALUE)
        running.value = true
        appScope.launch {
            try {
                forward(events)
            } finally {
                synchronized(lock) {
                    activeRun = null
                    running.value = false
                }
            }
        }
        return events.untilRunEnds()
    }

    private suspend fun forward(events: MutableSharedFlow<ForwardingEvent>) {
        val accepted = coroutineScope {
            // Subscribe before forward(): the terminal's progress flow does not replay.
            val relay = launch(start = CoroutineStart.UNDISPATCHED) {
                terminal.forwarding.untilRunEnds().collect { event ->
                    if (event is ForwardingEvent.Sent) journal.markSent(event.reference, clock.instant())
                    events.emit(event)
                }
            }
            terminal.forward().also { accepted -> if (!accepted) relay.cancel() }
        }
        if (!accepted) {
            events.emit(ForwardingEvent.Finished(ForwardingResult.NoneSent(ForwardingError.FAILED, 0, 0, 0)))
            return
        }
        reconcile()
    }

    private fun Flow<ForwardingEvent>.untilRunEnds(): Flow<ForwardingEvent> = transformWhile { event ->
        emit(event)
        event !is ForwardingEvent.Finished && event != ForwardingEvent.NothingToSend
    }
}
