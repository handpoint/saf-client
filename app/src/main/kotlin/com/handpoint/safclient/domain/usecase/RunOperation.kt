package com.handpoint.safclient.domain.usecase

import com.handpoint.safclient.domain.OnlineTerminal
import com.handpoint.safclient.domain.PaymentModeCoordinator
import com.handpoint.safclient.domain.SalesJournal
import com.handpoint.safclient.domain.model.OperationReport
import com.handpoint.safclient.domain.model.OperationRequest
import com.handpoint.safclient.domain.model.PaymentMode
import javax.inject.Inject

/**
 * Test only: runs one [OperationRequest] on the online API. Like a sale, it holds the payment
 * mode while it runs, so the app cannot switch to Store and Forward in the middle of it.
 */
class RunOperation @Inject constructor(
    private val online: OnlineTerminal,
    private val mode: PaymentModeCoordinator,
    private val journal: SalesJournal,
) {
    suspend operator fun invoke(request: OperationRequest): OperationReport {
        val current = mode.beginSale()
            ?: return OperationReport("The app is switching between online and offline. Try again in a moment.")
        try {
            if (current != PaymentMode.ONLINE) {
                return OperationReport(
                    "These operations need the online API. Turn the network on and send any stored sales first.",
                )
            }
            val report = online.run(request)
            // An approved MoTo sale is a sale like any other: it goes to the history.
            report.sale?.let { journal.record(it) }
            return report
        } finally {
            mode.endSale()
        }
    }
}
