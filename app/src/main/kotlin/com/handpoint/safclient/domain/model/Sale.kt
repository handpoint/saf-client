package com.handpoint.safclient.domain.model

import java.time.Instant

enum class EntryMode { CHIP, CONTACTLESS, SWIPE, MANUAL, UNKNOWN }

data class Sale(
    val reference: SaleReference,
    val amount: Money,
    val maskedCard: String?,
    val entryMode: EntryMode,
    val approvedAt: Instant,
    val sentAt: Instant?,
    val status: ProcessingStatus,
    val statusCheckedAt: Instant?,
    val merchantReceipt: String?,
    val customerReceipt: String?,
)
