package com.handpoint.safclient.data.journal

import androidx.room.ColumnInfo
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
    /** [com.handpoint.safclient.domain.model.SaleChannel] name. Sales from schema version 1 are offline. */
    @ColumnInfo(defaultValue = "OFFLINE")
    val channel: String,
)
