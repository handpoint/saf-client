package com.handpoint.safclient.domain

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
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The payment terminal, as the rest of the app sees it: Store and Forward only.
 *
 * This port hides the Handpoint SDK. Its callbacks become [Flow]s and its
 * request/callback pairs become `suspend` functions that always finish: they return a
 * result, or a defined failure value on timeout. They never hang.
 *
 * The production implementation is
 * [com.handpoint.safclient.data.handpoint.HandpointSafTerminal].
 */
interface StoreAndForwardTerminal {

    /** Current connection to the terminal's payment service. */
    val connection: StateFlow<TerminalConnection>

    /** Card prompts during a sale ("Present card", "Enter PIN", ...). Hot; no replay. */
    val cardSteps: Flow<CardStep>

    /** Progress of a forwarding run started with [forward]. Hot; no replay. */
    val forwarding: SharedFlow<ForwardingEvent>

    /** Starts the SDK. Returns `false` when the SDK refuses. */
    fun connect(): Boolean

    /** Stops the SDK. Returns `false` when the SDK refuses. */
    fun disconnect(): Boolean

    /**
     * Takes a sale and waits for its end. Every sale through this API is stored on the
     * terminal; an approval is [SaleOutcome.ApprovedOffline].
     *
     * Returns [SaleOutcome.Failed] when the SDK does not start the sale, or with
     * message `"timeout"` when no result arrives within 180 seconds.
     */
    suspend fun sale(amount: Money): SaleOutcome

    /** Asks the SDK to stop the current sale. Only works before the card is read. */
    fun cancelSale(): Boolean

    /**
     * Sales still stored on the terminal (not yet forwarded) and their total.
     * Returns `null` when the SDK refuses or does not answer within 15 seconds.
     */
    suspend fun storedOnTerminal(): StoredSummary?

    /**
     * Starts one forwarding run. Progress arrives on [forwarding].
     * Returns `false` when the SDK refuses (a run is already busy, or not connected).
     */
    fun forward(): Boolean

    /**
     * Asks the Handpoint gateway what happened to a sale.
     * Returns `null` when the gateway cannot be reached, the SDK refuses, or there is
     * no answer within 20 seconds.
     */
    suspend fun processingStatus(reference: SaleReference): ProcessingStatus?

    /**
     * Deletes a stored sale from the terminal. The sale is then never forwarded.
     * Returns [DeleteResult.ERROR] when the SDK refuses or does not answer within 15 seconds.
     */
    suspend fun delete(reference: SaleReference): DeleteResult

    /** Prints a receipt on the terminal printer. */
    fun printReceipt(receipt: String): Boolean
}
