package com.fastticket.checkout.domain

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class OrderStatus { CONFIRMED, CANCELLED }

data class Order(
    val id: UUID,
    val holdId: UUID,
    val status: OrderStatus,
    val totalAmount: BigDecimal,
    val confirmedAt: Instant? = null,
    val cancelledAt: Instant? = null,
)
