package com.handpoint.safclient.domain.model

import java.time.Instant

enum class EntryMode { CHIP, CONTACTLESS, SWIPE, MANUAL, UNKNOWN }

/**
 * How a sale was taken.
 *
 * - [OFFLINE]: through Store and Forward. The terminal stored it; it is charged after it is sent.
 * - [ONLINE]: through the online API. Handpoint approved it at once; nothing is left to send.
 */
enum class SaleChannel { ONLINE, OFFLINE }

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
    val channel: SaleChannel = SaleChannel.OFFLINE,
    /** The surcharge sent with the sale and what the SDK said about it; `null` for a sale without one. */
    val surcharge: SaleSurcharge? = null,
)
