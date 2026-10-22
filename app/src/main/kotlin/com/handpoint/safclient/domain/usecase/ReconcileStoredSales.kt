package com.handpoint.safclient.domain.usecase

import com.handpoint.safclient.domain.AppPreferences
import com.handpoint.safclient.domain.SalesJournal
import com.handpoint.safclient.domain.StoreAndForwardTerminal
import com.handpoint.safclient.domain.model.Money
import java.time.Clock
import javax.inject.Inject

/**
 * Makes the journal agree with the terminal, which is the source of truth for what is
 * still stored.
 *
 * A sale the journal holds as STORED but the terminal no longer lists has left the terminal
 * (it was forwarded, for example by a run the app did not see end), so it becomes IN_PROGRESS.
 * Use this on app start, after a forwarding run, and after a sale with an unknown outcome.
 *
 * Returns the total still stored on the terminal, or `null` when the terminal does not answer
 * (then nothing changes).
 *
 * The first call may create the SDK: call it off the main thread.
 */
class ReconcileStoredSales @Inject constructor(
    private val terminal: StoreAndForwardTerminal,
    private val journal: SalesJournal,
    private val settings: AppPreferences,
    private val clock: Clock,
) {
    suspend operator fun invoke(): Money? {
        val summary = terminal.storedOnTerminal() ?: return null
        val onTerminal = summary.references.toSet()
        journal.pending()
            .filter { it.reference !in onTerminal }
            .forEach { journal.markSent(it.reference, clock.instant()) }
        summary.total?.let { settings.setSafCurrency(it.currency) }
        return summary.total
    }
}
