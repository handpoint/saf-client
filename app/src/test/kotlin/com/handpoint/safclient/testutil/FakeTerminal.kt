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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow

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

    /** When set, [storedOnTerminal] waits for it before it answers, to hold a reconcile mid-way. */
    var storedGate: CompletableDeferred<Unit>? = null

    /** Thrown by [connect] when set, as when the SDK cannot be created. */
    var connectError: Exception? = null

    /** When set, [processingStatus] waits for it before it answers, to hold a refresh mid-way. */
    var statusGate: CompletableDeferred<Unit>? = null

    /** Thrown by [processingStatus] when set. */
    var statusError: Exception? = null

    /** When set, [sale] waits for it before it answers, to hold a sale in progress. */
    var saleGate: CompletableDeferred<Unit>? = null

    /** Answer of [cancelSale]; `false` acts as a card that has already been read. */
    var cancelAccepted: Boolean = true

    /** Answer of [printReceipt]; `false` acts as a terminal without a printer. */
    var printAccepted: Boolean = true

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
    var cancelCalls = 0
        private set

    /** Receipts sent to [printReceipt], in call order. */
    val printed = mutableListOf<String>()

    /** References asked about through [processingStatus], in call order. */
    val statusRequests = mutableListOf<SaleReference>()

    private val forwardingEvents = MutableSharedFlow<ForwardingEvent>(extraBufferCapacity = 64)

    /** Current connection; set it to script what the app sees. [connect] moves it to CONNECTED. */
    val connectionState = MutableStateFlow(TerminalConnection.CONNECTED)
    override val connection: StateFlow<TerminalConnection> = connectionState
    private val cardStepEvents = MutableSharedFlow<CardStep>(extraBufferCapacity = 16)
    override val cardSteps: Flow<CardStep> = cardStepEvents.asSharedFlow()

    /** Emits one card prompt, as the SDK would during a sale. */
    suspend fun emitCardStep(step: CardStep) = cardStepEvents.emit(step)
    override val forwarding: SharedFlow<ForwardingEvent> = forwardingEvents.asSharedFlow()

    /** Emits one forwarding event, as the SDK would during a run. */
    suspend fun emitForwarding(event: ForwardingEvent) = forwardingEvents.emit(event)

    override fun connect(): Boolean {
        connectCalls++
        connectError?.let { throw it }
        connectionState.value = TerminalConnection.CONNECTED
        return true
    }

    var disconnectCalls = 0
        private set

    override fun disconnect(): Boolean {
        disconnectCalls++
        connectionState.value = TerminalConnection.DISCONNECTED
        return true
    }

    override suspend fun sale(amount: Money): SaleOutcome {
        saleCalls++
        saleGate?.await()
        return saleOutcome
    }

    override fun cancelSale(): Boolean {
        cancelCalls++
        return cancelAccepted
    }

    override suspend fun storedOnTerminal(): StoredSummary? {
        storedCalls++
        storedGate?.await()
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
        statusGate?.await()
        statusError?.let { throw it }
        return statusAnswer
    }

    override suspend fun delete(reference: SaleReference): DeleteResult = deleteResult

    override fun printReceipt(receipt: String): Boolean {
        printed += receipt
        return printAccepted
    }
}
