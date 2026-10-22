package com.handpoint.safclient.domain.model

/**
 * Which payment API the app uses.
 *
 * - [ONLINE]: the online API. Each sale is approved or declined by Handpoint at once.
 * - [OFFLINE]: the Store and Forward API. Sales are stored on the terminal and sent later.
 */
enum class PaymentMode { ONLINE, OFFLINE }

/** Why the app still stores sales offline although the network is up and nothing waits to be sent. */
enum class OnlineWait {
    /** The terminal has not said yet which sales it stores. */
    AWAITING_TERMINAL,

    /** Handpoint is being asked about the sales just sent; that needs Store and Forward. */
    BUSY_FOLLOW_UP,

    /** A sale or a send is running, or the merchant is on the charge screen. */
    WAITING_IDLE,

    /** The network dropped recently: the app waits until it has been stable for a while. */
    NETWORK_SETTLING,
}

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
 * @property onlineWait why the app is not switching to online although it could; `null` otherwise.
 * @property wasOffline the network has been down at least once since the app started, so its
 *   return is a "back online".
 */
data class ModeState(
    val mode: PaymentMode = PaymentMode.OFFLINE,
    val switchingTo: PaymentMode? = null,
    val network: NetworkStatus = NetworkStatus.OFFLINE,
    val waitingToSend: Int = 0,
    val onlineUnavailable: Boolean = false,
    val onlineWait: OnlineWait? = null,
    val wasOffline: Boolean = false,
) {
    val isSwitching: Boolean get() = switchingTo != null

    /** `true` when the Store and Forward API is in use and no switch runs: sending and status checks may run. */
    val isStoreAndForwardReady: Boolean get() = mode == PaymentMode.OFFLINE && switchingTo == null
}
