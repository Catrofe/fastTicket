package com.fastticket.inventory.infra.allocator

import com.fastticket.inventory.domain.AllocationResult
import com.fastticket.inventory.domain.SeatAllocator
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.util.UUID

@Component
@ConditionalOnProperty(STRATEGY_PROPERTY, havingValue = "optimistic")
class OptimisticSeatAllocator(private val jdbc: JdbcClient) : SeatAllocator {

    override fun allocate(holdId: UUID, seatIds: Collection<UUID>): AllocationResult {
        val ids = seatIds.distinct().sorted()
        val seen = seededVersions(ids)
        val taken = activeSeats(jdbc, ids)
        if (taken.isNotEmpty()) return AllocationResult.Conflict(taken)

        for (id in ids) {
            val expected = seen[id] ?: return AllocationResult.Conflict(listOf(id))
            if (!bump(id, expected)) {
                return AllocationResult.Conflict((activeSeats(jdbc, ids) + id).distinct().sorted())
            }
        }
        val lost = ids.filter { !insertActiveAllocation(jdbc, holdId, it) }
        return if (lost.isEmpty()) AllocationResult.Allocated else AllocationResult.Conflict(lost)
    }

    // Reading first keeps the hot path lock-free: a blind seed would wait for any in-flight writer of an existing row.
    private fun seededVersions(ids: List<UUID>): Map<UUID, Long> {
        val seen = versions(ids)
        if (seen.size == ids.size) return seen
        jdbc.sql("INSERT INTO seat_version (seat_id) SELECT id FROM seat WHERE id IN (:ids) ORDER BY id ON CONFLICT DO NOTHING")
            .param("ids", ids - seen.keys).update()
        return versions(ids)
    }

    private fun versions(ids: List<UUID>): Map<UUID, Long> =
        jdbc.sql("SELECT seat_id, version FROM seat_version WHERE seat_id IN (:ids)")
            .param("ids", ids)
            .query { rs, _ -> rs.getObject("seat_id", UUID::class.java) to rs.getLong("version") }
            .list().toMap()

    private fun bump(seatId: UUID, expected: Long): Boolean =
        jdbc.sql("UPDATE seat_version SET version = version + 1 WHERE seat_id = :id AND version = :expected")
            .param("id", seatId).param("expected", expected)
            .update() == 1
}
