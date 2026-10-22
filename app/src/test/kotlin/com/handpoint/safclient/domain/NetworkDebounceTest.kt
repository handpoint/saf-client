package com.handpoint.safclient.domain

import app.cash.turbine.test
import com.handpoint.safclient.domain.model.NetworkStatus.OFFLINE
import com.handpoint.safclient.domain.model.NetworkStatus.ONLINE
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkDebounceTest {

    private val validated = MutableStateFlow(true)

    @Test
    fun `the first reading is reported at once`() = runTest {
        validated.value = false
        validated.debounceNetwork().test {
            assertEquals(OFFLINE, awaitItem())
            assertEquals(0L, testScheduler.currentTime)
        }
    }

    @Test
    fun `offline is reported after 3 seconds without a validated network`() = runTest {
        validated.debounceNetwork().test {
            assertEquals(ONLINE, awaitItem())
            validated.value = false

            advanceTimeBy(2_999)
            runCurrent()
            expectNoEvents()

            advanceTimeBy(1)
            runCurrent()
            assertEquals(OFFLINE, awaitItem())
        }
    }

    @Test
    fun `online is reported after 5 seconds of stable network`() = runTest {
        validated.value = false
        validated.debounceNetwork().test {
            assertEquals(OFFLINE, awaitItem())
            validated.value = true

            advanceTimeBy(4_999)
            runCurrent()
            expectNoEvents()

            advanceTimeBy(1)
            runCurrent()
            assertEquals(ONLINE, awaitItem())
        }
    }

    @Test
    fun `a flapping network does not switch`() = runTest {
        validated.debounceNetwork().test {
            assertEquals(ONLINE, awaitItem())
            repeat(5) {
                validated.value = false
                advanceTimeBy(2_000)
                runCurrent()
                validated.value = true
                advanceTimeBy(2_000)
                runCurrent()
            }
            advanceTimeBy(60_000)
            runCurrent()
            expectNoEvents()
        }
    }

    @Test
    fun `a network that comes back too briefly stays offline`() = runTest {
        validated.value = false
        validated.debounceNetwork().test {
            assertEquals(OFFLINE, awaitItem())
            validated.value = true
            advanceTimeBy(4_000)
            runCurrent()
            validated.value = false
            advanceTimeBy(60_000)
            runCurrent()
            expectNoEvents()
        }
    }
}
