package com.handpoint.safclient.ui.detail

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.handpoint.safclient.domain.model.ProcessingStatus
import com.handpoint.safclient.domain.model.SaleChannel
import com.handpoint.safclient.testutil.InMemoryJournal.Companion.sale
import com.handpoint.safclient.ui.theme.SafTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SaleDetailScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var deletes = 0
    private var refreshes = 0
    private var backs = 0

    private fun stateFor(status: ProcessingStatus) = sale("7c1e2b1a-5d4f-4a2e-9b7d-3f0c8e6da91f", status).let {
        SaleDetailUiState(sale = it, canDelete = status == ProcessingStatus.STORED, isLoading = false)
    }

    private fun show(state: SaleDetailUiState) = compose.setContent {
        SafTheme(darkTheme = false) {
            SaleDetailScreen(
                state = state,
                snackbar = SnackbarHostState(),
                onBack = { backs++ },
                onRefresh = { refreshes++ },
                onDelete = { deletes++ },
            )
        }
    }

    @Test
    fun deleteIsHiddenWhenTheSaleIsNotStored() {
        show(stateFor(ProcessingStatus.SUCCEEDED))
        compose.onNodeWithText("Delete sale").assertDoesNotExist()
        compose.onNodeWithText("Refresh status").assertExists()
    }

    @Test
    fun deleteAsksForConfirmationFirst() {
        show(stateFor(ProcessingStatus.STORED))

        compose.onNodeWithText("Delete sale").performClick()
        compose.onNodeWithText("It hasn't been charged and will be removed from the terminal.").assertExists()
        compose.onNodeWithText("Keep").performClick()
        assertEquals(0, deletes)

        compose.onNodeWithText("Delete sale").performClick()
        compose.onNodeWithText("Delete").performClick()
        assertEquals(1, deletes)
    }

    @Test
    fun showsCardEntryReferenceAndPill() {
        show(stateFor(ProcessingStatus.STORED))
        compose.onNodeWithText("**** 4242").assertExists()
        compose.onNodeWithText("Contactless").assertExists()
        compose.onNodeWithText("7c1e2b1a-5d4f-4a2e-9b7d-3f0c8e6da91f").assertExists()
        compose.onNodeWithContentDescription("Copy reference").assertExists()
        compose.onNodeWithText("Stored").assertExists()
        compose.onNodeWithText("Approved offline", substring = true).assertExists()
    }

    @Test
    fun timelineShowsSentAndFinalSteps() {
        val sent = stateFor(ProcessingStatus.SUCCEEDED).let {
            it.copy(
                sale = it.sale!!.copy(
                    sentAt = Instant.parse("2026-10-22T11:02:00Z"),
                    statusCheckedAt = Instant.parse("2026-10-22T11:03:00Z"),
                ),
            )
        }
        show(sent)
        compose.onNodeWithText("Sent to Handpoint · ", substring = true).assertExists()
        compose.onNodeWithText("Paid · ", substring = true).assertExists()
    }

    @Test
    fun refreshIgnoresTapsWhileRefreshing() {
        show(stateFor(ProcessingStatus.IN_PROGRESS).copy(refreshing = true))
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Working"))
            .assertIsNotEnabled()
            .performClick()
        assertEquals(0, refreshes)
    }

    @Test
    fun unknownReferenceShowsNotFoundWithBack() {
        show(SaleDetailUiState(sale = null, isLoading = false))
        compose.onNodeWithText("Sale not found").assertExists()
        compose.onNodeWithText("Refresh status").assertDoesNotExist()
        compose.onNodeWithText("Back").performClick()
        assertEquals(1, backs)
    }

    @Test
    fun anOnlineSaleShowsItsChannelAndCannotBeRefreshed() {
        val online = sale("7c1e2b1a-5d4f-4a2e-9b7d-3f0c8e6da91f", ProcessingStatus.SUCCEEDED)
            .copy(channel = SaleChannel.ONLINE, statusCheckedAt = java.time.Instant.parse("2026-10-22T10:00:00Z"))
        show(SaleDetailUiState(sale = online, isLoading = false))

        compose.onNodeWithText("Approved online", substring = true).assertExists()
        compose.onNodeWithText("Sent to Handpoint", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Online").assertExists()
        compose.onNodeWithText("Refresh status").assertDoesNotExist()
    }

    @Test
    fun anOfflineSaleShowsItsChannel() {
        show(stateFor(ProcessingStatus.STORED))

        compose.onNodeWithText("Approved offline", substring = true).assertExists()
        compose.onNodeWithText("Offline").assertExists()
    }
}
