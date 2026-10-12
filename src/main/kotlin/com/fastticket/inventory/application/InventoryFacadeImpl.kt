package com.fastticket.inventory.application

import com.fastticket.inventory.HoldCreated
import com.fastticket.inventory.HoldLock
import com.fastticket.inventory.HoldView
import com.fastticket.inventory.InventoryFacade
import com.fastticket.shared.validation.Cpf
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class InventoryFacadeImpl(
    private val holds: HoldService,
    private val expiration: HoldExpirationService,
    private val payments: HoldPaymentService,
) : InventoryFacade {

    override fun createHold(cpf: Cpf, idempotencyKey: String, eventId: UUID, seatIds: List<UUID>): HoldCreated =
        holds.create(cpf, idempotencyKey, eventId, seatIds)

    override fun getHold(holdId: UUID, cpf: Cpf): HoldView = holds.get(holdId, cpf)

    override fun release(holdId: UUID, cpf: Cpf): HoldView = holds.release(holdId, cpf)

    override fun lockHoldForPayment(holdId: UUID, cpf: Cpf): HoldLock = payments.lockForPayment(holdId, cpf)

    override fun confirmHold(holdId: UUID): Boolean = payments.confirm(holdId)

    override fun findDueHoldIds(limit: Int): List<UUID> = expiration.findDue(limit)

    override fun expireDueHolds(holdIds: List<UUID>): Int = expiration.expireAll(holdIds)
}
