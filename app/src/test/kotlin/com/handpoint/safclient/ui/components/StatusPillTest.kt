package com.handpoint.safclient.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.handpoint.safclient.domain.model.ProcessingStatus
import com.handpoint.safclient.ui.theme.SafTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StatusPillTest {
    @get:Rule
    val compose = createComposeRule()

    private fun assertLabel(status: ProcessingStatus, label: String) {
        compose.setContent { SafTheme(darkTheme = false) { StatusPill(status) } }
        compose.onNodeWithText(label).assertIsDisplayed()
    }

    @Test
    fun storedShowsStored() = assertLabel(ProcessingStatus.STORED, "Stored")

    @Test
    fun succeededShowsPaid() = assertLabel(ProcessingStatus.SUCCEEDED, "Paid")

    @Test
    fun notFoundShowsUnknown() = assertLabel(ProcessingStatus.NOT_FOUND, "Unknown")
}
