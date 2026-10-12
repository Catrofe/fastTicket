package com.fastticket.checkout.domain

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class PaymentStatus { PENDING, APPROVED, DECLINED }

enum class PaymentMethod { CREDIT, DEBIT }

data class Payment(
    val id: UUID,
    val holdId: UUID,
    val method: PaymentMethod,
    val amount: BigDecimal,
    val status: PaymentStatus,
    val gatewayRef: String? = null,
    val declineReason: String? = null,
    val approvedAt: Instant? = null,
    val createdAt: Instant? = null,
)
