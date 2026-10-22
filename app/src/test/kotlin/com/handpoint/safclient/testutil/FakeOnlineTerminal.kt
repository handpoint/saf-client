package com.handpoint.safclient.testutil

import com.handpoint.safclient.domain.OnlineTerminal
import com.handpoint.safclient.domain.model.CardStep
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.SaleOutcome
import com.handpoint.safclient.domain.model.TerminalConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Scripted stand-in for the online terminal port. Starts deactivated and disconnected. */
class FakeOnlineTerminal : OnlineTerminal {

    var saleOutcome: SaleOutcome = SaleOutcome.Cancelled

    /** When set, [sale] waits for it before it answers, to hold a sale in progress. */
    var saleGate: CompletableDeferred<Unit>? = null

    /** When `false`, [activate] leaves the connection DISCONNECTED: the online API does not come up. */
    var connectsOnActivate: Boolean = true

    /** Thrown by [activate] when set, as when the SDK cannot be created. */
    var activateError: Exception? = null

    var cancelAccepted: Boolean = true
    var printAccepted: Boolean = true

    var activateCalls = 0
        private set
    var deactivateCalls = 0
        private set
    var connectCalls = 0
        private set
    var saleCalls = 0
        private set
    var cancelCalls = 0
        private set
    val printed = mutableListOf<String>()

    val connectionState = MutableStateFlow(TerminalConnection.DISCONNECTED)
    override val connection: StateFlow<TerminalConnection> = connectionState

    private val cardStepEvents = MutableSharedFlow<CardStep>(extraBufferCapacity = 16)
    override val cardSteps: Flow<CardStep> = cardStepEvents.asSharedFlow()

    suspend fun emitCardStep(step: CardStep) = cardStepEvents.emit(step)

    override fun activate(): Boolean {
        activateCalls++
        activateError?.let { throw it }
        if (connectsOnActivate) connectionState.value = TerminalConnection.CONNECTED
        return true
    }

    override fun deactivate(): Boolean {
        deactivateCalls++
        connectionState.value = TerminalConnection.DISCONNECTED
        return true
    }

    override fun connect(): Boolean {
        connectCalls++
        connectionState.value = TerminalConnection.CONNECTED
        return true
    }

    override fun disconnect(): Boolean {
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

    override fun printReceipt(receipt: String): Boolean {
        printed += receipt
        return printAccepted
    }
}
