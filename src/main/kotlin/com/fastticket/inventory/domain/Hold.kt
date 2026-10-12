package com.fastticket.inventory.domain

import com.fastticket.shared.validation.Cpf
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

const val MAX_TICKETS_PER_BUYER = 6

enum class HoldStatus { ACTIVE, CONFIRMED, EXPIRED, RELEASED, CANCELLED }

data class HoldItem(
    val seatId: UUID,
    val sectorId: UUID,
    val unitPrice: BigDecimal,
    val halfPrice: Boolean = false,
)

data class Hold(
    val id: UUID,
    val eventId: UUID,
    val buyerCpf: Cpf,
    val status: HoldStatus,
    val expiresAt: Instant?,
    val totalAmount: BigDecimal,
    val items: List<HoldItem>,
)
