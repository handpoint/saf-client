package com.handpoint.safclient.data.journal

import com.handpoint.safclient.domain.SalesJournal
import com.handpoint.safclient.domain.model.EntryMode
import com.handpoint.safclient.domain.model.Money
import com.handpoint.safclient.domain.model.ProcessingStatus
import com.handpoint.safclient.domain.model.Sale
import com.handpoint.safclient.domain.model.SaleReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.Currency

class RoomSalesJournal(private val dao: SaleDao) : SalesJournal {

    override fun observeAll(): Flow<List<Sale>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observe(reference: SaleReference): Flow<Sale?> =
        dao.observe(reference.value).map { it?.toDomain() }

    override suspend fun find(reference: SaleReference): Sale? = dao.find(reference.value)?.toDomain()

    override suspend fun record(sale: Sale) = dao.upsert(sale.toEntity())

    override suspend fun markSent(reference: SaleReference, at: Instant) =
        dao.markSent(
            reference = reference.value,
            atEpochMs = at.toEpochMilli(),
            stored = ProcessingStatus.STORED.name,
            inProgress = ProcessingStatus.IN_PROGRESS.name,
        )

    override suspend fun updateStatus(reference: SaleReference, status: ProcessingStatus, at: Instant) =
        dao.updateStatus(reference.value, status.name, at.toEpochMilli())

    override suspend fun remove(reference: SaleReference) = dao.delete(reference.value)

    override suspend fun pending(): List<Sale> =
        dao.findByStatus(ProcessingStatus.STORED.name).map { it.toDomain() }
}

private fun SaleEntity.toDomain() = Sale(
    reference = SaleReference(reference),
    amount = Money(amountMinor, Currency.getInstance(currencyCode)),
    maskedCard = maskedCard,
    entryMode = EntryMode.valueOf(entryMode),
    approvedAt = Instant.ofEpochMilli(approvedAtEpochMs),
    sentAt = sentAtEpochMs?.let(Instant::ofEpochMilli),
    status = ProcessingStatus.valueOf(status),
    statusCheckedAt = statusCheckedAtEpochMs?.let(Instant::ofEpochMilli),
    merchantReceipt = merchantReceipt,
    customerReceipt = customerReceipt,
)

private fun Sale.toEntity() = SaleEntity(
    reference = reference.value,
    amountMinor = amount.minorUnits,
    currencyCode = amount.currency.currencyCode,
    maskedCard = maskedCard,
    entryMode = entryMode.name,
    approvedAtEpochMs = approvedAt.toEpochMilli(),
    sentAtEpochMs = sentAt?.toEpochMilli(),
    status = status.name,
    statusCheckedAtEpochMs = statusCheckedAt?.toEpochMilli(),
    merchantReceipt = merchantReceipt,
    customerReceipt = customerReceipt,
)
