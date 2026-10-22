package com.handpoint.safclient.data.journal

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sales")
data class SaleEntity(
    @PrimaryKey val reference: String,
    val amountMinor: Long,
    val currencyCode: String,
    val maskedCard: String?,
    val entryMode: String,
    val approvedAtEpochMs: Long,
    val sentAtEpochMs: Long?,
    val status: String,
    val statusCheckedAtEpochMs: Long?,
    val merchantReceipt: String?,
    val customerReceipt: String?,
)
