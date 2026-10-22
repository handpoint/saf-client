package com.handpoint.safclient.domain.usecase

import com.handpoint.safclient.domain.SalesJournal
import com.handpoint.safclient.domain.StoreAndForwardTerminal
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.SaleOutcome
import javax.inject.Inject

/**
 * Takes one sale on the terminal and records an offline approval in the journal.
 *
 * When the outcome is unknown (no result in time), the terminal may still have stored the
 * sale. This use case then reconciles the journal with the terminal (see [ReconcileStoredSales]):
 * journal sales the terminal no longer holds are marked sent, and the terminal's currency is
 * adopted. A sale the terminal stored but never reported is not added to the journal.
 *
 * The first call may create the SDK: call it off the main thread.
 */
class TakeSale @Inject constructor(
    private val terminal: StoreAndForwardTerminal,
    private val journal: SalesJournal,
    private val reconcile: ReconcileStoredSales,
) {
    suspend operator fun invoke(amount: Money): SaleOutcome {
        val outcome = terminal.sale(amount)
        when {
            outcome is SaleOutcome.ApprovedOffline -> journal.record(outcome.sale)
            outcome == SaleOutcome.Failed(StoreAndForwardTerminal.OUTCOME_UNKNOWN) -> reconcile()
        }
        return outcome
    }
}
