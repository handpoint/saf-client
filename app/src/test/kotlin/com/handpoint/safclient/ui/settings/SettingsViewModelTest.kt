package com.handpoint.safclient.ui.settings

import com.handpoint.safclient.domain.model.TerminalConnection
import com.handpoint.safclient.testutil.FakePreferences
import com.handpoint.safclient.testutil.FakeTerminal
import com.handpoint.safclient.testutil.RecordingErrorReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Currency

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val terminal = FakeTerminal()
    private val settings = FakePreferences(Currency.getInstance("EUR"))
    private val errors = RecordingErrorReporter()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.settingsViewModel(): SettingsViewModel {
        val viewModel = SettingsViewModel(settings, terminal, dispatcher, errors)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        return viewModel
    }

    @Test
    fun `shows merchant name, currency and connection`() = runTest(dispatcher) {
        settings.merchantName.value = "Café Aurora"
        val state = settingsViewModel().uiState.value

        assertEquals("Café Aurora", state.merchantName)
        assertEquals(Currency.getInstance("EUR"), state.currency)
        assertEquals(TerminalConnection.CONNECTED, state.connection)
    }

    @Test
    fun `a blank merchant name is rejected`() = runTest(dispatcher) {
        val viewModel = settingsViewModel()

        assertFalse(viewModel.saveMerchantName("   "))
        assertEquals("My Store", settings.merchantName.value)
    }

    @Test
    fun `the merchant name is trimmed and capped`() = runTest(dispatcher) {
        val viewModel = settingsViewModel()

        assertTrue(viewModel.saveMerchantName("  Café Aurora  "))
        assertEquals("Café Aurora", settings.merchantName.value)

        assertTrue(viewModel.saveMerchantName("x".repeat(60)))
        assertEquals("x".repeat(SettingsViewModel.MERCHANT_NAME_MAX), settings.merchantName.value)
    }

    @Test
    fun `connect connects the terminal`() = runTest(dispatcher) {
        terminal.connectionState.value = TerminalConnection.DISCONNECTED
        val viewModel = settingsViewModel()

        viewModel.connect()

        assertEquals(1, terminal.connectCalls)
        assertEquals(TerminalConnection.CONNECTED, viewModel.uiState.value.connection)
        assertFalse(viewModel.uiState.value.connecting)
    }

    @Test
    fun `a failing connect is reported and does not crash`() = runTest(dispatcher) {
        terminal.connectionState.value = TerminalConnection.DISCONNECTED
        terminal.connectError = IllegalStateException("SDK cannot be created")
        val viewModel = settingsViewModel()

        viewModel.connect()

        assertEquals(listOf("connect" to "SDK cannot be created"), errors.summary())
        assertEquals(TerminalConnection.DISCONNECTED, viewModel.uiState.value.connection)
        assertFalse(viewModel.uiState.value.connecting)
    }

    @Test
    fun `connect does nothing when already connected`() = runTest(dispatcher) {
        settingsViewModel().connect()

        assertEquals(0, terminal.connectCalls)
    }

    @Test
    fun `disconnect disconnects the terminal`() = runTest(dispatcher) {
        val viewModel = settingsViewModel()

        viewModel.disconnect()

        assertEquals(1, terminal.disconnectCalls)
        assertEquals(TerminalConnection.DISCONNECTED, viewModel.uiState.value.connection)
    }
}
