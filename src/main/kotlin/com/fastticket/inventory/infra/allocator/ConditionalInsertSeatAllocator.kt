package com.fastticket.inventory.infra.allocator

import com.fastticket.inventory.domain.AllocationResult
import com.fastticket.inventory.domain.SeatAllocator
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class ConditionalInsertSeatAllocator(private val jdbc: JdbcClient) : SeatAllocator {

    override fun allocate(holdId: UUID, seatIds: Collection<UUID>): AllocationResult {
        val taken = seatIds.distinct().sorted().filter { !insert(holdId, it) }
        return if (taken.isEmpty()) AllocationResult.Allocated else AllocationResult.Conflict(taken)
    }

    private fun insert(holdId: UUID, seatId: UUID): Boolean =
        jdbc.sql(
            """
            INSERT INTO seat_allocation (id, seat_id, hold_id, status)
            VALUES (:id, :seatId, :holdId, 'HELD')
            ON CONFLICT DO NOTHING
            RETURNING seat_id
            """.trimIndent(),
        )
            .param("id", UUID.randomUUID()).param("seatId", seatId).param("holdId", holdId)
            .query(UUID::class.java).list().isNotEmpty()
}
