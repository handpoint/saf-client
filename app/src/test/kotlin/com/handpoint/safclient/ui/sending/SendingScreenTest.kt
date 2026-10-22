package com.handpoint.safclient.ui.sending

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.handpoint.safclient.domain.model.ForwardingError
import com.handpoint.safclient.domain.model.ForwardingResult
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.SaleReference
import com.handpoint.safclient.testutil.InMemoryJournal.Companion.USD
import com.handpoint.safclient.ui.theme.SafTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SendingScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var retries = 0

    private fun show(state: SendingUiState, rows: List<SendingRow> = emptyList()) = compose.setContent {
        SafTheme(darkTheme = false) {
            SendingScreen(state = state, rows = rows, onTryAgain = { retries++ }, onDone = {})
        }
    }

    @Test
    fun runningShowsProgressAndRows() {
        show(
            SendingUiState.Running(sent = 1, total = 2),
            listOf(
                SendingRow(SaleReference("A"), Money(1250, USD), SendingRowStatus.SENT),
                SendingRow(SaleReference("B"), Money(4700, USD), SendingRowStatus.WAITING),
            ),
        )

        compose.onNodeWithText("Sending to Handpoint").assertExists()
        compose.onNodeWithText("1 of 2").assertExists()
        compose.onNodeWithText("Keep the terminal on and connected.").assertExists()
        compose.onNodeWithText("Sent").assertExists()
        compose.onNodeWithText("Waiting").assertExists()
    }

    @Test
    fun networkStopHalfwayOffersTryAgain() {
        show(
            SendingUiState.Done(ForwardingResult.Partial(1, 0, 2, 3, ForwardingError.NETWORK)),
            listOf(SendingRow(SaleReference("B"), Money(4700, USD), SendingRowStatus.STILL_ON_TERMINAL)),
        )

        compose.onNodeWithText("1 of 3 sent").assertExists()
        compose.onNodeWithText("2 still on this terminal").assertExists()
        compose.onNodeWithText("Still on terminal").assertExists()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun failedRunOffersOnlyDone() {
        show(SendingUiState.Done(ForwardingResult.NoneSent(ForwardingError.FAILED, 0, 0, 0)))

        compose.onNodeWithText("Sales couldn't be sent.").assertExists()
        compose.onNodeWithText("Try again").assertDoesNotExist()
        compose.onNodeWithText("Done").assertExists()
    }

    @Test
    fun nothingToSend() {
        show(SendingUiState.Done(null))

        compose.onNodeWithText("Nothing to send").assertExists()
    }
}
