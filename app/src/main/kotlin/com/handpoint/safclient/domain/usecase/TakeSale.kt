package com.handpoint.safclient.domain.usecase

import com.handpoint.safclient.domain.ErrorReporter
import com.handpoint.safclient.domain.OnlineTerminal
import com.handpoint.safclient.domain.PaymentModeCoordinator
import com.handpoint.safclient.domain.SalesJournal
import com.handpoint.safclient.domain.StoreAndForwardTerminal
import com.handpoint.safclient.domain.model.EntryMode
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.PaymentMode
import com.handpoint.safclient.domain.model.ProcessingStatus
import com.handpoint.safclient.domain.model.Sale
import com.handpoint.safclient.domain.model.SaleChannel
import com.handpoint.safclient.domain.model.SaleOutcome
import com.handpoint.safclient.domain.model.SaleReference
import com.handpoint.safclient.domain.model.SaleSurcharge
import kotlinx.coroutines.CancellationException
import java.time.Clock
import javax.inject.Inject

/**
 * Takes one sale with the payment API in use ([PaymentModeCoordinator]) and records it in the
 * journal.
 *
 * - **Online:** an approval is recorded as an ONLINE sale with status SUCCEEDED: the card is
 *   charged and nothing is left to send. A decline is not recorded. An unknown outcome is
 *   recorded as an ONLINE sale with status UNKNOWN, so the merchant has a trace to check with
 *   Handpoint; nothing asks for its status automatically.
 * - **Offline (Store and Forward):** an approval is recorded as STORED. When the outcome is
 *   unknown (no result in time), the terminal may still have stored the sale, so this use case
 *   reconciles the journal with the terminal (see [ReconcileStoredSales]): journal sales the
 *   terminal no longer holds are marked sent, and the terminal's currency is adopted. A sale the
 *   terminal stored but never reported is not added to the journal (the SDK gives no details for
 *   it), but it counts in [ReconcileStoredSales.lastSummary], so Home shows it as waiting to send.
 *
 * An error after the sale started (the journal or the reconcile fails) is reported and ends as
 * the unknown outcome of the mode the sale ran in: the sale may have gone through.
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
    private val clock: Clock,
    private val errors: ErrorReporter,
) {
    suspend operator fun invoke(amount: Money, surcharge: SaleSurcharge? = null): SaleOutcome {
        val current = mode.beginSale() ?: return SaleOutcome.Failed(PaymentModeCoordinator.SWITCHING)
        try {
            return when (current) {
                PaymentMode.ONLINE -> online(amount, surcharge)
                PaymentMode.OFFLINE -> offline(amount, surcharge)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errors.report("sale", e)
            return SaleOutcome.Failed(unknownIn(current))
        } finally {
            mode.endSale()
        }
    }

    private suspend fun online(amount: Money, surcharge: SaleSurcharge?): SaleOutcome {
        val outcome = online.sale(amount, surcharge)
        when {
            outcome is SaleOutcome.ApprovedOnline -> journal.record(outcome.sale)
            outcome is SaleOutcome.Failed && outcome.message == OnlineTerminal.OUTCOME_UNKNOWN ->
                outcome.reference?.let { journal.record(unconfirmed(it, amount, surcharge)) }
        }
        return outcome
    }

    private suspend fun offline(amount: Money, surcharge: SaleSurcharge?): SaleOutcome {
        val outcome = saf.sale(amount, surcharge)
        when {
            outcome is SaleOutcome.ApprovedOffline -> journal.record(outcome.sale)
            outcome is SaleOutcome.Failed && outcome.message == StoreAndForwardTerminal.OUTCOME_UNKNOWN -> reconcile()
        }
        return outcome
    }

    /** The trace of an online sale whose outcome is unknown: only what the app knows for sure. */
    private fun unconfirmed(reference: SaleReference, amount: Money, surcharge: SaleSurcharge?) = Sale(
        reference = reference,
        amount = amount,
        maskedCard = null,
        entryMode = EntryMode.UNKNOWN,
        approvedAt = clock.instant(),
        sentAt = null,
        status = ProcessingStatus.UNKNOWN,
        statusCheckedAt = null,
        merchantReceipt = null,
        customerReceipt = null,
        channel = SaleChannel.ONLINE,
        surcharge = surcharge,
    )

    private fun unknownIn(mode: PaymentMode): String = when (mode) {
        PaymentMode.ONLINE -> OnlineTerminal.OUTCOME_UNKNOWN
        PaymentMode.OFFLINE -> StoreAndForwardTerminal.OUTCOME_UNKNOWN
    }
}
