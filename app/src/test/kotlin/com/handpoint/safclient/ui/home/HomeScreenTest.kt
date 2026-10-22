package com.handpoint.safclient.ui.home

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.ProcessingStatus
import com.handpoint.safclient.domain.model.TerminalConnection
import com.handpoint.safclient.testutil.InMemoryJournal.Companion.USD
import com.handpoint.safclient.testutil.InMemoryJournal.Companion.sale
import com.handpoint.safclient.ui.theme.SafTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var sendClicks = 0
    private var settingsClicks = 0

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

        compose.onNodeWithContentDescription("$8.20,", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("$47.00,", substring = true).assertExists()
    }

    @Test
    fun gearOpensSettings() {
        show(waiting)
        compose.onNodeWithContentDescription("Settings").performClick()
        assertEquals(1, settingsClicks)
    }
}
