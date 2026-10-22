package com.handpoint.safclient.data.handpoint

import com.handpoint.api.shared.ConnectionStatus
import com.handpoint.api.shared.Currency
import com.handpoint.api.shared.Device
import com.handpoint.api.shared.Events
import com.handpoint.api.shared.SafTransactionStatus
import com.handpoint.api.shared.StatusInfo
import com.handpoint.api.shared.TransactionResult
import com.handpoint.api.shared.storeandforward.ForwardingTransactionStatus
import com.handpoint.api.shared.storeandforward.TransactionDeletionFailed
import com.handpoint.api.shared.storeandforward.TransactionReference
import com.handpoint.safclient.domain.model.CardStep
import com.handpoint.safclient.domain.model.DeleteResult
import com.handpoint.safclient.domain.model.ForwardingEvent
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.ProcessingStatus
import com.handpoint.safclient.domain.model.SaleReference
import com.handpoint.safclient.domain.model.StoredSummary
import com.handpoint.safclient.domain.model.TerminalConnection
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.math.BigInteger

/** The SDK's answer to `getTransactionProcessingStatus`. */
internal sealed interface StatusReply {
    data class Known(val reference: SaleReference, val status: ProcessingStatus) : StatusReply

    /** The SDK could not get a status (for example: no network). It does not say for which sale. */
    data object Unavailable : StatusReply
}

/** The SDK's answer to `deleteStoredTransaction`. */
internal data class DeletionReply(val reference: SaleReference, val result: DeleteResult)

/**
 * Receives every SDK callback the app needs and puts it on a flow.
 *
 * The SDK calls these methods on its own threads, at any time, possibly before the call that
 * caused them has returned. Each method only maps its arguments (with the functions in
 * `SdkMappers.kt`) and emits without suspending. It keeps no other state.
 *
 * Event flows have no replay: a value reaches only the collectors subscribed when it is
 * emitted. Each collector may fall [BUFFER] values behind; past that, a new value is dropped
 * rather than block the SDK thread. [connection] is a state, so it keeps the latest value.
 */
internal class SdkEventBridge :
    Events.SmartposRequired,
    Events.StoredTransactionsForwardingStatus,
    Events.StoredTransactionsEvent,
    Events.DeleteStoredTransactionEvent,
    Events.GetSafTransactionStatusEvent {

    val connection = MutableStateFlow(TerminalConnection.DISCONNECTED)

    /** Raw results: the adapter needs the requested amount and the clock to map them. */
    val transactionResults = eventFlow<TransactionResult>()
    val cardSteps = eventFlow<CardStep>()
    val forwarding = eventFlow<ForwardingEvent>()
    val storedSummaries = eventFlow<StoredSummary>()
    val deletions = eventFlow<DeletionReply>()
    val statusReplies = eventFlow<StatusReply>()

    // --- Events.SmartposRequired ---------------------------------------------------------

    override fun connectionStatusChanged(status: ConnectionStatus, device: Device) {
        connection.value = status.toTerminalConnection()
    }

    override fun currentTransactionStatus(info: StatusInfo, device: Device) {
        info.toCardStep()?.let { cardSteps.tryEmit(it) }
    }

    override fun endOfTransaction(result: TransactionResult, device: Device) {
        transactionResults.tryEmit(result)
    }

    /** A result recovered after a communication failure ends its transaction too. */
    override fun transactionResultReady(transactionResult: TransactionResult, device: Device) {
        transactionResults.tryEmit(transactionResult)
    }

    // --- Events.StoredTransactionsForwardingStatus ---------------------------------------

    override fun noTransactionsToForward() {
        forwarding.tryEmit(ForwardingEvent.NothingToSend)
    }

    override fun transactionsToForward(totalStoredTransactions: Int) {
        forwarding.tryEmit(ForwardingEvent.Started(totalStoredTransactions))
    }

    override fun transactionForwarded(
        transactionReference: String,
        successfullyForwardedTransaction: Int,
        totalStoredTransactions: Int,
    ) {
        forwarding.tryEmit(
            ForwardingEvent.Sent(
                reference = SaleReference(transactionReference),
                sent = successfullyForwardedTransaction,
                total = totalStoredTransactions,
            ),
        )
    }

    override fun transactionForwardingFinished(forwardingTransactionStatus: ForwardingTransactionStatus) {
        forwarding.tryEmit(ForwardingEvent.Finished(forwardingTransactionStatus.toForwardingResult()))
    }

    // --- Events.StoredTransactionsEvent --------------------------------------------------

    override fun transactionsStoredList(
        transactionReferences: List<TransactionReference>,
        currency: Currency,
        totalAmount: BigInteger,
        amountProcessed: BigInteger,
    ) {
        val total = currency.toJavaCurrencyOrNull()?.let { Money(totalAmount.toLong(), it) }
        storedSummaries.tryEmit(StoredSummary(transactionReferences.map { it.toSaleReference() }, total))
    }

    // --- Events.DeleteStoredTransactionEvent ---------------------------------------------

    override fun storedTransactionDeletedSuccessfully(transactionReference: TransactionReference) {
        deletions.tryEmit(DeletionReply(transactionReference.toSaleReference(), DeleteResult.DELETED))
    }

    override fun errorDeletingStoredTransaction(
        transactionReference: TransactionReference,
        error: TransactionDeletionFailed,
    ) {
        val result = when (error) {
            TransactionDeletionFailed.NO_EXISTING_TRANSACTION -> DeleteResult.NOT_ON_TERMINAL
            TransactionDeletionFailed.UNEXPECTED_ERROR -> DeleteResult.ERROR
        }
        deletions.tryEmit(DeletionReply(transactionReference.toSaleReference(), result))
    }

    // --- Events.GetSafTransactionStatusEvent ---------------------------------------------

    override fun safTransactionStatus(transactionReference: TransactionReference, status: SafTransactionStatus) {
        statusReplies.tryEmit(StatusReply.Known(transactionReference.toSaleReference(), status.toProcessingStatus()))
    }

    // "Trasnsaction" is the SDK's spelling. Keep it: this overrides the SDK interface method.
    override fun unexpectedSafTrasnsactionStatusError() {
        statusReplies.tryEmit(StatusReply.Unavailable)
    }

    private companion object {
        const val BUFFER = 64

        fun <T> eventFlow() = MutableSharedFlow<T>(extraBufferCapacity = BUFFER)
    }
}
