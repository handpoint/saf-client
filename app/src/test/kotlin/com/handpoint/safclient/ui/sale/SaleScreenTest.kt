package com.handpoint.safclient.ui.sale

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.handpoint.safclient.domain.StoreAndForwardTerminal
import com.handpoint.safclient.domain.model.CardStep
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.SaleOutcome
import com.handpoint.safclient.domain.model.TerminalConnection
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
class SaleScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var charges = 0
    private var cancels = 0

    private fun show(amount: Money, state: ChargeUiState) = compose.setContent {
        SafTheme(darkTheme = false) {
            SaleScreen(
                amount = amount,
                state = state,
                connection = TerminalConnection.CONNECTED,
                printing = false,
                shakeSignal = 0,
                snackbar = SnackbarHostState(),
                actions = SaleActions(onCharge = { charges++ }, onCancel = { cancels++ }),
            )
        }
    }

    @Test
    fun chargeIsDisabledAtZero() {
        show(Money(0, USD), ChargeUiState.Idle)

        compose.onNodeWithText("Charge $0.00").assertIsNotEnabled().performClick()

        assertEquals(0, charges)
    }

    @Test
    fun chargeShowsTheAmount() {
        show(Money(1250, USD), ChargeUiState.Idle)

        compose.onNodeWithText("Charge $12.50").assertIsEnabled().performClick()

        assertEquals(1, charges)
    }

    @Test
    fun cancelShowsOnlyWhileTheStepAllowsIt() {
        compose.mainClock.autoAdvance = false
        show(Money(1250, USD), ChargeUiState.InProgress(CardStep(CardStep.Kind.ENTER_PIN, cancelAllowed = false)))
        compose.mainClock.advanceTimeBy(1_000)

        compose.onNodeWithText("Customer is entering PIN").assertExists()
        compose.onNodeWithText("Cancel").assertDoesNotExist()
    }

    @Test
    fun unknownOutcomeOffersOnlyDone() {
        show(Money(1250, USD), ChargeUiState.Result(SaleOutcome.Failed(StoreAndForwardTerminal.OUTCOME_UNKNOWN)))

        compose.onNodeWithText("Couldn't confirm the sale").assertExists()
        compose.onNodeWithText("Done").assertExists()
        compose.onNodeWithText("Try again").assertDoesNotExist()
    }
}
