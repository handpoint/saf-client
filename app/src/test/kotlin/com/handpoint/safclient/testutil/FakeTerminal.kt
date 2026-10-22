package com.handpoint.safclient.testutil

import com.handpoint.safclient.domain.StoreAndForwardTerminal
import com.handpoint.safclient.domain.model.CardStep
import com.handpoint.safclient.domain.model.DeleteResult
import com.handpoint.safclient.domain.model.ForwardingEvent
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.ProcessingStatus
import com.handpoint.safclient.domain.model.SaleOutcome
import com.handpoint.safclient.domain.model.SaleReference
import com.handpoint.safclient.domain.model.StoredSummary
import com.handpoint.safclient.domain.model.TerminalConnection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Scripted stand-in for the terminal port. Set the answers before the call under test;
 * the counters show how often each member was called.
 */
class FakeTerminal : StoreAndForwardTerminal {

    var saleOutcome: SaleOutcome = SaleOutcome.Cancelled
    var storedSummary: StoredSummary? = StoredSummary(emptyList(), null)
    var statusAnswer: ProcessingStatus? = null
    var deleteResult: DeleteResult = DeleteResult.DELETED
    var forwardAccepted: Boolean = true

    /** Thrown by [forward] when set, as when the SDK cannot be created. */
    var forwardError: Exception? = null

    /** Thrown by [storedOnTerminal] when set. */
    var storedError: Exception? = null

    /** Emitted on [forwarding] as soon as [forward] accepts a run. Leave empty to drive events by hand. */
    var forwardScript: List<ForwardingEvent> = emptyList()

    var saleCalls = 0
        private set
    var forwardCalls = 0
        private set
    var storedCalls = 0
        private set
    var connectCalls = 0
        private set

    /** References asked about through [processingStatus], in call order. */
    val statusRequests = mutableListOf<SaleReference>()

    private val forwardingEvents = MutableSharedFlow<ForwardingEvent>(extraBufferCapacity = 64)

    /** Current connection; set it to script what the app sees. [connect] moves it to CONNECTED. */
    val connectionState = MutableStateFlow(TerminalConnection.CONNECTED)
    override val connection: StateFlow<TerminalConnection> = connectionState
    override val cardSteps: Flow<CardStep> = emptyFlow()
    override val forwarding: SharedFlow<ForwardingEvent> = forwardingEvents.asSharedFlow()

    /** Emits one forwarding event, as the SDK would during a run. */
    suspend fun emitForwarding(event: ForwardingEvent) = forwardingEvents.emit(event)

    override fun connect(): Boolean {
        connectCalls++
        connectionState.value = TerminalConnection.CONNECTED
        return true
    }

    override fun disconnect(): Boolean = true

    override suspend fun sale(amount: Money): SaleOutcome {
        saleCalls++
        return saleOutcome
    }

    override fun cancelSale(): Boolean = true

    override suspend fun storedOnTerminal(): StoredSummary? {
        storedCalls++
        storedError?.let { throw it }
        return storedSummary
    }

    override fun forward(): Boolean {
        forwardCalls++
        forwardError?.let { throw it }
        if (forwardAccepted) forwardScript.forEach { check(forwardingEvents.tryEmit(it)) }
        return forwardAccepted
    }

    override suspend fun processingStatus(reference: SaleReference): ProcessingStatus? {
        statusRequests += reference
        return statusAnswer
    }

    override suspend fun delete(reference: SaleReference): DeleteResult = deleteResult

    override fun printReceipt(receipt: String): Boolean = true
}
