package com.handpoint.safclient.domain.model

/**
 * Which payment API the app uses.
 *
 * - [ONLINE]: the online API. Each sale is approved or declined by Handpoint at once.
 * - [OFFLINE]: the Store and Forward API. Sales are stored on the terminal and sent later.
 */
enum class PaymentMode { ONLINE, OFFLINE }

/**
 * The payment mode and what the app needs to explain it to the merchant.
 *
 * @property mode the API in use. During a switch, the API that was in use.
 * @property switchingTo the mode a switch in progress goes to; `null` when no switch runs.
 *   While it is set, no sale can start and nothing can be sent.
 * @property network the device's internet connection.
 * @property waitingToSend sales stored on the terminal (the larger of the journal's STORED sales
 *   and the terminal's last answer).
 * @property onlineUnavailable the last switch to online failed; the app stays offline and tries
 *   again later.
 */
data class ModeState(
    val mode: PaymentMode = PaymentMode.OFFLINE,
    val switchingTo: PaymentMode? = null,
    val network: NetworkStatus = NetworkStatus.OFFLINE,
    val waitingToSend: Int = 0,
    val onlineUnavailable: Boolean = false,
) {
    val isSwitching: Boolean get() = switchingTo != null

    /** `true` when the Store and Forward API is in use and no switch runs: sending and status checks may run. */
    val isStoreAndForwardReady: Boolean get() = mode == PaymentMode.OFFLINE && switchingTo == null
}
