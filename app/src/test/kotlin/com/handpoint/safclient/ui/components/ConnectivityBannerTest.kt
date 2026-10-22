package com.handpoint.safclient.ui.components

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.handpoint.safclient.domain.model.ModeState
import com.handpoint.safclient.domain.model.NetworkStatus
import com.handpoint.safclient.domain.model.PaymentMode
import com.handpoint.safclient.ui.theme.SafTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConnectivityBannerTest {
    @get:Rule
    val compose = createComposeRule()

    private var sends = 0

    private val online = ModeState(mode = PaymentMode.ONLINE, network = NetworkStatus.ONLINE)
    private val offline = ModeState(mode = PaymentMode.OFFLINE, network = NetworkStatus.OFFLINE)
    private val backOnline = ModeState(mode = PaymentMode.OFFLINE, network = NetworkStatus.ONLINE)

    private fun show(state: ModeState, waiting: Int = 0, sendEnabled: Boolean = true, withSend: Boolean = true) =
        compose.setContent {
            SafTheme(darkTheme = false) {
                ConnectivityBanner(
                    state = state,
                    waitingToSend = waiting,
                    onSendNow = if (withSend) ({ sends++ }) else null,
                    sendEnabled = sendEnabled,
                )
            }
        }

    // --- which banner ----------------------------------------------------------------------------

    @Test
    fun eachStateHasItsBanner() {
        assertEquals(BannerKind.ONLINE, bannerKind(online, 0))
        assertEquals(BannerKind.NO_CONNECTION, bannerKind(offline, 3))
        assertEquals(BannerKind.SWITCHING_TO_OFFLINE, bannerKind(online.copy(switchingTo = PaymentMode.OFFLINE), 0))
        assertEquals(BannerKind.SWITCHING_TO_ONLINE, bannerKind(backOnline.copy(switchingTo = PaymentMode.ONLINE), 0))
        assertEquals(BannerKind.WAITING_TO_SEND, bannerKind(backOnline, 2))
        // Network back, nothing waiting: the switch to online comes once the app is idle.
        assertEquals(BannerKind.SWITCHING_TO_ONLINE, bannerKind(backOnline, 0))
        assertEquals(BannerKind.ONLINE_UNAVAILABLE, bannerKind(backOnline.copy(onlineUnavailable = true), 0))
        // Sales waiting matter more than a failed online switch: they can be sent.
        assertEquals(BannerKind.WAITING_TO_SEND, bannerKind(backOnline.copy(onlineUnavailable = true), 1))
    }

    // --- what it shows ---------------------------------------------------------------------------

    @Test
    fun onlineIsQuiet() {
        show(online)
        compose.onNodeWithText("Online").assertExists()
        compose.onNodeWithText("Send now").assertDoesNotExist()
    }

    @Test
    fun noConnectionExplainsThatSalesAreStored() {
        show(offline, waiting = 2)
        compose.onNodeWithText("No connection · Sales are stored on this terminal and sent later").assertExists()
    }

    @Test
    fun switchingToOfflineSaysSo() {
        show(online.copy(switchingTo = PaymentMode.OFFLINE))
        compose.onNodeWithText("Switching to offline sales…").assertExists()
        compose.onNodeWithTag(BANNER_PROGRESS_TAG).assertExists()
    }

    @Test
    fun switchingToOnlineSaysBackOnline() {
        show(backOnline.copy(switchingTo = PaymentMode.ONLINE))
        compose.onNodeWithText("Back online · getting ready…").assertExists()
        compose.onNodeWithTag(BANNER_PROGRESS_TAG).assertExists()
    }

    @Test
    fun backOnlineWithSalesWaitingOffersSendNow() {
        show(backOnline, waiting = 2)
        compose.onNodeWithText("Back online · 2 sales waiting to send").assertExists()
        compose.onNodeWithText("Send now").assertIsEnabled().performClick()
        assertEquals(1, sends)
    }

    @Test
    fun oneSaleWaitingIsSingular() {
        show(backOnline, waiting = 1)
        compose.onNodeWithText("Back online · 1 sale waiting to send").assertExists()
    }

    @Test
    fun sendNowCanBeDisabled() {
        show(backOnline, waiting = 2, sendEnabled = false)
        compose.onNodeWithText("Send now").assertIsNotEnabled().performClick()
        assertEquals(0, sends)
    }

    @Test
    fun withoutASendActionThereIsNoSendNow() {
        show(backOnline, waiting = 2, withSend = false)
        compose.onNodeWithText("Back online · 2 sales waiting to send").assertExists()
        compose.onNodeWithText("Send now").assertDoesNotExist()
    }

    @Test
    fun aFailedSwitchToOnlineIsExplained() {
        show(backOnline.copy(onlineUnavailable = true))
        compose.onNodeWithText("Online sales unavailable · Sales are stored on this terminal and sent later").assertExists()
    }
}
