package com.handpoint.safclient.data.handpoint

import com.handpoint.api.Hapi
import com.handpoint.api.shared.ConnectionMethod
import com.handpoint.api.shared.Events
import com.handpoint.api.shared.FinancialStatus
import com.handpoint.api.shared.OperationStartResult
import com.handpoint.api.shared.TransactionResult
import com.handpoint.api.shared.options.RefundOptions
import com.handpoint.api.shared.options.SaleOptions
import com.handpoint.safclient.domain.OnlineTerminal
import com.handpoint.safclient.domain.model.CardStep
import com.handpoint.safclient.domain.model.EntryMode
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.OperationKind
import com.handpoint.safclient.domain.model.OperationReport
import com.handpoint.safclient.domain.model.OperationRequest
import com.handpoint.safclient.domain.model.Sale
import com.handpoint.safclient.domain.model.SaleOutcome
import com.handpoint.safclient.domain.model.SaleSurcharge
import com.handpoint.safclient.domain.model.SaleTax
import com.handpoint.safclient.domain.model.TerminalConnection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.math.BigInteger
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Online sales on a Handpoint terminal, through the SDK's [Hapi] API.
 *
 * Read it next to [HandpointSafTerminal]: it works the same way (every SDK call returns at once,
 * the answer comes later as a callback, and [listenTo] subscribes before calling), but the
 * terminal sends each sale to Handpoint at once instead of storing it.
 *
 * ## Lifecycle
 *
 * 1. **Activate.** [activate] creates the API with
 *    `HapiFactory.getAsyncInterface(delegate, credentials = HandpointCredentials(sharedSecret), settings = Settings())`
 *    (this app passes that call in as [apiFactory]), registers the delegate with
 *    `registerEventsDelegate`, and connects to the terminal itself:
 *    `connect(getPairedDevices(ConnectionMethod.ANDROID_PAYMENT)[0])`. [connection] shows the
 *    result. Creating this API stops the Store and Forward API from receiving events, and the
 *    other way round, so the app activates one at a time
 *    (see [com.handpoint.safclient.domain.PaymentTerminal]). Each activation creates the API
 *    again.
 * 2. **Sale.** [sale] calls `sale(amount, currency, SaleOptions())` and waits for the
 *    `endOfTransaction` of the reference it returns. `AUTHORISED` means approved: the card is
 *    charged. `DECLINED` comes with the SDK's `errorMessage`. [cardSteps] shows the card
 *    prompts; [cancelSale] stops the sale before the card is read. If no result comes within
 *    180 seconds (or the caller is cancelled), this class asks the SDK to stop the sale and
 *    returns `Failed(OUTCOME_UNKNOWN)`: the card may have been charged.
 * 3. **Deactivate.** [deactivate] calls `disconnect()` and forgets the API. Until the next
 *    [activate], every member fails at once.
 *
 * The online API cannot send stored Store and Forward sales: only the Store and Forward API can.
 *
 * @param apiFactory creates the SDK API with the given delegate. Called on each [activate].
 * @param clock gives the approval time of a sale.
 */
