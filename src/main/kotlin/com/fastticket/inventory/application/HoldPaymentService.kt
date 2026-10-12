package com.fastticket.inventory.application

import com.fastticket.inventory.HoldExpiryVeto
import com.fastticket.inventory.HoldLock
import com.fastticket.inventory.domain.BuyerEventRepository
import com.fastticket.inventory.domain.Hold
import com.fastticket.inventory.domain.HoldRepository
import com.fastticket.inventory.domain.HoldStatus
import com.fastticket.inventory.domain.SeatAllocationRepository
import com.fastticket.shared.time.DbClock
import com.fastticket.shared.validation.Cpf
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class HoldPaymentService(
    private val holds: HoldRepository,
    private val buyers: BuyerEventRepository,
    private val allocations: SeatAllocationRepository,
    private val releaser: HoldReleaser,
    private val vetoes: List<HoldExpiryVeto>,
    private val clock: DbClock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.MANDATORY)
    fun lockForPayment(holdId: UUID, cpf: Cpf): HoldLock {
        val peek = holds.findById(holdId) ?: return HoldLock.NotFound
        if (peek.buyerCpf != cpf) return HoldLock.NotOwner
        if (peek.status != HoldStatus.ACTIVE) return HoldLock.Expired
        if (isDue(peek)) return expireLazily(peek)
        val hold = holds.lockById(holdId) ?: return HoldLock.NotFound
        if (hold.status != HoldStatus.ACTIVE || isDue(hold)) return HoldLock.Expired
        return HoldLock.Ready(hold.toView())
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun confirm(holdId: UUID): Boolean {
        val hold = holds.lockById(holdId) ?: return false
        if (hold.status != HoldStatus.ACTIVE || !holds.transition(holdId, HoldStatus.ACTIVE, HoldStatus.CONFIRMED)) return false
        allocations.confirm(holdId)
        return true
    }

    private fun expireLazily(peek: Hold): HoldLock {
        buyers.lock(peek.buyerCpf, peek.eventId)
        val due = holds.lockDueById(peek.id) ?: return HoldLock.Expired
        if (vetoes.none { it.vetoes(due.id) } && releaser.release(due.id, HoldStatus.EXPIRED)) {
            log.info("hold expired lazily id={}", due.id)
        }
        return HoldLock.Expired
    }

    private fun isDue(hold: Hold): Boolean = hold.expiresAt?.isBefore(clock.now()) ?: true
}
