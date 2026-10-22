package com.handpoint.safclient.ui.components

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.handpoint.safclient.ui.theme.SafTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrimaryButtonTest {
    @get:Rule
    val compose = createComposeRule()

    private var clicks = 0

    private fun show(loading: Boolean) = compose.setContent {
        SafTheme(darkTheme = false) {
            PrimaryButton("Charge", onClick = { clicks++ }, loading = loading, modifier = Modifier.testTag("cta"))
        }
    }

    @Test
    fun clickRunsActionWhenIdle() {
        show(loading = false)
        compose.onNodeWithTag("cta").assertIsEnabled().performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun loadingIgnoresTaps() {
        show(loading = true)
        compose.onNodeWithTag("cta").assertIsNotEnabled().performClick()
        assertEquals(0, clicks)
    }
}
