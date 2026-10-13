package com.fastticket.inventory.infra.allocator

import com.fastticket.inventory.domain.AllocationResult
import com.fastticket.inventory.domain.SeatAllocator
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import java.sql.SQLException
import java.util.UUID

private const val LOCK_NOT_AVAILABLE = "55P03"
private const val MAX_ATTEMPTS = 3

@Component
@ConditionalOnProperty(STRATEGY_PROPERTY, havingValue = "pessimistic")
class PessimisticSeatAllocator(
    private val jdbc: JdbcClient,
    txManager: PlatformTransactionManager,
) : SeatAllocator {

    private val savepoint = Savepoint(txManager)

    override fun allocate(holdId: UUID, seatIds: Collection<UUID>): AllocationResult =
        allocate(holdId, seatIds.distinct().sorted(), MAX_ATTEMPTS)

    private fun allocate(holdId: UUID, ids: List<UUID>, attemptsLeft: Int): AllocationResult {
        try {
            savepoint.guarded { lock(ids) }
        } catch (e: DataAccessException) {
            if (!e.isLockNotAvailable()) throw e
            val unavailable = (activeSeats(jdbc, ids) + lockedByOthers(ids)).distinct().sorted()
            if (unavailable.isNotEmpty() || attemptsLeft == 1) return AllocationResult.Conflict(unavailable.ifEmpty { ids })
            return allocate(holdId, ids, attemptsLeft - 1)
        }
        val taken = activeSeats(jdbc, ids)
        if (taken.isNotEmpty()) return AllocationResult.Conflict(taken)
        val lost = ids.filter { !insertActiveAllocation(jdbc, holdId, it) }
        return if (lost.isEmpty()) AllocationResult.Allocated else AllocationResult.Conflict(lost)
    }

    private fun DataAccessException.isLockNotAvailable(): Boolean =
        this is CannotAcquireLockException || generateSequence<Throwable>(this) { it.cause }
            .any { it is SQLException && it.sqlState == LOCK_NOT_AVAILABLE }

    private fun lock(ids: List<UUID>) {
        jdbc.sql("SELECT id FROM seat WHERE id IN (:ids) ORDER BY id FOR NO KEY UPDATE NOWAIT")
            .param("ids", ids)
            .query(UUID::class.java).list()
    }

    /** Seats another transaction holds the row lock on: whatever SKIP LOCKED cannot return. */
    private fun lockedByOthers(ids: List<UUID>): List<UUID> {
        val free = jdbc.sql("SELECT id FROM seat WHERE id IN (:ids) ORDER BY id FOR NO KEY UPDATE SKIP LOCKED")
            .param("ids", ids)
            .query(UUID::class.java).list().filterNotNull().toSet()
        return ids.filter { it !in free }
    }
}
