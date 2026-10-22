package com.handpoint.safclient.domain.usecase

import com.handpoint.safclient.domain.SalesJournal
import com.handpoint.safclient.domain.StoreAndForwardTerminal
import com.handpoint.safclient.domain.model.DeleteResult
import com.handpoint.safclient.domain.model.ProcessingStatus
import com.handpoint.safclient.domain.model.SaleReference
import java.time.Clock
import javax.inject.Inject

/**
 * Deletes a stored sale from the terminal, so it is never forwarded.
 *
 * - [DeleteResult.DELETED]: the sale was never charged, so a STORED journal entry goes too.
 * - [DeleteResult.NOT_ON_TERMINAL]: the terminal no longer holds the sale, so it was most
 *   likely forwarded and charged. The journal keeps it and marks it sent (IN_PROGRESS); the
 *   caller tells the merchant it cannot be deleted.
 * - [DeleteResult.ERROR]: nothing changes.
 *
 * In both first cases the sale leaves [ReconcileStoredSales.lastSummary].
 */
class DeleteStoredSale @Inject constructor(
    private val terminal: StoreAndForwardTerminal,
    private val journal: SalesJournal,
    private val reconcile: ReconcileStoredSales,
    private val clock: Clock,
) {
    suspend operator fun invoke(reference: SaleReference): DeleteResult {
        val result = terminal.delete(reference)
        val sale = journal.find(reference)
        when (result) {
            DeleteResult.DELETED -> if (sale?.status == ProcessingStatus.STORED) journal.remove(reference)
            DeleteResult.NOT_ON_TERMINAL -> journal.markSent(reference, clock.instant())
            DeleteResult.ERROR -> return result
        }
        reconcile.leftTerminal(reference, sale?.amount)
        return result
    }
}
