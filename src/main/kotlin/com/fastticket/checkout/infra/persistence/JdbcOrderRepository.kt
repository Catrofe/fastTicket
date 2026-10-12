package com.fastticket.checkout.infra.persistence

import com.fastticket.checkout.domain.Order
import com.fastticket.checkout.domain.OrderRepository
import com.fastticket.checkout.domain.OrderStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

private const val COLUMNS = "id, hold_id, status, total_amount, confirmed_at, cancelled_at"

@Repository
class JdbcOrderRepository(private val jdbc: JdbcClient) : OrderRepository {

    override fun insertConfirmed(order: Order): Order? =
        jdbc.sql(
            """
            INSERT INTO "order" (id, hold_id, status, total_amount, confirmed_at)
            VALUES (:id, :holdId, 'CONFIRMED', :total, clock_timestamp())
            ON CONFLICT (hold_id) DO NOTHING
            RETURNING $COLUMNS
            """.trimIndent(),
        )
            .param("id", order.id).param("holdId", order.holdId).param("total", order.totalAmount)
            .query { rs, _ -> map(rs) }.optional().orElse(null)

    override fun findById(id: UUID): Order? =
        jdbc.sql("""SELECT $COLUMNS FROM "order" WHERE id = :id""").param("id", id)
            .query { rs, _ -> map(rs) }.optional().orElse(null)

    override fun findByHoldId(holdId: UUID): Order? =
        jdbc.sql("""SELECT $COLUMNS FROM "order" WHERE hold_id = :holdId""").param("holdId", holdId)
            .query { rs, _ -> map(rs) }.optional().orElse(null)

    private fun map(rs: ResultSet) = Order(
        id = rs.getObject("id", UUID::class.java),
        holdId = rs.getObject("hold_id", UUID::class.java),
        status = OrderStatus.valueOf(rs.getString("status")),
        totalAmount = rs.getBigDecimal("total_amount"),
        confirmedAt = rs.getObject("confirmed_at", OffsetDateTime::class.java)?.toInstant(),
        cancelledAt = rs.getObject("cancelled_at", OffsetDateTime::class.java)?.toInstant(),
    )
}
