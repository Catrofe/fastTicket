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

@Component
@ConditionalOnProperty(STRATEGY_PROPERTY, havingValue = "pessimistic")
class PessimisticSeatAllocator(
    private val jdbc: JdbcClient,
    txManager: PlatformTransactionManager,
) : SeatAllocator {

    private val savepoint = Savepoint(txManager)

    override fun allocate(holdId: UUID, seatIds: Collection<UUID>): AllocationResult {
        val ids = seatIds.distinct().sorted()
        try {
            savepoint.guarded { lock(ids) }
        } catch (e: DataAccessException) {
            if (!e.isLockNotAvailable()) throw e
            return AllocationResult.Conflict(activeSeats(jdbc, ids).ifEmpty { ids })
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
}