class HandpointOnlineTerminal(
    private val apiFactory: (Events.SmartposRequired) -> Hapi,
    private val clock: Clock,
    /** The tax fields of a test sale, read when each sale starts. */
    private val saleTax: suspend () -> SaleTax = { SaleTax.NONE },
    /** The SDK registers its MoTo operations only when it has the cloud API key. */
    private val motoAvailable: Boolean = true,
) : OnlineTerminal {

    // Registered with the online API only. It also implements the Store and Forward events,
    // which this API never sends.
    private val bridge = SdkEventBridge()

    /** The SDK API between [activate] and [deactivate]; `null` otherwise. */
    @Volatile
    private var api: Hapi? = null

    override val connection: StateFlow<TerminalConnection> = bridge.connection.asStateFlow()

    override val cardSteps: Flow<CardStep> = bridge.cardSteps.asSharedFlow()

    /** Creates the API (heavy: call it off the main thread), registers the delegate and connects. */
    override fun activate(): Boolean {
        bridge.connection.value = TerminalConnection.DISCONNECTED
        val hapi = apiFactory(bridge)
        hapi.registerEventsDelegate(bridge)
        api = hapi
        return connectTo(hapi)
    }

    override fun deactivate(): Boolean {
        val hapi = api ?: return true
        api = null
        val disconnected = hapi.disconnect()
        bridge.connection.value = TerminalConnection.DISCONNECTED
        return disconnected
    }

    override fun connect(): Boolean = api?.let(::connectTo) ?: false

    override fun disconnect(): Boolean = api?.disconnect() ?: false

    /** On a PAX terminal the payment device is the terminal itself: the only paired device. */
    private fun connectTo(hapi: Hapi): Boolean {
        val device = hapi.getPairedDevices(ConnectionMethod.ANDROID_PAYMENT).firstOrNull() ?: return false
        return hapi.connect(device)
    }

    override suspend fun sale(amount: Money, surcharge: SaleSurcharge?): SaleOutcome {
        val hapi = api ?: return SaleOutcome.Failed(NOT_ACTIVE)
        return listenTo(bridge.transactionResults) { results ->
            val start = hapi.sale(
                BigInteger.valueOf(amount.minorUnits),
                amount.currency.toSdkCurrency(),
                SaleOptions().withSurcharge(surcharge).withTax(saleTax()),
            )
            if (start.operationStarted) {
                awaitSaleEnd(hapi, start.transactionReference, amount, surcharge, results)
            } else {
                rejectionOutcome(start.errorMessage, amount, results)
            }
        }
    }

    /**
     * Waits for the end of the started sale [reference]. On timeout or cancellation the SDK
     * transaction is still running: ask it to stop. The card may already have been charged,
     * so the outcome is unknown.
     */
    private suspend fun awaitSaleEnd(
        hapi: Hapi,
        reference: String,
        amount: Money,
        surcharge: SaleSurcharge?,
        results: Flow<TransactionResult>,
    ): SaleOutcome {
        var ended = false
        try {
            val result = withTimeoutOrNull(SALE_TIMEOUT) {
                results.first { it.transactionReference == reference }
            }
            ended = result != null
            return result?.toOnlineOutcome(amount, clock.instant(), surcharge)
                ?: SaleOutcome.Failed(OnlineTerminal.OUTCOME_UNKNOWN, reference.toReferenceOrNull())
        } finally {
            // Runs on timeout and on cancellation; a CancellationException still propagates.
            if (!ended) hapi.stopCurrentTransaction()
        }
    }

    /**
     * The outcome of a sale the SDK did not start: its message, or the reason it sends as an
     * `endOfTransaction` without a reference just after `sale()` returns.
     */
    private suspend fun rejectionOutcome(
        startError: String,
        amount: Money,
        results: Flow<TransactionResult>,
    ): SaleOutcome {
        if (startError.isNotBlank()) return SaleOutcome.Failed(startError)
        val rejection = withTimeoutOrNull(REJECTION_TIMEOUT) {
            results.first { it.transactionReference.isBlank() }
        }
        return rejection?.toOnlineOutcome(amount, clock.instant())
            ?: SaleOutcome.Failed("The terminal did not start the sale")
    }

    /**
     * Starts [request] and reports what the SDK did. An operation the SDK refuses (for example a
     * refund that sets a surcharge, SDKS-467) comes back as `REJECTED` with the SDK's message;
     * one it starts is awaited like a sale, and its `TransactionResult` is shown value by value.
     */
    override suspend fun run(request: OperationRequest): OperationReport {
        val hapi = api ?: return OperationReport(NOT_ACTIVE)
        request.problem()?.let { return OperationReport(it) }
        if (request.kind.isMoto && !motoAvailable) return OperationReport(MOTO_NEEDS_KEY)
        return listenTo(bridge.transactionResults) { results ->
            val start = start(hapi, request)
            if (start.operationStarted) {
                awaitOperationEnd(hapi, request, start.transactionReference, results)
            } else {
                rejectedOperation(start.errorMessage, results)
            }
        }
    }

    private fun start(hapi: Hapi, request: OperationRequest): OperationStartResult {
        val amount = request.amount?.let { BigInteger.valueOf(it.minorUnits) }
        val currency = request.amount?.currency?.toSdkCurrency()
        val originalId = request.originalTransactionId
        return when (request.kind) {
            OperationKind.SALE -> hapi.sale(amount!!, currency!!, SaleOptions().withExtras(request))
            OperationKind.REFUND -> hapi.refund(amount!!, currency!!, RefundOptions().withExtras(request))
            OperationKind.LINKED_REFUND ->
                hapi.refund(amount!!, currency!!, originalId, RefundOptions().withExtras(request))
            OperationKind.MOTO_SALE -> hapi.motoSale(amount!!, currency!!, request.toMoToOptions())
            OperationKind.MOTO_REFUND ->
                hapi.motoRefund(amount!!, currency!!, null as String?, request.toMoToOptions())
            OperationKind.MOTO_LINKED_REFUND ->
                hapi.motoRefund(amount!!, currency!!, originalId, request.toMoToOptions())
            OperationKind.REVERSAL -> hapi.saleReversal(amount!!, currency!!, originalId!!)
            OperationKind.MOTO_REVERSAL -> hapi.motoReversal(originalId!!)
            OperationKind.AUTOMATIC_REFUND ->
                if (amount == null) {
                    hapi.automaticRefund(originalId!!, request.toMoToOptions())
                } else {
                    hapi.automaticRefund(amount, currency!!, originalId!!, request.toMoToOptions())
                }
        }
    }

    /** Waits for the end of the started operation [reference]; on timeout or cancellation it stops it. */
    private suspend fun awaitOperationEnd(
        hapi: Hapi,
        request: OperationRequest,
        reference: String,
        results: Flow<TransactionResult>,
    ): OperationReport {
        var ended = false
        try {
            val result = withTimeoutOrNull(SALE_TIMEOUT) {
                results.first { it.transactionReference == reference }
            }
            ended = result != null
            return if (result == null) {
                OperationReport("STARTED (operationStarted = true), but no result arrived in $SALE_TIMEOUT. It was stopped.")
            } else {
                OperationReport(
                    summary = "STARTED (operationStarted = true), ended ${result.finStatus}",
                    lines = result.toResultLines() + result.toIdentifierLines(),
                    transactionId = result.transactionID.ifBlank { null },
                    approved = result.finStatus == FinancialStatus.AUTHORISED,
                    sale = request.takeIf { it.kind == OperationKind.MOTO_SALE }?.let { motoSale(it, result) },
                )
            }
        } finally {
            if (!ended) hapi.stopCurrentTransaction()
        }
    }

    /** The history entry of an approved MoTo sale; `null` for any other result. */
    private fun motoSale(request: OperationRequest, result: TransactionResult): Sale? {
        val amount = request.amount ?: return null
        val outcome = result.toOnlineOutcome(amount, clock.instant(), request.surcharge) as? SaleOutcome.ApprovedOnline
        return outcome?.sale?.copy(isMoto = true, entryMode = EntryMode.MANUAL)
    }

    /** What the SDK said about an operation it did not start: its message, or the result sent just after. */
    private suspend fun rejectedOperation(startError: String, results: Flow<TransactionResult>): OperationReport {
        val late = if (startError.isBlank()) {
            withTimeoutOrNull(REJECTION_TIMEOUT) { results.first { it.transactionReference.isBlank() } }
        } else {
            null
        }
        val message = startError.ifBlank { late?.errorMessage.orEmpty() }
        return OperationReport(
            summary = "REJECTED (operationStarted = false)\nerrorMessage: \"$message\"",
            lines = late?.let { it.toResultLines() + it.toIdentifierLines() }.orEmpty(),
        )
    }

    /** Why [OperationRequest] cannot start (a missing amount or transaction ID), or `null`. */
    private fun OperationRequest.problem(): String? = when {
        kind.needsAmount && amount == null -> "Enter an amount."
        kind.needsOriginalId && originalTransactionId.isNullOrBlank() -> "Enter the original transaction ID."
        else -> null
    }

    override fun cancelSale(): Boolean = api?.stopCurrentTransaction() ?: false

    override fun printReceipt(receipt: String): Boolean = api?.printReceipt(receipt) ?: false

    private companion object {
        /** A sale includes the cardholder: presenting the card, entering the PIN. */
        val SALE_TIMEOUT: Duration = 180.seconds

        /** How long to wait for the reason of a sale the SDK did not start. */
        val REJECTION_TIMEOUT: Duration = 2.seconds

        const val NOT_ACTIVE = "Online sales are not active"

        const val MOTO_NEEDS_KEY =
            "MoTo needs the cloud API key. Add handpoint.cloudApiKey to local.properties and build the app again."
    }
}
