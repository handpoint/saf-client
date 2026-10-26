package com.handpoint.safclient.data.handpoint

import com.handpoint.api.shared.Events
import com.handpoint.api.shared.OperationStartResult
import com.handpoint.api.shared.TransactionResult
import com.handpoint.api.shared.options.SaleOptions
import com.handpoint.api.shared.storeandforward.StoreAndForwardPaymentsApi
import com.handpoint.safclient.domain.StoreAndForwardTerminal
import com.handpoint.safclient.domain.model.CardStep
import com.handpoint.safclient.domain.model.DeleteResult
import com.handpoint.safclient.domain.model.ForwardingEvent
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.ProcessingStatus
import com.handpoint.safclient.domain.model.SaleOutcome
import com.handpoint.safclient.domain.model.SaleReference
import com.handpoint.safclient.domain.model.StoredSummary
import com.handpoint.safclient.domain.model.TerminalConnection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.math.BigInteger
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Store and Forward on a Handpoint terminal, through the SDK's [StoreAndForwardPaymentsApi].
 *
 * This is the only class in the app that talks to the SDK. Read it to learn the integration;
 * copy it to start yours.
 *
 * ## How the SDK works
 *
 * Every SDK call returns at once. The answer comes later, on an SDK thread, as a callback on a
 * delegate. [SdkEventBridge] receives those callbacks and puts them on flows. This class turns
 * each "call, then wait for its callback" pair into one `suspend` function that always ends:
 * with the answer, or with a defined failure value after a timeout.
 *
 * A callback can arrive before the SDK call that caused it returns. So every `suspend`
 * function here subscribes to the callback's flow first, then calls the SDK, then waits
 * (see [listenTo]).
 *
 * ## Lifecycle
 *
 * 1. **Start.** Create the API once per process with
 *    `HapiFactory.getStoreAndForwardPaymentsApi(delegate, HandpointCredentials(sharedSecret), Settings())`
 *    (this app passes that call in as [apiFactory]). Then register the delegate with
 *    `registerEventsDelegate`: that registration is the one that receives the events
 *    (registering the same object twice is harmless). Call [connect] (`start()`); [connection]
 *    shows the result.
 * 2. **Sale.** [sale] calls `sale(amount, currency, SaleOptions(checkDuplicates = false))`.
 *    Always turn the duplicate check off: the gateway runs it when the sale is forwarded,
 *    with no cardholder to confirm a retry, so a sale it flags is declined for good. The
 *    SDK still sends the check by default (fix requested in SDKS-447). The SDK makes the
 *    transaction reference (a UUID) and returns it in `OperationStartResult`. The sale ends with
 *    `endOfTransaction` for that reference. An offline approval has
 *    `finStatus = AUTHORISED_DEFERRED`: the sale is now stored on the terminal. Every sale
 *    through this API is stored, even when the terminal has network. [cardSteps] shows the
 *    card prompts while the sale runs; [cancelSale] stops it before the card is read.
 *    If no result comes within 180 seconds (or the caller is cancelled), this class asks the
 *    SDK to stop the sale and returns `Failed(OUTCOME_UNKNOWN)`: the terminal may still have
 *    stored it, so reconcile with [storedOnTerminal] before the merchant tries again.
 * 3. **Forward.** [forward] (`forwardStoredTransactions()`) sends all stored sales to Handpoint
 *    in one run. Progress arrives on [forwarding]. Only one run at a time: while a run is busy,
 *    the SDK refuses a second one. The SDK does not retry: call [forward] again for sales that
 *    stay stored. [storedOnTerminal] (`getStoredTransactions()`) lists what is still stored.
 * 4. **Status.** [processingStatus] (`getTransactionProcessingStatus(reference)`) asks the
 *    Handpoint gateway what happened to a forwarded sale. When the gateway cannot be reached,
 *    the SDK calls `unexpectedSafTrasnsactionStatusError()` instead. "Trasnsaction" is the SDK's
 *    spelling, not a typo in this app.
 *
 * [delete] removes a stored sale from the terminal before it is forwarded.
 *
 * ## Switching to the online API and back
 *
 * The SDK runs either this API or the online `Hapi`, never both (see
 * [com.handpoint.safclient.domain.PaymentTerminal]): creating the online API reconfigures the
 * SDK and drops this API's event listeners. So [deactivate] calls `stop()` and forgets the API;
 * from then on every member fails at once instead of creating the SDK again behind the online
 * API's back. [activate] creates the API again with `apiFactory`, registers the delegate again
 * and calls `start()`. Store and Forward starts without network when the terminal has
 * downloaded its configuration before: it uses the cached one.
 *
 * @param apiFactory creates the SDK API with the given delegate. Called on first use, and again
 *   on each [activate] after a [deactivate].
 * @param clock gives the approval time of a sale.
 */
