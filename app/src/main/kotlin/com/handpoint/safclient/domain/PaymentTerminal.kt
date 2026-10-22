package com.handpoint.safclient.domain

import com.handpoint.safclient.domain.model.CardStep
import com.handpoint.safclient.domain.model.TerminalConnection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * What both payment APIs of the terminal have in common: the online API ([OnlineTerminal]) and
 * the Store and Forward API ([StoreAndForwardTerminal]).
 *
 * **Only one of the two can be active at a time, in the whole process.** Creating either SDK
 * API reconfigures the SDK's shared state and drops the event listeners of the other one. So a
 * switch is: [deactivate] the active one, then [activate] the other one (which creates its SDK
 * API again) and wait for [connection] to be CONNECTED. This takes about 5 to 10 seconds.
 * [PaymentModeCoordinator] decides when to switch; nothing else calls [activate] or [deactivate].
 *
 * The members that talk to the SDK may block: call them off the main thread.
 */
interface PaymentTerminal {

    /** Current connection to the terminal's payment service. */
    val connection: StateFlow<TerminalConnection>

    /** Card prompts during a sale ("Present card", "Enter PIN", ...). Hot; no replay. */
    val cardSteps: Flow<CardStep>

    /**
     * Makes this API the active one: creates its SDK API (again, if the other API was active
     * since), registers for its events and connects. Returns `false` when the SDK refuses.
     * [connection] shows when it is ready.
     */
    fun activate(): Boolean

    /**
     * Stops this API so that the other one can be activated. Until the next [activate], calls
     * that need the SDK fail at once with their failure value; they never create the SDK API.
     */
    fun deactivate(): Boolean

    /** Connects again (for example after the merchant taps "Connect"). Returns `false` when the SDK refuses. */
    fun connect(): Boolean

    /** Disconnects. Returns `false` when the SDK refuses. */
    fun disconnect(): Boolean

    /** Asks the SDK to stop the current sale. Only works before the card is read. */
    fun cancelSale(): Boolean

    /** Prints a receipt on the terminal printer. */
    fun printReceipt(receipt: String): Boolean
}
