package com.handpoint.safclient.domain.model

data class StoredSummary(val references: List<SaleReference>, val total: Money?)

enum class DeleteResult { DELETED, NOT_ON_TERMINAL, ERROR }
