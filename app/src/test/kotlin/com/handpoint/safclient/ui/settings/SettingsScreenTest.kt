package com.handpoint.safclient.ui.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasSetTextAction
import com.handpoint.safclient.domain.model.TerminalConnection
import com.handpoint.safclient.ui.theme.SafTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.util.Currency

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val saved = mutableListOf<String>()
    private var connects = 0
    private var disconnects = 0
    private var guideOpens = 0

    private val base = SettingsUiState(
        merchantName = "Café Aurora",
        currency = Currency.getInstance("USD"),
        connection = TerminalConnection.CONNECTED,
    )

    private fun show(state: SettingsUiState) = compose.setContent {
        SafTheme(darkTheme = false) {
            SettingsScreen(
                state = state,
                sdkVersion = "7.1014.1",
                appVersion = "1.0",
                snackbar = SnackbarHostState(),
                actions = SettingsActions(
                    onBack = {},
                    onSaveMerchantName = { saved += it; it.isNotBlank() },
                    onConnect = { connects++ },
                    onDisconnect = { disconnects++ },
                    onOpenGuide = { guideOpens++ },
                ),
            )
        }
    }

    @Test
    fun showsCurrencyVersionsAndGuide() {
        show(base)
        compose.onNodeWithText("USD — US Dollar").assertExists()
        compose.onNodeWithText("Set by Handpoint on the terminal").assertExists()
        compose.onNodeWithText("7.1014.1").assertExists()
        compose.onNodeWithText("Store and Forward guide").performScrollTo().performClick()
        assertEquals(1, guideOpens)
    }

    @Test
    fun editingTheNameSavesIt() {
        show(base)
        compose.onNodeWithText("Café Aurora").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Bar Luna")
        compose.onNodeWithText("Save").performClick()

        assertEquals(listOf("Bar Luna"), saved)
        compose.onNodeWithText("Save").assertDoesNotExist()
    }

    @Test
    fun aBlankNameCannotBeSaved() {
        show(base)
        compose.onNodeWithText("Café Aurora").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("   ")
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun theNameFieldStopsAtTheLimit() {
        show(base)
        compose.onNodeWithText("Café Aurora").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("x".repeat(60))
        compose.onNodeWithText("40 / 40").assertExists()
    }

    @Test
    fun connectedTerminalOffersDisconnect() {
        show(base)
        compose.onNodeWithText("Connect").assertDoesNotExist()
        compose.onNodeWithText("Disconnect").performClick()
        assertEquals(1, disconnects)
    }

    @Test
    fun disconnectedTerminalOffersConnect() {
        show(base.copy(connection = TerminalConnection.DISCONNECTED))
        compose.onNodeWithText("Connect").assertIsEnabled().performClick()
        assertEquals(1, connects)
    }
}
