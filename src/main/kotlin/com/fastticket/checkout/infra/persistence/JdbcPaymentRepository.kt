package com.fastticket.checkout.infra.persistence

import com.fastticket.checkout.domain.Payment
import com.fastticket.checkout.domain.PaymentMethod
import com.fastticket.checkout.domain.PaymentRepository
import com.fastticket.checkout.domain.PaymentStatus
import com.fastticket.checkout.domain.PendingInsertResult
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

private const val COLUMNS = "id, hold_id, method, amount, status, gateway_ref, decline_reason, approved_at, created_at"

@Repository
class JdbcPaymentRepository(private val jdbc: JdbcClient) : PaymentRepository {

    override fun insertPending(payment: Payment): PendingInsertResult {
        val inserted = jdbc.sql(
            """
            INSERT INTO payment (id, hold_id, method, amount, status)
            VALUES (:id, :holdId, :method, :amount, 'PENDING')
            ON CONFLICT (hold_id) WHERE status = 'PENDING' DO NOTHING
            RETURNING $COLUMNS
            """.trimIndent(),
        )
            .param("id", payment.id).param("holdId", payment.holdId)
            .param("method", payment.method.name).param("amount", payment.amount)
            .query { rs, _ -> map(rs) }.optional()
        return inserted.map<PendingInsertResult> { PendingInsertResult.Inserted(it) }
            .orElse(PendingInsertResult.PendingAlreadyExists)
    }

    override fun resolvePending(id: UUID, to: PaymentStatus, gatewayRef: String?, declineReason: String?): Payment? {
        require(to != PaymentStatus.PENDING)
        return jdbc.sql(
            """
            UPDATE payment
            SET status = :status, gateway_ref = :gatewayRef, decline_reason = :declineReason,
                approved_at = CASE WHEN :status = 'APPROVED' THEN clock_timestamp() END,
                updated_at = clock_timestamp()
            WHERE id = :id AND status = 'PENDING'
            RETURNING $COLUMNS
            """.trimIndent(),
        )
            .param("id", id).param("status", to.name)
            .param("gatewayRef", gatewayRef).param("declineReason", declineReason)
            .query { rs, _ -> map(rs) }.optional().orElse(null)
    }

    override fun findById(id: UUID): Payment? =
        jdbc.sql("SELECT $COLUMNS FROM payment WHERE id = :id").param("id", id)
            .query { rs, _ -> map(rs) }.optional().orElse(null)

    override fun findByHoldId(holdId: UUID): List<Payment> =
        jdbc.sql("SELECT $COLUMNS FROM payment WHERE hold_id = :holdId ORDER BY created_at, id").param("holdId", holdId)
            .query { rs, _ -> map(rs) }.list()

    override fun existsRecentPending(holdId: UUID, grace: Duration): Boolean =
        jdbc.sql(
            """
            SELECT EXISTS (
                SELECT 1 FROM payment
                WHERE hold_id = :holdId AND status = 'PENDING'
                  AND created_at > clock_timestamp() - make_interval(secs => :graceSeconds)
            )
            """.trimIndent(),
        )
            .param("holdId", holdId).param("graceSeconds", grace.toMillis() / 1000.0)
            .query(Boolean::class.java).single()

    private fun map(rs: ResultSet) = Payment(
        id = rs.getObject("id", UUID::class.java),
        holdId = rs.getObject("hold_id", UUID::class.java),
        method = PaymentMethod.valueOf(rs.getString("method")),
        amount = rs.getBigDecimal("amount"),
        status = PaymentStatus.valueOf(rs.getString("status")),
        gatewayRef = rs.getString("gateway_ref"),
        declineReason = rs.getString("decline_reason"),
        approvedAt = rs.getObject("approved_at", OffsetDateTime::class.java)?.toInstant(),
        createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
    )
}
