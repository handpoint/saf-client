package com.handpoint.safclient.domain.usecase

import com.handpoint.safclient.domain.SalesJournal
import com.handpoint.safclient.domain.StoreAndForwardTerminal
import com.handpoint.safclient.domain.model.ProcessingStatus
import com.handpoint.safclient.domain.model.SaleReference
import java.time.Clock
import javax.inject.Inject

/**
 * Asks the Handpoint gateway what happened to a sale and saves the answer in the journal.
 *
 * Answers can be stale, so the journal never goes backwards: a final status
 * ([ProcessingStatus.isFinal]) never changes, and IN_PROGRESS never goes back to STORED.
 *
 * The terminal is the authority for STORED (see [ReconcileStoredSales]): a refresh never
 * moves a sale into STORED, and moves a STORED sale only to a final status. Only the
 * terminal's list of stored sales moves a sale out of STORED to IN_PROGRESS or NOT_FOUND.
 *
 * Returns the gateway's answer, or `null` when there is none (then nothing changes).
 */
class RefreshSaleStatus @Inject constructor(
    private val terminal: StoreAndForwardTerminal,
    private val journal: SalesJournal,
    private val clock: Clock,
) {
    suspend operator fun invoke(reference: SaleReference): ProcessingStatus? {
        val status = terminal.processingStatus(reference) ?: return null
        val current = journal.find(reference)?.status ?: return status
        val allowed = when {
            current.isFinal -> false
            current == ProcessingStatus.STORED -> status.isFinal
            else -> status != ProcessingStatus.STORED
        }
        if (allowed) {
            journal.updateStatus(reference, status, clock.instant())
        }
        return status
    }
}