class HandpointSafTerminal(
    private val apiFactory: (Events.SmartposRequired) -> StoreAndForwardPaymentsApi,
    private val clock: Clock,
) : StoreAndForwardTerminal {

    private val bridge = SdkEventBridge()

    private val lock = Any()

    /** The SDK API; `null` before its first use and while deactivated. */
    @Volatile
    private var current: StoreAndForwardPaymentsApi? = null

    /** `true` from [deactivate] until [activate]: then no member may create the SDK API. */
    @Volatile
    private var deactivated = false

    /** The SDK API, created on first use. `null` while deactivated. */
    private fun api(): StoreAndForwardPaymentsApi? = current ?: synchronized(lock) {
        current ?: if (deactivated) null else create()
    }

    /** Creates the SDK API and registers the delegate: that registration receives the events. */
    private fun create(): StoreAndForwardPaymentsApi =
        apiFactory(bridge).also {
            it.registerEventsDelegate(bridge)
            current = it
        }

    override val connection: StateFlow<TerminalConnection> = bridge.connection.asStateFlow()

    override val cardSteps: Flow<CardStep> = bridge.cardSteps.asSharedFlow()

    override val forwarding: SharedFlow<ForwardingEvent> = bridge.forwarding.asSharedFlow()

    /** `true` while a `start()` is in flight. */
    private val starting = AtomicBoolean(false)

    /**
     * Calls `start()`. The first call to this adapter creates the SDK through `apiFactory`,
     * which is heavy setup: call it off the main thread.
     *
     * Single flight: Home and Settings can both connect at the same time, but only one
     * `start()` runs at once. A call made while a start is in flight does not start the SDK
     * again; it returns `true` at once, and the result of the start in flight arrives on
     * [connection]. An AtomicBoolean keeps `connect()` non-suspending, as the port defines it,
     * and does not block the second caller's thread.
     */
    override fun connect(): Boolean {
        val api = api() ?: return false
        if (!starting.compareAndSet(false, true)) return true
        try {
            return api.start()
        } finally {
            starting.set(false)
        }
    }

    override fun disconnect(): Boolean = api()?.stop() ?: false

    /** Creates the SDK API again if it was deactivated (or never created), then calls `start()`. */
    override fun activate(): Boolean {
        synchronized(lock) {
            if (current == null) {
                deactivated = false
                bridge.connection.value = TerminalConnection.DISCONNECTED
                create()
            }
        }
        return connect()
    }

    /** Calls `stop()` and forgets the SDK API, so the online API can be created. */
    override fun deactivate(): Boolean {
        val api = synchronized(lock) {
            deactivated = true
            current.also { current = null }
        } ?: return true
        val stopped = api.stop()
        bridge.connection.value = TerminalConnection.DISCONNECTED
        return stopped
    }

    override suspend fun sale(amount: Money): SaleOutcome {
        val api = api() ?: return SaleOutcome.Failed(NOT_ACTIVE)
        return sale(api, amount)
    }

    private suspend fun sale(api: StoreAndForwardPaymentsApi, amount: Money): SaleOutcome =
        listenTo(bridge.transactionResults) { results ->
            val start = api.sale(
                BigInteger.valueOf(amount.minorUnits),
                amount.currency.toSdkCurrency(),
                SaleOptions(checkDuplicates = false),
            )
            if (start.operationStarted) {
                awaitSaleEnd(api, start.transactionReference, amount, results)
            } else {
                rejectionOutcome(start, amount, results)
            }
        }

    /**
     * Waits for the end of the started sale [reference].
     *
     * If no result comes in time, or the caller is cancelled, the SDK transaction is still
     * running. Ask it to stop, so the terminal does not approve and store a sale the app has
     * already given up on. Stopping only works before the card is read, so the result is
     * still "outcome unknown": the caller reconciles with [storedOnTerminal].
     */
    private suspend fun awaitSaleEnd(
        api: StoreAndForwardPaymentsApi,
        reference: String,
        amount: Money,
        results: Flow<TransactionResult>,
    ): SaleOutcome {
        var ended = false
        try {
            val result = withTimeoutOrNull(SALE_TIMEOUT) {
                results.first { it.transactionReference == reference }
            }
            ended = result != null
            return result?.toOutcome(amount, clock.instant())
                ?: SaleOutcome.Failed(StoreAndForwardTerminal.OUTCOME_UNKNOWN)
        } finally {
            // Runs on timeout and on cancellation; a CancellationException still propagates.
            if (!ended) api.stopCurrentTransaction()
        }
    }

    /**
     * The outcome of a sale the SDK did not start.
     *
     * When one of its checks declines the sale before the card is read (over the per-sale
     * limit, wrong currency, invalid amount, ...), the SDK also sends an endOfTransaction with
     * the decline code, possibly just after `sale()` returns. SDK 7.1014.2 and later give it the
     * sale's reference (also in `OperationStartResult`) and fill `errorMessage` with a readable
     * message; older SDKs send it without a reference and leave `errorMessage` empty. So wait
     * briefly for it in both cases, and map it by its code. Without it: a start with a
     * reference was a decline (keep the SDK's message); without one, a refusal (busy, not
     * connected, ...).
     */
    private suspend fun rejectionOutcome(
        start: OperationStartResult,
        amount: Money,
        results: Flow<TransactionResult>,
    ): SaleOutcome {
        val reference = start.transactionReference
        val rejection = withTimeoutOrNull(REJECTION_TIMEOUT) {
            results.first { it.transactionReference.isBlank() || it.transactionReference == reference }
        }
        if (rejection != null) return rejection.toOutcome(amount, clock.instant())
        val message = start.errorMessage.readableOrBlank()
        return when {
            reference.isNotBlank() && message.isNotBlank() ->
                SaleOutcome.Declined(start.errorMessage.toDeclineReason(), message)
            message.isNotBlank() -> SaleOutcome.Failed(message)
            else -> SaleOutcome.Failed("The terminal did not start the sale")
        }
    }

    override fun cancelSale(): Boolean = api()?.stopCurrentTransaction() ?: false

    override suspend fun storedOnTerminal(): StoredSummary? {
        val api = api() ?: return null
        return listenTo(bridge.storedSummaries) { summaries ->
            if (!api.getStoredTransactions()) return@listenTo null
            withTimeoutOrNull(STORED_LIST_TIMEOUT) { summaries.first() }
        }
    }

    override fun forward(): Boolean = api()?.forwardStoredTransactions() ?: false

    override suspend fun processingStatus(reference: SaleReference): ProcessingStatus? {
        val api = api() ?: return null
        val sdkReference = reference.toTransactionReferenceOrNull() ?: return null
        return listenTo(bridge.statusReplies) { replies ->
            if (!api.getTransactionProcessingStatus(sdkReference)) return@listenTo null
            val reply = withTimeoutOrNull(STATUS_TIMEOUT) {
                // The error callback does not name a sale, so it answers every pending request.
                replies.first { it is StatusReply.Unavailable || (it is StatusReply.Known && it.reference == reference) }
            }
            (reply as? StatusReply.Known)?.status
        }
    }

    override suspend fun delete(reference: SaleReference): DeleteResult {
        val api = api() ?: return DeleteResult.ERROR
        val sdkReference = reference.toTransactionReferenceOrNull() ?: return DeleteResult.ERROR
        return listenTo(bridge.deletions) { deletions ->
            if (!api.deleteStoredTransaction(sdkReference)) return@listenTo DeleteResult.ERROR
            val reply = withTimeoutOrNull(DELETE_TIMEOUT) {
                deletions.first { it.reference == reference }
            }
            reply?.result ?: DeleteResult.ERROR
        }
    }

    override fun printReceipt(receipt: String): Boolean = api()?.printReceipt(receipt) ?: false

    private companion object {
        /** A sale includes the cardholder: presenting the card, entering the PIN. */
        val SALE_TIMEOUT: Duration = 180.seconds

        /** How long to wait for the reason of a sale the SDK did not start. */
        val REJECTION_TIMEOUT: Duration = 2.seconds

        val STORED_LIST_TIMEOUT: Duration = 15.seconds
        val STATUS_TIMEOUT: Duration = 20.seconds
        val DELETE_TIMEOUT: Duration = 15.seconds

        /** Message of a sale asked for while the online API is active. */
        const val NOT_ACTIVE = "Store and Forward is not active"
    }
}
