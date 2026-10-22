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
        val wouldRegress = current == ProcessingStatus.IN_PROGRESS && status == ProcessingStatus.STORED
        if (!current.isFinal && !wouldRegress) {
            journal.updateStatus(reference, status, clock.instant())
        }
        return status
    }
}
