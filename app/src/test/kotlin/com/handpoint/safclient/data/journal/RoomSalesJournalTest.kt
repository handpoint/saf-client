package com.handpoint.safclient.data.journal

import android.content.Context
import androidx.room.Room
import org.robolectric.RuntimeEnvironment
import com.handpoint.safclient.domain.model.EntryMode
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.ProcessingStatus
import com.handpoint.safclient.domain.model.Sale
import com.handpoint.safclient.domain.model.SaleReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.util.Currency

@RunWith(RobolectricTestRunner::class)
class RoomSalesJournalTest {
    private lateinit var db: SafDatabase
    private lateinit var journal: RoomSalesJournal

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication() as Context
        db = Room.inMemoryDatabaseBuilder(context, SafDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        journal = RoomSalesJournal(db.saleDao())
    }

    @After
    fun tearDown() = db.close()

    private fun sale(ref: String, approvedAt: Long, status: ProcessingStatus = ProcessingStatus.STORED) = Sale(
        reference = SaleReference(ref),
        amount = Money(1250, Currency.getInstance("USD")),
        maskedCard = "**** 1234",
        entryMode = EntryMode.CHIP,
        approvedAt = Instant.ofEpochMilli(approvedAt),
        sentAt = null,
        status = status,
        statusCheckedAt = null,
        merchantReceipt = "merchant",
        customerReceipt = null,
    )

    @Test
    fun observeAllReturnsNewestFirst() = runBlocking {
        journal.record(sale("old", 1_000))
        journal.record(sale("new", 3_000))
        journal.record(sale("mid", 2_000))

        assertEquals(listOf("new", "mid", "old"), journal.observeAll().first().map { it.reference.value })
    }

    @Test
    fun recordRoundTripsAllFields() = runBlocking {
        val original = sale("a", 1_000)
        journal.record(original)

        assertEquals(original, journal.find(SaleReference("a")))
        assertEquals(original, journal.observe(SaleReference("a")).first())
    }

    @Test
    fun markSentMovesStoredToInProgressWithSentAt() = runBlocking {
        journal.record(sale("a", 1_000))

        journal.markSent(SaleReference("a"), Instant.ofEpochMilli(5_000))

        val updated = journal.find(SaleReference("a"))!!
        assertEquals(ProcessingStatus.IN_PROGRESS, updated.status)
        assertEquals(Instant.ofEpochMilli(5_000), updated.sentAt)
    }

    @Test
    fun markSentLeavesSucceededUntouched() = runBlocking {
        journal.record(sale("a", 1_000, ProcessingStatus.SUCCEEDED))

        journal.markSent(SaleReference("a"), Instant.ofEpochMilli(5_000))

        val unchanged = journal.find(SaleReference("a"))!!
        assertEquals(ProcessingStatus.SUCCEEDED, unchanged.status)
        assertNull(unchanged.sentAt)
    }

    @Test
    fun updateStatusSetsStatusAndCheckedAt() = runBlocking {
        journal.record(sale("a", 1_000))

        journal.updateStatus(SaleReference("a"), ProcessingStatus.FAILED, Instant.ofEpochMilli(7_000))

        val updated = journal.find(SaleReference("a"))!!
        assertEquals(ProcessingStatus.FAILED, updated.status)
        assertEquals(Instant.ofEpochMilli(7_000), updated.statusCheckedAt)
    }

    @Test
    fun pendingReturnsOnlyStored() = runBlocking {
        journal.record(sale("stored", 1_000))
        journal.record(sale("inprogress", 2_000, ProcessingStatus.IN_PROGRESS))
        journal.record(sale("done", 3_000, ProcessingStatus.SUCCEEDED))

        assertEquals(listOf("stored"), journal.pending().map { it.reference.value })
    }

    @Test
    fun removeDeletesSale() = runBlocking {
        journal.record(sale("a", 1_000))

        journal.remove(SaleReference("a"))

        assertNull(journal.find(SaleReference("a")))
        assertEquals(emptyList<Sale>(), journal.observeAll().first())
    }
}
