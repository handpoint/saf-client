package com.handpoint.safclient.domain

import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.OperationReport
import com.handpoint.safclient.domain.model.OperationRequest
import com.handpoint.safclient.domain.model.SaleOutcome
import com.handpoint.safclient.domain.model.SaleSurcharge

/**
 * The terminal's online API: Handpoint approves or declines each sale at once. Nothing is
 * stored on the terminal, so there is nothing to send later.
 *
 * It cannot run at the same time as [StoreAndForwardTerminal]; see [PaymentTerminal].
 *
 * The production implementation is
 * [com.handpoint.safclient.data.handpoint.HandpointOnlineTerminal].
 */
interface OnlineTerminal : PaymentTerminal {

    /**
     * Takes a sale and waits for its end. [amount] is the base amount; a [surcharge] is sent
     * next to it (`SaleOptions.surchargeAmount`), and the gateway decides whether to apply it.
     *
     * - Approved: [SaleOutcome.ApprovedOnline]; the card has been charged.
     * - Declined: [SaleOutcome.DeclinedOnline] with the SDK's message.
     * - Cancelled before the card was read: [SaleOutcome.Cancelled].
     * - Not started by the SDK: [SaleOutcome.Failed] with the SDK's message.
     *
     * When no result arrives within 180 seconds, or the caller is cancelled, the sale is asked
     * to stop and `sale` returns [SaleOutcome.Failed] with message [OUTCOME_UNKNOWN] (a
     * cancelled caller gets the cancellation). Any result the app does not expect is also
     * [OUTCOME_UNKNOWN]. The failure carries the sale's reference when the sale started.
     * **Outcome unknown** means the card may have been charged: do not charge again before you
     * know.
     */
    suspend fun sale(amount: Money, surcharge: SaleSurcharge? = null): SaleOutcome

    /**
     * Test only: starts [request] (a sale, a refund, a MoTo operation...) and waits for its end.
     * The report says whether the SDK started it (`operationStarted`) and carries the values of
     * the `TransactionResult`. After 180 seconds, or when the caller is cancelled, the operation
     * is asked to stop.
     */
    suspend fun run(request: OperationRequest): OperationReport = OperationReport("Operations are not supported")

    companion object {
        /** Message of the [SaleOutcome.Failed] returned when an online sale's outcome is unknown. */
        const val OUTCOME_UNKNOWN = "Outcome unknown: no online result from the terminal in time"
    }
}
