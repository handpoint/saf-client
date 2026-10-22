package com.handpoint.safclient.testutil

import com.handpoint.api.Hapi
import com.handpoint.api.shared.ConnectionMethod
import com.handpoint.api.shared.Currency
import com.handpoint.api.shared.Device
import com.handpoint.api.shared.OperationStartResult
import com.handpoint.api.shared.options.Options
import com.handpoint.api.shared.options.SaleOptions
import java.lang.reflect.Proxy
import java.math.BigInteger

/**
 * Hand-written stand-in for the SDK's online [Hapi] API.
 *
 * Like [FakeSafApi], it records every call in [calls] and [fire] delivers a callback to every
 * registered delegate. It implements only what the app uses; any other member throws, so a test
 * notices when the app starts to depend on it.
 */
class FakeHapi : Hapi by unsupported() {

    /** Every call, in order, e.g. `"sale(8450, USD)"` or `"connect"`. */
    val calls = mutableListOf<String>()

    val delegates = linkedSetOf<Any>()

    /** What [getPairedDevices] returns. A PAX terminal lists itself. */
    var pairedDevices: List<Device> = listOf(FakeSafApi.DEVICE)

    /** What [sale] returns. Defaults to a started sale with [FakeSafApi.SALE_REFERENCE]. */
    var saleStart = OperationStartResult(operationStarted = true, transactionReference = FakeSafApi.SALE_REFERENCE)

    /** What every Boolean request returns. Set to `false` to make the SDK refuse. */
    var accepts = true

    /** Runs inside [sale], before it returns: lets a test deliver a result "too early". */
    var duringSale: FakeHapi.() -> Unit = {}

    inline fun <reified T> fire(callback: (T) -> Unit) {
        delegates.filterIsInstance<T>().forEach(callback)
    }

    override fun registerEventsDelegate(delegate: Any?): Boolean {
        calls += "registerEventsDelegate"
        delegate?.let { delegates += it }
        return true
    }

    override fun getPairedDevices(method: ConnectionMethod): List<Device> {
        calls += "getPairedDevices($method)"
        return pairedDevices
    }

    override fun connect(device: Device): Boolean = record("connect")

    override fun disconnect(): Boolean = record("disconnect")

    override fun sale(amount: BigInteger, currency: Currency, options: SaleOptions): OperationStartResult {
        calls += "sale($amount, ${currency.alpha})"
        duringSale()
        return saleStart
    }

    override fun stopCurrentTransaction(): Boolean = record("stopCurrentTransaction")

    override fun printReceipt(receipt: String): Boolean = record("printReceipt($receipt)")

    override fun printReceipt(receipt: String, options: Options): Boolean = record("printReceipt($receipt)")

    private fun record(call: String): Boolean {
        calls += call
        return accepts
    }
}

/** A [Hapi] whose every member throws: the base that [FakeHapi] overrides. */
private fun unsupported(): Hapi = Proxy.newProxyInstance(Hapi::class.java.classLoader, arrayOf(Hapi::class.java)) { _, method, _ ->
    throw UnsupportedOperationException("FakeHapi does not implement ${method.name}")
} as Hapi
