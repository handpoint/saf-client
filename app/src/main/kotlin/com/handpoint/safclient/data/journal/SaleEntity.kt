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
    /** Surcharge percent as plain text (`"3"`); `null` for a sale without a surcharge. Schema version 3. */
    @ColumnInfo(defaultValue = "NULL")
    val surchargePercent: String? = null,
    /** Surcharge sent with the sale, in minor units of [currencyCode]. */
    @ColumnInfo(defaultValue = "NULL")
    val surchargeSentMinor: Long? = null,
    /** What the SDK said: 1 applied, 0 not applied, `null` not reported. */
    @ColumnInfo(defaultValue = "NULL")
    val surchargeApplied: Boolean? = null,
    @ColumnInfo(defaultValue = "NULL")
    val surchargeReason: String? = null,
)
