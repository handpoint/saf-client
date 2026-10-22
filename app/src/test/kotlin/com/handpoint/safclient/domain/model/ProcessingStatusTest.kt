package com.handpoint.safclient.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingStatusTest {

    @Test
    fun `stored, in progress and not found are not final`() {
        assertFalse(ProcessingStatus.STORED.isFinal)
        assertFalse(ProcessingStatus.IN_PROGRESS.isFinal)
        assertFalse(ProcessingStatus.NOT_FOUND.isFinal)
    }

    @Test
    fun `succeeded, failed, captured, refunded and cancelled are final`() {
        assertTrue(ProcessingStatus.SUCCEEDED.isFinal)
        assertTrue(ProcessingStatus.FAILED.isFinal)
        assertTrue(ProcessingStatus.CAPTURED.isFinal)
        assertTrue(ProcessingStatus.REFUNDED.isFinal)
        assertTrue(ProcessingStatus.CANCELLED.isFinal)
    }
}
