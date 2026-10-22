package com.handpoint.safclient.domain.usecase

import com.handpoint.safclient.domain.SalesJournal
import com.handpoint.safclient.domain.StoreAndForwardTerminal
import com.handpoint.safclient.domain.model.DeleteResult
import com.handpoint.safclient.domain.model.ProcessingStatus
import com.handpoint.safclient.domain.model.SaleReference
import javax.inject.Inject

/**
 * Deletes a stored sale from the terminal, so it is never forwarded, and then from the journal.
 *
 * The journal entry goes only when it is still STORED: a sale that was already sent stays,
 * because Handpoint may have processed it.
 */
class DeleteStoredSale @Inject constructor(
    private val terminal: StoreAndForwardTerminal,
    private val journal: SalesJournal,
) {
    suspend operator fun invoke(reference: SaleReference): DeleteResult {
        val result = terminal.delete(reference)
        val goneFromTerminal = result == DeleteResult.DELETED || result == DeleteResult.NOT_ON_TERMINAL
        if (goneFromTerminal && journal.find(reference)?.status == ProcessingStatus.STORED) {
            journal.remove(reference)
        }
        return result
    }
}
