package com.fastticket.checkout.infra.persistence

import com.fastticket.checkout.domain.Refund
import com.fastticket.checkout.domain.RefundReason
import com.fastticket.checkout.domain.RefundRepository
import com.fastticket.checkout.domain.RefundStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

private const val COLUMNS = "id, payment_id, amount, status, reason, gateway_ref, created_at"

@Repository
class JdbcRefundRepository(private val jdbc: JdbcClient) : RefundRepository {

    override fun insertPending(refund: Refund): Refund? =
        jdbc.sql(
            """
            INSERT INTO refund (id, payment_id, amount, status, reason)
            VALUES (:id, :paymentId, :amount, 'PENDING', :reason)
            ON CONFLICT (payment_id) DO NOTHING
            RETURNING $COLUMNS
            """.trimIndent(),
        )
            .param("id", refund.id).param("paymentId", refund.paymentId)
            .param("amount", refund.amount).param("reason", refund.reason.name)
            .query { rs, _ -> map(rs) }.optional().orElse(null)

    override fun findByPaymentId(paymentId: UUID): Refund? =
        jdbc.sql("SELECT $COLUMNS FROM refund WHERE payment_id = :paymentId").param("paymentId", paymentId)
            .query { rs, _ -> map(rs) }.optional().orElse(null)

    private fun map(rs: ResultSet) = Refund(
        id = rs.getObject("id", UUID::class.java),
        paymentId = rs.getObject("payment_id", UUID::class.java),
        amount = rs.getBigDecimal("amount"),
        status = RefundStatus.valueOf(rs.getString("status")),
        reason = RefundReason.valueOf(rs.getString("reason")),
        gatewayRef = rs.getString("gateway_ref"),
        createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
    )
}
