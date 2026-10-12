package com.fastticket.inventory

import com.fastticket.shared.validation.Cpf
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class HoldItemView(val seatId: UUID, val sectorId: UUID, val unitPrice: BigDecimal)

data class HoldView(
    val id: UUID,
    val eventId: UUID,
    val status: String,
    val expiresAt: Instant,
    val totalAmount: BigDecimal,
    val items: List<HoldItemView>,
)

data class HoldCreated(val hold: HoldView, val replayed: Boolean)

sealed interface HoldLock {
    data class Ready(val hold: HoldView) : HoldLock
    data object Expired : HoldLock
    data object NotFound : HoldLock
    data object NotOwner : HoldLock
}

interface InventoryFacade {
    fun createHold(cpf: Cpf, idempotencyKey: String, eventId: UUID, seatIds: List<UUID>): HoldCreated
    fun getHold(holdId: UUID, cpf: Cpf): HoldView
    fun release(holdId: UUID, cpf: Cpf): HoldView
    fun lockHoldForPayment(holdId: UUID, cpf: Cpf): HoldLock
    fun confirmHold(holdId: UUID): Boolean
    fun findDueHoldIds(limit: Int): List<UUID>
    fun expireDueHolds(holdIds: List<UUID>): Int
}
