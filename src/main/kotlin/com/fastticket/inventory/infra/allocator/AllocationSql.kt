package com.fastticket.inventory.infra.allocator

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

internal const val STRATEGY_PROPERTY = "inventory.allocator.strategy"

internal fun insertActiveAllocation(jdbc: JdbcClient, holdId: UUID, seatId: UUID): Boolean =
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

internal fun activeSeats(jdbc: JdbcClient, seatIds: Collection<UUID>): List<UUID> =
    jdbc.sql("SELECT seat_id FROM seat_allocation WHERE seat_id IN (:ids) AND status IN ('HELD', 'SOLD') ORDER BY seat_id")
        .param("ids", seatIds)
        .query(UUID::class.java).list().filterNotNull()

/** Runs [block] inside a JDBC savepoint, so a failed statement does not abort the surrounding transaction. */
internal class Savepoint(txManager: PlatformTransactionManager) {
    private val template = TransactionTemplate(txManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_NESTED
    }

    fun <T> guarded(block: () -> T): T {
        var result: T? = null
        template.executeWithoutResult { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
