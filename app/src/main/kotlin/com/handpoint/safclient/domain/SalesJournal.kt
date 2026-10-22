package com.handpoint.safclient.domain

import com.handpoint.safclient.domain.model.ProcessingStatus
import com.handpoint.safclient.domain.model.Sale
import com.handpoint.safclient.domain.model.SaleReference
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** Port over local storage of sales taken on this device. */
interface SalesJournal {
    /** All sales, newest first. */
    fun observeAll(): Flow<List<Sale>>

    fun observe(reference: SaleReference): Flow<Sale?>

    suspend fun find(reference: SaleReference): Sale?

    suspend fun record(sale: Sale)

    /** Moves a STORED sale to IN_PROGRESS and sets its sent time. Sales in any other status are left untouched. */
    suspend fun markSent(reference: SaleReference, at: Instant)

    /**
     * Moves a sale back to STORED and clears its sent time, when its status is IN_PROGRESS or
     * NOT_FOUND. Final statuses are left untouched. Only call it when the terminal lists the sale.
     */
    suspend fun markStored(reference: SaleReference)

    suspend fun updateStatus(reference: SaleReference, status: ProcessingStatus, at: Instant)

    suspend fun remove(reference: SaleReference)

    /** Sales whose status is STORED. */
    suspend fun pending(): List<Sale>
}
