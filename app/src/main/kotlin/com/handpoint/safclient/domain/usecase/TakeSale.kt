package com.handpoint.safclient.domain.usecase

import com.handpoint.safclient.domain.OnlineTerminal
import com.handpoint.safclient.domain.PaymentModeCoordinator
import com.handpoint.safclient.domain.SalesJournal
import com.handpoint.safclient.domain.StoreAndForwardTerminal
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.PaymentMode
import com.handpoint.safclient.domain.model.SaleOutcome
import javax.inject.Inject

/**
 * Takes one sale with the payment API in use ([PaymentModeCoordinator]) and records each
 * approval in the journal.
 *
 * - **Online:** an approval is recorded as an ONLINE sale with status SUCCEEDED: the card is
 *   charged and nothing is left to send. A decline is not recorded.
 * - **Offline (Store and Forward):** an approval is recorded as STORED. When the outcome is
 *   unknown (no result in time), the terminal may still have stored the sale, so this use case
 *   reconciles the journal with the terminal (see [ReconcileStoredSales]): journal sales the
 *   terminal no longer holds are marked sent, and the terminal's currency is adopted. A sale the
 *   terminal stored but never reported is not added to the journal (the SDK gives no details for
 *   it), but it counts in [ReconcileStoredSales.lastSummary], so Home shows it as waiting to send.
 *
 * The mode cannot switch while the sale runs. While a switch runs, no sale starts: the result is
 * [SaleOutcome.Failed] with [PaymentModeCoordinator.SWITCHING].
 *
 * The first call may create the SDK: call it off the main thread.
 */
class TakeSale @Inject constructor(
    private val saf: StoreAndForwardTerminal,
    private val online: OnlineTerminal,
    private val journal: SalesJournal,
    private val reconcile: ReconcileStoredSales,
    private val mode: PaymentModeCoordinator,
) {
    suspend operator fun invoke(amount: Money): SaleOutcome {
        val current = mode.beginSale() ?: return SaleOutcome.Failed(PaymentModeCoordinator.SWITCHING)
        try {
            return when (current) {
                PaymentMode.ONLINE -> online(amount)
                PaymentMode.OFFLINE -> offline(amount)
            }
        } finally {
            mode.endSale()
        }
    }

    private suspend fun online(amount: Money): SaleOutcome {
        val outcome = online.sale(amount)
        if (outcome is SaleOutcome.ApprovedOnline) journal.record(outcome.sale)
        return outcome
    }

    private suspend fun offline(amount: Money): SaleOutcome {
        val outcome = saf.sale(amount)
        when {
            outcome is SaleOutcome.ApprovedOffline -> journal.record(outcome.sale)
            outcome == SaleOutcome.Failed(StoreAndForwardTerminal.OUTCOME_UNKNOWN) -> reconcile()
        }
        return outcome
    }
}
