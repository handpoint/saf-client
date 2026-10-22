package com.handpoint.safclient.ui.sale

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.handpoint.safclient.domain.OnlineTerminal
import com.handpoint.safclient.domain.StoreAndForwardTerminal
import com.handpoint.safclient.domain.model.CardStep
import com.handpoint.safclient.domain.model.ModeState
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.NetworkStatus
import com.handpoint.safclient.domain.model.PaymentMode
import com.handpoint.safclient.domain.model.ProcessingStatus
import com.handpoint.safclient.domain.model.SaleOutcome
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
class SaleScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var charges = 0
    private var cancels = 0

    private val offline = ModeState(mode = PaymentMode.OFFLINE, network = NetworkStatus.OFFLINE)
    private val online = ModeState(mode = PaymentMode.ONLINE, network = NetworkStatus.ONLINE)

    private fun show(amount: Money, state: ChargeUiState, mode: ModeState = online) = compose.setContent {
        SafTheme(darkTheme = false) {
            SaleScreen(
                amount = amount,
                state = state,
                connection = TerminalConnection.CONNECTED,
                mode = mode,
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

    // --- online and offline ----------------------------------------------------------------------

    @Test
    fun chargeSaysOfflineWhenSalesAreStored() {
        show(Money(1250, USD), ChargeUiState.Idle, mode = offline)

        compose.onNodeWithText("Charge offline $12.50").assertIsEnabled().performClick()
        compose.onNodeWithText("No connection · Sales are stored on this terminal and sent later").assertExists()

        assertEquals(1, charges)
    }

    @Test
    fun chargeIsDisabledWhileSwitching() {
        show(Money(1250, USD), ChargeUiState.Idle, mode = online.copy(switchingTo = PaymentMode.OFFLINE))

        compose.onNodeWithText("Switching to offline sales…").assertExists()
        compose.onNodeWithText("Charge $12.50").assertIsNotEnabled()
    }

    @Test
    fun theBannerStaysVisibleWhileTheCardIsRead() {
        compose.mainClock.autoAdvance = false
        show(Money(1250, USD), ChargeUiState.InProgress(null), mode = offline)
        compose.mainClock.advanceTimeBy(1_000)

        compose.onNodeWithText("No connection · Sales are stored on this terminal and sent later").assertExists()
    }

    @Test
    fun anOnlineApprovalSaysApproved() {
        show(Money(1250, USD), ChargeUiState.Result(SaleOutcome.ApprovedOnline(sale("A", ProcessingStatus.SUCCEEDED))))

        compose.onNodeWithText("Approved").assertExists()
        compose.onNodeWithText("The card has been charged.").assertExists()
    }

    @Test
    fun anOnlineDeclineShowsTheSdkMessage() {
        show(Money(1250, USD), ChargeUiState.Result(SaleOutcome.DeclinedOnline("Insufficient funds")))

        compose.onNodeWithText("Declined").assertExists()
        compose.onNodeWithText("Insufficient funds").assertExists()
        compose.onNodeWithText("Try again").assertExists()
    }

    @Test
    fun anUnknownOnlineOutcomeOffersOnlyDone() {
        show(Money(1250, USD), ChargeUiState.Result(SaleOutcome.Failed(OnlineTerminal.OUTCOME_UNKNOWN)))

        compose.onNodeWithText("The card may have been charged. Check the transaction with Handpoint before charging again.").assertExists()
        compose.onNodeWithText("Try again").assertDoesNotExist()
    }
}
