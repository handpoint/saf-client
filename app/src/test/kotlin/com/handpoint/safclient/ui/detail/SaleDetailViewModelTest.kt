package com.handpoint.safclient.ui.detail

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.handpoint.safclient.domain.model.DeleteResult
import com.handpoint.safclient.domain.model.ProcessingStatus.IN_PROGRESS
import com.handpoint.safclient.domain.model.ProcessingStatus.STORED
import com.handpoint.safclient.domain.model.ProcessingStatus.SUCCEEDED
import com.handpoint.safclient.domain.model.SaleReference
import com.handpoint.safclient.domain.usecase.DeleteStoredSale
import com.handpoint.safclient.domain.usecase.ReconcileStoredSales
import com.handpoint.safclient.domain.usecase.RefreshSaleStatus
import com.handpoint.safclient.testutil.FakePreferences
import com.handpoint.safclient.testutil.FakeTerminal
import com.handpoint.safclient.testutil.InMemoryJournal
import com.handpoint.safclient.testutil.InMemoryJournal.Companion.sale
import com.handpoint.safclient.testutil.RecordingErrorReporter
import com.handpoint.safclient.ui.navigation.Routes
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class SaleDetailViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val clock = Clock.fixed(Instant.parse("2026-10-22T12:00:00Z"), ZoneOffset.UTC)
    private val terminal = FakeTerminal()
    private val errors = RecordingErrorReporter()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.detailViewModel(journal: InMemoryJournal, reference: String = "A"): SaleDetailViewModel {
        val viewModel = SaleDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf(Routes.SALE_REFERENCE to reference)),
            journal = journal,
            refresh = RefreshSaleStatus(terminal, journal, clock),
            delete = DeleteStoredSale(
                terminal,
                journal,
                ReconcileStoredSales(terminal, journal, FakePreferences(), clock),
                clock,
            ),
            io = dispatcher,
            errors = errors,
        )
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        return viewModel
    }

    @Test
    fun `only a STORED sale can be deleted`() = runTest(dispatcher) {
        assertTrue(detailViewModel(InMemoryJournal(sale("A", STORED))).uiState.value.canDelete)
        assertFalse(detailViewModel(InMemoryJournal(sale("A", IN_PROGRESS))).uiState.value.canDelete)
        assertFalse(detailViewModel(InMemoryJournal(sale("A", SUCCEEDED))).uiState.value.canDelete)
    }

    @Test
    fun `shows the sale from the journal`() = runTest(dispatcher) {
        val viewModel = detailViewModel(InMemoryJournal(sale("A", STORED)))

        val state = viewModel.uiState.value
        assertEquals(SaleReference("A"), state.sale?.reference)
        assertFalse(state.isLoading)
        assertFalse(state.notFound)
    }

    @Test
    fun `refresh without an answer sets a message and keeps the status`() = runTest(dispatcher) {
        terminal.statusAnswer = null
        val viewModel = detailViewModel(InMemoryJournal(sale("A", IN_PROGRESS)))

        viewModel.refreshStatus()

        val state = viewModel.uiState.value
        assertEquals(SaleDetailViewModel.REFRESH_FAILED, state.message)
        assertEquals(IN_PROGRESS, state.sale?.status)
        assertFalse(state.refreshing)

        viewModel.onMessageShown()
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun `refresh with an answer updates the sale`() = runTest(dispatcher) {
        terminal.statusAnswer = SUCCEEDED
        val viewModel = detailViewModel(InMemoryJournal(sale("A", IN_PROGRESS)))

        viewModel.refreshStatus()

        assertEquals(SUCCEEDED, viewModel.uiState.value.sale?.status)
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun `a refresh in progress ignores a second tap`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        terminal.statusGate = gate
        terminal.statusAnswer = SUCCEEDED
        val viewModel = detailViewModel(InMemoryJournal(sale("A", IN_PROGRESS)))

        viewModel.refreshStatus()
        assertTrue(viewModel.uiState.value.refreshing)
        viewModel.refreshStatus()

        gate.complete(Unit)
        assertEquals(1, terminal.statusRequests.size)
        assertFalse(viewModel.uiState.value.refreshing)
    }

    @Test
    fun `a failing refresh is reported and sets a message`() = runTest(dispatcher) {
        terminal.statusError = IllegalStateException("SDK gone")
        val viewModel = detailViewModel(InMemoryJournal(sale("A", IN_PROGRESS)))

        viewModel.refreshStatus()

        assertEquals(SaleDetailViewModel.REFRESH_FAILED, viewModel.uiState.value.message)
        assertEquals(listOf("refresh status" to "SDK gone"), errors.summary())
    }

    @Test
    fun `delete DELETED emits deleted`() = runTest(dispatcher) {
        terminal.deleteResult = DeleteResult.DELETED
        val journal = InMemoryJournal(sale("A", STORED))
        val viewModel = detailViewModel(journal)

        viewModel.deleted.test {
            viewModel.deleteSale()
            awaitItem()
        }
        assertNull(journal.find(SaleReference("A")))
    }

    @Test
    fun `delete NOT_ON_TERMINAL stays, keeps the sale as sent and says why`() = runTest(dispatcher) {
        terminal.deleteResult = DeleteResult.NOT_ON_TERMINAL
        val viewModel = detailViewModel(InMemoryJournal(sale("A", STORED)))

        viewModel.deleted.test {
            viewModel.deleteSale()
            expectNoEvents()
        }
        val state = viewModel.uiState.value
        assertEquals(SaleDetailViewModel.ALREADY_SENT, state.message)
        assertEquals(IN_PROGRESS, state.sale?.status)
        assertFalse(state.canDelete)
        assertFalse(state.deleting)
    }

    @Test
    fun `delete ERROR keeps the sale and sets a message`() = runTest(dispatcher) {
        terminal.deleteResult = DeleteResult.ERROR
        val viewModel = detailViewModel(InMemoryJournal(sale("A", STORED)))

        viewModel.deleted.test {
            viewModel.deleteSale()
            expectNoEvents()
        }
        assertEquals(SaleDetailViewModel.DELETE_FAILED, viewModel.uiState.value.message)
        assertEquals(STORED, viewModel.uiState.value.sale?.status)
        assertFalse(viewModel.uiState.value.deleting)
    }

    @Test
    fun `an unknown reference is not found`() = runTest(dispatcher) {
        val viewModel = detailViewModel(InMemoryJournal(sale("A", STORED)), reference = "missing")

        val state = viewModel.uiState.value
        assertTrue(state.notFound)
        assertFalse(state.isLoading)
        assertFalse(state.canDelete)
    }
}
