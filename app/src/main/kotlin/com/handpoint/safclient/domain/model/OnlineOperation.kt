package com.handpoint.safclient.domain.model

/**
 * An operation of the online API that the test screen can start. Every one of them runs on the
 * terminal against Handpoint; none is stored for later.
 *
 * - [needsAmount]: the operation sends an amount. Only [AUTOMATIC_REFUND] may leave it out: the
 *   SDK then refunds the whole original sale.
 * - [needsOriginalId]: the operation names the transaction it refunds. A [MOTO_REFUND] has none.
 * - [isMoto]: the operation is card-not-present; the SDK shows its MoTo dialog on the terminal.
 */
enum class OperationKind(
    val label: String,
    val hint: String,
    val needsOriginalId: Boolean = false,
    val isMoto: Boolean = false,
    val needsAmount: Boolean = true,
    /** Started from the detail of a sale in the history, never from New operation. */
    val fromHistory: Boolean = false,
) {
    SALE("Sale", "Card present, online."),
    REFUND("Refund (not linked)", "Card present. The card is read; no original transaction."),
    LINKED_REFUND(
        "Refund (linked)",
        "Card present, refunds the transaction ID below.",
        needsOriginalId = true,
        fromHistory = true,
    ),
    MOTO_SALE("MOTO sale", "Mail or telephone order. The terminal asks for the card data.", isMoto = true),
    MOTO_REFUND("MOTO refund (not linked)", "Mail or telephone order, no original transaction.", isMoto = true),
    MOTO_LINKED_REFUND(
        "MOTO refund (linked)",
        "Mail or telephone order, refunds the transaction ID below.",
        needsOriginalId = true,
        isMoto = true,
        fromHistory = true,
    ),
    AUTOMATIC_REFUND(
        "Automatic refund",
        "No card: the gateway refunds the card of the transaction ID below. A blank amount refunds all of it.",
        needsOriginalId = true,
        isMoto = true,
        needsAmount = false,
        fromHistory = true,
    ),
    REVERSAL("Reversal", "Cancels the sale of the transaction ID.", needsOriginalId = true, fromHistory = true),
    MOTO_REVERSAL(
        "MOTO reversal",
        "Cancels the MoTo sale of the transaction ID.",
        needsOriginalId = true,
        isMoto = true,
        needsAmount = false,
        fromHistory = true,
    ),
}

/** The MoTo channel the SDK sends (`MoToOptions.channel`). */
enum class MotoChannel(val label: String) {
    MAIL_ORDER("Mail order"),
    TELEPHONE_ORDER("Telephone order"),
}

/**
 * One test operation, with exactly the fields the tester set. The surcharge and tax fields go to
 * every operation as they are: a refund must refuse them (SDKS-467), a sale must charge them.
 *
 * [amount] is `null` only for an [OperationKind.AUTOMATIC_REFUND] of the whole sale.
 */
data class OperationRequest(
    val kind: OperationKind,
    val amount: Money?,
    val originalTransactionId: String? = null,
    val surchargeMinor: Long? = null,
    val tax: SaleTax = SaleTax.NONE,
    val motoChannel: MotoChannel = MotoChannel.TELEPHONE_ORDER,
    val avsFields: Boolean = false,
    /** The surcharge the customer accepted on the notice; its amount is also in [surchargeMinor]. */
    val surcharge: SaleSurcharge? = null,
)

/**
 * What the SDK did with an [OperationRequest]: [summary] says in words whether it started and how
 * it ended, [lines] are the values of the `TransactionResult` (empty when there is none), and
 * [transactionId] is the ID a linked refund of this operation needs.
 */
data class OperationReport(
    val summary: String,
    val lines: List<ResultLine> = emptyList(),
    val transactionId: String? = null,
    /** The SDK ended the operation with an approval (`AUTHORISED`). */
    val approved: Boolean = false,
    /** The sale a MoTo sale approval leaves in the history; `null` for any other result. */
    val sale: Sale? = null,
    /** The `TransactionResult` as the SDK serializes it (null fields are left out); `null` when there is none. */
    val resultJson: String? = null,
)
