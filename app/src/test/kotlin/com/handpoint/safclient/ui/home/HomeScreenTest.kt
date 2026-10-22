package com.handpoint.safclient.ui.home

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.handpoint.safclient.domain.model.ModeState
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.NetworkStatus
import com.handpoint.safclient.domain.model.PaymentMode
import com.handpoint.safclient.domain.model.ProcessingStatus
import com.handpoint.safclient.domain.model.SaleChannel
import com.handpoint.safclient.domain.model.TerminalConnection
import com.handpoint.safclient.testutil.InMemoryJournal.Companion.USD
import com.handpoint.safclient.testutil.InMemoryJournal.Companion.sale
import com.handpoint.safclient.ui.components.CONNECTIVITY_BANNER_TAG
import com.handpoint.safclient.ui.theme.SafTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// A PAX A920-sized screen: the default Robolectric screen (320 x 470 dp) is smaller than any
// terminal, and the connectivity banner would push the list rows out of view.
@Config(qualifiers = "w360dp-h640dp")
class HomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var sendClicks = 0
    private var settingsClicks = 0
    private var retryClicks = 0

    private val storedYesterday = sale("old", ProcessingStatus.STORED, minorUnits = 4700)
    private val succeededToday = sale("new", ProcessingStatus.SUCCEEDED, minorUnits = 820)

    private val waiting = HomeUiState(
        merchantName = "Café Aurora",
        connection = TerminalConnection.CONNECTED,
        pending = Money(4700, USD),
        pendingCount = 1,
        today = listOf(succeededToday),
        stored = listOf(storedYesterday),
        isLoading = false,
    )

    private fun show(state: HomeUiState) = compose.setContent {
        SafTheme(darkTheme = false) {
            HomeScreen(
                state = state,
                onNewSale = {},
                onSendNow = { sendClicks++ },
                onOpenSale = {},
                onOpenSettings = { settingsClicks++ },
                onRetryConnection = { retryClicks++ },
            )
        }
    }

    @Test
    fun showsPendingTotalAndSendsOnTap() {
        show(waiting)
        compose.onNodeWithText("$47.00").assertExists()
        compose.onNodeWithText("1 sale stored on this terminal").assertExists()
        compose.onNodeWithText("Send now").assertIsEnabled().performClick()
        assertEquals(1, sendClicks)
    }

    @Test
    fun sendNowIsDisabledWhileSending() {
        show(waiting.copy(isSending = true))
        compose.onNodeWithText("Sending…").assertIsNotEnabled().performClick()
        assertEquals(0, sendClicks)
    }

    @Test
    fun sendNowNeedsAConnectedTerminal() {
        show(waiting.copy(connection = TerminalConnection.DISCONNECTED))
        compose.onNodeWithText("Send now").assertIsNotEnabled()
        compose.onNodeWithText("Connect the terminal to send").assertExists()
    }

    @Test
    fun salesMissingFromTheListAreExplained() {
        show(waiting.copy(pendingCount = 3, notInList = 2))
        compose.onNodeWithText("3 sales stored on this terminal").assertExists()
        compose.onNodeWithText("2 sales on this terminal aren't in this app's list.").assertExists()
        compose.onNodeWithText("Send now").assertIsEnabled()
    }

    @Test
    fun oneSaleMissingFromTheListIsExplained() {
        show(waiting.copy(pendingCount = 2, notInList = 1))
        compose.onNodeWithText("1 sale on this terminal isn't in this app's list.").assertExists()
    }

    @Test
    fun noExplanationWhenTheListIsComplete() {
        show(waiting)
        compose.onNodeWithText("in this app's list", substring = true).assertDoesNotExist()
    }

    @Test
    fun nothingPendingSaysAllSalesSent() {
        show(waiting.copy(pending = null, pendingCount = 0, stored = emptyList()))
        compose.onNodeWithText("All sales sent").assertExists()
        compose.onNodeWithText("Send now").assertDoesNotExist()
    }

    @Test
    fun newSaleNeedsAConnectedTerminal() {
        show(waiting.copy(connection = TerminalConnection.DISCONNECTED))
        compose.onNodeWithText("New sale").assertIsNotEnabled()
        compose.onNodeWithText("Connect the terminal to take sales").assertExists()
    }

    @Test
    fun viewShowsTheSalesWaitingToSend() {
        show(waiting)
        // Sale rows read out as one sentence that starts with the amount.
        compose.onNodeWithContentDescription("$8.20,", substring = true).assertExists()

        compose.onNodeWithText("View").performClick()

        compose.onNodeWithTag(LIST_HEADER_TAG).assertIsDisplayed()
        compose.onNodeWithContentDescription("$8.20,", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("$47.00,", substring = true).assertExists()
    }

    @Test
    fun gearOpensSettings() {
        show(waiting)
        compose.onNodeWithContentDescription("Settings").performClick()
        assertEquals(1, settingsClicks)
    }

    @Test
    fun tappingTheChipRetriesWhenDisconnected() {
        show(waiting.copy(connection = TerminalConnection.DISCONNECTED))
        compose.onNodeWithText("Disconnected").performClick()
        assertEquals(1, retryClicks)
    }

    @Test
    fun theChipHasAFullTouchTargetWhenItCanBeTapped() {
        show(waiting.copy(connection = TerminalConnection.DISCONNECTED))
        // The chip's semantics cover the pill; the layout around it reserves the touch target.
        val chip = compose.onNodeWithTag(CONNECTION_CHIP_TAG, useUnmergedTree = true).fetchSemanticsNode()
        val minimum = with(compose.density) { 48.dp.roundToPx() }
        assertTrue(chip.layoutInfo.height >= minimum && chip.layoutInfo.width >= minimum)
    }

    @Test
    fun theChipDoesNothingWhenConnected() {
        show(waiting)
        compose.onNodeWithText("Connected").assertHasNoClickAction().performClick()
        assertEquals(0, retryClicks)
    }

    // --- online and offline ----------------------------------------------------------------------

    private val online = ModeState(mode = PaymentMode.ONLINE, network = NetworkStatus.ONLINE)

    @Test
    fun theBannerSaysOnline() {
        show(waiting.copy(pendingCount = 0, pending = null, stored = emptyList(), mode = online))
        compose.onNodeWithText("Online").assertExists()
    }

    @Test
    fun theBannerSaysWhenThereIsNoConnection() {
        show(waiting.copy(mode = ModeState(network = NetworkStatus.OFFLINE)))
        compose.onNodeWithText("No connection · Sales are stored on this terminal and sent later").assertExists()
    }

    @Test
    fun backOnlineWithSalesWaitingOffersSendNowInTheBanner() {
        show(waiting.copy(pendingCount = 2, mode = ModeState(network = NetworkStatus.ONLINE)))
        compose.onNodeWithText("Back online · 2 sales waiting to send").assertExists()
        compose.onNode(hasText("Send now") and hasAnyAncestor(hasTestTag(CONNECTIVITY_BANNER_TAG))).performClick()
        assertEquals(1, sendClicks)
    }

    @Test
    fun newSaleIsDisabledWhileSwitching() {
        val switching = online.copy(mode = PaymentMode.OFFLINE, switchingTo = PaymentMode.ONLINE)
        show(waiting.copy(pendingCount = 0, pending = null, stored = emptyList(), mode = switching))
        compose.onNodeWithText("Back online · getting ready…").assertExists()
        compose.onNodeWithText("New sale").assertIsNotEnabled()
        compose.onNodeWithText("Getting ready. New sale is back in a moment.").assertExists()
    }

    @Test
    fun sendNowIsDisabledWhileSwitching() {
        show(waiting.copy(mode = ModeState(network = NetworkStatus.OFFLINE, switchingTo = PaymentMode.OFFLINE)))
        compose.onNodeWithText("Send now").assertIsNotEnabled()
    }

    @Test
    fun offlineSalesCarryAnOfflineTag() {
        val online = sale("on", ProcessingStatus.SUCCEEDED).copy(channel = SaleChannel.ONLINE)
        show(waiting.copy(today = listOf(online, succeededToday)))
        // A row reads out as one sentence; the offline one says so.
        compose.onAllNodesWithContentDescription(", offline,", substring = true).assertCountEquals(1)
    }
}
