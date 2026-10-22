package com.handpoint.safclient.data.handpoint

import com.handpoint.api.shared.Events
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.math.BigInteger
import java.time.Clock
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
 * 2. **Sale.** [sale] calls `sale(amount, currency, SaleOptions())`. The SDK makes the
 *    transaction reference (a UUID) and returns it in `OperationStartResult`. The sale ends with
 *    `endOfTransaction` for that reference. An offline approval has
 *    `finStatus = AUTHORISED_DEFERRED`: the sale is now stored on the terminal. Every sale
 *    through this API is stored, even when the terminal has network. [cardSteps] shows the
 *    card prompts while the sale runs; [cancelSale] stops it before the card is read.
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
 * @param apiFactory creates the SDK API with the given delegate. Called once, on first use.
 * @param clock gives the approval time of a sale.
 */
class HandpointSafTerminal(
    apiFactory: (Events.SmartposRequired) -> StoreAndForwardPaymentsApi,
    private val clock: Clock,
) : StoreAndForwardTerminal {

    private val bridge = SdkEventBridge()

    private val api: StoreAndForwardPaymentsApi by lazy {
        apiFactory(bridge).also { it.registerEventsDelegate(bridge) }
    }

    override val connection: StateFlow<TerminalConnection> = bridge.connection.asStateFlow()

    override val cardSteps: Flow<CardStep> = bridge.cardSteps.asSharedFlow()

    override val forwarding: SharedFlow<ForwardingEvent> = bridge.forwarding.asSharedFlow()

    override fun connect(): Boolean = api.start()

    override fun disconnect(): Boolean = api.stop()

    override suspend fun sale(amount: Money): SaleOutcome = listenTo(bridge.transactionResults) { results ->
        val start = api.sale(
            BigInteger.valueOf(amount.minorUnits),
            amount.currency.toSdkCurrency(),
            SaleOptions(),
        )
        if (start.operationStarted) {
            val result = withTimeoutOrNull(SALE_TIMEOUT) {
                results.first { it.transactionReference == start.transactionReference }
            }
            result?.toOutcome(amount, clock.instant()) ?: SaleOutcome.Failed("timeout")
        } else {
            // When one of its checks rejects the sale (wrong currency, invalid amount, ...), the
            // SDK returns OperationStartResult(false) without a message and sends the reason as
            // an endOfTransaction without a reference. Other refusals (busy, not connected)
            // send nothing.
            val rejection = withTimeoutOrNull(REJECTION_TIMEOUT) {
                results.first { it.transactionReference.isBlank() }
            }
            rejection?.toOutcome(amount, clock.instant())
                ?: SaleOutcome.Failed(start.errorMessage.ifBlank { "The terminal did not start the sale" })
        }
    }

    override fun cancelSale(): Boolean = api.stopCurrentTransaction()

    override suspend fun storedOnTerminal(): StoredSummary? = listenTo(bridge.storedSummaries) { summaries ->
        if (!api.getStoredTransactions()) return@listenTo null
        withTimeoutOrNull(STORED_LIST_TIMEOUT) { summaries.first() }
    }

    override fun forward(): Boolean = api.forwardStoredTransactions()

    override suspend fun processingStatus(reference: SaleReference): ProcessingStatus? {
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
        val sdkReference = reference.toTransactionReferenceOrNull() ?: return DeleteResult.ERROR
        return listenTo(bridge.deletions) { deletions ->
            if (!api.deleteStoredTransaction(sdkReference)) return@listenTo DeleteResult.ERROR
            val reply = withTimeoutOrNull(DELETE_TIMEOUT) {
                deletions.first { it.reference == reference }
            }
            reply?.result ?: DeleteResult.ERROR
        }
    }

    override fun printReceipt(receipt: String): Boolean = api.printReceipt(receipt)

    /**
     * Subscribes to [events] now, then runs [block] with everything received from this moment.
     *
     * Call the SDK inside [block]. A callback that the SDK delivers on another thread while the
     * SDK call is still running is kept in a buffer, so [block] cannot miss it. The subscription
     * ends when [block] returns or is cancelled.
     */
    private suspend fun <E, R> listenTo(events: Flow<E>, block: suspend (received: Flow<E>) -> R): R =
        coroutineScope {
            val received = Channel<E>(Channel.UNLIMITED)
            // UNDISPATCHED: the collector subscribes before launch() returns, not later.
            val listener = launch(start = CoroutineStart.UNDISPATCHED) {
                events.collect { received.send(it) }
            }
            try {
                block(received.receiveAsFlow())
            } finally {
                listener.cancel()
            }
        }

    private companion object {
        /** A sale includes the cardholder: presenting the card, entering the PIN. */
        val SALE_TIMEOUT: Duration = 180.seconds

        /** How long to wait for the reason of a sale the SDK did not start. */
        val REJECTION_TIMEOUT: Duration = 2.seconds

        val STORED_LIST_TIMEOUT: Duration = 15.seconds
        val STATUS_TIMEOUT: Duration = 20.seconds
        val DELETE_TIMEOUT: Duration = 15.seconds
    }
}
