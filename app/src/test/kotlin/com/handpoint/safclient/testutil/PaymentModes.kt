package com.handpoint.safclient.testutil

import com.handpoint.safclient.domain.ErrorReporter
import com.handpoint.safclient.domain.NetworkMonitor
import com.handpoint.safclient.domain.OnlineTerminal
import com.handpoint.safclient.domain.PaymentModeCoordinator
import com.handpoint.safclient.domain.SalesJournal
import com.handpoint.safclient.domain.StoreAndForwardTerminal
import com.handpoint.safclient.domain.usecase.ReconcileStoredSales
import com.handpoint.safclient.domain.usecase.RefreshOpenStatuses
import com.handpoint.safclient.domain.usecase.RefreshSaleStatus
import com.handpoint.safclient.domain.usecase.SendStoredSales
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import java.time.Clock

/**
 * A real [PaymentModeCoordinator] over the given fakes, with its own use cases, running in the
 * test's background scope. Not started: call `start()` to apply the mode rule.
 */
fun TestScope.paymentMode(
    saf: StoreAndForwardTerminal,
    journal: SalesJournal,
    clock: Clock,
    online: OnlineTerminal = FakeOnlineTerminal(),
    network: NetworkMonitor = FakeNetworkMonitor(),
    reconcile: ReconcileStoredSales = ReconcileStoredSales(saf, journal, FakePreferences(), clock),
    statuses: RefreshOpenStatuses =
        RefreshOpenStatuses(journal, RefreshSaleStatus(saf, journal, clock), clock, backgroundScope),
    send: SendStoredSales = SendStoredSales(saf, journal, reconcile, statuses, clock, backgroundScope),
    errors: ErrorReporter = RecordingErrorReporter(),
    appScope: CoroutineScope = backgroundScope,
    io: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
): PaymentModeCoordinator =
    PaymentModeCoordinator(saf, online, network, journal, reconcile, send, statuses, errors, appScope, io)
