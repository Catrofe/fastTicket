package com.fastticket.checkout.domain

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class RefundStatus { PENDING, DONE }

enum class RefundReason { EXPIRED_AFTER_APPROVAL, CANCELLED }

data class Refund(
    val id: UUID,
    val paymentId: UUID,
    val amount: BigDecimal,
    val status: RefundStatus,
    val reason: RefundReason,
    val gatewayRef: String? = null,
    val createdAt: Instant? = null,
)
