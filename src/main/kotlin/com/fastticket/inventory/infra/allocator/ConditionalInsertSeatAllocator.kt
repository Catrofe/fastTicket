package com.fastticket.inventory.infra.allocator

import com.fastticket.inventory.domain.AllocationResult
import com.fastticket.inventory.domain.SeatAllocator
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.util.UUID

@Component
@ConditionalOnProperty(STRATEGY_PROPERTY, havingValue = "conditional", matchIfMissing = true)
class ConditionalInsertSeatAllocator(private val jdbc: JdbcClient) : SeatAllocator {

    override fun allocate(holdId: UUID, seatIds: Collection<UUID>): AllocationResult {
        val taken = seatIds.distinct().sorted().filter { !insertActiveAllocation(jdbc, holdId, it) }
        return if (taken.isEmpty()) AllocationResult.Allocated else AllocationResult.Conflict(taken)
    }
}
