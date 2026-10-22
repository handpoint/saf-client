package com.handpoint.safclient.testutil

import com.handpoint.safclient.domain.SalesJournal
import com.handpoint.safclient.domain.model.EntryMode
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.ProcessingStatus
import com.handpoint.safclient.domain.model.Sale
import com.handpoint.safclient.domain.model.SaleReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.util.Currency

/** [SalesJournal] kept in memory, with the same rules as the Room journal. */
class InMemoryJournal(vararg initial: Sale) : SalesJournal {

    private val sales = MutableStateFlow(initial.associateBy { it.reference })

    override fun observeAll(): Flow<List<Sale>> =
        sales.map { all -> all.values.sortedByDescending { it.approvedAt } }

    override fun observe(reference: SaleReference): Flow<Sale?> = sales.map { it[reference] }

    override suspend fun find(reference: SaleReference): Sale? = sales.value[reference]

    /** Thrown by [record] when set, as when the disk is full. */
    var failRecord: Exception? = null

    override suspend fun record(sale: Sale) {
        failRecord?.let { throw it }
        sales.update { it + (sale.reference to sale) }
    }

    override suspend fun markSent(reference: SaleReference, at: Instant) = sales.update { all ->
        val sale = all[reference]
        if (sale?.status != ProcessingStatus.STORED) all
        else all + (reference to sale.copy(status = ProcessingStatus.IN_PROGRESS, sentAt = at))
    }

    override suspend fun markStored(reference: SaleReference) = sales.update { all ->
        val sale = all[reference]
        if (sale == null || sale.status.isFinal || sale.status == ProcessingStatus.STORED) all
        else all + (reference to sale.copy(status = ProcessingStatus.STORED, sentAt = null))
    }

    override suspend fun updateStatus(reference: SaleReference, status: ProcessingStatus, at: Instant) =
        sales.update { all ->
            val sale = all[reference] ?: return@update all
            all + (reference to sale.copy(status = status, statusCheckedAt = at))
        }

    override suspend fun remove(reference: SaleReference) = sales.update { it - reference }

    override suspend fun pending(): List<Sale> = sales.value.values.filter { it.status == ProcessingStatus.STORED }

    fun status(reference: String): ProcessingStatus? = sales.value[SaleReference(reference)]?.status

    companion object {
        val USD: Currency = Currency.getInstance("USD")

        fun sale(
            reference: String,
            status: ProcessingStatus = ProcessingStatus.STORED,
            minorUnits: Long = 1000,
        ) = Sale(
            reference = SaleReference(reference),
            amount = Money(minorUnits, USD),
            maskedCard = "**** 4242",
            entryMode = EntryMode.CONTACTLESS,
            approvedAt = Instant.parse("2026-10-22T10:00:00Z"),
            sentAt = null,
            status = status,
            statusCheckedAt = null,
            merchantReceipt = null,
            customerReceipt = null,
        )
    }
}
