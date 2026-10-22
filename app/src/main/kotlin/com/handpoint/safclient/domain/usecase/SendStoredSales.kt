package com.handpoint.safclient.domain.usecase

import com.handpoint.safclient.domain.ApplicationScope
import com.handpoint.safclient.domain.SalesJournal
import com.handpoint.safclient.domain.StoreAndForwardTerminal
import com.handpoint.safclient.domain.model.ForwardingError
import com.handpoint.safclient.domain.model.ForwardingEvent
import com.handpoint.safclient.domain.model.ForwardingResult
import com.handpoint.safclient.domain.model.SaleReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
 *
 * Every run ends with a terminal event, also when something goes wrong:
 * - no SDK event for [INACTIVITY_TIMEOUT]: the run ends with a [ForwardingError.NETWORK] result;
 * - an error (for example the SDK cannot be created): the run ends with a
 *   [ForwardingError.FAILED] result, and the error goes to the scope's exception handler.
 * Sales not reported as sent stay STORED, so the merchant can try again.
 */
@Singleton
class SendStoredSales @Inject constructor(
    private val terminal: StoreAndForwardTerminal,
    private val journal: SalesJournal,
    private val reconcile: ReconcileStoredSales,
    private val statuses: RefreshOpenStatuses,
    private val clock: Clock,
    @param:ApplicationScope private val appScope: CoroutineScope,
) {
    private val lock = Any()
    private var activeRun: Flow<ForwardingEvent>? = null // guarded by lock
    private val running = MutableStateFlow(false)

    /** `true` from the start of a run until its journal updates, reconcile included, are done. */
    val isRunning: StateFlow<Boolean> = running.asStateFlow()

    /**
     * Starts a forwarding run, or joins the active one. Returns the run's events from its start.
     *
     * The run includes the reconcile after the last event: [isRunning] stays `true` until that
     * is done, although the returned flow already completed.
     */
    operator fun invoke(): Flow<ForwardingEvent> = synchronized(lock) {
        activeRun ?: startRun().also { activeRun = it }
    }

    private fun startRun(): Flow<ForwardingEvent> {
        val current = Run(MutableSharedFlow(replay = Int.MAX_VALUE))
        running.value = true
        appScope.launch {
            var error: Throwable? = null
            try {
                try {
                    current.forward()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e
                    current.end(current.result(ForwardingError.FAILED))
                }
                if (current.accepted) {
                    try {
                        reconcile()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (error == null) error = e else error.addSuppressed(e)
                    }
                }
            } finally {
                synchronized(lock) {
                    activeRun = null
                    running.value = false
                }
            }
            // Handpoint processes the forwarded sales asynchronously: ask for their result later.
            statuses.followUp(current.sentReferences)
            // The run has ended cleanly for its collectors; report the error to the scope's handler.
            error?.let { throw it }
        }
        return current.events.untilRunEnds()
    }

    /** State of one forwarding run. */
    private inner class Run(val events: MutableSharedFlow<ForwardingEvent>) {
        var accepted = false
        val sentReferences = mutableListOf<SaleReference>()
        private var ended = false
        private var sent = 0
        private var total = 0

        suspend fun forward() = coroutineScope {
            val inbox = Channel<ForwardingEvent>(Channel.UNLIMITED)
            // Subscribe before forward(): the terminal's progress flow does not replay.
            val subscription = launch(start = CoroutineStart.UNDISPATCHED) {
                terminal.forwarding.collect { inbox.send(it) }
            }
            try {
                accepted = terminal.forward()
                if (!accepted) {
                    end(ForwardingEvent.Finished(ForwardingResult.NoneSent(ForwardingError.FAILED, 0, 0, 0)))
                    return@coroutineScope
                }
                while (!ended) {
                    val event = withTimeoutOrNull(INACTIVITY_TIMEOUT) { inbox.receive() }
                    if (event == null) {
                        end(result(ForwardingError.NETWORK))
                    } else {
                        relay(event)
                    }
                }
            } finally {
                subscription.cancel()
            }
        }

        private suspend fun relay(event: ForwardingEvent) {
            when (event) {
                is ForwardingEvent.Started -> total = event.total
                is ForwardingEvent.Sent -> {
                    journal.markSent(event.reference, clock.instant())
                    sentReferences += event.reference
                    sent = event.sent
                    total = event.total
                }
                else -> Unit
            }
            if (event.endsRun()) end(event) else events.emit(event)
        }

        /** Ends the run with [event], unless it has already ended. */
        suspend fun end(event: ForwardingEvent) {
            if (ended) return
            ended = true
            events.emit(event)
        }

        /** A result for a run that stopped early, built from the progress so far. */
        fun result(error: ForwardingError): ForwardingEvent.Finished = ForwardingEvent.Finished(
            when {
                sent > 0 -> ForwardingResult.Partial(sent, 0, total - sent, total, error)
                error == ForwardingError.NETWORK -> ForwardingResult.NoneSent(error, 0, total, total)
                else -> ForwardingResult.NoneSent(error, 0, 0, 0)
            },
        )
    }

    private fun Flow<ForwardingEvent>.untilRunEnds(): Flow<ForwardingEvent> = transformWhile { event ->
        emit(event)
        !event.endsRun()
    }

    private fun ForwardingEvent.endsRun() =
        this is ForwardingEvent.Finished || this == ForwardingEvent.NothingToSend

    companion object {
        /**
         * Longest wait for the next SDK event during a run, counted from `forward()` and reset by
         * every event. When it passes, the run ends as a network stop with the progress so far.
         */
        val INACTIVITY_TIMEOUT: Duration = 60.seconds
    }
}
