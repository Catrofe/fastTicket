package com.fastticket.inventory.application

import com.fastticket.inventory.domain.BuyerEventRepository
import com.fastticket.inventory.domain.HoldRepository
import com.fastticket.inventory.domain.HoldStatus
import com.fastticket.inventory.domain.SeatAllocationRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
class HoldReleaser(
    private val holds: HoldRepository,
    private val buyers: BuyerEventRepository,
    private val allocations: SeatAllocationRepository,
) {

    @Transactional(propagation = Propagation.MANDATORY)
    fun release(holdId: UUID, to: HoldStatus): Boolean {
        require(to == HoldStatus.EXPIRED || to == HoldStatus.RELEASED)
        val hold = holds.lockById(holdId) ?: return false
        if (hold.status != HoldStatus.ACTIVE || !holds.transition(holdId, HoldStatus.ACTIVE, to)) return false
        allocations.release(holdId)
        buyers.decrement(hold.buyerCpf, hold.eventId, hold.items.size)
        return true
    }
}
