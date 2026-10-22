package com.handpoint.safclient.testutil

import com.handpoint.api.shared.Currency
import com.handpoint.api.shared.Device
import com.handpoint.api.shared.OperationStartResult
import com.handpoint.api.shared.options.Options
import com.handpoint.api.shared.options.SaleOptions
import com.handpoint.api.shared.storeandforward.StoreAndForwardPaymentsApi
import com.handpoint.api.shared.storeandforward.TransactionReference
import java.math.BigInteger

/**
 * Hand-written stand-in for the SDK's [StoreAndForwardPaymentsApi].
 *
 * It records every call in [calls] and lets a test play the SDK's part: [fire] delivers a
 * callback to every delegate registered with [registerEventsDelegate], exactly as the SDK's
 * event handler does. Only registered delegates get callbacks, as in the real SDK.
 */
class FakeSafApi : StoreAndForwardPaymentsApi {

    /** Every call, in order, e.g. `"sale(8450, USD)"` or `"getStoredTransactions"`. */
    val calls = mutableListOf<String>()

    /** Registered delegates. A set, because the SDK ignores a second registration. */
    val delegates = linkedSetOf<Any>()

    /** What [sale] returns. Defaults to a started sale with [SALE_REFERENCE]. */
    var saleStart = OperationStartResult(operationStarted = true, transactionReference = SALE_REFERENCE)

    /** What every Boolean request returns. Set to `false` to make the SDK refuse. */
    var accepts = true

    /** Runs inside [sale], before it returns: lets a test deliver a result "too early". */
    var duringSale: FakeSafApi.() -> Unit = {}

    /** Delivers an SDK callback to every registered delegate that implements [T]. */
    inline fun <reified T> fire(callback: (T) -> Unit) {
        delegates.filterIsInstance<T>().forEach(callback)
    }

    override fun start(): Boolean = record("start")

    override fun stop(): Boolean = record("stop")

    override fun registerEventsDelegate(delegate: Any): Boolean {
        calls += "registerEventsDelegate"
        delegates += delegate
        return true
    }

    override fun sale(amount: BigInteger, currency: Currency, options: SaleOptions): OperationStartResult {
        calls += "sale($amount, ${currency.alpha})"
        duringSale()
        return saleStart
    }

    override fun stopCurrentTransaction(): Boolean = record("stopCurrentTransaction")

    override fun deleteStoredTransaction(transactionReference: TransactionReference): Boolean =
        record("deleteStoredTransaction(${transactionReference.value()})")

    override fun getStoredTransactions(): Boolean = record("getStoredTransactions")

    override fun forwardStoredTransactions(): Boolean = record("forwardStoredTransactions")

    override fun getTransactionProcessingStatus(transactionReference: TransactionReference): Boolean =
        record("getTransactionProcessingStatus(${transactionReference.value()})")

    override fun printReceipt(receipt: String): Boolean = record("printReceipt($receipt)")

    override fun printReceipt(receipt: String, options: Options): Boolean = record("printReceipt($receipt)")

    private fun record(call: String): Boolean {
        calls += call
        return accepts
    }

    companion object {
        /** The SDK only accepts UUID references. */
        const val SALE_REFERENCE = "6f1c2a52-3a4f-4b9e-9a51-7d2b8c0e4f11"
        const val OTHER_REFERENCE = "0b7e9d3c-1f2a-4c5d-8e6f-9a0b1c2d3e4f"

        /** The device argument every SDK callback carries. Its content does not matter here. */
        val DEVICE = Device()
    }
}
