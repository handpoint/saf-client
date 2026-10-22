package com.handpoint.safclient.domain

import com.handpoint.safclient.domain.model.DeclineReason
import com.handpoint.safclient.domain.model.DeleteResult
import com.handpoint.safclient.domain.model.ForwardingEvent
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.ProcessingStatus
import com.handpoint.safclient.domain.model.SaleOutcome
import com.handpoint.safclient.domain.model.SaleReference
import com.handpoint.safclient.domain.model.StoredSummary
import kotlinx.coroutines.flow.SharedFlow

/**
 * The terminal's Store and Forward API, as the rest of the app sees it.
 *
 * It cannot run at the same time as [OnlineTerminal]; see [PaymentTerminal]. Only this API can
 * send stored sales ([forward]) and ask for their status ([processingStatus]).
 *
 * This port hides the Handpoint SDK. Its callbacks become flows and its
 * request/callback pairs become `suspend` functions that always finish: they return a
 * result, or a defined failure value on timeout. They never hang.
 *
 * The production implementation is
 * [com.handpoint.safclient.data.handpoint.HandpointSafTerminal].
 */
interface StoreAndForwardTerminal : PaymentTerminal {

    /** Progress of a forwarding run started with [forward]. Hot; no replay. */
    val forwarding: SharedFlow<ForwardingEvent>

    /**
     * Starts the SDK. Returns `false` when the SDK refuses.
     *
     * The first call (of this or any other member) may create the SDK, which is heavy setup:
     * call it off the main thread. After [deactivate], only [activate] creates it again.
     */
    override fun connect(): Boolean

    /** Stops the SDK. Returns `false` when the SDK refuses. */
    override fun disconnect(): Boolean

    /**
     * Takes a sale and waits for its end. Every sale through this API is stored on the
     * terminal; an approval is [SaleOutcome.ApprovedOffline].
     *
     * When the SDK does not start the sale, returns [SaleOutcome.Declined] if the SDK gave a
     * known reason (for example [DeclineReason.CURRENCY_NOT_ACCEPTED]), else [SaleOutcome.Failed].
     *
     * When no result arrives within 180 seconds, or the caller is cancelled, the sale is asked
     * to stop and `sale` returns [SaleOutcome.Failed] with message [OUTCOME_UNKNOWN] (a cancelled
     * caller gets the cancellation).
     * **Outcome unknown** means the terminal may still have approved and stored the sale: call
     * [storedOnTerminal] to reconcile before the merchant tries again, or the card can be
     * charged twice.
     */
    suspend fun sale(amount: Money): SaleOutcome

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

    companion object {
        /** Message of the [SaleOutcome.Failed] returned when a sale's outcome is unknown. */
        const val OUTCOME_UNKNOWN = "Outcome unknown: no result from the terminal in time"
    }
}
