package com.handpoint.safclient.data.journal

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SaleDao {
    @Query("SELECT * FROM sales ORDER BY approvedAtEpochMs DESC")
    fun observeAll(): Flow<List<SaleEntity>>

    @Query("SELECT * FROM sales WHERE reference = :reference")
    fun observe(reference: String): Flow<SaleEntity?>

    @Query("SELECT * FROM sales WHERE reference = :reference")
    suspend fun find(reference: String): SaleEntity?

    @Upsert
    suspend fun upsert(entity: SaleEntity)

    @Query(
        "UPDATE sales SET status = :inProgress, sentAtEpochMs = :atEpochMs " +
            "WHERE reference = :reference AND status = :stored",
    )
    suspend fun markSent(reference: String, atEpochMs: Long, stored: String, inProgress: String)

    @Query("UPDATE sales SET status = :status, statusCheckedAtEpochMs = :atEpochMs WHERE reference = :reference")
    suspend fun updateStatus(reference: String, status: String, atEpochMs: Long)

    @Query("DELETE FROM sales WHERE reference = :reference")
    suspend fun delete(reference: String)

    @Query("SELECT * FROM sales WHERE status = :status ORDER BY approvedAtEpochMs DESC")
    suspend fun findByStatus(status: String): List<SaleEntity>
}
